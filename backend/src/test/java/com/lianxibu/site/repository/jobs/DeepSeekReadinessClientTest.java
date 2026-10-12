package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry.Status;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DeepSeekReadinessClientTest {
    HttpServer server;URI base;List<String> requests;AtomicInteger keyReads;int modelStatus,balanceStatus;String models,balance;
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"),ZoneOffset.UTC);
    @BeforeEach void setup() throws Exception {
        requests=Collections.synchronizedList(new ArrayList<>());keyReads=new AtomicInteger();modelStatus=balanceStatus=200;
        models="{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-flash\",\"object\":\"model\",\"owned_by\":\"deepseek\",\"name\":\"Fictional display name\"}]}";
        balance="{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"10.00\",\"granted_balance\":\"1.00\",\"topped_up_balance\":\"9.00\"}]}";
        server=HttpServer.create(new InetSocketAddress("localhost",0),0);base=URI.create("http://localhost:"+server.getAddress().getPort());
        server.createContext("/",exchange->{
            assertEquals("GET",exchange.getRequestMethod());assertEquals("Bearer fictional-readiness-key",exchange.getRequestHeaders().getFirst("Authorization"));
            String path=exchange.getRequestURI().getPath();requests.add(path);boolean model="/models".equals(path);var bytes=(model?models:balance).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");if(modelStatus==302)exchange.getResponseHeaders().set("Location",base.resolve("/user/balance").toString());
            exchange.sendResponseHeaders(model?modelStatus:balanceStatus,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
    }
    @AfterEach void close(){TransactionSynchronizationManager.clear();server.stop(0);Thread.interrupted();}
    DeepSeekReadinessClient client(){return new DeepSeekReadinessClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),base,()->{keyReads.incrementAndGet();return "fictional-readiness-key";},CLOCK,Duration.ofSeconds(2));}
    @Test void actualTwoAuthenticatedGetsAreReadyAndCaptureOnlyFinishedMicros() {
        var value=client();assertEquals(0,keyReads.get());assertTrue(requests.isEmpty());
        var fact=value.read();assertNotNull(fact);assertEquals(Status.READY,fact.status());assertEquals(Instant.parse("2026-01-01T00:00:00.123456Z"),fact.receivedAt());
        assertEquals(List.of("/models","/user/balance"),requests);assertEquals(1,keyReads.get());assertEquals("ProviderReadinessEvidence[redacted]",fact.toString());
    }
    @Test void explicitCredentialAndBalanceFailuresRemainKnownWithoutAnotherGet() {
        for(int code:List.of(401,403,402)){requests.clear();modelStatus=code;var fact=client().read();assertNotNull(fact);assertEquals(code==402?Status.BALANCE_UNAVAILABLE:Status.CREDENTIAL_REJECTED,fact.status());assertEquals(List.of("/models"),requests);}
        modelStatus=200;balanceStatus=402;assertEquals(Status.BALANCE_UNAVAILABLE,client().read().status());
    }
    @Test void absentConfiguredModelAndActualUnavailableBalanceAreExplicit() {
        models=models.replace("deepseek-flash","fictional-other-model");assertEquals(Status.MODEL_UNAVAILABLE,client().read().status());assertEquals(List.of("/models"),requests);
        models=models.replace("fictional-other-model","deepseek-flash");balance=balance.replace("true","false");assertEquals(Status.BALANCE_UNAVAILABLE,client().read().status());
    }
    @Test void malformedPartialOrOversizedModelListNeverSynthesizesReady() {
        String original=models;
        for(String raw:List.of("{}","{\"object\":\"list\",\"data\":null}",original.replace("\"model\"","\"wrong\""),original.replace("\"deepseek\"","\"fictional\""),original.substring(0,original.length()-1)," ".repeat(262145))){requests.clear();models=raw;assertNull(client().read());assertEquals(List.of("/models"),requests);}
    }
    @Test void unqualifiedBalanceIsUnknownAndNeverRetainedInDiagnostics() {
        String original=balance;
        for(String raw:List.of("{}",original.replace("true","\"true\""),original.replace("10.00","NaN"),original.replace("10.00","0.00"),original.replace("CNY","fictional"),original.substring(0,original.length()-1))){balance=raw;assertNull(client().read());}
    }
    @Test void redirectServerFailureAndBadCredentialNeverRetry() {
        for(int code:List.of(302,429,500)){requests.clear();modelStatus=code;assertNull(client().read());assertEquals(List.of("/models"),requests);}
        requests.clear();var bad=new DeepSeekReadinessClient(HttpClient.newHttpClient(),base,()->"fictional\r\ninjected",CLOCK,Duration.ofSeconds(2));assertNull(bad.read());assertTrue(requests.isEmpty());
    }
    @Test void activeTransactionOrInterruptionDoesNotAcquireCredentialOrNetwork() {
        var value=client();TransactionSynchronizationManager.setActualTransactionActive(true);assertNull(value.read());assertEquals(0,keyReads.get());assertTrue(requests.isEmpty());
        TransactionSynchronizationManager.clear();Thread.currentThread().interrupt();assertNull(value.read());assertTrue(Thread.currentThread().isInterrupted());assertTrue(requests.isEmpty());
    }
    @Test void endpointsRedirectPolicyAndTimeoutAreHostControlled() {
        for(String endpoint:List.of("https://fictional.invalid","https://api.deepseek.com/chat/completions","https://api.deepseek.com?secret=fictional","https://fictional@api.deepseek.com","https://api.deepseek.com#fragment","http://127.0.0.1:12","https://api.deepseek.com:444"))assertThrows(IllegalArgumentException.class,()->new DeepSeekReadinessClient(HttpClient.newHttpClient(),URI.create(endpoint),()->"fictional",CLOCK,Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,()->new DeepSeekReadinessClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),base,()->"fictional",CLOCK,Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,()->new DeepSeekReadinessClient(HttpClient.newHttpClient(),base,()->"fictional",CLOCK,Duration.ofSeconds(13)));
    }
}
