package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JobResultComparisonReadServiceTest {
    static final Actor OWNER=new Actor("fictional-owner","owner");
    static final String HASH="a".repeat(64);
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final ContentService.Coverage COVERAGE=new ContentService.Coverage("complete",List.of(),List.of(),List.of());
    static JsonNode body(String title){return CanonicalJson.parse("{\"title\":\""+title+"\",\"sections\":[],\"citation_ids\":[],\"type\":\"daily\",\"period_start\":\"2026-01-01T16:00:00.000000Z\",\"period_end\":\"2026-01-02T16:00:00.000000Z\",\"timezone\":\"Asia/Shanghai\",\"learning_task\":null}");}
    static ResultApplicationService.Input input(){return new ResultApplicationService.Input("fictional-job","fictional-attempt",Stage.DAILY_REPORT,2,HASH,1,1L,Set.of(),Map.of(),Set.of(),Set.of(),Set.of(),null,"2026-01-01T16:00:00.000000Z","2026-01-02T16:00:00.000000Z");}
    static ResultApplicationService.Validated result(){return result("report",body("Fictional candidate"));}
    static ResultApplicationService.Validated result(String kind,JsonNode body){try{var payload=JSON.createObjectNode();payload.put("kind",kind);payload.set("body",body);var core=JSON.createObjectNode();core.put("schema_version","1");core.put("input_hash",HASH);core.set("payload",payload);var digest=MessageDigest.getInstance("SHA-256");digest.update("ai-result-v1\n".getBytes(StandardCharsets.UTF_8));digest.update(CanonicalJson.bytes(core));return new ResultApplicationService.Validated(kind,new String(CanonicalJson.bytes(payload),StandardCharsets.UTF_8),HexFormat.of().formatHex(digest.digest()),HASH);}catch(Exception e){throw new AssertionError(e);}}
    static ResultApplicationService.Current current(boolean valid,boolean discarded,long generation){return new ResultApplicationService.Current(generation,HASH,4,5L,valid,discarded,null,result().resultHash(),"fictional-attempt",true,"CONTENT_REVISION_CONFLICT");}
    static JobResultComparisonReadService.Snapshot snapshot(ResultApplicationService.Validated result,ResultApplicationService.Current current,JsonNode body){return new JobResultComparisonReadService.Snapshot("fictional-job","logical_task",4,"fictional-content","report",5L,3L,body,"fictional-input",Instant.parse("2026-01-03T01:00:00Z"),COVERAGE,input(),result,current);}
    @Test void safeComparisonRevalidatesResultAndKeepsBodyRevisionSeparate(){var source=body("Fictional current");var service=new JobResultComparisonReadService(id->snapshot(result(),current(true,false,2),source));var view=(JobResultComparisonReadService.Comparison)service.read(OWNER,"fictional-job");assertEquals(5,view.currentContentRevision());assertEquals(3,view.currentBodyRevision());assertTrue(view.canApply());assertTrue(view.blockingReasons().isEmpty());assertEquals("Fictional candidate",view.candidateBody().path("title").asText());((tools.jackson.databind.node.ObjectNode)source).put("title","Fictional changed later");assertEquals("Fictional current",view.currentBody().path("title").asText());}
    @Test void fourInterpretationBodiesKeepCategoryAndEntityIdentity(){
        var stages=List.of(Stage.AGENT_ANALYSIS,Stage.SKILL_ANALYSIS,Stage.WORKFLOW_ANALYSIS,Stage.MODEL_ANALYSIS);
        var categories=List.of("agent","skill","workflow","model");
        for(int i=0;i<stages.size();i++){
            var category=categories.get(i);var body=JSON.createObjectNode();body.put("title","Fictional interpretation");body.putArray("sections");body.putArray("citation_ids");body.put("category",category);body.put("entity_id","fictional-entity");
            var result=result("analysis",body);
            var input=new ResultApplicationService.Input("fictional-job","fictional-attempt",stages.get(i),2,HASH,1,1L,Set.of(),Map.of(),Set.of(),Set.of(),Set.of(),"fictional-entity",null,null);
            var state=new ResultApplicationService.Current(2,HASH,4,5L,true,false,null,result.resultHash(),"fictional-attempt",true,"CONTENT_REVISION_CONFLICT");
            var snapshot=new JobResultComparisonReadService.Snapshot("fictional-job","logical_task",4,"fictional-content",category,5L,3L,body,"fictional-input",Instant.parse("2026-01-03T01:00:00Z"),COVERAGE,input,result,state);
            assertTrue(((JobResultComparisonReadService.Comparison)new JobResultComparisonReadService(id->snapshot).read(OWNER,"fictional-job")).canApply());
            body.put("entity_id","fictional-other-entity");
            assertThrows(IllegalStateException.class,()->new JobResultComparisonReadService(id->snapshot).read(OWNER,"fictional-job"));
        }
    }
    @Test void unavailableIsExplicitAndNeverInventsBodies(){var service=new JobResultComparisonReadService(id->snapshot(null,current(true,false,2),body("Fictional current")));var view=(JobResultComparisonReadService.Unavailable)service.read(OWNER,"fictional-job");assertEquals("no_retained_result",view.reason());assertEquals(4,view.jobRevision());}
    @Test void supersededInvalidOrDiscardedResultsAreReadOnlyBlocked(){var service=new JobResultComparisonReadService(id->snapshot(result(),current(false,true,3),body("Fictional current")));var view=(JobResultComparisonReadService.Comparison)service.read(OWNER,"fictional-job");assertFalse(view.canApply());assertEquals(List.of("discarded","superseded","invalid_evidence"),view.blockingReasons());}
    @Test void corruptHashOrWrongPeriodNeverReturnsFalseProof(){var original=result();var bad=new ResultApplicationService.Validated(original.kind(),original.payloadJson(),"b".repeat(64),original.inputHash());assertThrows(IllegalStateException.class,()->new JobResultComparisonReadService(id->snapshot(bad,current(true,false,2),body("Fictional current"))).read(OWNER,"fictional-job"));var wrong=body("Fictional wrong");((tools.jackson.databind.node.ObjectNode)wrong).put("period_end","2026-01-03T16:00:00.000000Z");assertThrows(IllegalStateException.class,()->new JobResultComparisonReadService(id->snapshot(result(),current(true,false,2),wrong)).read(OWNER,"fictional-job"));}
    @Test void corruptCurrentProofAndStoreFailureRemainUnavailable(){
        var unknown=new ResultApplicationService.Current(2,null,4,5L,true,false,null,result().resultHash(),"fictional-attempt",true,"CONTENT_REVISION_CONFLICT");
        var view=(JobResultComparisonReadService.Unavailable)new JobResultComparisonReadService(id->snapshot(result(),unknown,body("Fictional current"))).read(OWNER,"fictional-job");
        assertEquals("unproven_input",view.reason());
        assertThrows(IllegalStateException.class,()->new JobResultComparisonReadService(id->{throw new IllegalArgumentException("Fictional storage corruption");}).read(OWNER,"fictional-job"));
        var unsafe=new ResultApplicationService.Current(-1,HASH,4,5L,true,false,null,result().resultHash(),"fictional-attempt",true,"CONTENT_REVISION_CONFLICT");
        assertThrows(IllegalStateException.class,()->new JobResultComparisonReadService(id->snapshot(result(),unsafe,body("Fictional current"))).read(OWNER,"fictional-job"));
    }
    @Test void ownershipPrecedesStoreAndUnknownTaskIsNotFound(){int[] reads={0};var service=new JobResultComparisonReadService(id->{reads[0]++;return null;});assertThrows(JobResultComparisonReadService.Denied.class,()->service.read(new Actor("fictional-user","user"),"fictional-job"));assertEquals(0,reads[0]);assertThrows(JobResultComparisonReadService.Missing.class,()->service.read(OWNER,"fictional-job"));assertEquals(1,reads[0]);}
}
