package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceStageBirthLineageTest {
    SourceStageBirthProofServiceTest f;GenerationLineageService lineage;
    SourceStageBirthProofServiceTest fixture(){return new SourceStageBirthProofServiceTest();}
    @BeforeEach void setup(){f=fixture();f.setup();lineage=lineage();}
    GenerationLineageService lineage(){return new GenerationLineageService(new JdbcGenerationLineage(f.f.source),f.f.clock,"fictional-owner",f.service::read);}
    GenerationLineageService.Lineage record(String root){return f.tx(()->lineage.record(root));}
    @Test void birthHasAnOriginalV2ReceiptAndReplaysWithActualReadOnlyArchives(){
        f.archive(1,0,false);var current=f.archive(2,5,true);String root=f.root(current,0);var result=record(root);int count=f.f.count("1=1");
        assertNull(result.supersedesJobId());assertEquals(2,result.generation());var payload=CanonicalJson.parse(f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='generation_lineage'",String.class));
        assertEquals("generation-lineage-v2",payload.get("schema_version").asText());assertEquals(f.readonly(()->f.service.read(root)),SourceStageBirthProof.fromJson(payload.get("source_stage_birth")));
        lineage=lineage();assertEquals(result,f.readonly(()->lineage.read(root)));assertEquals(result,record(root));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void existingPartKeepsV1AndFutureV1CanFollowAnActualBirthV2(){
        var first=f.archive(1,11,true);String old=f.root(first,0);record(old);var next=f.archive(2,251,true);String continued=f.root(next,0),born=f.root(next,1);
        assertEquals(old,record(continued).supersedesJobId());assertNull(record(born).supersedesJobId());
        var third=f.archive(3,251,true);String descendant=f.root(third,1);assertEquals(born,record(descendant).supersedesJobId());
        assertEquals(born,f.readonly(()->lineage().read(descendant)).supersedesJobId());assertEquals(3L,f.f.jdbc.queryForList("SELECT action_payload FROM jobs WHERE target_type='generation_lineage'",String.class).stream().map(CanonicalJson::parse).filter(n->"generation-lineage-v1".equals(n.get("schema_version").asText())).count());
    }
    @Test void absentBirthProviderAndExistingOriginalPartCannotBecomeFirstGeneration(){
        f.archive(1,0,false);var next=f.archive(2,5,true);String root=f.root(next,0);var old=new GenerationLineageService(new JdbcGenerationLineage(f.f.source),f.f.clock,"fictional-owner");
        assertThrows(RuntimeException.class,()->f.tx(()->old.record(root)));assertEquals(0,f.f.count("target_type='generation_lineage'"));record(root);
        assertThrows(RuntimeException.class,()->f.readonly(()->old.read(root)));
    }
    @Test void missingArchiveAndOuterFailureNeverLeavePartialLineage(){
        var next=f.archive(2,5,true);String root=f.root(next,0);int count=f.f.count("1=1");assertThrows(RuntimeException.class,()->record(root));assertEquals(count,f.f.count("1=1"));
        f.archive(1,0,false);count=f.f.count("1=1");assertThrows(RuntimeException.class,()->f.tx(()->{lineage.record(root);throw new IllegalStateException("fictional outer failure");}));assertEquals(count,f.f.count("1=1"));
        assertNull(f.f.jdbc.queryForObject("SELECT supersedes_job_id FROM jobs WHERE id=?",String.class,root));assertEquals(0,f.f.count("target_type='generation_lineage'"));assertNotNull(record(root));
    }
    @Test void rehashedBirthReceiptCannotSubstituteAnotherPlanOrDowngradeToV1(){
        f.archive(1,0,false);var current=f.archive(2,5,true);String root=f.root(current,0);record(root);String original=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='generation_lineage'",String.class);
        for(String mode:java.util.List.of("hash","v1","unknown","generation")){
            var payload=(ObjectNode)CanonicalJson.parse(original);switch(mode){
                case "hash"->((ObjectNode)payload.get("source_stage_birth").get("previous_plan_ref")).put("plan_hash","b".repeat(64));
                case "v1"->{payload.put("schema_version","generation-lineage-v1");payload.remove("source_stage_birth");}
                case "unknown"->payload.put("unknown",true);case "generation"->payload.put("generation",1);
            }
            String changed=JdbcFrozenInputs.canonical(payload);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='generation_lineage'",changed,JdbcGenerationLineage.hash(changed));
            assertThrows(RuntimeException.class,()->f.readonly(()->lineage.read(root)),mode);
        }
    }
    @Test void retainedLineageStillRequiresTheWholeOriginalPreviousArchive(){
        var previous=f.archive(1,0,false);var current=f.archive(2,5,true);String root=f.root(current,0);record(root);
        f.f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",previous.ref().ownerId(),JdbcFrozenSourcePlans.stamp(previous.ref().ownerId()));
        assertThrows(RuntimeException.class,()->f.readonly(()->lineage().read(root)));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
}
