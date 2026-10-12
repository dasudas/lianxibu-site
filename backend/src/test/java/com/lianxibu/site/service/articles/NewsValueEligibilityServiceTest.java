package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

class NewsValueEligibilityServiceTest {
    NewsDedupEligibilityServiceTest f;NewsValueEligibilityService service;
    DataSource source(){return new NewsDedupEligibilityServiceTest().source();}
    String jsonType(){return "CLOB";}
    @BeforeEach void setup() throws Exception {
        f=new NewsDedupEligibilityServiceTest(){@Override DataSource source(){return NewsValueEligibilityServiceTest.this.source();}@Override String jsonType(){return NewsValueEligibilityServiceTest.this.jsonType();}};
        f.setup();f.jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key TEXT,identity_hash BINARY(32),first_seen_business_date DATE,legacy_flag INT,revision BIGINT,first_recorded_at TIMESTAMP(6))");
        f.jdbc.update("UPDATE source_run_items SET state='ready',entity_id='fictional-new-entity' WHERE id='fictional-item-0'");
        entity("fictional-new-entity",f.day,0);entity("fictional-entity",f.day.minusDays(1),0);
        service=new NewsValueEligibilityService(new JdbcNewsDedupEligibility(f.source),new JdbcNewsValueEntities(f.source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source)),Clock.fixed(f.cutoff.plusNanos(999),ZoneOffset.UTC));
    }
    void entity(String id,LocalDate first,int legacy) {
        String key="{\"key\":\"fictional-original-event\",\"namespace\":\"article_url\"}";
        f.jdbc.update("INSERT INTO analyzed_entities VALUES(?,'news_tech',?,?,?, ?,0,?)",id,key,JdbcEntityDedup.identityHash("news_tech",key),first==null?null:java.sql.Date.valueOf(first),legacy,Timestamp.from(f.cutoff.minusSeconds(3)));
    }
    NewsValueEligibilityService.Selection selection(){return f.tx.execute(a->service.capture(f.universe,f.day));}
    NewsValueEligibilityService later(Instant now){return new NewsValueEligibilityService(new JdbcNewsDedupEligibility(f.source),new JdbcNewsValueEntities(f.source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source)),Clock.fixed(now,ZoneOffset.UTC));}
    @Test void originalCaptureRemainsComparableAfterTheClockAdvancesWithoutBackdatingNewMetadata(){
        var original=selection();service=later(f.cutoff.plusSeconds(2));assertEquals(Boolean.TRUE,f.tx.execute(a->service.matchesCurrent(f.universe,original)));
        assertEquals(f.cutoff,original.cutoff());assertEquals(f.cutoff.plusSeconds(2),selection().entries().getFirst().entity().metadataAsOf());
    }
    @Test void currentQualificationAndEntityRevisionChangesRejectTheOriginalCapture(){
        var original=selection();service=later(f.cutoff.plusSeconds(2));f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1 WHERE id='fictional-new-entity'");
        assertEquals(Boolean.FALSE,f.tx.execute(a->service.matchesCurrent(f.universe,original)));f.jdbc.update("UPDATE analyzed_entities SET revision=0 WHERE id='fictional-new-entity'");f.jdbc.update("UPDATE source_run_items SET effective_valid=0 WHERE id='fictional-item-0'");assertEquals(Boolean.FALSE,f.tx.execute(a->service.matchesCurrent(f.universe,original)));
    }
    @Test void dataArrivingAfterTheOriginalCutoffCannotBeAcceptedByACommitTimeClock(){
        var original=selection();service=later(f.cutoff.plusSeconds(2));f.jdbc.update("UPDATE analyzed_entities SET first_recorded_at=? WHERE id='fictional-new-entity'",Timestamp.from(f.cutoff.plusSeconds(1)));
        assertNotNull(selection().entries().getFirst().entity());assertThrows(RuntimeException.class,()->f.tx.execute(a->service.matchesCurrent(f.universe,original)));
        f.jdbc.update("UPDATE analyzed_entities SET first_recorded_at=? WHERE id='fictional-new-entity'",Timestamp.from(f.cutoff.minusSeconds(3)));f.jdbc.update("UPDATE source_run_items SET created_at=? WHERE id='fictional-item-0'",Timestamp.from(f.cutoff.plusSeconds(1)));
        assertEquals(NewsValueEligibilityService.Reason.ELIGIBLE,selection().entries().getFirst().reason());assertEquals(Boolean.FALSE,f.tx.execute(a->service.matchesCurrent(f.universe,original)));
    }
    @Test void commitPredicateRequiresAnActualWriterAndNeverAcceptsAFutureCapture(){
        var original=selection();assertThrows(RuntimeException.class,()->service.matchesCurrent(f.universe,original));f.tx.setReadOnly(true);assertThrows(RuntimeException.class,()->f.tx.execute(a->service.matchesCurrent(f.universe,original)));f.tx.setReadOnly(false);
        service=later(f.cutoff.minusNanos(1000));assertThrows(RuntimeException.class,()->f.tx.execute(a->service.matchesCurrent(f.universe,original)));service=later(f.cutoff.plusSeconds(2));f.jdbc.update("DELETE FROM analyzed_entities WHERE id='fictional-new-entity'");assertThrows(RuntimeException.class,()->f.tx.execute(a->service.matchesCurrent(f.universe,original)));
    }
    @Test void completeCurrentReadKeepsOldValueEvidenceButOnlyTheActualNewEntityGetsNewness() {
        var result=selection();assertEquals(f.cutoff,result.cutoff());assertEquals(6,result.entries().size());
        assertEquals(List.of("ELIGIBLE","COLLECTOR_EXCLUDED","ELIGIBLE","NOT_AI_ELIGIBLE","NOT_AI_ELIGIBLE","NOT_AI_ELIGIBLE"),result.entries().stream().map(e->e.reason().name()).toList());
        var fresh=result.entries().getFirst();assertTrue(fresh.eligibleHighValue());assertEquals("fictional-new-entity",fresh.entity().id());assertEquals(2,fresh.fact().revision());assertEquals(f.cutoff.minusSeconds(1),fresh.completedAt());
        assertFalse(result.entries().get(2).eligibleHighValue());assertNotNull(result.entries().get(2).fact());assertEquals(f.day.minusDays(1),result.entries().get(2).entity().firstSeenBusinessDate());
        assertEquals(12,f.jdbc.queryForObject("SELECT SUM(revision) FROM source_run_items",Integer.class));
    }
    @Test void legacyOrMissingMaterialNeverGetsHighValueWhileItsEvidenceRemainsAvailable() throws Exception {
        f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=1,first_seen_business_date=NULL WHERE id='fictional-new-entity'");
        assertFalse(selection().entries().getFirst().eligibleHighValue());assertNotNull(selection().entries().getFirst().fact());
        f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=0,first_seen_business_date=? WHERE id='fictional-new-entity'",java.sql.Date.valueOf(f.day));
        var node=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(f.payload(0,Instant.parse("2026-01-01T08:00:00Z")));node.putNull("source_summary");node.putNull("body_text");String payload=new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);
        f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=? WHERE id='fictional-item-0'",payload,f.sha(payload));
        var missing=selection().entries().getFirst();assertNotNull(missing.fact());assertFalse(missing.eligibleHighValue());assertEquals("missing",NewsValueMaterial.from(f.universe.sourceId(),missing.fact()).inputBasis());
    }
    @Test void missingReplacedOrCorruptEntityFailsTheWholeReadInsteadOfBecomingAnEmptySource() {
        for(String change:List.of("category='agent'","legacy_flag=2","legacy_flag=NULL","revision=NULL","revision=-1","identity_hash=NULL","first_seen_business_date=NULL","first_recorded_at=NULL","identity_key='{}'","identity_key='"+"x".repeat(4097)+"'")) {
            f.jdbc.update("UPDATE analyzed_entities SET "+change+" WHERE id='fictional-new-entity'");assertThrows(RuntimeException.class,this::selection);
            f.jdbc.update("DELETE FROM analyzed_entities WHERE id='fictional-new-entity'");entity("fictional-new-entity",f.day,0);
        }
        f.jdbc.update("UPDATE analyzed_entities SET id='FICTIONAL-NEW-ENTITY' WHERE id='fictional-new-entity'");assertThrows(RuntimeException.class,this::selection);
        f.jdbc.update("DELETE FROM analyzed_entities WHERE id='FICTIONAL-NEW-ENTITY'");assertThrows(RuntimeException.class,this::selection);
    }
    @Test void originalTransportIdentityAndActualNativeFlagsCannotBeReplacedOrSilentlyExcluded() {
        for(String change:List.of("effective_valid=2","effective_valid=NULL","revision=NULL","source_id='fictional-other'","source_item_id='fictional-replacement'","terminal_state='excluded'","payload_hash=NULL","state='fictional-unknown'")) {
            f.jdbc.update("UPDATE source_run_items SET "+change+" WHERE id='fictional-item-0'");assertThrows(RuntimeException.class,this::selection);
            f.jdbc.update("UPDATE source_run_items SET effective_valid=1,revision=2,source_id='fictional-source',source_item_id='fictional-key-0',terminal_state='accepted',state='ready',payload_hash=? WHERE id='fictional-item-0'",HexFormat.of().parseHex(f.HASH));
        }
    }
    @Test void realSourceWindowTargetDateAndArrivalTimeAreAppliedBeforeValueQualification() throws Exception {
        f.jdbc.update("UPDATE source_run_items SET created_at=? WHERE id='fictional-item-0'",Timestamp.from(f.cutoff.plusNanos(1000)));assertEquals("NOT_AI_ELIGIBLE",selection().entries().getFirst().reason().name());
        f.jdbc.update("UPDATE source_run_items SET created_at=?,target_business_date=? WHERE id='fictional-item-0'",Timestamp.from(f.cutoff.minusSeconds(2)),java.sql.Date.valueOf(f.day.plusDays(1)));assertFalse(selection().entries().getFirst().eligibleHighValue());
        f.jdbc.update("UPDATE source_run_items SET target_business_date=NULL WHERE id='fictional-item-0'");
        String payload=f.payload(0,f.day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=? WHERE id='fictional-item-0'",payload,f.sha(payload));assertEquals("NOT_AI_ELIGIBLE",selection().entries().getFirst().reason().name());
        payload=f.payload(0,f.day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());f.jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=? WHERE id='fictional-item-0'",payload,f.sha(payload));assertTrue(selection().entries().getFirst().eligibleHighValue());
        f.jdbc.update("UPDATE analyzed_entities SET first_recorded_at=? WHERE id='fictional-new-entity'",Timestamp.from(f.cutoff.plusNanos(1000)));assertThrows(RuntimeException.class,this::selection);
    }
    @Test void noHistoricalCutoffIsAcceptedAndEmptyOriginalMembershipDoesNotInventCompletion() {
        assertThrows(RuntimeException.class,()->service.capture(f.universe,f.day));f.tx.setReadOnly(true);assertThrows(RuntimeException.class,this::selection);f.tx.setReadOnly(false);
        var original=new MemberUniverse.Universe(f.universe.runId(),f.universe.sourceId(),f.HASH,List.of(),List.of(),0,0);
        assertTrue(f.tx.execute(a->service.capture(original,f.day)).entries().isEmpty());
        assertThrows(RuntimeException.class,()->f.tx.execute(a->service.capture(original,f.day.plusDays(1))));
        var ro=new TransactionTemplate(f.tx.getTransactionManager());ro.setReadOnly(true);assertThrows(RuntimeException.class,()->ro.execute(a->new JdbcNewsValueEntities(f.source).entity("fictional-new-entity")));
    }
    @Test void aggregateCapacityRejectsTheCompleteReadAndClockIsCapturedOnceInTheWriter() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();return f.cutoff;}};
        service=new NewsValueEligibilityService(new JdbcNewsDedupEligibility(f.source),new JdbcNewsValueEntities(f.source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source)),clock,1);
        assertThrows(NewsValueEligibilityService.CapacityGuarded.class,this::selection);assertEquals(1,calls.get());assertEquals(12,f.jdbc.queryForObject("SELECT SUM(revision) FROM source_run_items",Integer.class));
    }
}
