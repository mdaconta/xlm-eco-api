package us.daconta.xlmeco;

import com.sun.net.httpserver.HttpServer;
import io.grpc.stub.StreamObserver;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.*;
import us.daconta.xlmeco.provider.impl.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyTextAdapterTest {
    HttpServer server; String response, requestBody, authorization; int status=200;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requestBody=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            authorization=e.getRequestHeaders().getFirst("Authorization"); byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status,bytes.length); try(var out=e.getResponseBody()){out.write(bytes);}}); server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    Properties config(){Properties p=new Properties();p.setProperty("chat_url","http://127.0.0.1:"+server.getAddress().getPort()+"/chat");p.setProperty("default_lm_model","default-model");return p;}
    ChatRequest request(){return ChatRequest.newBuilder().setModelName("selected-model").setPrompt("a \"quoted\" prompt").build();}
    static class Stream implements StreamObserver<ChatResponsePart>{
        String text="";boolean complete; public void onNext(ChatResponsePart p){text+=p.getToken();}public void onCompleted(){complete=true;}public void onError(Throwable t){fail(t);}
    }
    @Test void ollamaPayloadOptionsDefaultsAndLegacyResponses() throws Exception {
        for(String timeout:new String[]{null,"","invalid","0","-2","3"}){
            var p=config();if(timeout!=null)p.setProperty("timeout_seconds",timeout);
            if("3".equals(timeout))p.setProperty("api_key","synthetic"); else if("".equals(timeout))p.setProperty("api_key","");
            var provider=new OllamaProvider();provider.initialize(p);response="{\"message\":{\"content\":\" answer \"}}";
            var request=ChatRequest.newBuilder().setPrompt("hello").setParams(LmParameters.newBuilder().setMaxTokens(12).setTemperature(.5f).setFrequencyPenalty(.2f).setPresencePenalty(.3f)).build();
            assertEquals("answer",provider.generateChatResponse(request));var payload=new JSONObject(requestBody);
            assertEquals("default-model",payload.getString("model"));assertFalse(payload.getBoolean("stream"));
            assertEquals(12,payload.getJSONObject("options").getInt("num_predict"));
            assertEquals(.5,payload.getJSONObject("options").getDouble("temperature"));
            assertTrue(payload.getJSONObject("options").has("repeat_penalty"));assertTrue(payload.getJSONObject("options").has("presence_penalty"));
            assertEquals("3".equals(timeout)?"Bearer synthetic":null,authorization);
            assertEquals(GenerativeProvider.ServiceLevel.LEVEL_1,provider.getServiceLevel());assertFalse(provider.supportsAgents());assertFalse(provider.supportsRAG());assertFalse(provider.supportsEmbeddings());assertTrue(provider.supportsChat());
        }
        var provider=new OllamaProvider();provider.initialize(config());
        response="{\"response\":\" legacy \"}";assertEquals("legacy",provider.generateChatResponse(request()));assertFalse(new JSONObject(requestBody).has("options"));
        response="";assertEquals("",provider.generateChatResponse(request()));
        response="{}";assertEquals("",provider.generateChatResponse(request()));
        response="{\"message\":{}}";assertEquals("",provider.generateChatResponse(request()));
        response="not JSON";assertThrows(SafeProviderFailure.class,()->provider.generateChatResponse(request()));
    }
    @Test void ollamaStreamingHandlesChunksDoneEmptyAndProviderErrors() throws Exception {
        var provider=new OllamaProvider();provider.initialize(config());
        response="\n{\"message\":{\"content\":\"A\"}}\n{\"message\":{\"content\":\"\"}}\n{\"response\":\"B\"}\n{\"response\":\"\"}\n{}\n{\"message\":{\"content\":\"C\"},\"done\":true}\n";
        Stream stream=new Stream();provider.streamChatResponse(request(),stream);assertEquals("ABC",stream.text);assertTrue(stream.complete);assertTrue(new JSONObject(requestBody).getBoolean("stream"));
        response="{\"done\":true}\n{\"response\":\"ignored\"}\n";stream=new Stream();provider.streamChatResponse(request(),stream);assertEquals("",stream.text);assertTrue(stream.complete);
        response="{\"response\":\"EOF\"}\n";stream=new Stream();provider.streamChatResponse(request(),stream);assertEquals("EOF",stream.text);assertTrue(stream.complete);
        response="bad";assertThrows(SafeProviderFailure.class,()->provider.streamChatResponse(request(),new Stream()));
        status=503;response="synthetic secret";assertThrows(SafeProviderFailure.class,()->provider.streamChatResponse(request(),new Stream()));assertThrows(SafeProviderFailure.class,()->provider.generateChatResponse(request()));
    }
    @Test void grokTextAndStreamingMapWirePayloadAndSanitizeErrors() throws Exception {
        for(ChatProvider provider:List.of(new GrokProvider(),new OpenAIProvider())) {
        status=200;var p=config();p.setProperty("api_key","synthetic");((GenerativeProvider)provider).initialize(p);
        response="{\"choices\":[{\"message\":{\"content\":\" answer \"}}]}";assertEquals("answer",provider.generateChatResponse(request()));
        assertEquals("selected-model",new JSONObject(requestBody).getString("model"));assertEquals(request().getPrompt(),new JSONObject(requestBody).getJSONArray("messages").getJSONObject(1).getString("content"));assertEquals("Bearer synthetic",authorization);
        response="event: ignored\ndata: {\"choices\":[{\"delta\":{}}]}\ndata: {\"choices\":[{\"delta\":{\"content\":\"token\"}}]}\ndata: [DONE]\n";
        Stream stream=new Stream();provider.streamChatResponse(request(),stream);assertEquals("token",stream.text);assertTrue(stream.complete);
        response="";stream=new Stream();provider.streamChatResponse(request(),stream);assertTrue(stream.complete);
        response="data: bad\n";assertThrows(SafeProviderFailure.class,()->provider.streamChatResponse(request(),new Stream()));
        response="bad";assertThrows(SafeProviderFailure.class,()->provider.generateChatResponse(request()));
        status=401;assertThrows(SafeProviderFailure.class,()->provider.streamChatResponse(request(),new Stream()));assertThrows(SafeProviderFailure.class,()->provider.generateChatResponse(request()));
        var generative=(GenerativeProvider)provider;assertEquals(GenerativeProvider.ServiceLevel.LEVEL_2,generative.getServiceLevel());assertFalse(generative.supportsAgents());assertFalse(generative.supportsRAG());assertEquals(provider instanceof OpenAIProvider,generative.supportsEmbeddings());assertTrue(generative.supportsChat());
        }
    }
    @Test void embeddingAdaptersHonorSelectedModelAndValidateProviderResponses() {
        for(EmbeddingProvider provider:List.of(new OpenAIProvider(),new GoogleProvider())) {
            var p=config();p.setProperty("api_key","synthetic");p.setProperty("embedding_url",p.getProperty("chat_url"));p.setProperty("default_embedding_model","default-embedding");
            ((GenerativeProvider)provider).initialize(p);status=200;
            response=provider instanceof OpenAIProvider?"{\"data\":[{\"embedding\":[1.0,2.0]}]}":"{\"embedding\":{\"values\":[1.0,2.0]}}";
            assertEquals(List.of(1f,2f),provider.generateEmbedding("hello",ModelParameters.newBuilder().putParameters("model","selected-embedding").build()));
            assertEquals(provider instanceof OpenAIProvider?"selected-embedding":"models/selected-embedding",new JSONObject(requestBody).getString("model"));
            response="{}";assertThrows(SafeProviderFailure.class,()->provider.generateEmbedding("hello",ModelParameters.getDefaultInstance()));
            status=403;assertThrows(SafeProviderFailure.class,()->provider.generateEmbedding("hello",ModelParameters.getDefaultInstance()));
        }
    }
    @Test void googleRestTextFiltersThoughtsAndRejectsEmptyOrMalformedContent() throws Exception {
        var p=config();p.setProperty("api_key","synthetic");var provider=new GoogleProvider();provider.initialize(p);
        response="{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden\"},{\"text\":\"answer\"},{}]}}]}";
        assertEquals("answer",provider.generateChatResponse(ChatRequest.newBuilder().setPrompt("hello").build()));
        Stream stream=new Stream();provider.streamChatResponse(request(),stream);assertEquals("answer",stream.text);assertTrue(stream.complete);
        for(String malformed:List.of("{}","{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}]}","{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")) {
            response=malformed;assertThrows(SafeProviderFailure.class,()->provider.generateChatResponse(request()));
        }
        assertEquals("google",provider.getProviderName());assertTrue(provider.supportsChat());assertTrue(provider.supportsEmbeddings());assertFalse(provider.supportsAgents());assertFalse(provider.supportsRAG());assertNotNull(provider.getServiceLevel());
    }
    @Test void anthropicCapabilityClaimsAndMalformedStructuredResponsesAreConsistent() throws Exception {
        var p=config();p.setProperty("api_key","synthetic");var provider=new AnthropicProvider();provider.initialize(p);
        assertFalse(provider.supportsChat());assertFalse(provider.supportsEmbeddings());assertFalse(provider.supportsRAG());assertFalse(provider.supportsAgents());
        assertThrows(UnsupportedOperationException.class,()->provider.generateChatResponse(request()));
        assertThrows(UnsupportedOperationException.class,()->provider.streamChatResponse(request(),new Stream()));
        assertThrows(UnsupportedOperationException.class,()->provider.generateEmbedding("hello",ModelParameters.getDefaultInstance()));
        for(StructuredImageProvider adapter:List.of(provider,new OpenAIProvider(),new GoogleProvider())) {
            assertFalse(adapter.supportsStructuredImageModel(null));assertFalse(adapter.supportsStructuredImageModel(" "));assertTrue(adapter.supportsStructuredImageModel("model"));
        }
        var input=new StructuredImageProvider.Input("read","image/png",new byte[]{1},"model","{\"type\":\"object\"}");
        for(String invalid:List.of("{}","{\"stop_reason\":\"max_tokens\"}","{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"image\"}]}")) {
            response=invalid;assertThrows(StructuredImageProvider.Failure.class,()->provider.generateStructuredImage(input));
        }
        response="{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"image\"},{\"type\":\"text\",\"text\":\"{}\"}]}";
        assertEquals("{}",provider.generateStructuredImage(input).jsonPayload());
        var defaults=new AbstractGenerativeProvider(){ public void initialize(Properties ignored){} };
        assertEquals("UNKNOWN",defaults.getProviderName());assertEquals(GenerativeProvider.ServiceLevel.LEVEL_1,defaults.getServiceLevel());
        assertFalse(defaults.getSupportedCapabilities().containsValue(true));
    }
}
