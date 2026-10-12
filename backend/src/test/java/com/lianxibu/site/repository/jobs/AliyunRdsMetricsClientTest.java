package com.lianxibu.site.repository.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.RdsTelemetry.Resource;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class AliyunRdsMetricsClientTest {
    static final Instant NOW=Instant.parse("2026-10-01T00:10:00Z");
    static final Resource RESOURCE=new Resource("1212121212121212","cn-hangzhou","rm-fictional");
    static final AliyunRdsMetricsClient.Credentials CREDS=new AliyunRdsMetricsClient.Credentials("fictionalAccessId000001","fictionalSecret000000000000000001");
    @Test void signatureMatchesIndependentReferenceWithCanonicalQuery() {
        var signed=AliyunRdsMetricsClient.sign("rds.aliyuncs.com","DescribeDBInstanceAttribute","2014-08-15",Map.of("DBInstanceId","rm-fictional","Text","space +/~*"),CREDS,NOW,"fictional-nonce");
        assertEquals("DBInstanceId=rm-fictional&Text=space%20%2B%2F~%2A",signed.query());
        assertEquals("dee6f861469606fb1fd24317c411526b8f533b47709e2c0f69f53a052d915eeb",signed.headers().get("Authorization").substring(signed.headers().get("Authorization").indexOf("Signature=")+10));
        assertFalse(CREDS.toString().contains("fictionalSecret"));
    }
    @Test void threeReadOnlyCallsUseExactDimensionsAndFreshNonceWithoutTransaction()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("localhost",0),0);var seen=new ArrayList<String>();var nonces=new HashSet<String>();
        server.createContext("/",exchange->{
            seen.add(exchange.getRequestHeaders().getFirst("x-acs-action"));nonces.add(exchange.getRequestHeaders().getFirst("x-acs-signature-nonce"));
            String query=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            if(seen.size()==1)assertTrue(query.contains("DBInstanceId=rm-fictional"));
            if(seen.size()==2)assertTrue(query.contains("MetricName=DiskUsage"));
            if(seen.size()==3){assertTrue(query.contains("\"userId\":\"1212121212121212\""));assertTrue(query.contains("\"instanceId\":\"rm-fictional\""));assertTrue(query.contains("Period=60"));}
            assertEquals("POST",exchange.getRequestMethod());assertEquals(0,exchange.getRequestBody().readAllBytes().length);
            byte[] body="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {var result=client(server,Duration.ofSeconds(2)).read(RESOURCE,NOW);assertNotNull(result);assertEquals("{}",result.attributesJson());assertEquals(List.of("DescribeDBInstanceAttribute","DescribeMetricMetaList","DescribeMetricList"),seen);assertEquals(3,nonces.size());}
        finally {server.stop(0);}
    }
    @Test void statusRedirectOversizeAndInvalidUtf8FailClosed()throws Exception{
        for(int mode=0;mode<4;mode++){
            final int scenario=mode;HttpServer server=HttpServer.create(new InetSocketAddress("localhost",0),0);var calls=new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/",e->{calls.incrementAndGet();byte[] body=scenario==2?new byte[262145]:scenario==3?new byte[]{(byte)0xff}:new byte[0];
                if(scenario==1)e.getResponseHeaders().add("Location","http://localhost:"+server.getAddress().getPort()+"/other");
                e.sendResponseHeaders(scenario==0?403:scenario==1?302:200,body.length);try{e.getResponseBody().write(body);}catch(java.io.IOException closed){}e.close();});server.start();
            try {assertNull(client(server,Duration.ofSeconds(2)).read(RESOURCE,NOW));assertEquals(1,calls.get());}finally{server.stop(0);}
        }
    }
    @Test void slowBodyHasTotalDeadlineAndInterruptionIsPreserved()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("localhost",0),0);server.createContext("/",e->{e.sendResponseHeaders(200,0);try{e.getResponseBody().write('{');e.getResponseBody().flush();Thread.sleep(500);e.getResponseBody().write('}');}catch(Exception closed){}e.close();});server.start();
        try {long start=System.nanoTime();assertNull(client(server,Duration.ofMillis(150)).read(RESOURCE,NOW));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2));
            Thread.currentThread().interrupt();assertNull(client(server,Duration.ofSeconds(1)).read(RESOURCE,NOW));assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();server.stop(0);}
    }
    @Test void arbitraryEndpointIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new AliyunRdsMetricsClient(HttpClient.newHttpClient(),URI.create("https://example.invalid/"),URI.create("https://example.invalid/"),()->CREDS,Clock.systemUTC(),Duration.ofSeconds(1)));
    }
    static AliyunRdsMetricsClient client(HttpServer server,Duration deadline){URI uri=URI.create("http://localhost:"+server.getAddress().getPort()+"/");return new AliyunRdsMetricsClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),uri,uri,()->CREDS,Clock.fixed(NOW,ZoneOffset.UTC),deadline);}
}
