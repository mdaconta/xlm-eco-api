package us.daconta.xlmeco;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.milvus.grpc.*;
import org.junit.jupiter.api.*;
import us.daconta.xlmeco.provider.impl.MilvusDbProvider;
import us.daconta.xlmeco.grpc.MetadataValue;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK serialization against an isolated loopback Milvus wire fixture. */
class MilvusAdapterContractTest {
    private Server server;
    private Fixture fixture;
    private MilvusDbProvider provider;
    @BeforeEach void start() throws Exception {
        fixture = new Fixture(); server = ServerBuilder.forPort(0).addService(fixture).build().start();
        Properties p = new Properties(); p.setProperty("host", "127.0.0.1"); p.setProperty("port", "" + server.getPort());
        provider = new MilvusDbProvider(); provider.initialize(p);
    }
    @AfterEach void stop() throws Exception {
        // Legacy provider exposes no close; close its owned SDK channel in the test fixture.
        var field = MilvusDbProvider.class.getDeclaredField("milvusClient"); field.setAccessible(true);
        ((io.milvus.client.MilvusServiceClient) field.get(provider)).close();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    static Status ok() { return Status.newBuilder().setErrorCode(ErrorCode.Success).build(); }
    static <T> void reply(StreamObserver<T> out, T value) { out.onNext(value); out.onCompleted(); }
    static FieldData stringField(String name, String value) {
        return FieldData.newBuilder().setFieldName(name).setType(DataType.VarChar)
                .setScalars(ScalarField.newBuilder().setStringData(StringArray.newBuilder().addData(value))).build();
    }
    static FieldData vectorField() {
        return FieldData.newBuilder().setFieldName("embedding").setType(DataType.FloatVector)
                .setVectors(VectorField.newBuilder().setDim(2).setFloatVector(FloatArray.newBuilder().addData(1).addData(2))).build();
    }
    static class Fixture extends MilvusServiceGrpc.MilvusServiceImplBase {
        String failure = ""; boolean exists, indexExists = true, metadata, emptyQuery;
        LoadState state = LoadState.LoadStateLoaded;
        Deque<LoadState> states = new ArrayDeque<>(); int stateReads;
        CreateCollectionRequest created; InsertRequest inserted; DeleteRequest deleted; SearchRequest searched;
        Status status(String operation) { return failure.equals(operation) ? Status.newBuilder().setErrorCode(ErrorCode.UnexpectedError).setReason("fixture failure").build() : ok(); }
        public void connect(ConnectRequest r, StreamObserver<ConnectResponse> o) { reply(o, ConnectResponse.newBuilder().setStatus(ok()).build()); }
        public void hasCollection(HasCollectionRequest r, StreamObserver<BoolResponse> o) { reply(o, BoolResponse.newBuilder().setStatus(status("has")).setValue(exists).build()); }
        public void createCollection(CreateCollectionRequest r, StreamObserver<Status> o) { created=r; reply(o,status("create")); }
        public void describeIndex(DescribeIndexRequest r, StreamObserver<DescribeIndexResponse> o) {
            var b=DescribeIndexResponse.newBuilder().setStatus(status("describeIndex"));
            if(failure.equals("missingIndex")) b.setStatus(Status.newBuilder().setErrorCode(ErrorCode.IndexNotExist));
            if(failure.equals("missingIndexMessage")) b.setStatus(Status.newBuilder().setErrorCode(ErrorCode.UnexpectedError).setReason("index not found"));
            if(failure.startsWith("missingIndex"))failure="";
            if(indexExists) b.addIndexDescriptions(IndexDescription.newBuilder().setIndexName("fixture").setFieldName("embedding").setState(IndexState.Finished)); reply(o,b.build());
        }
        public void createIndex(CreateIndexRequest r, StreamObserver<Status> o) { indexExists=true; reply(o,status("index")); }
        public void loadCollection(LoadCollectionRequest r, StreamObserver<Status> o) { reply(o,status("load")); }
        public void showCollections(ShowCollectionsRequest r, StreamObserver<ShowCollectionsResponse> o) { reply(o,ShowCollectionsResponse.newBuilder().setStatus(ok()).addCollectionNames("vectors").addCollectionIds(1).addInMemoryPercentages(100).build()); }
        public void getLoadState(GetLoadStateRequest r, StreamObserver<GetLoadStateResponse> o) {
            stateReads++;
            reply(o,GetLoadStateResponse.newBuilder().setStatus(status(failure.equals("poll") && stateReads>1 ? "poll" : "state"))
                .setState(states.isEmpty()?state:states.removeFirst()).build());
        }
        public void describeCollection(DescribeCollectionRequest r, StreamObserver<DescribeCollectionResponse> o) {
            var schema=CollectionSchema.newBuilder().setName("vectors")
                .addFields(FieldSchema.newBuilder().setName("id").setDataType(DataType.VarChar).setIsPrimaryKey(true).addTypeParams(KeyValuePair.newBuilder().setKey("max_length").setValue("255")))
                .addFields(FieldSchema.newBuilder().setName("embedding").setDataType(DataType.FloatVector).addTypeParams(KeyValuePair.newBuilder().setKey("dim").setValue("2")))
                .addFields(FieldSchema.newBuilder().setName("content").setDataType(DataType.VarChar).addTypeParams(KeyValuePair.newBuilder().setKey("max_length").setValue("1024")));
            if(metadata) schema.addFields(FieldSchema.newBuilder().setName("metadata").setDataType(DataType.VarChar).addTypeParams(KeyValuePair.newBuilder().setKey("max_length").setValue("8192")));
            reply(o,DescribeCollectionResponse.newBuilder().setStatus(ok()).setSchema(schema).build());
        }
        public void insert(InsertRequest r, StreamObserver<MutationResult> o) { inserted=r; reply(o,MutationResult.newBuilder().setStatus(status("insert")).build()); }
        public void delete(DeleteRequest r, StreamObserver<MutationResult> o) { deleted=r; reply(o,MutationResult.newBuilder().setStatus(status("delete")).build()); }
        public void flush(FlushRequest r, StreamObserver<FlushResponse> o) { reply(o,FlushResponse.newBuilder().setStatus(status("flush")).build()); }
        public void query(QueryRequest r, StreamObserver<QueryResults> o) {
            var vector=vectorField();if(emptyQuery)vector=vector.toBuilder().setVectors(VectorField.newBuilder().setDim(2).setFloatVector(FloatArray.getDefaultInstance())).build();
            var b=QueryResults.newBuilder().setStatus(status("query")).addFieldsData(vector).addFieldsData(stringField("content","content"));
            if(metadata) b.addFieldsData(stringField("metadata","{\"label\":\"value\"}")); reply(o,b.build());
        }
        public void search(SearchRequest r, StreamObserver<SearchResults> o) {
            searched=r;
            reply(o,SearchResults.newBuilder().setStatus(status("search")).setResults(SearchResultData.newBuilder()
                .setNumQueries(1).setTopK(1).addTopks(1).addScores(.5f).setIds(IDs.newBuilder().setStrId(StringArray.newBuilder().addData("id")))
                .addAllOutputFields(List.of("id","embedding","content"))
                .addFieldsData(stringField("id","id")).addFieldsData(vectorField()).addFieldsData(stringField("content","content"))).build());
        }
    }
    @Test void definesSchemaAndPreservesExistingIndex() throws Exception {
        assertTrue(provider.defineVectorSchema(2,null));
        var schema=CollectionSchema.parseFrom(fixture.created.getSchema()); assertEquals(3,schema.getFieldsCount());
        fixture.exists=true; fixture.created=null; assertTrue(provider.defineVectorSchema(2,Map.of())); assertNull(fixture.created);
        fixture.exists=false; assertTrue(provider.defineVectorSchema(2,Map.of())); assertEquals(4,CollectionSchema.parseFrom(fixture.created.getSchema()).getFieldsCount());
    }
    @Test void configuredVectorFeatureLoadsMilvusThroughSpi() throws Exception {
        Properties properties=new Properties();properties.setProperty("feature.vectordb.enabled","true");
        properties.setProperty("milvus.host","127.0.0.1");properties.setProperty("milvus.port",""+server.getPort());
        var service=new VectorDbServiceImpl(properties);
        var field=VectorDbServiceImpl.class.getDeclaredField("vectorDbProvider");field.setAccessible(true);
        var loaded=(MilvusDbProvider)field.get(service);assertEquals("milvus",loaded.getProviderName());
        var client=MilvusDbProvider.class.getDeclaredField("milvusClient");client.setAccessible(true);
        ((io.milvus.client.MilvusServiceClient)client.get(loaded)).close();
    }
    @Test void schemaAndIndexFailuresAreNotReportedAsSuccess() {
        for(String stage:List.of("create","describeIndex","index","load")) {
            fixture.exists=false; fixture.indexExists=false; fixture.failure=stage;
            assertThrows(RuntimeException.class,()->provider.defineVectorSchema(2,null),stage);
        }
        fixture.failure=""; fixture.indexExists=false; assertTrue(provider.defineVectorSchema(2,null));
        for(String missing:List.of("missingIndex","missingIndexMessage")) {
            fixture.failure=missing;fixture.indexExists=false;assertTrue(provider.defineVectorSchema(2,null));
        }
    }
    @Test void writesFlushesAndDeletesUseExpectedWireValues() {
        assertTrue(provider.upsertVector("id",List.of(1f,2f),"content",null));
        assertEquals("vectors",fixture.inserted.getCollectionName()); assertEquals(3,fixture.inserted.getFieldsDataCount());
        fixture.failure="insert"; assertFalse(provider.upsertVector("id",List.of(1f,2f),"content",null));
        fixture.failure="flush"; assertThrows(RuntimeException.class,()->provider.upsertVector("id",List.of(1f,2f),"content",null));
        fixture.failure=""; assertTrue(provider.deleteVector("id")); assertEquals("id == \"id\"",fixture.deleted.getExpr());
        fixture.failure="delete"; assertFalse(provider.deleteVector("id"));
    }
    @Test void retrievalAndSearchHandleProviderFailureAndExposeKnownMetadataDefect() {
        var record=provider.getVector("id"); assertEquals("content",record.getContent()); assertEquals(List.of(1f,2f),record.getEmbedding()); assertNull(record.getMetadata());
        fixture.metadata=true; assertThrows(NullPointerException.class,()->provider.getVector("id"),"Known legacy defect: metadata field wrapper is not assigned");
        fixture.failure="query"; assertThrows(RuntimeException.class,()->provider.getVector("id"));
        fixture.failure="search"; assertEquals(List.of(),provider.searchVectors(List.of(1f,2f),3));
        fixture.failure=""; var results=provider.searchVectors(List.of(1f,2f),3); assertEquals(1,results.size()); assertEquals("id",results.get(0).getId()); assertEquals(.5f,results.get(0).getScore());
        fixture.metadata=false;fixture.emptyQuery=true;assertNull(provider.getVector("missing"));fixture.emptyQuery=false;
        fixture.failure="state"; assertThrows(RuntimeException.class,()->provider.getVector("id"));
        fixture.state=LoadState.LoadStateNotLoad; fixture.failure="load"; assertThrows(RuntimeException.class,()->provider.getVector("id"));
    }
    @Test void metadataSerializationRetainsSupportedTypes() {
        fixture.metadata=true;
        var metadata=new LinkedHashMap<String,MetadataValue>();
        metadata.put("s",MetadataValue.newBuilder().setStringValue("text").build());
        metadata.put("i",MetadataValue.newBuilder().setIntValue(42).build());
        metadata.put("f",MetadataValue.newBuilder().setFloatValue(1.5f).build());
        metadata.put("unset",MetadataValue.getDefaultInstance());
        assertTrue(provider.upsertVector("id",List.of(1f,2f),"content",metadata));
        var field=fixture.inserted.getFieldsDataList().stream().filter(f->f.getFieldName().equals("metadata")).findFirst().orElseThrow();
        var json=new org.json.JSONObject(field.getScalars().getStringData().getData(0));
        assertEquals("text",json.getString("s")); assertEquals(42,json.getInt("i")); assertEquals(1.5,json.getDouble("f"));
        assertTrue(provider.upsertVector("id",List.of(1f,2f),"content",Map.of()));
        metadata.put("bad",null); assertThrows(RuntimeException.class,()->provider.upsertVector("id",List.of(1f,2f),"content",metadata));
    }
    @Test void loadingPollsUntilReadyAndRejectsFailedLoad() {
        fixture.states.addAll(List.of(LoadState.LoadStateNotLoad,LoadState.LoadStateLoading,LoadState.LoadStateLoaded));
        assertEquals("content",provider.getVector("id").getContent()); assertEquals(3,fixture.stateReads);
        fixture.stateReads=0; fixture.states.addAll(List.of(LoadState.LoadStateNotLoad,LoadState.LoadStateNotLoad));
        assertThrows(RuntimeException.class,()->provider.getVector("id"));
        fixture.stateReads=0; fixture.failure="poll"; fixture.states.add(LoadState.LoadStateNotLoad);
        assertThrows(RuntimeException.class,()->provider.getVector("id"));
    }
}
