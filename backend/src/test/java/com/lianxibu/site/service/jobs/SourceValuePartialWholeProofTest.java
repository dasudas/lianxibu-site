package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Actual sealed original partial value output; retained outcomes and databases are fictional. */
class SourceValuePartialWholeProofTest {
    SourceValuePartialSealTest q;SourceValuePartialResolutionTest f;SourceValueStageProofService service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(boolean mixed,boolean applied)throws Exception{q=new SourceValuePartialSealTest(){@Override GenerationJdbcStoreTest database(){return SourceValuePartialWholeProofTest.this.database();}};q.setup(mixed,applied);f=q.p.f;service=provider(q.service);}
    SourceValueStageProofService provider(SourceValuePartialSealService seals){var dependencies=new SourceDedupDependencyService(f.authority.observations,f.authority.proven,f.authority.service);return new SourceValueStageProofService(f.f.f.plans,f.original.p1.universes,new SourceValueResultProofService(new JdbcSourceDedupResults(f.db.source),f.f.f.plans,f.original.p1.universes,dependencies),dependencies,seals);}
    SourceValueStageProofService.Committed read(){return f.original.read(()->service.readCommitted(f.ref));}
    @Test void StagedZeroValueCannotBecomeWholeUntilItsRealPartialSealExists()throws Exception{
        setup(false,false);assertNull(read());assertNotNull(q.p.service.stage(f.ref));assertNull(read());service=provider(null);assertThrows(RuntimeException.class,this::read);service=provider(q.service);var seal=q.service.seal(f.ref);var actual=read();assertEquals(seal.proof().json(),actual.result().json());assertEquals(seal.minimumObservationTime(),actual.minimumObservationTime());assertEquals(1,actual.result().decisions().size());assertTrue(actual.result().fragments().isEmpty());int count=f.db.count("1=1"),calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");service=provider(q.service);assertEquivalent(actual,read());assertEquals(count,f.db.count("1=1"));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void EveryAppliedValueStillRequiresSealAndExactOriginalMutableMetadata()throws Exception{
        setup(true,true);assertNull(read());assertNotNull(q.p.service.stage(f.ref));assertNull(read());var seal=q.service.seal(f.ref);var actual=read();assertEquals(seal.proof().json(),actual.result().json());assertEquals("source-value-stage-result-v2",CanonicalJson.parse(actual.result().json()).get("schema_version").asText());assertEquals(200,actual.result().members().size());assertEquals(seal.minimumObservationTime(),actual.minimumObservationTime());var original=f.read().fragments().getFirst().nativeRoot();f.db.jdbc.update("UPDATE jobs SET updated_at=? WHERE id=?",Timestamp.from(original.updatedAt().plusNanos(1000)),original.id());assertThrows(RuntimeException.class,this::read);f.db.jdbc.update("UPDATE jobs SET updated_at=? WHERE id=?",Timestamp.from(original.updatedAt()),original.id());assertEquivalent(actual,read());var body=(ObjectNode)CanonicalJson.parse(seal.receiptJson());body.put("extra",true);String changed=JdbcFrozenInputs.canonical(body);f.db.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",changed,JdbcGenerationLineage.hash(changed),JdbcSourceValuePartialReceipts.receiptId(f.ref.ownerId()));assertThrows(RuntimeException.class,this::read);
    }
    @Test void FailedValueResolutionsStayOriginalAndWritableOrExternalReadsFail()throws Exception{
        setup(true,false);assertNull(read());var seal=q.service.seal(f.ref);var result=read().result();assertEquals(seal.proof().json(),result.json());assertEquals(f.f.plan.chunks().size(),CanonicalJson.parse(result.json()).get("resolutions").size());assertTrue(result.fragments().isEmpty());assertEquals(201,result.decisions().size());assertEquals(0,CanonicalJson.parse(result.json()).get("applied_member_count").asInt());assertEquals(200,CanonicalJson.parse(result.json()).get("unapplied_member_count").asInt());assertThrows(RuntimeException.class,()->service.readCommitted(f.ref));assertThrows(RuntimeException.class,()->f.original.p1.tx(()->service.readCommitted(f.ref)));assertThrows(RuntimeException.class,()->f.original.read(()->service.readCommitted(new FrozenSourceStagePlan.Reference(f.ref.ownerId(),"b".repeat(64),f.ref.indexHash()))));service=provider(null);assertThrows(RuntimeException.class,this::read);
    }
    static void assertEquivalent(SourceValueStageProofService.Committed expected,SourceValueStageProofService.Committed actual){assertEquals(expected.result().json(),actual.result().json());assertEquals(expected.result().hash(),actual.result().hash());assertEquals(expected.minimumObservationTime(),actual.minimumObservationTime());}
    @Test void ConstructorsDoNoSqlAndActualProviderCapacityRemainsTyped()throws Exception{
        var plans=mock(FrozenSourceStagePlanService.class);var universe=mock(FrozenSourceUniverseService.class);var fragments=mock(SourceValueResultProofService.class);var dependencies=mock(SourceDedupDependencyService.class);var seal=mock(SourceValuePartialSealService.class);new SourceValueStageProofService(plans,universe,fragments,dependencies,seal);verifyNoInteractions(plans,universe,fragments,dependencies,seal);setup(false,false);var guarded=spy(q.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(guarded).readCommitted(f.ref);service=provider(guarded);assertThrows(GenerationFlowService.CapacityGuarded.class,this::read);assertEquals(0,q.seals());
    }
}
