package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupPartialDependencyPermitTest {
    SourceDedupPartialAvailabilityTest a;SourceDedupResolvedStageResultTest f;SourceDedupPartialDependencyService proven;SourceDedupPartialDependencyPermitService service;JdbcSourceStageRoots roots;JdbcSourceStageAvailability observations;int padding;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(int count,int pad)throws Exception {
        padding=pad;a=new SourceDedupPartialAvailabilityTest(){@Override SourceDedupPartialSealTest fixture(){return new SourceDedupPartialSealTest(){@Override SourceDedupPartialArchiveTest fixture(){return new SourceDedupPartialArchiveTest(){@Override SourceDedupResolvedStageResultTest fixture(){return new SourceDedupResolvedStageResultTest(){@Override GenerationJdbcStoreTest database(){return SourceDedupPartialDependencyPermitTest.this.database();}};}@Override void setup(int c,int ignored)throws Exception {super.setup(c,padding);}};}};}};
        a.setup(count);f=a.f.a.f;roots=new JdbcSourceStageRoots(f.f.db.source);observations=new JdbcSourceStageAvailability(f.f.db.source);proven=new SourceDedupPartialDependencyService(a.f.service,observations);service=provider();
    }
    SourceDedupPartialDependencyPermitService provider(){return new SourceDedupPartialDependencyPermitService(proven,a.f.a.resolutions,a.f.a.store,a.f.receipts,roots,observations,new DataSourceTransactionManager(f.f.db.source));}
    FrozenSourceDedupDependency original(){var seal=a.f.service.seal(f.ref);var observed=a.observer.capture(f.ref);return FrozenSourceDedupDependency.create(seal.proof(),observed,observed);}
    FrozenSourceDedupDependency verify(SourceDedupPartialDependencyPermitService.Captured captured){return f.f.db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-permit-probe","fictional-permit-resource",null),key->null,c->service.verify(captured,captured.dependency().observedAt()));}
    @Test void actualSparseAndMultipleBlocksVerifyWithinOriginalShortWriterWithNoWritesOrCalls()throws Exception {
        for(int pad:List.of(0,900)){setup(201,pad);f.applied(0);f.terminal(1,"failed");f.applied(2);var original=original();int count=f.f.db.count("1=1"),calls=f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var capture=service.capture(original.toJson(),original.observedAt());assertEquals(original.json(),capture.dependency().json());assertTrue(a.f.a.blocks()>=2);if(pad>0)assertTrue(a.f.a.blocks()>2);long start=System.nanoTime();assertEquals(original.json(),verify(capture).json());assertTrue(System.nanoTime()-start<1_000_000_000L);service=provider();assertEquals(original.json(),verify(capture).json());assertEquals(count,f.f.db.count("1=1"));assertEquals(calls,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals("CapturedPartialSourceDedupDependency[redacted]",capture.toString());}
    }
    @Test void allFailedRequiresRealSealAndObservationAndKeepsOriginalMissing()throws Exception {
        setup(1,0);f.terminal(0,"failed");var pure=SourceDedupStageResult.createResolved(f.snapshot(f.ref));var unsaved=FrozenSourceDedupDependency.create(pure,f.f.clock.instant(),f.f.clock.instant());assertThrows(RuntimeException.class,()->service.capture(unsaved.toJson(),unsaved.observedAt()));a.f.a.service.stage(f.ref);assertThrows(RuntimeException.class,()->service.capture(unsaved.toJson(),unsaved.observedAt()));var original=original();var capture=service.capture(original.toJson(),original.observedAt());var actual=verify(capture);assertTrue(actual.dependencies().isEmpty());assertEquals(1,actual.toJson().get("stage_result").get("unapplied_member_count").asInt());assertEquals(1,a.observations());assertEquals(1,a.f.seals());
    }
    @Test void nativeChangesAfterCaptureRejectOldProofWithoutAnyAdditionalStateWrites()throws Exception {
        for(String mode:List.of("revision","updatedAt","state","calls")){setup(1,0);f.terminal(0,"failed");var original=original();var capture=service.capture(original.toJson(),original.observedAt());String root=f.plan.chunks().getFirst().rootId();switch(mode){case "revision"->f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);case "updatedAt"->f.f.db.jdbc.update("UPDATE jobs SET updated_at=? WHERE id=?",Timestamp.from(f.f.clock.instant().plusNanos(1000)),root);case "state"->f.f.db.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",root);case "calls"->f.f.db.jdbc.update("UPDATE jobs SET call_count=2 WHERE id=?",root);}int count=f.f.db.count("1=1");assertThrows(RuntimeException.class,()->verify(capture));assertEquals(count,f.f.db.count("1=1"));assertEquals(1,a.f.seals());assertEquals(1,a.observations());}
    }
    @Test void rehashedArchiveOrSealAndDamagedObservationAfterCaptureRemainRejected()throws Exception {
        for(String mode:List.of("archive","actor","seal","observation")){setup(1,0);f.terminal(0,"failed");var original=original();var capture=service.capture(original.toJson(),original.observedAt());var jdbc=f.f.db.jdbc;switch(mode){
            case "archive"->{String id=jdbc.queryForObject("SELECT id FROM jobs WHERE record_kind='snapshot_part' AND purpose='result' AND part_no=1",String.class);var row=(ObjectNode)CanonicalJson.parse(jdbc.queryForObject("SELECT payload_json FROM jobs WHERE id=?",String.class,id));row.put("extra",true);jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE id=?",JdbcFrozenInputs.canonical(row),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,row)),id);}
            case "actor"->jdbc.update("UPDATE jobs SET operator_id='fictional-different-actor' WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(f.ref.ownerId()));
            case "seal"->{String id=JdbcSourceDedupPartialReceipts.receiptId(f.ref.ownerId());var row=(ObjectNode)CanonicalJson.parse(jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id));row.put("native_capture_hash","b".repeat(64));String raw=JdbcFrozenInputs.canonical(row);jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);}
            case "observation"->jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_availability'");
        }int count=f.f.db.count("1=1");assertThrows(RuntimeException.class,()->verify(capture));assertEquals(count,f.f.db.count("1=1"));}
    }
    @Test void changeInsideWriterRollsBackAndCapacityTypeIsPreservedWithoutGrantingPermission()throws Exception {
        setup(1,0);f.terminal(0,"failed");var original=original();var capture=service.capture(original.toJson(),original.observedAt());String root=f.plan.chunks().getFirst().rootId();long revision=f.f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,root);roots=spy(roots);doAnswer(call->{f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);return call.callRealMethod();}).when(roots).lockOriginal(anyList());service=provider();assertThrows(RuntimeException.class,()->verify(capture));assertEquals(revision,f.f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,root));roots=new JdbcSourceStageRoots(f.f.db.source);service=provider();assertEquals(original.json(),verify(capture).json());roots=spy(roots);doThrow(new JdbcSourceStageRoots.CapacityRejected()).when(roots).lockOriginal(anyList());service=provider();assertThrows(GenerationFlowService.CapacityGuarded.class,()->verify(capture));assertEquals(1,a.observations());
    }
    @Test void strictTransactionAndCutoffBoundariesAndConstructorsDoNotOpenHiddenTransactions()throws Exception {
        var p=mock(SourceDedupPartialDependencyService.class);var r=mock(SourceStageResolutionService.class);var b=mock(JdbcSourceDedupPartialBlocks.class);var s=mock(JdbcSourceDedupPartialReceipts.class);var n=mock(JdbcSourceStageRoots.class);var o=mock(JdbcSourceStageAvailability.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);new SourceDedupPartialDependencyPermitService(p,r,b,s,n,o,tx);verifyNoInteractions(p,r,b,s,n,o,tx);
        setup(1,0);f.terminal(0,"failed");var original=original();var captured=service.capture(original.toJson(),original.observedAt());assertThrows(RuntimeException.class,()->service.verify(captured,original.observedAt()));assertThrows(RuntimeException.class,()->f.f.read(()->service.verify(captured,original.observedAt())));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.capture(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.read(()->service.capture(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.verify(captured,original.observedAt().minusNanos(1000))));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.verify(captured,original.observedAt().plusNanos(1))));assertEquals(original.json(),f.f.p1.tx(()->service.verify(captured,original.observedAt().plusSeconds(1))).json());
    }
}
