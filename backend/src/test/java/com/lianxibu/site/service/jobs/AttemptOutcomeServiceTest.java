package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class AttemptOutcomeServiceTest {
    ResultJdbcStoreTest fixture;AttemptOutcomeService service;HttpServer server;DeepSeekChatClient client;
    int status=200;String payload;boolean truncate;AtomicInteger calls=new AtomicInteger();
    volatile boolean stall;
    final java.util.concurrent.CountDownLatch headers=new java.util.concurrent.CountDownLatch(1),finishBody=new java.util.concurrent.CountDownLatch(1);
    @BeforeEach void setup() throws Exception {setup(true);}
    void setup(boolean send) throws Exception {
        fixture=new ResultJdbcStoreTest();fixture.initialize(send);
        for(String column:new String[]{"error_code VARCHAR(64)","retry_of_job_id VARCHAR(64)"})fixture.jdbc.execute("ALTER TABLE jobs ADD "+column);
        fixture.jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");
        fixture.jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");
        service=new AttemptOutcomeService(new AttemptUsageService(fixture.writes,new JdbcAttemptUsage(fixture.source),fixture.clock,"fictional-epoch"),
                new AttemptFailureService(fixture.writes,new JdbcAttemptFailure(fixture.source),fixture.clock,"fictional-epoch"),
                new TransportUnknownService(fixture.writes,new JdbcTransportUnknown(fixture.source),fixture.clock,"fictional-epoch"),
                id->new AttemptOutcomeService.Application(fixture.store.input(),fixture.service));
        payload="{\"kind\":\"report\",\"body\":"+ResultJdbcStoreTest.BODY+"}";
        server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] response=("{\"id\":\"fictional-provider\",\"model\":\"deepseek-flash\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"+JsonMapper.shared().writeValueAsString(payload)+"}}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":12}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,response.length+(truncate?20:0));
            if(stall){exchange.getResponseBody().write(response,0,1);exchange.getResponseBody().flush();headers.countDown();try{finishBody.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();return;}
            exchange.getResponseBody().write(response);exchange.close();
        });server.start();client=DeepSeekLoopbackFixture.client(HttpClient.newHttpClient(),URI.create("http://localhost:"+server.getAddress().getPort()+"/chat/completions"));
    }
    @AfterEach void stop(){finishBody.countDown();if(server!=null)server.stop(0);}
    DeepSeekChatClient.Outcome response(){return client.sendOnce(fixture.identity.frozenRequestJson());}
    @Test void completeResponsePersistsUsageAndAppliesOnceAcrossReplay() {
        var outcome=response();var completed=service.consume(fixture.identity,outcome);
        assertEquals(AttemptOutcomeService.State.APPLIED,completed.state());assertEquals(1,fixture.handlerCalls);
        assertEquals("fictional-provider",fixture.jdbc.queryForObject("SELECT provider_request_id FROM jobs WHERE id=?",String.class,fixture.identity.attemptId()));
        assertEquals("free",new JdbcWorkerState(fixture.source).slot().state());
        assertEquals(AttemptOutcomeService.State.APPLIED,service.consume(fixture.identity,outcome).state());
        assertEquals(1,fixture.handlerCalls);assertEquals(1,calls.get());
    }
    @Test void semanticFailurePreparesOnlyTheBoundedOriginalRequestRetry() {
        payload="{\"answer\":1}";var outcome=response();
        assertEquals(AttemptOutcomeService.State.KNOWN_FAILURE,service.consume(fixture.identity,outcome).state());
        assertEquals("waiting_retry",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
        service.consume(fixture.identity,outcome);assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));assertEquals(1,calls.get());
    }
    @Test void unexpectedClientFailureBlocksConfigurationAndDoesNotRetry() {
        status=400;service.consume(fixture.identity,response());
        assertEquals("blocked_configuration",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
    }
    @Test void throttlingUsesKnownRetryAndIncompleteResponseIsUnknown() {
        status=429;service.consume(fixture.identity,response());assertEquals("waiting_retry",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
    }
    @Test void incompleteResponseReleasesWithoutRefundOrNewAttempt() {
        truncate=true;var outcome=response();assertEquals(AttemptOutcomeService.State.UNKNOWN,service.consume(fixture.identity,outcome).state());
        assertEquals("unknown_external",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
        assertEquals(1,new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).callCount());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
    }
    @Test void applicationStorageFailureNeverBecomesInvalidModelResultOrAnotherSend() {
        var outcome=response();fixture.failDownstream=true;
        assertThrows(RuntimeException.class,()->service.consume(fixture.identity,outcome));
        assertEquals("running",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
        fixture.failDownstream=false;assertEquals(AttemptOutcomeService.State.APPLIED,service.consume(fixture.identity,outcome).state());assertEquals(1,calls.get());
    }
}
