package us.daconta.xlmeco.admin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Catalog mutations fail atomically rather than publishing a partially valid model. */
class RegistryValidationTest {
    @Test void rejectsInvalidSettingsAndIdentityWithoutPublishing() throws Exception {
        try (var r = ProviderRegistry.inMemory(new Properties())) {
            var before = r.snapshot();
            for (String provider : List.of("openai", "google", "grok", "anthropic")) {
                for (Map<String,String> settings : List.of(Map.of("timeout_seconds", "0"), Map.of("timeout_seconds", "151"),
                        Map.of("timeout_seconds", "bad"), Map.of("output_tokens", "0"), Map.of("output_tokens", "8193"),
                        Map.of("project_id", "bad/value"), Map.of("location", "bad value"))) {
                    assertThrows(ProviderRegistry.Invalid.class, () -> r.updateProvider(1, provider, null, settings));
                    assertSame(before, r.snapshot());
                }
            }
            assertThrows(ProviderRegistry.Invalid.class, () -> r.updateProvider(1, "missing", true, Map.of()));
            assertThrows(ProviderRegistry.Invalid.class, () -> r.updateProvider(1, "grok", true, Map.of("timeout_seconds", "1")));
            assertThrows(ProviderRegistry.Invalid.class, () -> r.updateProvider(1, "openai", true, Map.of("project_id", "valid")));
            assertThrows(ProviderRegistry.Invalid.class, () -> r.updateProvider(1, "openai", true, Map.of("output_tokens", "1")));
            for (var model : List.of(
                    new ProviderRegistry.Model("missing", "m", true, Set.of("chat"), "m"),
                    new ProviderRegistry.Model("openai", "", true, Set.of("chat"), "m"),
                    new ProviderRegistry.Model("openai", "has space", true, Set.of("chat"), "m"),
                    new ProviderRegistry.Model("openai", "m", true, Set.of(), "m"),
                    new ProviderRegistry.Model("openai", "m", true, Set.of("bad"), "m"),
                    new ProviderRegistry.Model("openai", "m", true, Set.of("chat"), "x".repeat(129)),
                    new ProviderRegistry.Model("openai", "m", true, Set.of("chat"), "bad\nname")))
                assertThrows(ProviderRegistry.Invalid.class, () -> r.upsertModel(1, model));
            assertSame(before, r.snapshot());
        }
    }

    @Test void validBoundariesAndMissingDefaultsRemainExplicit() throws Exception {
        Properties bootstrap = new Properties();
        bootstrap.setProperty("google.project_id", "project"); bootstrap.setProperty("google.location", "region");
        bootstrap.setProperty("openai.default_lm_model", "custom-text");
        bootstrap.setProperty("openai.vision_models", "custom-vision, ,custom-text");
        try (var r = ProviderRegistry.inMemory(bootstrap)) {
            r.updateProvider(1, "google", null, Map.of("timeout_seconds", "1", "output_tokens", "8192"));
            r.updateProvider(2, "google", true, Map.of("timeout_seconds", "150", "output_tokens", "1"));
            assertEquals("custom-text", ProviderRegistry.resolve(r.snapshot(), "chat", "", "", "openai").model());
            assertEquals("custom-text", ProviderRegistry.resolve(r.snapshot(), "chat", "openai", "", null).model());
            assertThrows(ProviderRegistry.Invalid.class, () -> ProviderRegistry.resolve(r.snapshot(), "image_generation", "", "", null));
            assertThrows(ProviderRegistry.Invalid.class, () -> ProviderRegistry.resolve(r.snapshot(), "image_generation", "", "", "openai"));
            assertThrows(ProviderRegistry.Invalid.class, () -> ProviderRegistry.resolve(r.snapshot(), "chat", "google", "", null));
            assertThrows(ProviderRegistry.Invalid.class, () -> ProviderRegistry.resolve(r.snapshot(), "chat", "", "m", ""));
            assertThrows(ProviderRegistry.Invalid.class, () -> ProviderRegistry.resolve(r.snapshot(), "bad", "", "", null));
            assertThrows(ProviderRegistry.Invalid.class, () -> r.setDefault(3, "bad", null));
        }
    }

    @Test void malformedWholeStateCannotBePublished() throws Exception {
        try (var r = ProviderRegistry.inMemory(new Properties())) {
            var original = r.snapshot(); var s = original.state();
            var providers = new HashMap<>(s.providers());
            providers.put("wrong-key", new ProviderRegistry.Provider("openai", true, Map.of()));
            assertThrows(ProviderRegistry.Invalid.class, () -> r.update(1, old -> new ProviderRegistry.State(1,1,providers,s.models(),s.defaults(),s.legacyDefaults())));
            var models = new HashMap<>(s.models());
            models.put("wrong-key", new ProviderRegistry.Model("openai", "m", true, Set.of("chat"), "m"));
            assertThrows(ProviderRegistry.Invalid.class, () -> r.update(1, old -> new ProviderRegistry.State(1,1,s.providers(),models,s.defaults(),s.legacyDefaults())));
            for (Map<String,String> invalid : List.of(Map.of("bad", "m"), Map.of("openai/bad", "m"), Map.of("openai/chat", "missing")))
                assertThrows(ProviderRegistry.Invalid.class, () -> r.update(1, old -> new ProviderRegistry.State(1,1,s.providers(),s.models(),s.defaults(),invalid)));
            assertSame(original, r.snapshot());
        }
    }
}
