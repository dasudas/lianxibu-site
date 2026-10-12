package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.DeepSeekChatClient.Status;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekChatClientTest {
    private HttpServer server;
    private DeepSeekChatClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = reply("{\"answer\":1}", true);
    private volatile boolean truncate;
    private volatile boolean stall;
    private final CountDownLatch headersSent=new CountDownLatch(1);
    private final CountDownLatch finishBody=new CountDownLatch(1);

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Retry-After", "7");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length + (truncate ? 20 : 0));
            if(stall) {
                exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();headersSent.countDown();
                try { finishBody.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                exchange.close();return;
            }
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new DeepSeekChatClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/chat/completions"), () -> "fixture-key");
    }
    @AfterEach void stop() { finishBody.countDown();server.stop(0); }

    @Test void responseBodyCannotOutliveTotalDeadlineAndIsNeverRetried() {
        stall=true;
        var bounded=new DeepSeekChatClient(HttpClient.newHttpClient(),URI.create("http://localhost:"+server.getAddress().getPort()+"/chat/completions"),()->"fictional-key",Duration.ofMillis(500));
        var result=assertTimeoutPreemptively(Duration.ofSeconds(3),()->bounded.sendOnce(request()));
        assertEquals(Status.UNKNOWN,result.status());assertEquals(1,calls.get());assertEquals(0,headersSent.getCount());
    }
    @Test void interruptCancelsTheActualBodyWaitAndPreservesInterruptStatus() throws Exception {
        stall=true;var outcome=new AtomicReference<DeepSeekChatClient.Outcome>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        Thread sender=new Thread(()->{outcome.set(client.sendOnce(request()));interrupted.set(Thread.currentThread().isInterrupted());});
        sender.start();try {
            assertTrue(headersSent.await(3,TimeUnit.SECONDS));sender.interrupt();sender.join(2000);
            assertFalse(sender.isAlive());assertEquals(Status.UNKNOWN,outcome.get().status());assertTrue(interrupted.get());assertEquals(1,calls.get());
        } finally {finishBody.countDown();sender.interrupt();sender.join(3000);}
    }
    @Test void oversizedResponseCancelsWithoutAnotherSend() {
        response="x".repeat(2*1024*1024+1);
        assertEquals(Status.INVALID_JSON,client.sendOnce(request()).status());assertEquals(1,calls.get());
    }

    @Test void sendsOneOfficialShapeAndPreservesUnknownCost() throws Exception {
        var outcome = client.sendOnce(request());
        assertEquals(1, calls.get());
        assertEquals(Status.VALID, outcome.status());
        assertEquals("response-1", outcome.providerRequestId());
        assertEquals("{\"answer\":1}", outcome.contentJson());
        assertEquals(12L, outcome.usage().totalTokens());
        assertNull(outcome.usage().cacheHitTokens());
        assertEquals(DeepSeekChatClient.CostState.UNKNOWN, outcome.costState());
        assertEquals("Bearer fixture-key", authorization.get());
        var wire = JsonMapper.shared().readTree(received.get());
        assertEquals("deepseek-flash", wire.get("model").asText());
        assertEquals("json_object", wire.get("response_format").get("type").asText());
        assertEquals(128, wire.get("max_tokens").asInt());
        assertFalse(wire.has("input_hash"));
        assertFalse(wire.has("prompt_version"));
        assertFalse(wire.has("provider"));
        assertFalse(outcome.toString().contains("answer"));
    }

    @Test void receivedFailuresAndBadJsonNeverCauseAnotherHttpSend() {
        status = 429;
        response = "{\"error\":\"rate limit\"}";
        var rateLimit = client.sendOnce(request());
        assertEquals(Status.DEFINITE_FAILURE, rateLimit.status());
        assertEquals(7, rateLimit.retryAfterSeconds());
        assertNull(rateLimit.usage());
        assertEquals(1, calls.get());
        status = 402;
        assertEquals(Status.BLOCKED_CONFIGURATION, client.sendOnce(request()).status());
        assertEquals(2, calls.get());
        status = 200;
        response = reply("not-json", false);
        var invalid = client.sendOnce(request());
        assertEquals(Status.INVALID_JSON, invalid.status());
        assertNull(invalid.usage());
        assertEquals(3, calls.get());
    }

    @Test void absentUsageStaysUnknownAndTruncatedOutputIsInvalid() {
        response = reply("{\"answer\":1}", false);
        assertNull(client.sendOnce(request()).usage());
        response = response.replace("\"stop\"", "\"length\"");
        assertEquals(Status.INVALID_JSON, client.sendOnce(request()).status());
        assertEquals(2, calls.get());
    }

    @Test void invalidUsageIsUnknownWithoutLongOverflowOrInventedZero() {
        for(String usage:java.util.List.of(
                "{\"prompt_tokens\":18446744073709551624,\"completion_tokens\":4,\"total_tokens\":12}",
                "{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":13}",
                "{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":12,\"prompt_cache_hit_tokens\":9}")) {
            response=reply("{\"answer\":1}",false).replace("}]}","}],\"usage\":"+usage+"}");
            var result=client.sendOnce(request());assertEquals(Status.VALID,result.status());assertNull(result.usage());
        }
    }

    @Test void incompleteHttpBodyIsUnknownAndNeverResent() {
        truncate = true;
        var outcome = client.sendOnce(request());
        assertEquals(Status.UNKNOWN, outcome.status());
        assertNull(outcome.usage());
        assertEquals(1, calls.get());
    }

    @Test void invalidOrSecretBearingFrozenRequestFailsBeforeNetwork() {
        assertThrows(IllegalArgumentException.class,
                () -> client.sendOnce(request().replace("deepseek-flash", "other-model")));
        assertThrows(IllegalArgumentException.class,
                () -> client.sendOnce(request().replace("\"provider\"",
                        "\"api_key\":\"fake\",\"provider\"")));
        assertEquals(0, calls.get());
    }

    private static String request() {
        return "{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"give json\"}],"
                + "\"parameters\":{\"max_tokens\":128},\"response_format\":{\"type\":"
                + "\"json_object\"},\"input_hash\":\"" + "a".repeat(64) + "\","
                + "\"schema_version\":\"v1\",\"prompt_version\":\"v1\"}";
    }
    private static String reply(String content, boolean usage) {
        return "{\"id\":\"response-1\",\"model\":\"deepseek-flash\","
                + "\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + JsonMapper.shared().writeValueAsString(content) + "}}]"
                + (usage ? ",\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":4,"
                    + "\"total_tokens\":12}" : "") + "}";
    }
}
