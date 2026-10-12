package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.JdbcArticlePublicEvidence;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class ArticlePublicEvidenceServiceTest {
    SourceMemberEvidenceServiceTest f;ArticlePublicEvidenceService service;
    @BeforeEach void setup() throws Exception {
        f=new SourceMemberEvidenceServiceTest();f.setup();
        f.jdbc.execute("ALTER TABLE source_run_items ADD article_id VARCHAR(64)");f.jdbc.execute("ALTER TABLE source_run_items ADD article_revision_id VARCHAR(64)");
        f.jdbc.update("UPDATE source_run_items SET article_id='fictional-article',article_revision_id='fictional-revision'");
        f.jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,article_id VARCHAR(64),title VARCHAR(512),source_summary CLOB,body_text CLOB,content_hash BINARY(32),created_at TIMESTAMP,legacy_flag INT,effective_valid INT)");
        var body=JsonMapper.shared().createObjectNode().put("title","Fictional source title").put("summary","Fictional summary").put("body_text","Fictional body");
        f.jdbc.update("INSERT INTO article_revisions VALUES('fictional-revision','fictional-article','Fictional source title','Fictional summary','Fictional body',?,?,0,1)",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.ARTICLE_PROJECTION,body)),Timestamp.from(f.cutoff.minusSeconds(2)));
        service=new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(f.source),f.service);
    }
    ArticlePublicEvidenceService.Fact read(){return f.tx.execute(s->service.read("fictional-revision",f.cutoff));}
    @Test void exactRetainedRevisionAndOriginalPayloadSupplyCitationWithoutCurrentArticleRow() {
        var value=read();assertEquals("fictional-revision",value.revisionId());assertEquals("Fictional source title",value.title());assertEquals("https://example.invalid/story",value.sourceUrl());
    }
    @Test void missingTransactionInvalidRevisionHashAndCrossArticleAssociationReject() {
        assertThrows(RuntimeException.class,()->service.read("fictional-revision",f.cutoff));
        f.jdbc.update("UPDATE source_run_items SET article_id='fictional-other'");assertThrows(RuntimeException.class,this::read);
        f.jdbc.update("UPDATE source_run_items SET article_id='fictional-article'");f.jdbc.update("UPDATE article_revisions SET content_hash=?",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void legacyInvalidAndFutureFactsAreNeverMadePublic() {
        for(String change:List.of("legacy_flag=1","effective_valid=0","created_at=TIMESTAMP '2026-01-03 00:00:00'")) {
            f.jdbc.update("UPDATE article_revisions SET legacy_flag=0,effective_valid=1,created_at=?",Timestamp.from(f.cutoff.minusSeconds(2)));
            f.jdbc.update("UPDATE article_revisions SET "+change);assertThrows(RuntimeException.class,this::read);
        }
    }
    @Test void conflictingOriginalUrlsAndProjectionMismatchReject() throws Exception {
        var payload=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);payload.put("source_url","https://example.invalid/other");
        f.jdbc.update("INSERT INTO source_run_items SELECT 'fictional-second',run_id,source_id,revision,category,created_at,terminal_state,state,projection_decision,effective_valid,evidence_json,evidence_hash,article_id,article_revision_id FROM source_run_items WHERE id='fictional-item'");
        f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=? WHERE id='fictional-second'",payload.toString(),java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)));assertThrows(RuntimeException.class,this::read);
        f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-second'");payload.put("source_url","https://example.invalid/story");payload.put("title","Fictional different title");
        f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=?",payload.toString(),java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)));assertThrows(RuntimeException.class,this::read);
    }
}
