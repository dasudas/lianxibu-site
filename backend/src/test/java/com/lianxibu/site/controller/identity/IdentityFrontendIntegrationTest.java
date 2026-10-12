package com.lianxibu.site.controller.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.identity.BrowserSessionStore;
import com.lianxibu.site.service.identity.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentityFrontendIntegrationTest {
    @TempDir Path temp;
    @Test void frontendManualRetryUsesRealJavaSessionAndController() throws Exception {
        var sessions=new HashMap<String,BrowserSessionStore.StoredSession>();
        BrowserSessionStore store=new BrowserSessionStore(){
            public User findUser(String username){return username.equals("fictional")?new User("fictional-user","$argon2id$fictional"):null;}
            public StoredSession findSession(byte[] hash){return sessions.get(HexFormat.of().formatHex(hash));}
            public void createSession(String id,String user,byte[] token,byte[] csrf,Instant expires){sessions.put(HexFormat.of().formatHex(token),new StoredSession(user,csrf,expires,null));}
            public boolean revokeSession(byte[] hash,Instant at){return sessions.remove(HexFormat.of().formatHex(hash))!=null;}
        };
        var verifier=new BrowserSessionService.PasswordVerifier(){public boolean matches(String p,String h){return "correct".equals(p);}public String dummyHash(){return "$argon2id$fictional";}};
        var service=new BrowserIdentityAdapter(new BrowserSessionService(store,verifier,u->"user",Set.of("https://site.example.invalid"),Clock.systemUTC(),new SecureRandom()),null);
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("identity",service);
        var mvc=MockMvcBuilders.standaloneSetup(new IdentityController(factory.getBeanProvider(IdentityService.class))).build();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/business/v1/session",exchange->{
            try {
                var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()),"/session");
                exchange.getRequestHeaders().forEach((key,values)->request.header(key,values.toArray()));
                String cookie=exchange.getRequestHeaders().getFirst("Cookie");
                if(cookie!=null)for(String pair:cookie.split(";")){String[] parts=pair.trim().split("=",2);if(parts.length==2)request.cookie(new jakarta.servlet.http.Cookie(parts[0],parts[1]));}
                request.content(exchange.getRequestBody().readAllBytes());
                var response=mvc.perform(request).andReturn().getResponse();
                for(String name:response.getHeaderNames())exchange.getResponseHeaders().put(name,new ArrayList<>(response.getHeaders(name)));
                byte[] body=response.getContentAsByteArray();exchange.sendResponseHeaders(response.getStatus(),body.length);exchange.getResponseBody().write(body);
            }catch(Exception failure){exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });
        server.start();Process child=null;
        try {
            Path output=temp.resolve("node.txt");
            child=new ProcessBuilder("node",Path.of("../frontend/tests/integration/login-java.mjs").toAbsolutePath().normalize().toString(),"http://127.0.0.1:"+server.getAddress().getPort()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"frontend integration timed out");
            assertEquals(0,child.exitValue(),()->{try{return Files.readString(output);}catch(Exception e){return e.toString();}});
        }finally{if(child!=null&&child.isAlive())child.destroyForcibly().waitFor();server.stop(0);}
    }
}
