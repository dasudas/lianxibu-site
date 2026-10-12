package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcNewsReportLeaves;
import com.lianxibu.site.service.articles.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class NewsReportLeafEvidenceTest {
    GenerationJdbcStoreTest f;NewsReportLeafEvidenceService service;String body;final Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup()throws Exception{
        f=fixture();f.fixture();boolean h2=f.source instanceof org.h2.jdbcx.JdbcDataSource;for(String name:List.of("source_run_items","source_runs","article_revisions","articles"))f.jdbc.execute("DROP TABLE IF EXISTS "+name);String text=h2?"CLOB":"MEDIUMTEXT",json=h2?"CLOB":"JSON",time=h2?"TIMESTAMP(6)":"DATETIME(6)";
        f.jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),activation_stage VARCHAR(16),delivery_state VARCHAR(24),finished_at "+time+")");
        f.jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),category VARCHAR(16),revision BIGINT,created_at "+time+",terminal_state VARCHAR(16),state VARCHAR(32),projection_decision VARCHAR(16),effective_valid INT,evidence_json "+json+",evidence_hash BINARY(32),article_id VARCHAR(64),article_revision_id VARCHAR(64))");
        f.jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,article_id VARCHAR(64),title VARCHAR(512),source_summary "+text+",body_text "+text+",content_hash BINARY(32),created_at "+time+",legacy_flag INT,effective_valid INT,validity_revision BIGINT)");
        body=new String(CanonicalJson.bytes(CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD)),java.nio.charset.StandardCharsets.UTF_8);
        f.jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','production','complete',?)",LocalDateTime.ofInstant(cutoff.minusSeconds(1),ZoneOffset.UTC));
        f.jdbc.update("INSERT INTO source_run_items VALUES('fictional-item','fictional-run','fictional-source','news_tech',4,?,'accepted','ready','initialized',1,?,?,'fictional-article','fictional-revision')",LocalDateTime.ofInstant(cutoff.minusSeconds(2),ZoneOffset.UTC),body,sha(CanonicalJson.bytes(CanonicalJson.parse(body))));
        var projected=JsonMapper.shared().createObjectNode().put("title","Fictional source title").put("summary","Fictional summary").put("body_text","Fictional body");f.jdbc.update("INSERT INTO article_revisions VALUES('fictional-revision','fictional-article','Fictional source title','Fictional summary','Fictional body',?,?,0,1,0)",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.ARTICLE_PROJECTION,projected)),LocalDateTime.ofInstant(cutoff.minusSeconds(2),ZoneOffset.UTC));
        service=new NewsReportLeafEvidenceService(new JdbcNewsReportLeaves(f.source));
    }
    byte[] sha(byte[] bytes)throws Exception{return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);}
    NewsReportLeafEvidenceService.Original original(){return new NewsReportLeafEvidenceService.Original("fictional-item","fictional-run","fictional-source",2,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(body)),body);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    NewsReportLeafEvidenceService.Verified read(){return rr(()->service.read(original(),cutoff));}
    @Test void OriginalFullPayloadAndImmutableRevisionSupplyExactNativeLeafWithoutCurrentArticle(){
        var leaf=read();assertEquals("fictional-revision",CanonicalJson.parse(leaf.referenceJson()).get("id").asText());assertEquals("article_revision",CanonicalJson.parse(leaf.referenceJson()).get("kind").asText());assertEquals(0,leaf.validityRevision());assertTrue(leaf.effectiveValid());assertFalse(leaf.blocked());assertFalse(leaf.toString().contains("fictional"));assertFalse(original().toString().contains("Fictional body"));
    }
    @Test void NativeInvalidityAndPostAnalysisReviewAreExplicitRatherThanInventedValid(){
        f.jdbc.update("UPDATE article_revisions SET effective_valid=0,validity_revision=1");assertFalse(read().effectiveValid());assertEquals(1,read().validityRevision());f.jdbc.update("UPDATE article_revisions SET effective_valid=1");f.jdbc.update("UPDATE source_run_items SET state='needs_review',revision=5");assertTrue(read().effectiveValid());assertTrue(read().blocked());f.jdbc.update("UPDATE source_run_items SET effective_valid=0,state='excluded',revision=6");assertFalse(read().effectiveValid());assertTrue(read().blocked());
    }
    @Test void LegacyMissingAssociationAndMissingNativeRevisionStayUnproven(){
        f.jdbc.update("UPDATE article_revisions SET legacy_flag=1");assertNull(read());f.jdbc.update("UPDATE article_revisions SET legacy_flag=0");f.jdbc.update("UPDATE source_run_items SET article_revision_id=NULL");assertNull(read());f.jdbc.update("UPDATE source_run_items SET article_revision_id='fictional-revision'");f.jdbc.update("DELETE FROM article_revisions");assertNull(read());
    }
    @Test void CrossRunSourceArticleOriginalHashAndNativeRevisionCorruptionAreRejected(){
        f.jdbc.update("UPDATE source_run_items SET run_id='fictional-foreign'");assertNull(read());f.jdbc.update("UPDATE source_run_items SET run_id='fictional-run',source_id='fictional-other'");assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE source_run_items SET source_id='fictional-source',article_id='fictional-foreign'");assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE source_run_items SET article_id='fictional-article',evidence_hash=?",new byte[32]);assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE source_run_items SET evidence_hash=?",assertDoesNotThrow(()->sha(CanonicalJson.bytes(CanonicalJson.parse(body)))));f.jdbc.update("UPDATE article_revisions SET content_hash=?",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void EntireNativeBodyIsComparedNotJustTitleSummaryOrStoredHash()throws Exception{
        var altered=(ObjectNode)CanonicalJson.parse(body);altered.put("body_text","Fictional body altered tail");String changed=new String(CanonicalJson.bytes(altered),java.nio.charset.StandardCharsets.UTF_8);f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=?",changed,sha(CanonicalJson.bytes(altered)));assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=?",body,sha(CanonicalJson.bytes(CanonicalJson.parse(body))));f.jdbc.update("UPDATE article_revisions SET body_text='Fictional different body'");assertThrows(RuntimeException.class,this::read);
    }
    @Test void SqlRejectsWideOriginalPayloadAndRevisionBeforeLoadingTheirText(){
        f.jdbc.update("UPDATE source_run_items SET evidence_json=?","{\"fictional_wide\":\""+"x".repeat(204800)+"\"}");assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE source_run_items SET evidence_json=?",body);f.jdbc.update("UPDATE article_revisions SET body_text=?","x".repeat(262145));assertThrows(RuntimeException.class,this::read);
    }
    @Test void BorrowedRrCutoffAndNativeUtcFactsAreMandatory(){
        assertThrows(RuntimeException.class,()->service.read(original(),cutoff));assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-leaf-role","fictional-owner",null),k->null,s->service.read(original(),cutoff)));var rc=new TransactionTemplate(new DataSourceTransactionManager(f.source));rc.setReadOnly(true);rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->rc.execute(s->service.read(original(),cutoff)));assertThrows(RuntimeException.class,()->rr(()->service.read(original(),cutoff.plusNanos(1))));assertEquals(cutoff.minusSeconds(2),rr(()->new JdbcNewsReportLeaves(f.source).member("fictional-item")).createdAt());assertEquals(cutoff.minusSeconds(1),rr(()->new JdbcNewsReportLeaves(f.source).member("fictional-item")).finishedAt());var previous=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/New_York")){TimeZone.setDefault(TimeZone.getTimeZone(zone));assertEquals("fictional-revision",CanonicalJson.parse(read().referenceJson()).get("id").asText());}}finally{TimeZone.setDefault(previous);}f.jdbc.update("UPDATE article_revisions SET created_at=?",LocalDateTime.ofInstant(cutoff.plusNanos(1000),ZoneOffset.UTC));assertThrows(RuntimeException.class,this::read);
    }
    @Test void ColdNativeReaderRequiresItsPortWithoutIo(){var rows=mock(JdbcNewsReportLeaves.class);new NewsReportLeafEvidenceService(rows);verifyNoInteractions(rows);assertThrows(NullPointerException.class,()->new NewsReportLeafEvidenceService(null));}
}
