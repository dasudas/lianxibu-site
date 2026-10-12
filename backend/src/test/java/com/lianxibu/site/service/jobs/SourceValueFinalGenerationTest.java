package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

/** Two independent original generations; retained fictional AI results, actual final commits. */
class SourceValueFinalGenerationTest {
    SourceValueFinalizationTest f;
    SourcePartitionPlanner.Plan first,second;
    FrozenSourceStagePlan.Reference firstRef,secondRef;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){
        f=new SourceValueFinalizationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueFinalGenerationTest.this.fixture();}};
        f.setup();first=f.f.plan;firstRef=f.f.ref;
        var x=f.f.pure.f.fixture(5);
        String owner=JdbcFrozenInputs.stableId("intent",first.logicalKey()+":2");
        var universeRef=FrozenSourceUniverse.encode(owner,f.f.original).reference();
        second=new NewsValueSourcePlanBuilder("2").build(f.f.original,universeRef,x.selection(),new FrozenGenerationSettings(2,List.of("fictional-second-preference"),List.of()),x.dependency(),2);
        var writer=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.f.f.source),run->f.f.original,f.f.clock);
        secondRef=f.f.tx(()->{
            var records=new JdbcJobRecords(f.f.f.source);
            records.ensureIntent(new JdbcJobRecords.Intent(owner,second.logicalKey(),2,"news_value",second.scope().businessDate()),f.f.clock.instant());
            assertEquals(universeRef,writer.freeze(owner,f.f.original.runId()));
            var ref=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.f.f.source),writer,f.f.clock,"fictional-owner").freeze(second);
            for(var c:second.chunks()){
                records.createRoot(new JdbcJobRecords.Root(c.rootId(),owner,second.logicalKey(),2,c.stagePart(),"news_value",2),f.f.clock.instant());
                new JdbcFrozenInputs(f.f.f.source).save(c.rootId(),c.snapshotJson(),c.inputHash(),f.f.clock.instant());
            }
            return ref;
        });
        var refs=(tools.jackson.databind.node.ArrayNode)CanonicalJson.parse(f.sourceText("job_refs_json"));refs.add(owner);
        f.f.f.jdbc.update("UPDATE source_runs SET job_refs_json=?",JdbcFrozenInputs.canonical(refs));
        f.f.pure=new SourceValueStageResultTest(){@Override ObjectNode payload(SourcePartitionPlanner.Chunk c){var n=super.payload(c);n.put("summary",second.chunks().stream().anyMatch(p->p.rootId().equals(c.rootId()))?"Fictional second generation summary":"Fictional first generation summary");return n;}};
    }
    void select(boolean later){f.f.plan=later?second:first;f.f.ref=later?secondRef:firstRef;}
    SourceValueFinalReceiptService.Stored completeAndSeal(boolean later){select(later);f.f.complete(0);return f.service.finish(f.f.ref);}
    long revision(){return f.f.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class);}
    @Test void laterGenerationReplacesOnceAndOldReplayCannotRewriteTheCurrentSummary(){
        var old=completeAndSeal(false);assertEquals("applied",old.disposition());assertEquals(3,revision());
        var next=completeAndSeal(true);assertEquals("applied",next.disposition());assertEquals(4,revision());
        String summary=f.sourceText("analysis_summary");assertTrue(summary.contains("Fictional second generation summary"));
        select(false);int records=f.f.f.count("1=1");assertEquals(old.json(),f.service.finish(firstRef).json());assertEquals(old.json(),f.read().receipt().json());
        assertEquals(summary,f.sourceText("analysis_summary"));assertEquals(4,revision());assertEquals(records,f.f.f.count("1=1"));assertEquals(2,f.seals());
        select(true);assertEquals(next.json(),f.read().receipt().json());
    }
    @Test void olderGenerationFinishingLateSealsAsSupersededWithoutChangingSourceProjection(){
        var next=completeAndSeal(true);String summary=f.sourceText("analysis_summary"),refs=f.sourceText("job_refs_json");
        var old=completeAndSeal(false);assertEquals("superseded",old.disposition());assertEquals(3,old.projectionRevision());
        assertEquals(summary,f.sourceText("analysis_summary"));assertEquals(refs,f.sourceText("job_refs_json"));assertEquals(3,revision());
        assertEquals("succeeded",f.intentState());assertEquals(old.json(),f.read().receipt().json());assertEquals(2,f.seals());
        select(true);assertEquals(next.json(),f.service.finish(secondRef).json());assertEquals(summary,f.sourceText("analysis_summary"));
    }
    @Test void corruptedNativePriorPreventsReplacementUntilTheOriginalProofIsRestored(){
        var old=completeAndSeal(false);String summary=f.sourceText("analysis_summary");select(true);f.f.complete(0);
        byte[] originalHash=f.f.f.jdbc.queryForObject("SELECT action_payload_hash FROM jobs WHERE id=?",byte[].class,old.position().receiptId());
        f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],old.position().receiptId());
        assertThrows(RuntimeException.class,()->f.service.finish(secondRef));assertEquals(summary,f.sourceText("analysis_summary"));assertEquals(3,revision());assertEquals("waiting_input",f.intentState());assertEquals(1,f.seals());
        f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",originalHash,old.position().receiptId());
        assertEquals("applied",f.service.finish(secondRef).disposition());assertEquals(4,revision());assertEquals(2,f.seals());
    }
    @Test void replacementSealFailureRollsBackNewProjectionAndRetainsTheOldCommittedGeneration(){
        var old=completeAndSeal(false);String summary=f.sourceText("analysis_summary"),refs=f.sourceText("job_refs_json");select(true);f.f.complete(0);
        String sealId=JdbcFrozenInputs.stableId("source-value-final-receipt",secondRef.ownerId());
        f.f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_replacement_seal_failure CHECK(id<>'"+sealId+"')");
        assertThrows(RuntimeException.class,()->f.service.finish(secondRef));assertEquals(summary,f.sourceText("analysis_summary"));assertEquals(refs,f.sourceText("job_refs_json"));assertEquals(3,revision());assertEquals("waiting_input",f.intentState());assertEquals(1,f.seals());
        select(false);assertEquals(old.json(),f.read().receipt().json());
        f.f.f.jdbc.execute(f.f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_replacement_seal_failure":"ALTER TABLE jobs DROP CHECK fictional_replacement_seal_failure");
        select(true);assertEquals("applied",f.service.finish(secondRef).disposition());assertEquals(4,revision());assertEquals(2,f.seals());
    }
}
