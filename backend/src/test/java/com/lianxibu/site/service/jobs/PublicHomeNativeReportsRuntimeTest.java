package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

class PublicHomeNativeReportsRuntimeTest extends PublicContentRuntimeTest {
    @Test void ActualHomeReadsCompleteNativeReportAndRechecksTamperingAndWithdrawal() throws Exception {
        f=fixture();noUpdateReport();var jdbc=f.fixture.jdbc;
        for(String table:java.util.List.of("learning_packages","runtime_settings","request_receipts"))jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json TEXT,revision BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE request_receipts(operation VARCHAR(64),scope VARCHAR(128),revision BIGINT,response_json TEXT)");
        String body=jdbc.queryForObject("SELECT body_json FROM contents WHERE id='fictional-no-update'",String.class);
        try(var app=start(true);var client=HttpClient.newHttpClient()){
            String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            var first=request(client,root,"/home","GET");assertEquals(200,first.statusCode(),first.body());assertEquals("no-store",first.headers().firstValue("Cache-Control").orElseThrow());
            var reports=CanonicalJson.parse(first.body()).path("data").path("reports");assertEquals(1,reports.size());assertEquals("fictional-no-update",reports.get(0).path("id").asText());assertEquals("published",reports.get(0).path("publication_state").asText());assertFalse(reports.get(0).path("review_required").asBoolean());
            jdbc.update("UPDATE contents SET body_json='{}' WHERE id='fictional-no-update'");
            var damaged=request(client,root,"/home","GET");assertEquals(200,damaged.statusCode(),damaged.body());assertTrue(CanonicalJson.parse(damaged.body()).path("data").path("reports").isEmpty());
            jdbc.update("UPDATE contents SET body_json=? WHERE id='fictional-no-update'",body);
            assertEquals(1,CanonicalJson.parse(request(client,root,"/home","GET").body()).path("data").path("reports").size());
            jdbc.update("UPDATE contents SET state='withdrawn',revision=revision+1 WHERE id='fictional-no-update'");
            var withdrawn=request(client,root,"/home","GET");assertEquals(200,withdrawn.statusCode(),withdrawn.body());assertTrue(CanonicalJson.parse(withdrawn.body()).path("data").path("reports").isEmpty());assertEquals("no-store",withdrawn.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
}
