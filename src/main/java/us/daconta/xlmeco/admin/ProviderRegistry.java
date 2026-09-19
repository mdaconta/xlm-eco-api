package us.daconta.xlmeco.admin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import us.daconta.xlmeco.provider.*;
import us.daconta.xlmeco.security.ExternalSecrets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import static java.nio.file.StandardOpenOption.*;

/** Single writer, immutable request snapshots, atomic durable configuration; never stores secrets. */
public final class ProviderRegistry implements AutoCloseable {
    public static final Set<String> CAPABILITIES = Set.of("chat", "structured_image", "embedding", "image_generation");
    private static final Map<String, Set<String>> SUPPORT = Map.of(
        "openai", CAPABILITIES, "google", CAPABILITIES,
        "anthropic", Set.of("structured_image"), "grok", Set.of("chat"), "ollama", Set.of("chat"));
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record Provider(String id, boolean enabled, Map<String,String> configuration) {
        public Provider { configuration = Map.copyOf(configuration); }
    }
    public record Model(String provider, String id, boolean enabled, Set<String> capabilities, String displayName) {
        public Model { capabilities = Set.copyOf(capabilities); }
    }
    public record Selection(String provider, String model) {}
    public record State(int schemaVersion, long revision, Map<String,Provider> providers,
                        Map<String,Model> models, Map<String,Selection> defaults, Map<String,String> legacyDefaults) {
        public State {
            providers = Map.copyOf(providers); models = Map.copyOf(models);
            defaults = Map.copyOf(defaults); legacyDefaults = Map.copyOf(legacyDefaults);
        }
    }
    public record Snapshot(State state, Map<String,GenerativeProvider> adapters, Map<String,Boolean> credentials) {
        public Snapshot { adapters = Map.copyOf(adapters); credentials = Map.copyOf(credentials); }
    }
    public static final class Invalid extends RuntimeException {
        public enum Kind { INVALID, NOT_FOUND, PRECONDITION, STALE }
        public final Kind kind;
        public Invalid(Kind kind, String safe) { super(safe); this.kind = kind; }
    }
    private final Path file;
    private FileChannel lockChannel;
    private FileLock lock;
    private final Properties bootstrap;
    private final ExternalSecrets secrets;
    private final boolean testConfiguration;
    private volatile Snapshot current;

    public ProviderRegistry(Path directory, ExternalSecrets secrets, Properties nonSecretBootstrap) throws IOException {
        this.file = directory.resolve("registry.json"); this.secrets = secrets;
        this.bootstrap = new Properties(); this.bootstrap.putAll(nonSecretBootstrap);
        this.testConfiguration = false;
        try {
            Files.createDirectories(directory);
            lockChannel = FileChannel.open(directory.resolve("registry.lock"), CREATE, WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Registry is already in use");
            State state;
            if (Files.exists(file)) {
                if (Files.size(file) > 2_097_152) throw new IOException("Registry exceeds size limit");
                state = JSON.readValue(Files.readAllBytes(file), State.class);
            } else state = seed(bootstrap);
            validate(state);
            current = prepare(state);
            if (!Files.exists(file)) persist(state);
        } catch (Exception e) {
            close();
            throw new IOException("Registry initialization failed");
        }
    }
    /** Explicit test/legacy component seam; production bootstrap always uses the persistent constructor. */
    public static ProviderRegistry inMemory(Properties testProperties) { return new ProviderRegistry(testProperties); }
    private ProviderRegistry(Properties props) {
        file = null; secrets = null; testConfiguration = true; bootstrap = props;
        State state = seed(props); validate(state); current = prepare(state);
    }
    public Snapshot snapshot() { return current; }
    public static Set<String> capabilities(String provider) { return SUPPORT.getOrDefault(provider, Set.of()); }
    public static String key(String provider, String model) { return provider + "/" + model; }
    public synchronized Snapshot update(long expected, UnaryOperator<State> change) {
        if (expected != current.state.revision) throw new Invalid(Invalid.Kind.STALE, "Configuration revision changed");
        State changed = change.apply(current.state);
        State candidate = new State(1, Math.addExact(current.state.revision, 1), changed.providers,
                changed.models, changed.defaults, changed.legacyDefaults);
        validate(candidate);
        Snapshot ready = prepare(candidate);
        try { persist(candidate); }
        catch (IOException e) { throw new Invalid(Invalid.Kind.PRECONDITION, "Configuration could not be persisted"); }
        current = ready;
        return ready;
    }
    public Snapshot reload(long expected) { return update(expected, s -> s); }
    public Snapshot updateProvider(long revision, String id, Boolean enabled, Map<String,String> patch) {
        return update(revision, s -> {
            Provider before = s.providers.get(id);
            if (before == null) throw new Invalid(Invalid.Kind.NOT_FOUND, "Provider not found");
            Map<String,String> config = new HashMap<>(before.configuration); config.putAll(patch);
            Map<String,Provider> providers = new HashMap<>(s.providers);
            providers.put(id, new Provider(id, enabled == null ? before.enabled : enabled, config));
            return new State(1, s.revision, providers, s.models, s.defaults, s.legacyDefaults);
        });
    }
    public Snapshot upsertModel(long revision, Model model) {
        return update(revision, s -> {
            Map<String,Model> models = new HashMap<>(s.models); models.put(key(model.provider, model.id), model);
            return new State(1,s.revision,s.providers,models,s.defaults,s.legacyDefaults);
        });
    }
    public Snapshot setDefault(long revision, String capability, Selection selection) {
        checkCapability(capability);
        return update(revision, s -> {
            Map<String,Selection> defaults = new HashMap<>(s.defaults);
            if (selection == null) defaults.remove(capability); else defaults.put(capability, selection);
            return new State(1,s.revision,s.providers,s.models,defaults,s.legacyDefaults);
        });
    }
    public static void checkCapability(String capability) {
        if (!CAPABILITIES.contains(capability)) throw new Invalid(Invalid.Kind.INVALID, "Unknown capability");
    }
    public static Selection resolve(Snapshot snapshot, String capability, String provider, String model, String preference) {
        checkCapability(capability);
        State state = snapshot.state;
        provider = provider.trim().toLowerCase(Locale.ROOT); model = model.trim();
        if (provider.isEmpty() && !model.isEmpty()) {
            if (preference == null || preference.isBlank()) throw new Invalid(Invalid.Kind.INVALID, "Provider is required with model");
            provider = preference;
        } else if (provider.isEmpty() && preference != null && !preference.isBlank()) {
            provider = preference;
            model = state.legacyDefaults.getOrDefault(key(provider,capability), "");
            if (model.isEmpty()) throw new Invalid(Invalid.Kind.PRECONDITION,"Preferred provider has no default model");
        }
        if (provider.isEmpty()) {
            Selection selection = state.defaults.get(capability);
            if (selection == null) throw new Invalid(Invalid.Kind.PRECONDITION,"Capability default is not configured");
            provider = selection.provider; model = selection.model;
        } else if (model.isEmpty()) {
            Selection selection = state.defaults.get(capability);
            if (selection == null || !provider.equals(selection.provider))
                throw new Invalid(Invalid.Kind.INVALID,"Model is required for selected provider");
            model = selection.model;
        }
        validateSelection(state, capability, new Selection(provider,model));
        return new Selection(provider,model);
    }
    public static void validateSelection(State state, String capability, Selection selection) {
        Provider provider = state.providers.get(selection.provider);
        if (provider == null) throw new Invalid(Invalid.Kind.NOT_FOUND,"Unknown provider");
        if (!provider.enabled) throw new Invalid(Invalid.Kind.PRECONDITION,"Provider disabled");
        Model model = state.models.get(key(selection.provider,selection.model));
        if (model == null) throw new Invalid(Invalid.Kind.NOT_FOUND,"Unknown model");
        if (!model.enabled) throw new Invalid(Invalid.Kind.PRECONDITION,"Model disabled");
        if (!capabilities(provider.id).contains(capability) || !model.capabilities.contains(capability))
            throw new Invalid(Invalid.Kind.PRECONDITION,"Incompatible capability");
    }
    private static void validate(State s) {
        if (s.schemaVersion != 1 || s.revision < 1 || s.providers.size() > 32 || s.models.size() > 1000)
            throw new Invalid(Invalid.Kind.INVALID,"Invalid registry bounds or version");
        for (var entry : s.providers.entrySet()) {
            Provider p = entry.getValue();
            if (!SUPPORT.containsKey(p.id) || !entry.getKey().equals(p.id)) throw new Invalid(Invalid.Kind.INVALID,"Unknown adapter");
            for (var cfg : p.configuration.entrySet()) {
                try {
                    switch (cfg.getKey()) {
                        case "timeout_seconds" -> { if(p.id.equals("grok")) throw new IllegalArgumentException(); int n=Integer.parseInt(cfg.getValue()); if(n<1||n>150) throw new IllegalArgumentException(); }
                        case "output_tokens" -> { if(!Set.of("google","anthropic").contains(p.id)) throw new IllegalArgumentException(); int n=Integer.parseInt(cfg.getValue()); if(n<1||n>8192) throw new IllegalArgumentException(); }
                        case "project_id", "location" -> {
                            if (!p.id.equals("google") || !cfg.getValue().matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
                        }
                        default -> throw new IllegalArgumentException();
                    }
                } catch (RuntimeException ex) { throw new Invalid(Invalid.Kind.INVALID,"Unsupported provider configuration"); }
            }
        }
        for (var entry : s.models.entrySet()) {
            Model m = entry.getValue();
            if (!s.providers.containsKey(m.provider) || !m.id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
                    || !entry.getKey().equals(key(m.provider,m.id)) || m.capabilities.isEmpty()
                    || !capabilities(m.provider).containsAll(m.capabilities) || m.displayName.length()>128
                    || m.displayName.chars().anyMatch(Character::isISOControl))
                throw new Invalid(Invalid.Kind.INVALID,"Invalid model configuration");
        }
        for (var entry : s.defaults.entrySet()) {
            checkCapability(entry.getKey()); validateSelection(s,entry.getKey(),entry.getValue());
        }
        for (var entry : s.legacyDefaults.entrySet()) {
            int slash=entry.getKey().indexOf('/');
            if(slash<1) throw new Invalid(Invalid.Kind.INVALID,"Invalid migrated default");
            String p=entry.getKey().substring(0,slash), cap=entry.getKey().substring(slash+1);
            checkCapability(cap);
            if (!s.models.containsKey(key(p,entry.getValue()))) throw new Invalid(Invalid.Kind.INVALID,"Invalid migrated default");
        }
    }
    private Snapshot prepare(State state) {
        Map<String,GenerativeProvider> adapters = new HashMap<>(); Map<String,Boolean> present=new HashMap<>();
        for (GenerativeProvider adapter : ServiceLoader.load(GenerativeProvider.class)) {
            String id=adapter.getProviderName(); Provider p=state.providers.get(id); if(p==null) continue;
            Properties props=new Properties();
            switch(id) {
                case "openai" -> { props.setProperty("chat_url","https://api.openai.com/v1/chat/completions"); props.setProperty("embedding_url","https://api.openai.com/v1/embeddings"); props.setProperty("image_url","https://api.openai.com/v1/images/generations"); }
                case "google" -> { props.setProperty("chat_url","https://generativelanguage.googleapis.com/v1beta/models/"); }
                case "anthropic" -> props.setProperty("chat_url","https://api.anthropic.com/v1/messages");
                case "grok" -> props.setProperty("chat_url","https://api.x.ai/v1/chat/completions");
                case "ollama" -> props.setProperty("chat_url","http://127.0.0.1:11434/api/chat");
            }
            props.putAll(p.configuration);
            props.setProperty("default_lm_model",state.legacyDefaults.getOrDefault(key(id,"chat"),""));
            props.setProperty("default_embedding_model",state.legacyDefaults.getOrDefault(key(id,"embedding"),""));
            String credential="";
            try { if(secrets!=null) credential=secrets.providerCredential(id).orElse(""); }
            catch (Exception e) { throw new Invalid(Invalid.Kind.PRECONDITION,"Credential configuration could not be loaded"); }
            if(testConfiguration) {
                for(String name:bootstrap.stringPropertyNames()) if(name.startsWith(id+".")) props.setProperty(name.substring(id.length()+1),bootstrap.getProperty(name));
                credential=props.getProperty("api_key","");
            }
            props.setProperty("api_key",credential); present.put(id,!credential.isBlank());
            try { adapter.initialize(props); }
            catch(RuntimeException e) { throw new Invalid(Invalid.Kind.INVALID,"Provider configuration could not be initialized"); }
            adapters.put(id,adapter);
        }
        return new Snapshot(state,adapters,present);
    }
    private void persist(State state) throws IOException {
        if(file==null) return;
        byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        if(bytes.length>2_097_152) throw new IOException("Registry exceeds size limit");
        Path temporary=Files.createTempFile(file.getParent(),"registry-",".tmp");
        try {
            try(FileChannel channel=FileChannel.open(temporary,WRITE,TRUNCATE_EXISTING)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void close() throws IOException {
        if(lock!=null) { lock.release(); lock=null; }
        if(lockChannel!=null) { lockChannel.close(); lockChannel=null; }
    }
    private static State seed(Properties legacy) {
        Map<String,Provider> providers=new HashMap<>(); Map<String,Model> models=new HashMap<>();
        Map<String,String> defaults=new HashMap<>();
        for(String id:SUPPORT.keySet()) {
            Map<String,String> config=new HashMap<>();
            if(!id.equals("grok"))config.put("timeout_seconds",id.equals("ollama")?"120":"150");
            if(Set.of("google","anthropic").contains(id))config.put("output_tokens","4096");
            for(String key:List.of("timeout_seconds","output_tokens","project_id","location")) {
                String value=legacy.getProperty(id+"."+key,""); if(!value.isBlank())config.put(key,value);
            }
            providers.put(id,new Provider(id,true,config));
        }
        for(String id:List.of("gpt-4o-mini","gpt-4.1","gpt-4.1-mini","gpt-4.1-nano")) add(models,"openai",id,Set.of("chat","structured_image"));
        add(models,"openai","text-embedding-ada-002",Set.of("embedding"));
        add(models,"openai","gpt-image-1",Set.of("image_generation"));
        add(models,"google","gemini-2.5-flash-image",Set.of("image_generation"));
        for(String id:List.of("gemini-2.5-pro","gemini-2.5-flash","gemini-2.5-flash-lite")) add(models,"google",id,Set.of("chat","structured_image"));
        for(String id:List.of("claude-sonnet-4-6","claude-opus-4-6","claude-haiku-4-5-20251001")) add(models,"anthropic",id,Set.of("structured_image"));
        add(models,"grok","grok-beta",Set.of("chat")); add(models,"ollama","llama3",Set.of("chat"));
        defaults.put("openai/chat","gpt-4o-mini"); defaults.put("openai/embedding","text-embedding-ada-002");
        defaults.put("google/chat","gemini-2.5-flash"); defaults.put("grok/chat","grok-beta"); defaults.put("ollama/chat","llama3");
        for(String p:SUPPORT.keySet()) {
            for(String c:List.of("chat","embedding")) {
                String value=legacy.getProperty(p+(c.equals("chat")?".default_lm_model":".default_embedding_model"),"").trim();
                if(!value.isEmpty()&&capabilities(p).contains(c)) { add(models,p,value,Set.of(c)); defaults.put(key(p,c),value); }
            }
            for(String value:legacy.getProperty(p+".vision_models","").split(","))
                if(!value.isBlank()&&capabilities(p).contains("structured_image")) add(models,p,value.trim(),Set.of("structured_image"));
        }
        return new State(1,1,providers,models,Map.of("chat",new Selection("openai",defaults.get("openai/chat")),
                "embedding",new Selection("openai",defaults.get("openai/embedding")),"structured_image",new Selection("openai","gpt-4o-mini")),defaults);
    }
    private static void add(Map<String,Model> models,String provider,String id,Set<String> capabilities) {
        Set<String> caps=new HashSet<>(capabilities); Model old=models.get(key(provider,id)); if(old!=null)caps.addAll(old.capabilities);
        models.put(key(provider,id),new Model(provider,id,true,caps,id));
    }
}
