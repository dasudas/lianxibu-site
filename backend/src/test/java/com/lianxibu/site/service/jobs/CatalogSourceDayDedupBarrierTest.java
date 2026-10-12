package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Actual full cohort/closure storage; whole catalog final progress and raw directory acquisition are explicit doubles. */
class CatalogSourceDayDedupBarrierTest {
    String category="agent";final LocalDate day=LocalDate.of(2026,1,1);final Instant registered=Instant.parse("2026-01-01T16:10:00Z");
    GenerationJdbcStoreTest f;DataSourceTransactionManager manager;SourceDayCohortSealService cohorts;JdbcSourceDayDedupClosures rows;
    CatalogSourceDayDedupProgressService progress;Map<String,SourceDedupBarrier.Progress> facts;SourceDayRoster.Api inputs;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){
        f=fixture();f.fixture();manager=new DataSourceTransactionManager(f.source);rows=new JdbcSourceDayDedupClosures(f.source);
        inputs=mock(SourceDayRoster.Api.class);when(inputs.read()).thenReturn(new SourceDayRoster.Evidence(registered,4,List.of(source("fictional-a","production"),source("fictional-b","production"),source("fictional-disabled","disabled"),source("fictional-draft","draft"))));
        var directories=new JdbcSourceDayRosters(f.source);cohorts=new SourceDayCohortSealService(new SourceDayRosterRegistrationService(directories,inputs,f.writes,manager,Clock.fixed(registered,ZoneOffset.UTC)),directories,new JdbcSourceDayCohortSeals(f.source),f.writes,manager,Clock.fixed(registered,ZoneOffset.UTC));
        facts=new HashMap<>();progress=mock(CatalogSourceDayDedupProgressService.class);
        when(progress.read(any(),any(),any())).thenAnswer(call->{SourceDayRunRegistration.Request request=call.getArgument(0);Instant observed=call.getArgument(2);var fact=facts.get(request.source().id());return fact!=null&&!fact.availableAt().isAfter(observed)?fact:null;});
    }
    SourceDayRoster.RawSource source(String id,String stage){return new SourceDayRoster.RawSource(id,category,2,3,stage,"enabled",registered.plusSeconds(86400),true,true,"a".repeat(64));}
    CatalogSourceDayDedupBarrierService service(Clock clock){return service(clock,f.writes,manager);}
    CatalogSourceDayDedupBarrierService service(Clock clock,SharedWriteExecutor writes,DataSourceTransactionManager tx){return new CatalogSourceDayDedupBarrierService(cohorts,progress,rows,writes,tx,clock,category);}
    void complete(String source,Instant at){var request=cohorts.find(day).plan().requests().stream().filter(r->r.source().id().equals(source)).findFirst().orElseThrow();facts.put(source,new SourceDedupBarrier.Progress(category,day,source,3,"a".repeat(64),request.runId(),1,SourceDedupBarrier.State.COMPLETE,at,"b".repeat(64)));}
    int archives(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='source_day_dedup'",Integer.class);}
    @Test void FourCategoriesFreezeTheirCompleteOriginalCohortAndOnlyTheirOwnProgress(){
        for(String value:List.of("agent","skill","workflow","model")){
            category=value;setup();cohorts.seal(day);complete("fictional-a",registered.plusSeconds(1));assertNull(service(Clock.fixed(registered.plusSeconds(60),ZoneOffset.UTC)).close(day));complete("fictional-b",registered.plusSeconds(2));var closed=service(Clock.fixed(registered.plusSeconds(60),ZoneOffset.UTC)).close(day);assertEquals(value,closed.closure().cohort().category());assertEquals(4,closed.closure().entries().size());assertEquals(2,closed.closure().entries().stream().filter(e->e.decision()==SourceDedupBarrier.Decision.INCLUDED).count());assertTrue(closed.closure().early());assertEquals(1,archives());assertEquals(0,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        }
    }
    @Test void ClosedCategoryAndRequiredPortsHaveZeroConstructionIo(){
        var cohorts=mock(SourceDayCohortSealService.class);var progress=mock(CatalogSourceDayDedupProgressService.class);var rows=mock(JdbcSourceDayDedupClosures.class);var writes=mock(SharedWriteExecutor.class);var tx=mock(DataSourceTransactionManager.class);var clock=mock(Clock.class);
        for(String value:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogSourceDayDedupBarrierService(cohorts,progress,rows,writes,tx,clock,value));verifyNoInteractions(cohorts,progress,rows,writes,tx,clock);assertThrows(RuntimeException.class,()->new CatalogSourceDayDedupBarrierService(cohorts,progress,rows,writes,tx,clock,"news_tech"));
    }
    @Test void missingFullSealCannotBeReplacedByEmptyAndBatchSubsetCannotCloseEarly(){
        assertNull(service(Clock.fixed(registered.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,archives());verifyNoInteractions(progress);verify(inputs,never()).read();
        cohorts.seal(day);complete("fictional-a",registered.plusSeconds(1));assertNull(service(Clock.fixed(registered.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,archives());
        complete("fictional-b",registered.plusSeconds(2));var closed=service(Clock.fixed(registered.plusSeconds(60),ZoneOffset.UTC)).close(day);assertTrue(closed.closure().early());assertEquals(4,closed.closure().entries().size());assertEquals(2,closed.closure().entries().stream().filter(e->e.decision()==SourceDedupBarrier.Decision.INCLUDED).count());assertEquals(1,archives());
    }
    @Test void fixedDeadlineKeepsMissingAndDisabledAndRestartDoesNotIncludeNewLateResult(){
        cohorts.seal(day);complete("fictional-a",registered.plusSeconds(1));Instant deadline=SourceDedupBarrier.deadline(day);
        var closed=service(Clock.fixed(deadline,ZoneOffset.UTC)).close(day);assertFalse(closed.closure().early());assertEquals(deadline,closed.closure().cutoff());assertEquals("not_completed",closed.closure().entries().stream().filter(e->e.sourceId().equals("fictional-b")).findFirst().orElseThrow().reason());
        complete("fictional-b",deadline.plusSeconds(1));Clock clock=mock(Clock.class);clearInvocations(progress,inputs);
        var replay=service(clock).close(day);assertEquals(closed.closure().json(),replay.closure().json());verifyNoInteractions(clock,inputs);
        verify(progress,never()).read(argThat(r->r.source().id().equals("fictional-b")),any(),any());assertEquals(1,archives());
    }
    @Test void lateFactStaysMissingAndGenuineEmptyCohortNeedsOriginalCompleteSeal(){
        cohorts.seal(day);Instant deadline=SourceDedupBarrier.deadline(day);complete("fictional-a",deadline.plusSeconds(1));var closed=service(Clock.fixed(deadline.plusSeconds(2),ZoneOffset.UTC)).close(day);
        assertEquals("late",closed.closure().entries().stream().filter(e->e.sourceId().equals("fictional-a")).findFirst().orElseThrow().reason());assertEquals(1,archives());
        setup();when(inputs.read()).thenReturn(new SourceDayRoster.Evidence(registered,0,List.of()));cohorts.seal(day);
        var empty=service(Clock.fixed(registered,ZoneOffset.UTC)).close(day);assertTrue(empty.closure().entries().isEmpty());assertTrue(empty.closure().early());assertEquals(1,archives());verifyNoInteractions(progress);
    }
    @Test void rehashedMissingMemberOrWrongParentAndRoleNeverReplaceOriginalClosure(){
        cohorts.seal(day);var closed=service(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day);String saved=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='source_day_dedup'",String.class);
        var envelope=(ObjectNode)CanonicalJson.parse(saved);((tools.jackson.databind.node.ArrayNode)envelope.get("closure").get("sources")).remove(0);envelope.put("closure_hash",CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,envelope.get("closure")));
        String raw=JdbcFrozenInputs.canonical(envelope);f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='source_day_dedup'",raw,JdbcGenerationLineage.hash(raw));assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));
        f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='source_day_dedup'",saved,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(saved))));assertEquals(closed.closure().hash(),service(mock(Clock.class)).find(day).closure().hash());
        var wrongParent=(ObjectNode)CanonicalJson.parse(saved);wrongParent.put("bundle_hash","c".repeat(64));String wrongRaw=JdbcFrozenInputs.canonical(wrongParent);
        f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='source_day_dedup'",wrongRaw,JdbcGenerationLineage.hash(wrongRaw));assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));
        f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='source_day_dedup'",saved,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(saved))));
        f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE target_type='source_day_dedup'");assertThrows(RuntimeException.class,()->service(mock(Clock.class)).close(day));assertEquals(1,archives());
    }
    @Test void actualWriteRollbackAndCommitAcknowledgementLossRecoverOnlyOriginal(){
        cohorts.seal(day);Instant deadline=SourceDedupBarrier.deadline(day);f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_closure_failure CHECK(target_type IS NULL OR target_type<>'source_day_dedup')");
        assertThrows(RuntimeException.class,()->service(Clock.fixed(deadline,ZoneOffset.UTC)).close(day));assertEquals(0,archives());f.jdbc.execute(f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_closure_failure":"ALTER TABLE jobs DROP CHECK fictional_closure_failure");
        var fault=new DataSourceTransactionManager(f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional closure ACK lost",new java.sql.SQLException("fictional transport","08006"));}}};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.source),fault,()->25,n->{});var closed=service(Clock.fixed(deadline,ZoneOffset.UTC),writes,fault).close(day);
        assertEquals(closed.closure().json(),service(mock(Clock.class)).close(day).closure().json());assertEquals(1,archives());assertEquals(0,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void sqlFailureCallerTransactionClockRollbackAndChangedOriginalProgressReject(){
        cohorts.seal(day);assertThrows(RuntimeException.class,()->service(Clock.fixed(registered.minusNanos(1000),ZoneOffset.UTC)).close(day));
        var tx=new TransactionTemplate(manager);assertThrows(RuntimeException.class,()->tx.execute(s->service(mock(Clock.class)).close(day)));assertThrows(RuntimeException.class,()->tx.execute(s->service(mock(Clock.class)).find(day)));
        complete("fictional-a",registered.plusSeconds(1));complete("fictional-b",registered.plusSeconds(2));service(Clock.fixed(registered.plusSeconds(3),ZoneOffset.UTC)).close(day);facts.remove("fictional-a");assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));
        f.jdbc.execute("DROP TABLE jobs");assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));
    }
    @Test void aliasChangedPhysicalTimeAndOversizeBodyNeverBecomeAbsentArchive(){
        cohorts.seal(day);service(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day);
        String id=f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_day_dedup'",String.class);
        f.jdbc.update("UPDATE jobs SET id='fictional-closure-alias' WHERE id=?",id);assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));
        f.jdbc.update("UPDATE jobs SET id=? WHERE id='fictional-closure-alias'",id);
        f.jdbc.update("UPDATE jobs SET acted_at='2026-01-01 20:30:01' WHERE id=?",id);assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));
        String oversized="{\"fictional\":\""+"F".repeat(2097153)+"\"}";f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",oversized,JdbcGenerationLineage.hash(oversized),id);
        assertThrows(RuntimeException.class,()->service(mock(Clock.class)).find(day));assertEquals(1,archives());
        assertThrows(RuntimeException.class,()->JdbcSourceDayDedupClosures.prepare(oversized));
    }
    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void actualClosureUtcTimesAndOriginalReplaySurviveJvmTimezone(String zone){
        cohorts.seal(day);Instant deadline=SourceDedupBarrier.deadline(day);var prior=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone(zone));var closed=service(Clock.fixed(deadline,ZoneOffset.UTC)).close(day);
            for(String field:List.of("acted_at","created_at","updated_at","eligible_at"))assertEquals(deadline,f.jdbc.query("SELECT "+field+" FROM jobs WHERE target_type='source_day_dedup'",(rs,n)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()).getFirst());
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertEquals(closed.closure().json(),service(mock(Clock.class)).find(day).closure().json());assertEquals(1,archives());
        }finally{TimeZone.setDefault(prior);}
    }
}
