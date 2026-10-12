package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcNewsReportLeaves;
import com.lianxibu.site.service.articles.NewsReportLeafEvidenceService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Actual native archive, original body association and validity. Whole-day roster remains outside this component. */
class NewsSourceReportComponentTest {
    NewsSourceReportCandidatesTest h;NewsSourceReportComponentService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    GenerationJdbcStoreTest db(){return h.f.f.f;}
    @BeforeEach void setup()throws Exception{h=new NewsSourceReportCandidatesTest(){@Override GenerationJdbcStoreTest fixture(){return NewsSourceReportComponentTest.this.fixture();}};h.setup();install();}
    void install()throws Exception{
        boolean h2=db().source instanceof org.h2.jdbcx.JdbcDataSource;String json=h2?"CLOB":"JSON",text=h2?"CLOB":"MEDIUMTEXT",time=h2?"TIMESTAMP(6)":"DATETIME(6)";
        for(String table:List.of("source_run_items","source_runs","article_revisions","articles"))db().jdbc.execute("DROP TABLE IF EXISTS "+table);
        db().jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),activation_stage VARCHAR(16),delivery_state VARCHAR(24),finished_at "+time+")");
        db().jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),category VARCHAR(16),revision BIGINT,created_at "+time+",terminal_state VARCHAR(16),state VARCHAR(32),projection_decision VARCHAR(16),effective_valid INT,evidence_json "+json+",evidence_hash BINARY(32),article_id VARCHAR(64),article_revision_id VARCHAR(64))");
        db().jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,article_id VARCHAR(64),title VARCHAR(512),source_summary "+text+",body_text "+text+",content_hash BINARY(32),created_at "+time+",legacy_flag INT,effective_valid INT,validity_revision BIGINT)");
        var receipt=h.done.finalResult().receipt();var recorded=LocalDateTime.ofInstant(h.done.availableAt().minusSeconds(1),ZoneOffset.UTC);db().jdbc.update("INSERT INTO source_runs VALUES(?,?,'production','complete',?)",receipt.runId(),receipt.sourceId(),recorded);
        for(var fragment:h.done.stage().fragments()){
            var chunk=h.done.plan().chunks().stream().filter(c->c.rootId().equals(fragment.rootId())).findFirst().orElseThrow();var snapshot=CanonicalJson.parse(chunk.snapshotJson());
            for(var member:snapshot.get("members")){
                String id=member.get("id").asText(),article="fictional-article-"+id,revision="fictional-revision-"+id;var fact=h.done.stage().members().stream().filter(m->m.itemId().equals(id)).findFirst().orElseThrow();var payload=member.get("body");String body=com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(payload);var projected=JsonMapper.shared().createObjectNode();projected.set("title",payload.get("title"));projected.set("summary",payload.get("source_summary"));projected.set("body_text",payload.get("body_text"));
                db().jdbc.update("INSERT INTO source_run_items VALUES(?,?,?,'news_tech',?,?,'accepted','ready','initialized',1,?,?,?,?)",id,receipt.runId(),receipt.sourceId(),fact.revision(),recorded,body,java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)),article,revision);
                db().jdbc.update("INSERT INTO article_revisions VALUES(?,?,?,?,?,?,?,0,1,0)",revision,article,payload.get("title").asText(),payload.get("source_summary").isNull()?null:payload.get("source_summary").asText(),payload.get("body_text").isNull()?null:payload.get("body_text").asText(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.ARTICLE_PROJECTION,projected)),recorded);
            }
        }
        service=provider();
    }
    NewsSourceReportComponentService provider(){return new NewsSourceReportComponentService(h.f.service,new NewsReportLeafEvidenceService(new JdbcNewsReportLeaves(db().source)),new DataSourceTransactionManager(db().source));}
    NewsSourceReportComponentService.Captured capture(){return service.capture(h.f.f.ref,h.done.availableAt());}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(db().source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    @Test void CompleteOriginalSourceHasAllNativeLeafReferencesAndExactDailyCandidates(){
        var captured=capture();assertNotNull(captured);assertEquals(h.done.finalResult().receipt().sourceId(),captured.sourceId());assertEquals(h.done.finalResult().receipt().runId(),captured.runId());assertEquals(h.done.plan().scope().businessDate(),captured.day());assertEquals(h.value().candidates(),captured.value().candidates());assertEquals(captured.value().candidates().size(),captured.referenceTargetsByEvidence().size());var summary=captured.value().candidates().getFirst();assertEquals(h.done.stage().members().size(),captured.referenceTargetsByEvidence().get(summary.evidence().id()).size());for(var refs:captured.referenceTargetsByEvidence().values())for(String target:refs)assertEquals("article_revision",CanonicalJson.parse(target).get("kind").asText());assertSame(captured,rr(()->service.verifyObserved(captured)));assertFalse(captured.successfulOriginalEmpty());assertThrows(UnsupportedOperationException.class,()->captured.referenceTargetsByEvidence().clear());assertThrows(UnsupportedOperationException.class,()->captured.referenceTargetsByEvidence().get(summary.evidence().id()).clear());assertFalse(captured.toString().contains("Fictional"));
    }
    @Test void ActualLeafInvalidationRejectsOldCaptureAndFreshCandidateReflectsOriginalVersionValidity(){
        var old=capture();String selected=h.done.finalResult().result().selected().getFirst().itemId();db().jdbc.update("UPDATE article_revisions SET effective_valid=0,validity_revision=1 WHERE id=?","fictional-revision-"+selected);assertThrows(RuntimeException.class,()->rr(()->service.verifyObserved(old)));var fresh=capture();assertFalse(fresh.value().candidates().getFirst().evidence().effectiveValid());assertFalse(fresh.value().candidates().stream().filter(c->selected.equals(c.metadata().resourceRef().id())).findFirst().orElseThrow().evidence().effectiveValid());assertEquals(old.value().candidates().getFirst().evidence().bodyJson(),fresh.value().candidates().getFirst().evidence().bodyJson());
    }
    @Test void UnprovenOriginalLeafCreatesExplicitSourceGapAndDoesNotBecomeSuccessfulEmpty(){
        db().jdbc.update("DELETE FROM article_revisions WHERE id=?","fictional-revision-"+h.done.stage().members().getFirst().itemId());var captured=capture();assertTrue(captured.value().candidates().isEmpty());assertTrue(captured.referenceTargetsByEvidence().isEmpty());assertTrue(captured.value().missing().stream().anyMatch(m->"article_revision_unproven".equals(m.reason())));assertTrue(captured.value().missing().stream().anyMatch(m->"source_leaf_unproven".equals(m.reason())));assertFalse(captured.successfulOriginalEmpty());assertEquals(h.done.stage().fragments().size(),captured.value().dependencies().size());assertSame(captured,rr(()->service.verifyObserved(captured)));
    }
    @Test void LateOrMissingFirstAvailabilityCannotSupplyAnySourceCandidate(){
        assertNull(service.capture(h.f.f.ref,h.done.availableAt().minusNanos(1000)));var old=capture();db().jdbc.update("DELETE FROM jobs WHERE target_type='source_value_final_availability'");assertNull(capture());assertThrows(RuntimeException.class,()->rr(()->service.verifyObserved(old)));
    }
    @Test void CorruptNativeAssociationAndChangedOriginalProofFailTheComponent(){
        db().jdbc.update("UPDATE source_run_items SET article_id='fictional-other'");assertThrows(RuntimeException.class,this::capture);db().jdbc.update("UPDATE source_run_items SET article_id=CONCAT('fictional-article-',id)");var old=capture();db().jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_value_final_availability'",new byte[32]);assertThrows(RuntimeException.class,()->rr(()->service.verifyObserved(old)));
    }
    @Test void RealOriginalZeroPlanRemainsExplicitNativeEmptyWithoutNewsBody()throws Exception{
        h.f.f.install(0);h.f.original.x.install();h.f.original.install();h.f.install();h.finish();install();var captured=capture();assertTrue(captured.successfulOriginalEmpty());assertTrue(captured.value().candidates().isEmpty());assertTrue(captured.value().missing().isEmpty());assertTrue(captured.referenceTargetsByEvidence().isEmpty());assertEquals(0,db().count("record_kind='logical_task'"));
    }
    @Test void IssuerAndTransactionBoundaryRemainMandatory(){
        var captured=capture();var other=provider();assertThrows(RuntimeException.class,()->rr(()->other.verifyObserved(captured)));assertThrows(RuntimeException.class,()->service.verifyObserved(captured));assertThrows(RuntimeException.class,()->rr(this::capture));var rc=new TransactionTemplate(new DataSourceTransactionManager(db().source));rc.setReadOnly(true);rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->rc.execute(s->service.verifyObserved(captured)));assertThrows(RuntimeException.class,()->db().writes.execute(new SharedWriteExecutor.ActionContext("fictional-component-role","fictional-owner",null),k->null,s->service.verifyObserved(captured)));
    }
    @Test void MandatoryActualPortsAssembleColdWithoutNativeReads(){var completed=mock(SourceValueReportCompletionService.class);var leaf=mock(NewsReportLeafEvidenceService.class);var tx=mock(PlatformTransactionManager.class);new NewsSourceReportComponentService(completed,leaf,tx);verifyNoInteractions(completed,leaf,tx);assertThrows(NullPointerException.class,()->new NewsSourceReportComponentService(completed,null,tx));}
}
