package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

/** Actual original P1 SQL proof/observation; source registration API is an explicit test double. */
class SourceDayNewsDedupProgressServiceTest {
    final LocalDate day=LocalDate.of(2026,1,1);
    final Instant registered=Instant.parse("2026-01-01T16:10:00Z"),sealed=registered.plusSeconds(5),reserved=sealed.plusSeconds(5);
    SourceDedupResultProofServiceTest f;SourceDayRunRegistration.Api runs;SourceDayRunRegistration.Request request;
    SourceDedupStageProofService proofs;SourceDedupStageAvailabilityService observer;SourceDayNewsDedupProgressService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    String runId(){return FrozenSourceUniverseTest.RUN;}
    SourcePartitionPlanner.Scope scope(){return FrozenSourceStagePlanTest.scope();}
    @BeforeEach void setup(){install(5,runId());}
    void install(int count,String archivedRun){
        request=new SourceDayRunRegistration.Request(day,"a".repeat(64),registered,
                new SourceDayRoster.RawSource("fictional-source","news_tech",2,3,"production","enabled",registered.plusSeconds(43200),true,true,"b".repeat(64)),runId());
        var address=CollectionIntentIdentity.create(request.source().id(),"daily",day,1,"news_tech");
        f=new SourceDedupResultProofServiceTest(){
            @Override GenerationJdbcStoreTest fixture(){return SourceDayNewsDedupProgressServiceTest.this.fixture();}
            @Override MemberUniverse.Universe universe(){var u=FrozenSourceUniverseTest.universe(count,false);return new MemberUniverse.Universe(archivedRun,u.sourceId(),u.manifestHash(),u.parts(),u.members(),u.accepted(),u.excluded());}
            @Override SourcePartitionPlanner.Plan plan(MemberUniverse.Universe u){
                var ref=FrozenSourceUniverse.encode(address.id(),u).reference();
                return new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_DEDUP,address.logicalKey(),1,u,ref,
                        SourcePartitionPlannerTest.decisions(u),scope(),(ids,n,root)->{
                    var body=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,n,root,"Fictional original input"));body.put("logical_key",address.logicalKey());
                    body.put("cutoff",scope().cutoff().toString());for(var member:body.get("members"))((ObjectNode)member).put("completed_at",scope().cutoff().minusSeconds(60).toString());
                    for(var c:body.get(FrozenApplicationContext.FIELD).get("candidates_by_item"))((ObjectNode)c).putArray("entity_ids").add("fictional-existing-entity");
                    return JdbcFrozenInputs.canonical(body);
                });
            }
        };f.setup();f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs ALTER COLUMN acted_at TIMESTAMP(6)":"ALTER TABLE jobs MODIFY acted_at TIMESTAMP(6) NULL");
        runs=mock(SourceDayRunRegistration.Api.class);when(runs.lookup(request)).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new SourceDayRunRegistration.Reservation(request,1,reserved,request.hash());});
        proofs=new SourceDedupStageProofService(f.archives,f.universes,f.service);
        var manager=new DataSourceTransactionManager(f.f.source);observer=new SourceDedupStageAvailabilityService(proofs,new JdbcSourceStageAvailability(f.f.source),f.f.writes,manager,f.f.clock);
        service=new SourceDayNewsDedupProgressService(runs,f.archives,proofs,observer,manager);
    }
    FrozenSourceStagePlan.Reference ref(){return FrozenSourceStagePlan.encode(f.plan,f.original).reference();}
    Instant now(){return f.f.clock.instant().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    void complete(){for(var chunk:f.plan.chunks()){f.chunk=chunk;f.complete();}}
    SourceDedupBarrier.Progress read(){return service.read(request,sealed,now());}
    @Test void actualOriginalP1RequiresCompletionAndItsOwnPermanentFirstObservation(){
        assertNull(read());complete();assertNull(read());Instant at=observer.capture(ref());int rows=f.f.count("1=1");var progress=read();
        assertEquals(SourceDedupBarrier.State.COMPLETE,progress.state());assertEquals(at,progress.availableAt());assertEquals(request.runId(),progress.runId());
        assertEquals(request.source().effectiveConfigHash(),progress.configHash());assertEquals(3,progress.configRevision());
        assertEquals(f.readonly(()->proofs.read(ref())).hash(),progress.proofHash());assertEquals(progress,read());assertEquals(rows,f.f.count("1=1"));
        assertNull(service.read(request,sealed,at.minusNanos(1000)));assertFalse(progress.toString().contains("fictional-source"));
    }
    @Test void actualEmptyOriginalPlanRequiresObservationAndKeepsLateOriginalTime(){
        install(0,FrozenSourceUniverseTest.RUN);assertNull(read());Instant at=observer.capture(ref());var progress=read();assertEquals(SourceDedupBarrier.State.EMPTY_SUCCESS,progress.state());assertEquals(at,progress.availableAt());
        var cohort=SourceDedupBarrier.freeze("news_tech",day,registered,List.of(new SourceDedupBarrier.Member("fictional-source",SourceDedupBarrier.Inclusion.ENABLED,3L,"b".repeat(64),request.runId(),1L)));
        var closure=SourceDedupBarrier.close(cohort,now(),List.of(progress));assertEquals("late",closure.entries().getFirst().reason());assertEquals(SourceDedupBarrier.Decision.MISSING,closure.entries().getFirst().decision());
        assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
    @Test void unverifiedRegistrationAndOldReservationNeverAdmitEvenValidOriginalP1(){
        complete();observer.capture(ref());when(runs.lookup(request)).thenReturn(null);assertNull(read());
        when(runs.lookup(request)).thenReturn(new SourceDayRunRegistration.Reservation(request,1,registered,request.hash()));assertThrows(RuntimeException.class,this::read);
        var other=new SourceDayRunRegistration.Request(day,request.rosterHash(),registered,request.source(),"fictional-other-run");
        when(runs.lookup(request)).thenReturn(new SourceDayRunRegistration.Reservation(other,1,reserved,other.hash()));assertThrows(RuntimeException.class,this::read);
    }
    @Test void wrongRunOrDamagedProofAndSqlFailureCannotTurnIntoEmptyCompletion(){
        install(0,"fictional-other-run");observer.capture(ref());assertThrows(RuntimeException.class,this::read);
        install(5,FrozenSourceUniverseTest.RUN);complete();observer.capture(ref());f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(ref().ownerId()));assertThrows(RuntimeException.class,this::read);
        install(0,FrozenSourceUniverseTest.RUN);f.f.jdbc.execute("DROP TABLE jobs");assertThrows(RuntimeException.class,this::read);
    }
    @Test void missingIntentRemainsUnpreparedWhileOriginalOrphansAndCallerTransactionReject(){
        f.f.jdbc.update("DELETE FROM jobs");assertNull(read());
        assertThrows(RuntimeException.class,()->f.readonly(this::read));assertThrows(RuntimeException.class,()->f.tx(this::read));
        assertThrows(RuntimeException.class,()->service.read(request,sealed.minusSeconds(6),now()));assertThrows(RuntimeException.class,()->service.read(request,sealed,now().plusNanos(1)));
        verify(runs,never()).reserve(any());
    }
}
