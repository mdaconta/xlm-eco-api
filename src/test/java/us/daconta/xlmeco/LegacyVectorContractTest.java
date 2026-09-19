package us.daconta.xlmeco;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyVectorContractTest {
    static class Result<T> implements StreamObserver<T> {
        T value; boolean completed;
        public void onNext(T v) { value = v; }
        public void onError(Throwable t) { fail(t); }
        public void onCompleted() { completed = true; }
    }
    static class Vectors implements VectorDbProvider {
        VectorRecord record; boolean success = true;
        public String getProviderName() { return "fixture"; }
        public void initialize(Properties p) { }
        public boolean defineVectorSchema(int dimension, Map<String, us.daconta.xlmeco.provider.FieldType> metadata) {
            assertEquals(2, dimension); assertEquals(us.daconta.xlmeco.provider.FieldType.STRING, metadata.get("label")); return success;
        }
        public boolean upsertVector(String id, List<Float> vector, String content, Map<String, MetadataValue> metadata) {
            assertEquals("id", id); assertEquals(List.of(1f, 2f), vector); assertEquals("content", content);
            assertEquals("value", metadata.get("label").getStringValue()); return success;
        }
        public VectorRecord getVector(String id) { assertEquals("id", id); return record; }
        public boolean deleteVector(String id) { assertEquals("id", id); return success; }
        public List<VectorSearchResult> searchVectors(List<Float> vector, int k) {
            assertEquals(List.of(1f, 2f), vector); assertEquals(3, k);
            return success ? List.of(new VectorSearchResult("id", .75f, vector, "content", null)) : List.of();
        }
    }
    @Test void vectorRpcMappingAndEmptyResults() throws Exception {
        Properties props = new Properties(); props.setProperty("feature.vectordb.enabled", "false");
        VectorDbServiceImpl service = new VectorDbServiceImpl(props);
        Vectors vectors = new Vectors();
        var field = VectorDbServiceImpl.class.getDeclaredField("vectorDbProvider"); field.setAccessible(true); field.set(service, vectors);
        var schema = new Result<DefineVectorSchemaResponse>();
        service.defineVectorSchema(DefineVectorSchemaRequest.newBuilder().setEmbeddingDimension(2)
                .addFields(MetadataField.newBuilder().setName("label").setType(us.daconta.xlmeco.grpc.FieldType.STRING)).build(), schema);
        assertTrue(schema.value.getSuccess()); assertTrue(schema.completed);
        var upsert = new Result<UpsertVectorResponse>();
        service.upsertVector(UpsertVectorRequest.newBuilder().setId("id").addAllEmbedding(List.of(1f,2f)).setContent("content")
                .putMetadata("label", MetadataValue.newBuilder().setStringValue("value").build()).build(), upsert);
        assertEquals("id", upsert.value.getId()); assertTrue(upsert.completed);
        for (boolean success : List.of(true, false)) {
            vectors.success = success;
            var deletion = new Result<DeleteVectorResponse>(); service.deleteVector(DeleteVectorRequest.newBuilder().setId("id").build(), deletion);
            assertEquals(success, deletion.value.getSuccess()); assertTrue(deletion.completed);
            var search = new Result<SearchVectorsResponse>();
            service.searchVectors(SearchVectorsRequest.newBuilder().addAllQueryEmbedding(List.of(1f,2f)).setTopK(3).build(), search);
            assertEquals(success ? 1 : 0, search.value.getResultsCount()); assertTrue(search.completed);
            if (success) { assertEquals("id", search.value.getResults(0).getId()); assertEquals(.75f, search.value.getResults(0).getScore()); }
        }
        for (String metadata : new String[]{null, "", "{\"s\":\"value\",\"i\":42}"}) {
            vectors.record = new VectorRecord("id", List.of(1f,2f), "content", metadata);
            if (vectors.record.getMetadata() != null) vectors.record.getMetadata().put("f", 1.5f).put("d", 2.5d);
            var get = new Result<GetVectorResponse>(); service.getVector(GetVectorRequest.newBuilder().setId("id").build(), get);
            assertEquals("content", get.value.getContent()); assertEquals(List.of(1f,2f), get.value.getEmbeddingList()); assertTrue(get.completed);
            if (metadata != null && !metadata.isEmpty()) {
                assertEquals("value", get.value.getMetadataOrThrow("s").getStringValue());
                assertEquals(42, get.value.getMetadataOrThrow("i").getIntValue());
                assertEquals(1.5f, get.value.getMetadataOrThrow("f").getFloatValue());
            }
        }
        vectors.record = null; var missing = new Result<GetVectorResponse>();
        service.getVector(GetVectorRequest.newBuilder().setId("id").build(), missing); assertEquals("", missing.value.getId()); assertTrue(missing.completed);
        vectors.record = new VectorRecord("id", List.of(), "content", "{\"bad\":true}");
        assertThrows(IllegalArgumentException.class, () -> service.getVector(GetVectorRequest.newBuilder().setId("id").build(), new Result<>()));
        // Existing limitation: JSON decimal parsing produces BigDecimal, which this legacy RPC rejects.
        vectors.record = new VectorRecord("id", List.of(), "content", "{\"decimal\":1.5}");
        assertThrows(IllegalArgumentException.class, () -> service.getVector(GetVectorRequest.newBuilder().setId("id").build(), new Result<>()));
    }

    @Test void metadataTypesAndSearchRecordsPreserveValues() {
        for (Object value : List.of("text", 42, 1.5f, 2.5d)) {
            Object converted = MetadataValueConverter.toJavaObject(MetadataValueConverter.fromJavaObject(value));
            assertEquals(value instanceof Double ? 2.5f : value, converted);
        }
        assertNull(MetadataValueConverter.toJavaObject(MetadataValueConverter.fromJavaObject(null)));
        assertNull(MetadataValueConverter.toJavaObject(MetadataValueConverter.fromJavaObject(true)));
        for (String metadata : new String[]{null, "", "{\"label\":\"value\"}"}) {
            var result = new VectorSearchResult("id", .5f, List.of(1f), "content", metadata);
            assertEquals("id", result.getId()); assertEquals(.5f, result.getScore()); assertEquals(List.of(1f), result.getEmbedding());
            assertEquals("content", result.getContent());
            if (metadata == null || metadata.isEmpty()) assertNull(result.getMetadata());
            else assertEquals("value", result.getMetadata().getString("label"));
        }
    }

    @Test void factoriesOnlyLoadConfiguredProvidersAndFilterExactPrefixes() {
        Properties p = new Properties(); p.setProperty("openai.api_key", "synthetic"); p.setProperty("notopenai.api_key", "other");
        assertEquals(Map.of("api_key", "synthetic"), GenerativeProviderFactory.filterPropertiesForPrefix(p, "openai."));
        assertEquals("openai", GenerativeProviderFactory.getProvider("OPENAI").getProviderName());
        assertThrows(IllegalArgumentException.class, () -> GenerativeProviderFactory.getProvider("unsupported"));
        assertEquals(Set.of("openai"), GenerativeProviderFactory.loadProviders(p).keySet());
        assertEquals(Map.of(), VectorDbProviderFactory.loadProviders(new Properties()));
        assertEquals("milvus", VectorDbProviderFactory.getVectorDBProvider().getProviderName());
        var provider = GenerativeProviderFactory.getProvider("openai");
        assertTrue(provider.getSupportedCapabilities().get("chat"));
        assertFalse(provider.getSupportedCapabilities().get("rag"));
        assertNotNull(provider.getServiceLevel());
    }
}
