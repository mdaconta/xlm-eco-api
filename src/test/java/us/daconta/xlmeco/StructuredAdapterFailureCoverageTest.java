package us.daconta.xlmeco;

import org.json.*;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.grpc.StructuredImageErrorCode;
import us.daconta.xlmeco.provider.StructuredImageProvider;
import us.daconta.xlmeco.provider.impl.OpenAIProvider;
import static org.junit.jupiter.api.Assertions.*;

class StructuredAdapterFailureCoverageTest {
    final StructuredImageProvider.Input input = new StructuredImageProvider.Input("Describe", "image/png",new byte[]{1},"requested-model","{}");
    static JSONObject answer(Object content) {
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop")
                .put("message",new JSONObject().put("content",content))));
    }
    @Test void structuredEnvelopeAndOutputFailuresStayDistinctAndSafe() throws Exception {
        try(var e=new ImageGenerationAdapterTest.Endpoint()) {
            var p=(OpenAIProvider)e.provider(true);
            assertFalse(p.supportsStructuredImageModel(null));assertFalse(p.supportsStructuredImageModel(" "));assertTrue(p.supportsStructuredImageModel("anything"));
            for(String body:new String[]{"", "null", "[]", "{", "{}", "{\"pad\":\""+"x".repeat(1_048_577)+"\"}"}) {
                e.body.set(body);assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                        assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input)).code());
            }
            for(String content:new String[]{"null","[]","{", ""}) {
                e.body.set(answer(content).toString());assertEquals(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                        assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input)).code());
            }
            var refusal=answer("{}");refusal.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal","PRIVATE UPSTREAM REFUSAL");
            e.body.set(refusal.toString());var failure=assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input));
            assertEquals(StructuredImageErrorCode.PROVIDER_FAILURE,failure.code());assertFalse(failure.getMessage().contains("PRIVATE"));
            refusal.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal","");
            e.body.set(refusal.toString());assertEquals("{}",p.generateStructuredImage(input).jsonPayload());
            var truncated=answer("{}");truncated.getJSONArray("choices").getJSONObject(0).put("finish_reason","length");
            e.body.set(truncated.toString());assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input)).code());
            e.body.set(answer("{}").put("model","").toString());assertEquals(input.model(),p.generateStructuredImage(input).model());
        }
    }
    @Test void statusClassificationDoesNotExposeErrorDetailsOrCauses() throws Exception {
        try(var e=new ImageGenerationAdapterTest.Endpoint()) {
            var p=(OpenAIProvider)e.provider(true);
            for(int status:new int[]{400,401,403,408,429,500,504}) {
                e.status.set(status);e.body.set("{\"error\":{\"type\":\"other\",\"message\":\"PRIVATE\"}}");
                var f=assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input));
                assertEquals(status,f.httpStatus());assertFalse(f.getMessage().contains("PRIVATE"));assertNull(f.getCause());
            }
            e.status.set(429);e.body.set("{\"error\":{\"type\":\"insufficient_quota\"}}");
            assertEquals(StructuredImageErrorCode.PROVIDER_QUOTA,assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input)).code());
            e.body.set("invalid error body");assertEquals(StructuredImageErrorCode.PROVIDER_RATE_LIMIT,assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input)).code());
        }
        try(var e=new ImageGenerationAdapterTest.Endpoint()) {
            var p=(OpenAIProvider)e.provider(true);e.server.stop(0);
            var failure=assertThrows(StructuredImageProvider.Failure.class,()->p.generateStructuredImage(input));
            assertEquals(StructuredImageErrorCode.PROVIDER_UNAVAILABLE,failure.code());assertNull(failure.getCause());
        }
    }
    @Test void boundedGenerationTransportTimeoutAndConnectionFailuresAreSafe() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            try { release.await(5,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });server.start();
        try {
            for(boolean openai:new boolean[]{true,false}) {
                var properties=new java.util.Properties();properties.setProperty("timeout_seconds","1");properties.setProperty("api_key","synthetic");
                String url="http://127.0.0.1:"+server.getAddress().getPort()+"/";
                properties.setProperty("image_url",url);properties.setProperty("chat_url",url);
                us.daconta.xlmeco.provider.ImageGenerationProvider provider;
                if(openai){var p=new OpenAIProvider();p.initialize(properties);provider=p;}
                else {var p=new us.daconta.xlmeco.provider.impl.GoogleProvider();p.initialize(properties);provider=p;}
                var failure=assertThrows(StructuredImageProvider.Failure.class,()->provider.generateImage(ImageGenerationAdapterTest.INPUT));
                assertEquals(StructuredImageErrorCode.PROVIDER_TIMEOUT,failure.code());assertTrue(failure.retryable());assertNull(failure.getCause());
            }
        } finally {release.countDown();server.stop(0);executor.shutdownNow();}
        try(var e=new ImageGenerationAdapterTest.Endpoint()) {
            var provider=e.provider(false);e.server.stop(0);
            assertEquals(StructuredImageErrorCode.PROVIDER_UNAVAILABLE,
                    assertThrows(StructuredImageProvider.Failure.class,()->provider.generateImage(ImageGenerationAdapterTest.INPUT)).code());
        }
    }
    @Test void generationQuotaFallbackAndKnownRateLimitRemainDistinct() throws Exception {
        try(var e=new ImageGenerationAdapterTest.Endpoint()) {
            var provider=e.provider(false);
            e.body.set("");assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                    assertThrows(StructuredImageProvider.Failure.class,()->provider.generateImage(ImageGenerationAdapterTest.INPUT)).code());
            e.status.set(429);e.body.set("{\"error\":{\"type\":\"insufficient_quota\"}}");
            assertEquals(StructuredImageErrorCode.PROVIDER_QUOTA,
                    assertThrows(StructuredImageProvider.Failure.class,()->provider.generateImage(ImageGenerationAdapterTest.INPUT)).code());
            e.body.set("{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
            var rate=assertThrows(StructuredImageProvider.Failure.class,()->provider.generateImage(ImageGenerationAdapterTest.INPUT));
            assertEquals(StructuredImageErrorCode.PROVIDER_RATE_LIMIT,rate.code());assertTrue(rate.retryable());
        }
    }
}
