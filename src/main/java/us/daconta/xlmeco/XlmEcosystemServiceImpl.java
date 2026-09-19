package us.daconta.xlmeco;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.*;
import us.daconta.xlmeco.admin.ProviderRegistry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;

public class XlmEcosystemServiceImpl extends XlmEcosystemServiceGrpc.XlmEcosystemServiceImplBase {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ProviderRegistry registry;
    private final Map<String,Map<String,String>> clients = new ConcurrentHashMap<>();
    public XlmEcosystemServiceImpl(ProviderRegistry registry) { this.registry=registry; }
    public XlmEcosystemServiceImpl(Properties properties) { this(ProviderRegistry.inMemory(properties)); }
    private static <T> void reply(StreamObserver<T> observer,T response) { observer.onNext(response); observer.onCompleted(); }
    private Map<String,String> client(String id) {
        Map<String,String> choices=clients.get(id);
        if(choices==null) throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.PRECONDITION,"Client is not registered");
        return choices;
    }
    @Override public void registerClient(ClientRegistrationRequest r,StreamObserver<ClientRegistrationResponse> o) {
        boolean valid=!r.getClientId().isBlank()&&r.getClientId().length()<=128;
        boolean success=valid&&clients.putIfAbsent(r.getClientId(),Map.of())==null;
        reply(o,ClientRegistrationResponse.newBuilder().setSuccess(success).setMessage(success?"Client registered":"Invalid or duplicate client").build());
    }
    @Override public void unregisterClient(ClientUnregistrationRequest r,StreamObserver<ClientUnregistrationResponse> o) {
        boolean success=clients.remove(r.getClientId())!=null;
        reply(o,ClientUnregistrationResponse.newBuilder().setSuccess(success).setMessage(success?"Client unregistered":"Client not found").build());
    }
    @Override public void setPreferredProviders(ProviderSelectionRequest r,StreamObserver<SelectionResponse> o) {
        try {
            var snapshot=registry.snapshot(); Map<String,String> choices=new HashMap<>(client(r.getClientId()));
            Set<String> assigned=new HashSet<>();
            for(var entry:r.getProviderCapabilitiesMap().entrySet()) {
                var provider=snapshot.state().providers().get(entry.getKey());
                if(provider==null||!provider.enabled()) throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.PRECONDITION,"Provider unavailable");
                for(String capability:entry.getValue().getCapabilitiesList()) {
                    if(!ProviderRegistry.capabilities(provider.id()).contains(capability)||!assigned.add(capability))
                        throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.INVALID,"Invalid capability selection");
                    choices.put(capability,provider.id());
                }
            }
            if(clients.replace(r.getClientId(),Map.copyOf(choices))==null) throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.PRECONDITION,"Client is not registered");
            reply(o,SelectionResponse.newBuilder().setSuccess(true).setMessage("Preferences updated").build());
        } catch(ProviderRegistry.Invalid e) { reply(o,SelectionResponse.newBuilder().setSuccess(false).setMessage(e.getMessage()).build()); }
    }
    private ProviderRegistry.Selection select(ProviderRegistry.Snapshot s,String id,String cap,String p,String m) {
        String preference=client(id).get(cap);
        var selected=ProviderRegistry.resolve(s,cap,p,m,cap.equals("structured_image")?null:preference);
        ensureCredentials(s, selected, cap);
        return selected;
    }
    private static void ensureCredentials(ProviderRegistry.Snapshot s, ProviderRegistry.Selection selected, String cap) {
        var config=s.state().providers().get(selected.provider()).configuration();
        boolean legacyGoogleAdc=selected.provider().equals("google")&&(cap.equals("chat")||cap.equals("embedding"))
                &&config.containsKey("project_id")&&config.containsKey("location");
        if(!selected.provider().equals("ollama")&&!legacyGoogleAdc&&!s.credentials().getOrDefault(selected.provider(),false))
            throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.PRECONDITION,"Provider credential is not configured");
    }
    private static void safeFailure(StreamObserver<?> o,Throwable e) {
        if(e instanceof ProviderRegistry.Invalid invalid) {
            Status status=switch(invalid.kind) {
                case INVALID -> Status.INVALID_ARGUMENT; case NOT_FOUND -> Status.NOT_FOUND;
                case PRECONDITION -> Status.FAILED_PRECONDITION; case STALE -> Status.ABORTED;
            };
            o.onError(status.withDescription(invalid.getMessage()).asRuntimeException());
        } else if(e instanceof SafeProviderFailure failure) {
            o.onError(Status.UNAVAILABLE.withDescription(failure.safeMessage()).asRuntimeException());
        } else o.onError(Status.UNAVAILABLE.withDescription("PROVIDER_FAILURE").asRuntimeException());
    }
    @Override public void syncChat(ChatRequest r,StreamObserver<ChatResponse> o) {
        try {
            var s=registry.snapshot(); var chosen=select(s,r.getClientId(),"chat",r.getProvider(),r.getModelName());
            String text=((ChatProvider)s.adapters().get(chosen.provider())).generateChatResponse(r.toBuilder().setProvider(chosen.provider()).setModelName(chosen.model()).build());
            reply(o,ChatResponse.newBuilder().setCompletion(text).build());
        } catch(Exception e) { safeFailure(o,e); }
    }
    @Override public void asyncChat(ChatRequest r,StreamObserver<ChatResponsePart> o) {
        try {
            var s=registry.snapshot(); var chosen=select(s,r.getClientId(),"chat",r.getProvider(),r.getModelName());
            ((ChatProvider)s.adapters().get(chosen.provider())).streamChatResponse(r.toBuilder().setProvider(chosen.provider()).setModelName(chosen.model()).build(),new StreamObserver<ChatResponsePart>() {
                public void onNext(ChatResponsePart value) { o.onNext(value); }
                public void onError(Throwable failure) { safeFailure(o,failure); }
                public void onCompleted() { o.onCompleted(); }
            });
        } catch(Exception e) { safeFailure(o,e); }
    }
    @Override public void getEmbedding(EmbeddingRequest r,StreamObserver<EmbeddingResponse> o) {
        try {
            var s=registry.snapshot(); String requestedModel=r.getModel().isBlank()?r.getModelParameters().getParametersOrDefault("model",""):r.getModel();
            var chosen=select(s,r.getClientId(),"embedding",r.getProvider(),requestedModel);
            var params=r.getModelParameters().toBuilder().putParameters("model",chosen.model()).build();
            var embedding=((EmbeddingProvider)s.adapters().get(chosen.provider())).generateEmbedding(r.getText(),params);
            reply(o,EmbeddingResponse.newBuilder().addAllEmbedding(embedding).build());
        } catch(Exception e) { safeFailure(o,e); }
    }
    @Override public void listProviders(EmptyRequest r,StreamObserver<ProvidersListResponse> o) {
        var s=registry.snapshot(); var response=ProvidersListResponse.newBuilder();
        s.state().providers().values().stream().sorted(Comparator.comparing(ProviderRegistry.Provider::id)).forEach(p -> {
            Map<String,Boolean> caps=new HashMap<>(); for(String c:List.of("chat","embedding","structured_image","image_generation","rag","agents"))caps.put(c,ProviderRegistry.capabilities(p.id()).contains(c)&&p.enabled());
            response.addProviders(ProviderInfo.newBuilder().setProviderName(p.id()).setServiceLevel(s.adapters().get(p.id()).getServiceLevel().name()).putAllCapabilities(caps));
        });
        reply(o,response.build());
    }
    @Override public void getProviderCapabilities(ProviderRequest r,StreamObserver<ProviderCapabilitiesResponse> o) {
        try {
            client(r.getClientId()); var s=registry.snapshot(); var p=s.state().providers().get(r.getProvider());
            if(p==null) throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.NOT_FOUND,"Unknown provider");
            Map<String,Boolean> caps=new HashMap<>(); for(String c:List.of("chat","embedding","structured_image","image_generation","rag","agents"))caps.put(c,p.enabled()&&ProviderRegistry.capabilities(p.id()).contains(c));
            reply(o,ProviderCapabilitiesResponse.newBuilder().setProviderName(p.id()).setServiceLevel(s.adapters().get(p.id()).getServiceLevel().name()).putAllCapabilities(caps).build());
        } catch(Exception e) { safeFailure(o,e); }
    }
    @Override public void generateStructuredImage(StructuredImageRequest r,StreamObserver<StructuredImageResponse> o) {
        String provider=r.getProvider().trim().toLowerCase(Locale.ROOT), model=r.getModel().trim();
        StructuredImageResponse.Builder response=StructuredImageResponse.newBuilder().setProvider(provider).setModel(model);
        StructuredImageError failure=validateStructuredImage(r);
        if(failure==null) {
            try {
                var s=registry.snapshot(); client(r.getClientId());
                var selected=ProviderRegistry.resolve(s,"structured_image",provider,model,null);
                response.setProvider(selected.provider()).setModel(selected.model());
                ensureCredentials(s,selected,"structured_image");
                var adapter=(StructuredImageProvider)s.adapters().get(selected.provider());
                var output=adapter.generateStructuredImage(new StructuredImageProvider.Input(r.getInstructions(),r.getImage().getMimeType(),r.getImage().getData().toByteArray(),selected.model(),r.getJsonSchema()));
                response.setSuccess(true).setStatus(StructuredImageStatus.STRUCTURED_IMAGE_COMPLETED).setJsonPayload(output.jsonPayload()).setModel(output.model());
            } catch(ProviderRegistry.Invalid e) {
                StructuredImageErrorCode code=switch(e.getMessage()) {
                    case "Unknown provider" -> StructuredImageErrorCode.UNSUPPORTED_PROVIDER;
                    case "Unknown model" -> StructuredImageErrorCode.UNSUPPORTED_MODEL;
                    case "Provider disabled" -> StructuredImageErrorCode.PROVIDER_DISABLED;
                    case "Model disabled" -> StructuredImageErrorCode.MODEL_DISABLED;
                    case "Incompatible capability" -> StructuredImageErrorCode.INCOMPATIBLE_CAPABILITY;
                    case "Provider credential is not configured" -> StructuredImageErrorCode.PROVIDER_AUTHENTICATION;
                    default -> StructuredImageErrorCode.INVALID_REQUEST;
                };
                failure=error(code,e.getMessage(),false,0);
            } catch(StructuredImageProvider.Failure e) { failure=error(e.code(),e.getMessage(),e.retryable(),e.httpStatus()); }
            catch(Exception e) { failure=error(StructuredImageErrorCode.PROVIDER_FAILURE,"Provider request failed",false,0); }
        }
        if(failure!=null) response.setSuccess(false).setStatus(StructuredImageStatus.STRUCTURED_IMAGE_FAILED).setError(failure);
        reply(o,response.build());
    }
    @Override public void listModels(ModelCatalogRequest r, StreamObserver<ModelCatalogResponse> o) {
        try {
            client(r.getClientId());
            if (!r.getCapability().isEmpty()) ProviderRegistry.checkCapability(r.getCapability());
            var snapshot = registry.snapshot();
            if (!r.getProvider().isEmpty() && !snapshot.state().providers().containsKey(r.getProvider()))
                throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.NOT_FOUND, "Unknown provider");
            var result = ModelCatalogResponse.newBuilder().setRevision(snapshot.state().revision());
            snapshot.state().models().values().stream()
                    .sorted(Comparator.comparing(m -> ProviderRegistry.key(m.provider(), m.id())))
                    .filter(m -> r.getProvider().isEmpty() || r.getProvider().equals(m.provider()))
                    .filter(m -> r.getCapability().isEmpty() || m.capabilities().contains(r.getCapability()))
                    .forEach(m -> result.addModels(ModelCatalogEntry.newBuilder().setProvider(m.provider())
                            .setModel(m.id()).setDisplayName(m.displayName())
                            .setEnabled(m.enabled() && snapshot.state().providers().get(m.provider()).enabled())
                            .addAllCapabilities(new TreeSet<>(m.capabilities()))));
            reply(o, result.build());
        } catch (Exception e) { safeFailure(o, e); }
    }

    @Override public void generateImage(ImageGenerationRequest r, StreamObserver<ImageGenerationResponse> o) {
        var response = ImageGenerationResponse.newBuilder().setRequestId(UUID.randomUUID().toString());
        try {
            if (r.getClientId().isBlank() || r.getClientId().length() > 128
                    || r.getProvider().length() > 128 || r.getModel().length() > 128
                    || r.getPrompt().isBlank() || r.getPrompt().getBytes(StandardCharsets.UTF_8).length > 65_536)
                throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.INVALID, "Required field is missing or exceeds limit");
            response.setProvider(r.getProvider().trim().toLowerCase(Locale.ROOT)).setModel(r.getModel().trim());
            client(r.getClientId());
            var snapshot = registry.snapshot();
            var selection = ProviderRegistry.resolve(snapshot, ImageGenerationProvider.CAPABILITY,
                    r.getProvider(), r.getModel(), null);
            response.setProvider(selection.provider()).setModel(selection.model());
            ensureCredentials(snapshot, selection, ImageGenerationProvider.CAPABILITY);
            if (!(snapshot.adapters().get(selection.provider()) instanceof ImageGenerationProvider adapter))
                throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.PRECONDITION, "Incompatible capability");
            var result = adapter.generateImage(new ImageGenerationProvider.Input(r.getPrompt(), selection.model()));
            GeneratedImageValidator.validate(result.mimeType(), result.data());
            if (result.model() == null || !result.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
                throw GeneratedImageValidator.malformed();
            response.setSuccess(true).setOutputType(GeneratedOutputType.GENERATED_IMAGE).setModel(result.model())
                    .setImage(GeneratedImage.newBuilder().setMimeType(result.mimeType())
                            .setData(com.google.protobuf.ByteString.copyFrom(result.data())));
        } catch (ProviderRegistry.Invalid e) {
            StructuredImageErrorCode code = switch(e.getMessage()) {
                case "Unknown provider" -> StructuredImageErrorCode.UNSUPPORTED_PROVIDER;
                case "Unknown model" -> StructuredImageErrorCode.UNSUPPORTED_MODEL;
                case "Provider disabled" -> StructuredImageErrorCode.PROVIDER_DISABLED;
                case "Model disabled" -> StructuredImageErrorCode.MODEL_DISABLED;
                case "Incompatible capability" -> StructuredImageErrorCode.INCOMPATIBLE_CAPABILITY;
                case "Provider credential is not configured" -> StructuredImageErrorCode.PROVIDER_AUTHENTICATION;
                default -> StructuredImageErrorCode.INVALID_REQUEST;
            };
            response.setError(error(code, e.getMessage(), false, 0));
        } catch (StructuredImageProvider.Failure e) {
            // Messages crossing this public boundary are local and finite, never upstream text.
            response.setError(error(e.code(), "Image generation failed: " + e.code().name(), e.retryable(), e.httpStatus()));
        } catch (Exception e) {
            response.setError(error(StructuredImageErrorCode.PROVIDER_FAILURE, "Image generation failed", false, 0));
        }
        reply(o, response.build());
    }

    private static StructuredImageError validateStructuredImage(StructuredImageRequest request) {
        if (request.getClientId().isBlank() || request.getClientId().length() > 128
                || request.getProvider().length() > 128
                || request.getModel().length() > 128
                || request.getInstructions().isBlank()
                || request.getInstructions().getBytes(StandardCharsets.UTF_8).length > 65_536
                || request.getJsonSchema().isBlank()
                || request.getJsonSchema().getBytes(StandardCharsets.UTF_8).length > 65_536
                || !request.hasImage()) {
            return error(StructuredImageErrorCode.INVALID_REQUEST, "Required field is missing or exceeds limit", false, 0);
        }
        try {
            if (!JSON.readTree(request.getJsonSchema()).isObject()) {
                return error(StructuredImageErrorCode.INVALID_REQUEST, "JSON schema must be an object", false, 0);
            }
        } catch (JsonProcessingException e) {
            return error(StructuredImageErrorCode.INVALID_REQUEST, "JSON schema must be an object", false, 0);
        }
        ImageInput image = request.getImage();
        if (image.getData().isEmpty() || image.getData().size() > 3 * 1024 * 1024
                || !matchesMime(image.getMimeType(), image.getData().toByteArray())) {
            return error(StructuredImageErrorCode.INVALID_REQUEST, "Image bytes or MIME type are invalid", false, 0);
        }
        return null;
    }

    private static boolean matchesMime(String mime, byte[] data) {
        if ("image/png".equals(mime)) return data.length >= 8
                && (data[0] & 255) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10;
        if ("image/jpeg".equals(mime)) return data.length >= 4
                && (data[0] & 255) == 0xff && (data[1] & 255) == 0xd8 && (data[2] & 255) == 0xff;
        if ("image/webp".equals(mime)) return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        return false;
    }

    private static StructuredImageError error(StructuredImageErrorCode code, String message,
                                              boolean retryable, int status) {
        return StructuredImageError.newBuilder().setCode(code).setMessage(message)
                .setRetryable(retryable).setProviderHttpStatus(status).build();
    }

}
