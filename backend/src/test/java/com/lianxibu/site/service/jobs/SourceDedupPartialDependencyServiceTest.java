package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupPartialDependencyServiceTest {
    SourceDedupPartialAvailabilityTest a;SourceDedupResolvedStageResultTest f;SourceDedupPartialDependencyService service;JdbcSourceStageAvailability observations;
    SourceDedupPartialAvailabilityTest fixture(){return new SourceDedupPartialAvailabilityTest();}
    void setup(int count)throws Exception {a=fixture();a.setup(count);f=a.f.a.f;observations=new JdbcSourceStageAvailability(f.f.db.source);service=new SourceDedupPartialDependencyService(a.f.service,observations);}
    FrozenSourceDedupDependency original(){var seal=a.f.service.seal(f.ref);var observed=a.observer.capture(f.ref);return FrozenSourceDedupDependency.create(seal.proof(),observed,observed);}
    SourceDedupPartialDependencyService.Proven read(FrozenSourceDedupDependency original){return f.f.read(()->service.read(original.toJson(),original.observedAt()));}
    @Test void actualSparseSealAndObservationProveExactOriginalWithoutWritesOrCurrentSourceReads()throws Exception {
        setup(201);f.applied(0);f.terminal(1,"failed");f.applied(2);var original=original();int count=f.f.db.count("1=1"),calls=f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var proven=read(original);
        assertEquals(original.json(),proven.dependency().json());assertEquals(original.toJson().get("stage_result").get("plan_ref"),CanonicalJson.parse(proven.seal().proof().planRef().json()));assertEquals(original.toJson().get("stage_key").get("result_hash").asText(),proven.seal().proof().hash());assertEquals(2,proven.dependency().dependencies().size());assertEquals(201,proven.dependency().toJson().get("stage_result").get("eligible_count").asInt());assertEquals(100,proven.dependency().toJson().get("stage_result").get("unapplied_member_count").asInt());
        service=new SourceDedupPartialDependencyService(a.f.provider(f.f.db.writes),new JdbcSourceStageAvailability(f.f.db.source));assertEquals(original.json(),read(original).dependency().json());f.f.db.jdbc.execute("DROP TABLE IF EXISTS source_run_items");f.f.db.jdbc.execute("DROP TABLE IF EXISTS source_runs");assertEquals(original.json(),read(original).dependency().json());assertEquals(count,f.f.db.count("1=1"));assertEquals(calls,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals("ProvenPartialSourceDedupDependency[redacted]",proven.toString());
    }
    @Test void allExplicitFailuresRemainMissingAndNeverBecomeCompleteZeroInput()throws Exception {
        for(String state:List.of("failed","cancelled","discarded","superseded")){setup(1);f.terminal(0,state);var proven=read(original());assertEquals(state,proven.dependency().toJson().get("stage_result").get("resolutions").get(0).get("state").asText());assertEquals(1,proven.dependency().toJson().get("stage_result").get("unapplied_member_count").asInt());assertTrue(proven.dependency().dependencies().isEmpty());assertTrue(proven.seal().proof().members().isEmpty());assertEquals(1,a.observations());}
    }
    @Test void pureOrStagedProofAndEvenAnInsertedObservationWithoutClosedSealAreInsufficient()throws Exception {
        setup(1);f.terminal(0,"failed");var pure=SourceDedupStageResult.createResolved(f.snapshot(f.ref));var profile=FrozenSourceDedupDependency.create(pure,f.f.clock.instant(),f.f.clock.instant());assertThrows(RuntimeException.class,()->read(profile));a.f.a.service.stage(f.ref);assertThrows(RuntimeException.class,()->read(profile));f.f.p1.tx(()->{observations.save(profile.observationKeyJson(),profile.observedAt());return true;});assertEquals(1,a.observations());assertEquals(0,a.f.seals());assertThrows(RuntimeException.class,()->read(profile));
    }
    @Test void aRealLaterSealCannotBorrowAnEarlierInsertedNativeObservation()throws Exception {
        setup(1);f.terminal(0,"failed");var pure=SourceDedupStageResult.createResolved(f.snapshot(f.ref));var early=FrozenSourceDedupDependency.create(pure,f.f.clock.instant(),f.f.clock.instant());f.f.p1.tx(()->{observations.save(early.observationKeyJson(),early.observedAt());return true;});
        var later=new SourceDedupPartialSealService(a.f.a.resolutions,a.f.a.service,a.f.receipts,a.f.roots,f.f.db.writes,new DataSourceTransactionManager(f.f.db.source),Clock.fixed(f.f.clock.instant().plusSeconds(2),ZoneOffset.UTC));var seal=later.seal(f.ref);assertTrue(seal.minimumObservationTime().isAfter(early.observedAt()));service=new SourceDedupPartialDependencyService(later,observations);assertThrows(RuntimeException.class,()->read(early));assertEquals(1,a.observations());assertEquals(1,a.f.seals());
    }
    @Test void damagedCurrentNativeArchiveAndClosedReceiptCannotReuseARegisteredObservation()throws Exception {
        for(String damage:List.of("native","archive","receipt")){setup(1);f.terminal(0,"failed");var original=original();int count=f.f.db.count("1=1");switch(damage){
            case "native"->f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.plan.chunks().getFirst().rootId());
            case "archive"->{String id=f.f.db.jdbc.queryForObject("SELECT id FROM jobs WHERE record_kind='snapshot_part' AND purpose='result' AND part_no=1",String.class);String saved=f.f.db.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE id=?",String.class,id);var changed=(ObjectNode)CanonicalJson.parse(saved);changed.put("extra",true);f.f.db.jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE id=?",JdbcFrozenInputs.canonical(changed),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,changed)),id);}
            case "receipt"->f.f.db.jdbc.update("UPDATE jobs SET operator_id='fictional-wrong-actor' WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(f.ref.ownerId()));
        }assertThrows(RuntimeException.class,()->read(original));assertEquals(count,f.f.db.count("1=1"));assertEquals(1,a.observations());}
    }
    @Test void selfRehashedOriginalAndChangedTimeOrVersionNeverReceiveProvenPermission()throws Exception {
        setup(201);f.applied(0);f.terminal(1,"failed");f.applied(2);var original=original();var changed=(ObjectNode)original.toJson();((ObjectNode)changed.get("stage_result").get("fragments").get(0).get("payload")).put("summary","Fictional self replacement");((ObjectNode)changed.get("reports").get(0)).put("summary","Fictional self replacement");((ObjectNode)changed.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,changed.get("stage_result")));assertDoesNotThrow(()->FrozenSourceDedupDependency.readPartial(changed,original.observedAt()));assertThrows(RuntimeException.class,()->f.f.read(()->service.read(changed,original.observedAt())));
        var later=(ObjectNode)original.toJson();later.put("observed_at","2026-01-02T00:03:00.000000Z");assertThrows(RuntimeException.class,()->f.f.read(()->service.read(later,Instant.parse("2026-01-02T00:04:00Z"))));var wrong=(ObjectNode)original.toJson();wrong.put("schema_version","source-dedup-dependency-v1");assertThrows(RuntimeException.class,()->f.f.read(()->service.read(wrong,original.observedAt())));assertThrows(RuntimeException.class,()->f.f.read(()->service.read(original.toJson(),original.observedAt().minusNanos(1000))));
    }
    @Test void constructorsAreZeroSqlAndOnlyActualReadonlyTransactionsCanRead()throws Exception {
        var seals=mock(SourceDedupPartialSealService.class);var ledger=mock(JdbcSourceStageAvailability.class);new SourceDedupPartialDependencyService(seals,ledger);verifyNoInteractions(seals,ledger);setup(1);f.terminal(0,"failed");var original=original();assertThrows(RuntimeException.class,()->service.read(original.toJson(),original.observedAt()));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.read(original.toJson(),original.observedAt())));var capacity=spy(a.f.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capacity).readCommitted(any());var limited=new SourceDedupPartialDependencyService(capacity,observations);assertThrows(GenerationFlowService.CapacityGuarded.class,()->f.f.read(()->limited.read(original.toJson(),original.observedAt())));assertEquals(1,a.observations());
    }
}
