package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSourceStageAvailability;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;

/** Fictional original manifests and results; pure dependency bytes never grant SQL permission. */
class SourceDedupPartialDependencyTest {
    SourceDedupResolvedStageResultTest f;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourceDedupPartialAvailabilityTest availabilityFixture(){return new SourceDedupPartialAvailabilityTest();}
    void setup(int count)throws Exception {f=new SourceDedupResolvedStageResultTest(){@Override GenerationJdbcStoreTest database(){return SourceDedupPartialDependencyTest.this.database();}};f.setup(count);}
    SourceDedupStageResult partial(){return SourceDedupStageResult.createResolved(f.snapshot(f.ref));}
    Instant observed(){return f.f.clock.instant().plusNanos(1000);}
    FrozenSourceDedupDependency dependency(){return FrozenSourceDedupDependency.create(partial(),observed(),observed().plusSeconds(1));}
    @Test void sparseOriginalReportsKeepGapsAndFullFailuresWithoutWritingOrInventingDependencies()throws Exception {
        setup(201);f.applied(0);f.terminal(1,"failed");f.applied(2);int before=f.f.db.count("1=1");var original=partial();var dependency=dependency();var node=(ObjectNode)dependency.toJson();
        assertEquals("source-dedup-dependency-v2",node.get("schema_version").asText());assertEquals(2,node.get("reports").size());assertEquals(0,node.get("reports").get(0).get("stage_part").asInt());assertEquals(2,node.get("reports").get(1).get("stage_part").asInt());
        assertEquals(201,node.get("stage_result").get("decisions").size());assertEquals(3,node.get("stage_result").get("resolutions").size());assertEquals(101,node.get("stage_result").get("applied_member_count").asInt());assertEquals(100,node.get("stage_result").get("unapplied_member_count").asInt());assertEquals(original.hash(),node.get("stage_key").get("result_hash").asText());
        assertEquals(2,dependency.dependencies().size());assertEquals(List.of(f.plan.chunks().get(0).rootId(),f.plan.chunks().get(2).rootId()),dependency.dependencies().stream().map(GenerationFlowService.Dependency::id).toList());assertTrue(dependency.dependencies().stream().allMatch(d->d.stage()==GenerationFlowService.Stage.NEWS_DEDUP&&d.state().equals("succeeded")&&d.completedAt().equals(observed())));
        assertEquals(dependency.json(),FrozenSourceDedupDependency.readPartial(node,observed()).json());assertEquals(dependency.json(),FrozenSourceDedupDependency.read(node,original,observed()).json());assertEquals(before,f.f.db.count("1=1"));assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(node,observed()));assertThrows(UnsupportedOperationException.class,()->dependency.dependencies().clear());node.put("extra",true);assertFalse(dependency.toJson().has("extra"));
    }
    @Test void allFourTerminalFailuresKeepActualInputMissingEvenWithNoSuccessfulReports()throws Exception {
        setup(1);for(String state:List.of("failed","cancelled","discarded","superseded")){f.terminal(0,state);var dependency=dependency();var full=dependency.toJson().get("stage_result");assertEquals(1,full.get("eligible_count").asInt());assertEquals(1,full.get("unapplied_member_count").asInt());assertEquals(state,full.get("resolutions").get(0).get("state").asText());assertEquals(0,dependency.toJson().get("reports").size());assertTrue(dependency.dependencies().isEmpty());assertEquals(dependency.json(),FrozenSourceDedupDependency.readPartial(dependency.toJson(),observed()).json());}
    }
    @Test void completeAndZeroKeepExactV1BytesAndVersionSpecificReadersRemainClosed() {
        var existing=new FrozenSourceDedupDependencyTest();for(int count:List.of(203,0)){var original=existing.original(count);var dependency=FrozenSourceDedupDependency.create(original,existing.observed,existing.cutoff);assertEquals("source-dedup-dependency-v1",dependency.toJson().get("schema_version").asText());assertEquals(dependency.json(),FrozenSourceDedupDependency.read(dependency.toJson(),existing.cutoff).json());assertEquals(dependency.json(),FrozenSourceDedupDependency.read(dependency.toJson(),original,existing.cutoff).json());assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(dependency.toJson(),existing.cutoff));}
    }
    void reject(ObjectNode valid,Consumer<ObjectNode> mutation){var changed=valid.deepCopy();mutation.accept((ObjectNode)changed.get("stage_result"));((ObjectNode)changed.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,changed.get("stage_result")));assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(changed,observed()));}
    @Test void rehashedFullResultsStillRejectOmissionsSubstitutionsAndImpossibleFailureSemantics()throws Exception {
        setup(201);f.applied(0);f.terminal(1,"failed");f.applied(2);var valid=(ObjectNode)dependency().toJson();
        reject(valid,n->n.put("eligible_count",200));reject(valid,n->n.put("applied_member_count",100));reject(valid,n->n.put("unapplied_member_count",99));reject(valid,n->n.put("coverage_state","complete"));reject(valid,n->n.put("extra",true));reject(valid,n->((ArrayNode)n.get("decisions")).remove(0));
        reject(valid,n->((ObjectNode)n.get("decisions").get(1)).put("item_id",n.get("decisions").get(0).get("item_id").asText()));reject(valid,n->((ObjectNode)n.get("decisions").get(0)).put("reason","PREFERENCE_BLOCKED"));reject(valid,n->((ArrayNode)n.get("resolutions")).remove(1));reject(valid,n->((ObjectNode)n.get("resolutions").get(2)).put("stage_part",1));reject(valid,n->((ObjectNode)n.get("resolutions").get(1)).put("root_id","b".repeat(64)));
        reject(valid,n->((ObjectNode)n.get("resolutions").get(1)).put("state","running"));reject(valid,n->((ObjectNode)n.get("resolutions").get(1)).put("call_count",2));reject(valid,n->((ObjectNode)n.get("resolutions").get(1)).put("application_state","APPLIED"));reject(valid,n->((ObjectNode)n.get("resolutions").get(1)).put("result_hash","b".repeat(64)));reject(valid,n->((ObjectNode)n.get("resolutions").get(0)).put("applied_at","2026-01-02T00:03:00.000000Z"));
        reject(valid,n->((ArrayNode)n.get("resolutions").get(1).get("item_ids")).remove(0));reject(valid,n->((ObjectNode)n.get("fragments").get(1)).put("stage_part",1));reject(valid,n->((ArrayNode)n.get("fragments").get(0).get("members")).remove(0));reject(valid,n->((ObjectNode)n.get("fragments").get(0).get("members").get(0)).put("itemId","fictional-substitution"));reject(valid,n->((ObjectNode)n.get("fragments").get(0).get("members").get(0)).put("activationStage","trial"));
        var reports=valid.deepCopy();((ObjectNode)reports.get("reports").get(0)).put("summary","Fictional substitute");assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(reports,observed()));var outer=valid.deepCopy();outer.put("extra",true);assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(outer,observed()));
    }
    @Test void microsecondCutoffCapacityAndExactTypedOriginalRemainMandatory()throws Exception {
        setup(1);f.terminal(0,"failed");var original=partial();var dependency=dependency();assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(dependency.toJson(),observed().minusNanos(1000)));assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(dependency.toJson(),observed().plusNanos(1)));assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.create(original,observed().plusNanos(1),observed().plusSeconds(1)));assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenSourceDedupDependency.createWithinCapacity(original,observed(),observed(),1));
        f.terminal(0,"cancelled");var changed=partial();assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(dependency.toJson(),changed,observed()));
    }
    @Test void evenActualPartialSealAndRegisteredObservationCannotEnableOldSqlConsumer()throws Exception {
        var a=availabilityFixture();a.setup(1);var original=a.f.a.f;original.terminal(0,"failed");var seal=a.f.service.seal(original.ref);var observed=a.observer.capture(original.ref);var dependency=FrozenSourceDedupDependency.create(seal.proof(),observed,observed);assertEquals(1,a.observations());var sql=new SourceDedupDependencyService(new JdbcSourceStageAvailability(original.f.db.source));int count=original.f.db.count("1=1");assertThrows(RuntimeException.class,()->original.f.read(()->sql.read(dependency.toJson(),observed)));assertThrows(RuntimeException.class,()->original.f.p1.tx(()->sql.read(dependency.toJson(),observed)));assertEquals(count,original.f.db.count("1=1"));assertEquals(dependency.json(),FrozenSourceDedupDependency.readPartial(dependency.toJson(),observed).json());
    }
}
