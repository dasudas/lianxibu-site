package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceStageBirthProofTest {
    record Archive(SourcePartitionPlanner.Plan plan,FrozenSourceStagePlan.Reference ref) { }
    static Archive archive(long generation,int members,boolean eligible){
        var original=FrozenSourceUniverseTest.universe(members,false);String owner=JdbcFrozenInputs.stableId("intent",SourcePartitionPlannerTest.KEY+":"+generation);
        var universe=FrozenSourceUniverse.encode(owner,original).reference();var decisions=original.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:eligible?SourcePartitionPlanner.Reason.ELIGIBLE:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).toList();
        var plan=new SourcePartitionPlanner().plan(Stage.NEWS_DEDUP,SourcePartitionPlannerTest.KEY,generation,original,universe,decisions,FrozenSourceStagePlanTest.scope(),(ids,n,root)->{
            var snapshot=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(universe,ids,n,root,"Fictional original body"));snapshot.put("generation",generation);((ObjectNode)snapshot.get(FrozenApplicationContext.FIELD)).put("generation",generation);return JdbcFrozenInputs.canonical(snapshot);
        });return new Archive(plan,FrozenSourceStagePlan.encode(plan,original).reference());
    }
    SourceStageBirthProof proof(Archive current,Archive previous,int part){return SourceStageBirthProof.create(current.plan().chunks().get(part).rootId(),current.plan(),current.ref(),previous.plan(),previous.ref());}
    @Test void realZeroPartPlanProvesPartZeroBirthAndCodecRoundTripsOriginalReferences(){
        var current=archive(2,5,true);var previous=archive(1,5,false);var proof=proof(current,previous,0);
        assertEquals(0,proof.previousStagePartCount());assertEquals(2,proof.generation());assertEquals(current.ref(),proof.currentPlanRef());assertEquals(previous.ref(),proof.previousPlanRef());
        assertEquals(proof,SourceStageBirthProof.fromJson(CanonicalJson.parse(proof.json())));assertFalse(proof.toString().contains(SourcePartitionPlannerTest.KEY));
        var zero=archive(1,0,false);assertEquals(0,proof(current,zero,0).previousStagePartCount());
    }
    @Test void wholePreviousPlanProvesOnlyNewGrowthPartAndExistingPartNeedsItsPredecessor(){
        var current=archive(2,251,true);var previous=archive(1,11,true);assertEquals(2,current.plan().chunks().size());assertEquals(1,previous.plan().chunks().size());
        var proof=proof(current,previous,1);assertEquals(1,proof.stagePart());assertEquals(1,proof.previousStagePartCount());assertThrows(RuntimeException.class,()->proof(current,previous,0));
        assertThrows(RuntimeException.class,()->SourceStageBirthProof.create("fictional-other-root",current.plan(),current.ref(),previous.plan(),previous.ref()));
    }
    @Test void mixedScopeGenerationAndReferencesCannotManufactureAnAbsentPart(){
        var current=archive(2,5,true);var previous=archive(1,0,false);
        assertThrows(RuntimeException.class,()->SourceStageBirthProof.create(current.plan().chunks().getFirst().rootId(),current.plan(),previous.ref(),previous.plan(),current.ref()));
        assertThrows(RuntimeException.class,()->proof(archive(3,5,true),previous,0));assertThrows(RuntimeException.class,()->proof(archive(1,5,true),current,0));
        var p=previous.plan();var scopes=List.of(new SourcePartitionPlanner.Scope(p.scope().businessDate().minusDays(1),p.scope().cutoff(),p.scope().settings()),new SourcePartitionPlanner.Scope(p.scope().businessDate(),p.scope().cutoff().plusSeconds(1),p.scope().settings()));
        for(var scope:scopes){var bad=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),scope);assertThrows(RuntimeException.class,()->SourceStageBirthProof.create(current.plan().chunks().getFirst().rootId(),current.plan(),current.ref(),bad,previous.ref()));}
        var bad=new SourcePartitionPlanner.Plan(Stage.DAILY_REPORT,p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),p.scope());assertThrows(RuntimeException.class,()->SourceStageBirthProof.create(current.plan().chunks().getFirst().rootId(),current.plan(),current.ref(),bad,previous.ref()));
        var first=current.plan().chunks().getFirst();var wrong=new SourcePartitionPlanner.Chunk(1,first.rootId(),first.itemIds(),first.snapshotJson(),first.inputHash(),first.requestJson());
        var malformed=new SourcePartitionPlanner.Plan(current.plan().stage(),current.plan().logicalKey(),2,current.plan().reference(),current.plan().decisions(),List.of(wrong),current.plan().scope());assertThrows(RuntimeException.class,()->SourceStageBirthProof.create(first.rootId(),malformed,current.ref(),p,previous.ref()));
    }
    @Test void closedCodecRefusesAliasedFieldsUnknownVersionsUnsafeNumbersAndForgedOwners(){
        var proof=proof(archive(2,5,true),archive(1,0,false),0);
        for(String mode:List.of("unknown","version","fraction","unsafe","existing","owner","stage","root","generation","missing")){
            var body=(ObjectNode)CanonicalJson.parse(proof.json());switch(mode){
                case "unknown"->body.put("unexpected",true);case "version"->body.put("schema_version","unknown");case "fraction"->body.put("stage_part",0.1);
                case "unsafe"->body.put("generation",9007199254740992L);case "existing"->body.put("previous_stage_part_count",1);
                case "owner"->((ObjectNode)body.get("previous_plan_ref")).put("owner_job_id","fictional-other-owner");case "stage"->body.put("task_type","NEWS_DEDUP");
                case "root"->body.put("root_id","fictional-other-root");case "generation"->body.put("generation",1);case "missing"->body.remove("previous_plan_ref");
            }assertThrows(RuntimeException.class,()->SourceStageBirthProof.fromJson(body),mode);
        }
    }
}
