package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.OperationalNotification.Payload;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AliyunNotificationClientTest {
    static final String ACCOUNT="1212121212121212",GROUP="fictional-ops";
    static final String REQUEST="11111111-1111-4111-8111-111111111111";
    static final String SUCCESS="{\"code\":\"200\",\"requestId\":\""+REQUEST+"\",\"msg\":null,\"data\":{\"requestId\":\""+REQUEST+"\"}}";
    static final Payload PAYLOAD=new Payload("OPS-04","fictional alert","event_id=fictional-event; action=trigger");
    static String token(String account,String group){return Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"aliUid\":\""+account+"\",\"name\":\""+group+"\",\"secure\":\"11111111-1111-4111-8111-111111111111\",\"tokenType\":\"contactGroup\"}").getBytes(StandardCharsets.UTF_8))+".fictionalSignature";}
    static AliyunNotificationClient.Credentials creds(){return new AliyunNotificationClient.Credentials(ACCOUNT,GROUP,token(ACCOUNT,GROUP));}
    static AliyunNotificationClient client(HttpServer server,Duration timeout){return new AliyunNotificationClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("http://localhost:"+server.getAddress().getPort()+"/event/notify"),()->creds(),timeout);}
    @Test void exactPostUsesFixedMailLevelAndAcceptsOnlyPlatformReceipt()throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/event/notify",e->{calls.incrementAndGet();assertEquals("POST",e.getRequestMethod());
            assertEquals("token="+token(ACCOUNT,GROUP)+"&level=INFO",e.getRequestURI().getRawQuery());
            assertEquals("application/json; charset=utf-8",e.getRequestHeaders().getFirst("Content-Type"));
            assertEquals(PAYLOAD.json(),new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());byte[] bytes=SUCCESS.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try{assertEquals(REQUEST,client(server,Duration.ofSeconds(2)).send(PAYLOAD).requestId());assertEquals(1,calls.get());}finally{server.stop(0);}
    }
    @Test void statusRedirectStreamOversizeAndMalformedResponsesFailClosedWithoutRetry()throws Exception {
        for(int mode=0;mode<9;mode++){
            int scenario=mode;var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
            server.createContext("/",e->{calls.incrementAndGet();byte[] bytes=switch(scenario){case 2,3->new byte[16385];case 4->new byte[]{(byte)0xff};case 5->"{\"code\":\"403\"}".getBytes(StandardCharsets.UTF_8);case 6->SUCCESS.replace(REQUEST,"arbitrary-message").getBytes(StandardCharsets.UTF_8);case 7->(SUCCESS+"{}").getBytes(StandardCharsets.UTF_8);case 8->SUCCESS.replace("\"code\":\"200\"","\"code\":\"200\",\"code\":\"403\"").getBytes(StandardCharsets.UTF_8);default->SUCCESS.getBytes(StandardCharsets.UTF_8);};
                if(scenario==1)e.getResponseHeaders().add("Location","http://localhost:"+server.getAddress().getPort()+"/other");
                e.sendResponseHeaders(scenario==0?403:scenario==1?302:200,scenario==3?0:bytes.length);try{e.getResponseBody().write(bytes);}catch(java.io.IOException closed){}e.close();});server.start();
            try{assertNull(client(server,Duration.ofSeconds(2)).send(PAYLOAD));assertEquals(1,calls.get());}finally{server.stop(0);}
        }
    }
    @Test void slowBodyHasTotalDeadlineAndInterruptIsPreserved()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);server.createContext("/",e->{e.sendResponseHeaders(200,0);try{e.getResponseBody().write('{');e.getResponseBody().flush();Thread.sleep(500);e.getResponseBody().write('}');}catch(Exception closed){}e.close();});server.start();
        try{long start=System.nanoTime();assertNull(client(server,Duration.ofMillis(150)).send(PAYLOAD));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2));
            Thread.currentThread().interrupt();assertNull(client(server,Duration.ofSeconds(1)).send(PAYLOAD));assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();server.stop(0);}
    }
    @Test void identitiesEndpointAndTransactionAreCheckedBeforeNetwork()throws Exception {
        assertThrows(IllegalArgumentException.class,()->new AliyunNotificationClient.Credentials(ACCOUNT,GROUP,token(ACCOUNT,"another-fictional-group")));
        assertThrows(IllegalArgumentException.class,()->new AliyunNotificationClient.Credentials("3434343434343434",GROUP,token(ACCOUNT,GROUP)));
        assertThrows(IllegalArgumentException.class,()->new AliyunNotificationClient.Credentials(ACCOUNT,GROUP,"invalid-token"));
        assertFalse(creds().toString().contains(token(ACCOUNT,GROUP)));
        assertThrows(IllegalArgumentException.class,()->new AliyunNotificationClient(HttpClient.newHttpClient(),URI.create("https://example.invalid/event/notify"),()->creds(),Duration.ofSeconds(1)));
        var client=new AliyunNotificationClient(()->creds());TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->client.send(PAYLOAD));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
