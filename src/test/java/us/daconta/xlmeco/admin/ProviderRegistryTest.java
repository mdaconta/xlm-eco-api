package us.daconta.xlmeco.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.daconta.xlmeco.security.ExternalSecrets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {
    @TempDir Path temp;
    ProviderRegistry open() throws Exception { return new ProviderRegistry(temp.resolve("config"),new ExternalSecrets(temp.resolve("secrets")),new Properties()); }
    @Test void persistsRegistryStatesDefaultsAndNeverSecretsAcrossRestart() throws Exception {
        Files.createDirectories(temp.resolve("secrets")); Files.writeString(temp.resolve("secrets/openai.key"),"synthetic-key-marker");
        long revision;
        try(var registry=open()) {
            assertTrue(registry.snapshot().credentials().get("openai"));
            var added=new ProviderRegistry.Model("google","test-image",true,Set.of("structured_image"),"A model");
            registry.upsertModel(1,added);
            registry.setDefault(2,"structured_image",new ProviderRegistry.Selection("google","test-image"));
            registry.updateProvider(3,"anthropic",false,Map.of("timeout_seconds","80"));
            registry.upsertModel(4,new ProviderRegistry.Model("openai","gpt-4.1-nano",false,Set.of("structured_image"),"disabled"));
            revision=registry.snapshot().state().revision();
            assertThrows(java.io.IOException.class,this::open);
        }
        assertFalse(Files.readString(temp.resolve("config/registry.json")).contains("synthetic-key-marker"));
        try(var registry=open()) {
            assertEquals(revision,registry.snapshot().state().revision());
            assertEquals("test-image",registry.snapshot().state().defaults().get("structured_image").model());
            assertFalse(registry.snapshot().state().providers().get("anthropic").enabled());
            assertFalse(registry.snapshot().state().models().get("openai/gpt-4.1-nano").enabled());
            assertTrue(registry.snapshot().credentials().get("openai"));
        }
    }
    @Test void invalidChangesNeverPublishOrPersistAndCorruptionFailsClosed() throws Exception {
        try(var registry=open()) {
            var before=registry.snapshot(); String disk=Files.readString(temp.resolve("config/registry.json"));
            assertThrows(ProviderRegistry.Invalid.class,()->registry.setDefault(1,"embedding",new ProviderRegistry.Selection("anthropic","claude-sonnet-4-6")));
            assertThrows(ProviderRegistry.Invalid.class,()->registry.updateProvider(1,"google",null,Map.of("api_key","synthetic")));
            assertThrows(ProviderRegistry.Invalid.class,()->registry.updateProvider(1,"google",null,Map.of("chat_url","https://invalid.example")));
            assertThrows(ProviderRegistry.Invalid.class,()->registry.updateProvider(1,"openai",false,Map.of()));
            assertThrows(ProviderRegistry.Invalid.class,()->registry.upsertModel(1,new ProviderRegistry.Model("anthropic","x",true,Set.of("embedding"),"bad")));
            assertSame(before,registry.snapshot()); assertEquals(disk,Files.readString(temp.resolve("config/registry.json")));
        }
        Files.writeString(temp.resolve("config/registry.json"),"{broken");
        assertThrows(java.io.IOException.class,this::open);
        assertEquals("{broken",Files.readString(temp.resolve("config/registry.json")));
    }
    @Test void explicitDefaultsPreferencesAndDisabledValidation() {
        try(var registry=ProviderRegistry.inMemory(new Properties())) {
            var s=registry.snapshot();
            assertEquals("gpt-4o-mini",ProviderRegistry.resolve(s,"structured_image","","",null).model());
            assertEquals("gpt-4.1",ProviderRegistry.resolve(s,"structured_image","openai","gpt-4.1",null).model());
            assertEquals("gpt-4.1",ProviderRegistry.resolve(s,"chat","","gpt-4.1","openai").model());
            assertEquals("llama3",ProviderRegistry.resolve(s,"chat","","","ollama").model());
            assertThrows(ProviderRegistry.Invalid.class,()->ProviderRegistry.resolve(s,"structured_image","","gpt-4.1",null));
            assertThrows(ProviderRegistry.Invalid.class,()->ProviderRegistry.resolve(s,"structured_image","unknown","x",null));
            assertThrows(ProviderRegistry.Invalid.class,()->ProviderRegistry.resolve(s,"structured_image","openai","unknown",null));
            assertThrows(ProviderRegistry.Invalid.class,()->ProviderRegistry.resolve(s,"embedding","openai","gpt-4.1",null));
            registry.updateProvider(1,"ollama",false,Map.of());
            assertThrows(ProviderRegistry.Invalid.class,()->ProviderRegistry.resolve(registry.snapshot(),"chat","","","ollama"));
            registry.upsertModel(2,new ProviderRegistry.Model("openai","gpt-4.1",false,Set.of("chat","structured_image"),""));
            assertThrows(ProviderRegistry.Invalid.class,()->ProviderRegistry.resolve(registry.snapshot(),"chat","openai","gpt-4.1",null));
        } catch(java.io.IOException e) { fail(e); }
    }
    @Test void concurrentWritersHaveOneWinnerAndReadersSeeWholeSnapshots() throws Exception {
        try(var registry=open()) {
            var old=registry.snapshot(); ExecutorService pool=Executors.newFixedThreadPool(6);
            try {
                List<Callable<Boolean>> work=new ArrayList<>();
                for(int i=0;i<5;i++)work.add(()-> { try { registry.updateProvider(1,"google",false,Map.of());return true; } catch(ProviderRegistry.Invalid e){assertEquals(ProviderRegistry.Invalid.Kind.STALE,e.kind);return false;} });
                long wins=0;for(var result:pool.invokeAll(work))if(result.get())wins++;
                assertEquals(1,wins); assertEquals(2,registry.snapshot().state().revision());
                assertTrue(old.state().providers().get("google").enabled()); assertFalse(registry.snapshot().state().providers().get("google").enabled());
                assertThrows(UnsupportedOperationException.class,()->old.state().providers().clear());
            } finally {pool.shutdownNow();}
        }
    }
    @Test void credentialReloadPublishesOnlyPresenceAndNewSnapshot() throws Exception {
        try(var registry=open()) {
            assertFalse(registry.snapshot().credentials().get("google"));
            Files.createDirectories(temp.resolve("secrets"));Files.writeString(temp.resolve("secrets/google.key"),"synthetic-key");
            registry.reload(1);assertTrue(registry.snapshot().credentials().get("google"));
            Files.delete(temp.resolve("secrets/google.key"));registry.reload(2);assertFalse(registry.snapshot().credentials().get("google"));
        }
    }
    @Test void failedReplacementKeepsPublishedStateAndRestartReadsLastCommit() throws Exception {
        try(var registry=open()) {
            var before=registry.snapshot();Path file=temp.resolve("config/registry.json"),backup=temp.resolve("config/last-good.json");
            Files.move(file,backup);Files.createDirectory(file);
            assertThrows(ProviderRegistry.Invalid.class,()->registry.updateProvider(1,"google",false,Map.of()));
            assertSame(before,registry.snapshot());
            Files.delete(file);Files.move(backup,file);
            Files.writeString(temp.resolve("config/registry-orphan.tmp"),"incomplete candidate");
        }
        try(var restarted=open()) { assertEquals(1,restarted.snapshot().state().revision()); }
    }
}
