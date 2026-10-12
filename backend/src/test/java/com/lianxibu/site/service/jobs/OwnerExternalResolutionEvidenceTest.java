package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.JobsService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Input;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OwnerExternalResolutionEvidenceTest {
    static final Actor OWNER=new Actor("fictional-owner","owner");
    static final String ROOT="fictional-root",ATTEMPT="fictional-attempt",HASH="a".repeat(64);
    Input input(Stage stage){return new Input(ROOT,ATTEMPT,stage,1,HASH,7,4L,Set.of("fictional-item"),Map.of("fictional-item",Set.of("fictional-entity")),Set.of("fictional-item"),Set.of("fictional-citation"),Set.of(),"fictional-entity","2026-09-29T16:00:00.000000Z","2026-09-30T16:00:00.000000Z");}
    String payload(Stage stage){return switch(stage){
        case NEWS_DEDUP -> "{\"kind\":\"dedup\",\"summary\":\"Fictional summary\",\"items\":[{\"item_id\":\"fictional-item\",\"category\":\"news_tech\",\"decision\":\"existing\",\"candidate_entity_id\":\"fictional-entity\",\"reason\":\"Fictional reason\",\"confidence\":\"0.5\",\"suggested_title\":\"Fictional title\",\"suggested_summary\":null,\"reanalyze_recommended\":false}]}";
        case NEWS_VALUE -> "{\"kind\":\"value\",\"summary\":\"Fictional summary\",\"items\":[{\"item_id\":\"fictional-item\",\"category\":\"news_tech\",\"high_value\":true,\"reason\":\"Fictional reason\"}],\"selected_item_ids\":[\"fictional-item\"]}";
        case AGENT_ANALYSIS -> "{\"kind\":\"analysis\",\"body\":{\"title\":\"Fictional title\",\"sections\":[],\"citation_ids\":[\"fictional-citation\"],\"category\":\"agent\",\"entity_id\":\"fictional-entity\"}}";
        case DAILY_REPORT -> "{\"kind\":\"report\",\"body\":{\"title\":\"Fictional daily\",\"sections\":[],\"citation_ids\":[\"fictional-citation\"],\"type\":\"daily\",\"period_start\":\"2026-09-29T16:00:00.000000Z\",\"period_end\":\"2026-09-30T16:00:00.000000Z\",\"timezone\":\"Asia/Shanghai\",\"learning_task\":null}}";
        case AD_HOC_AI -> "{\"kind\":\"ad_hoc\",\"title\":\"Fictional answer\",\"sections\":[],\"citation_ids\":[]}";
        default -> throw new IllegalArgumentException();
    };}
    ValidatedResult signed(String raw,String hash){var body=CanonicalJson.parse(raw);var core=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",hash);core.set("payload",body);try{var digest=MessageDigest.getInstance("SHA-256");digest.update("ai-result-v1\n".getBytes(StandardCharsets.UTF_8));digest.update(CanonicalJson.bytes(core));return new ValidatedResult("1",hash,body,HexFormat.of().formatHex(digest.digest()));}catch(Exception failed){throw new AssertionError(failed);}}
    JobAction command(String attempt,long revision,String state,String reason,List<String> refs,ValidatedResult result,Usage usage){return new JobAction(revision,"resolve_external",reason,attempt,null,null,state,refs,result,usage);}
    JobAction command(String state,ValidatedResult result){return command(ATTEMPT,35,state,"Fictional verification",List.of("fictional-evidence"),result,null);}
    OwnerExternalResolutionEvidence verify(JobAction command,Input input){return OwnerExternalResolutionEvidence.verify(OWNER,ROOT,command,input);}
    @Test void originalFiveKindsValidateAndUnknownUsageStaysNull(){
        for(Stage stage:List.of(Stage.NEWS_DEDUP,Stage.NEWS_VALUE,Stage.AGENT_ANALYSIS,Stage.DAILY_REPORT,Stage.AD_HOC_AI)){
            var proof=verify(command("succeeded",signed(payload(stage),HASH)),input(stage));assertNotNull(proof.result());assertEquals(ROOT,proof.rootId());assertEquals(ATTEMPT,proof.attemptId());assertEquals(35,proof.expectedRevision());assertEquals("fictional-owner",proof.operatorId());assertTrue(CanonicalJson.parse(proof.canonical()).get("usage").isNull());
        }
        for(String state:List.of("failed","unknown")){var proof=verify(command(state,null),input(Stage.AD_HOC_AI));assertNull(proof.result());assertEquals(state,proof.externalState());}
    }
    @Test void ClientCannotRebindRootAttemptOrOriginalInput(){
        var good=command("succeeded",signed(payload(Stage.AD_HOC_AI),HASH));assertThrows(IllegalArgumentException.class,()->OwnerExternalResolutionEvidence.verify(OWNER,"fictional-other-root",good,input(Stage.AD_HOC_AI)));
        assertThrows(IllegalArgumentException.class,()->verify(command("fictional-other-attempt",35,"unknown","Fictional reason",List.of("fictional-evidence"),null,null),input(Stage.AD_HOC_AI)));
        assertThrows(IllegalArgumentException.class,()->verify(command("succeeded",signed(payload(Stage.AD_HOC_AI),"b".repeat(64))),input(Stage.AD_HOC_AI)));
    }
    @Test void NativeDigestAloneCannotAuthorizeWrongCitationCandidatePeriodOrKind(){
        for(Stage stage:List.of(Stage.NEWS_DEDUP,Stage.NEWS_VALUE,Stage.AGENT_ANALYSIS,Stage.DAILY_REPORT)){
            String wrong=switch(stage){case NEWS_DEDUP->payload(stage).replace("fictional-entity","fictional-outside");case NEWS_VALUE->payload(stage).replace("fictional-item","fictional-outside");case AGENT_ANALYSIS->payload(stage).replace("fictional-citation","fictional-outside");default->payload(stage).replace("2026-09-30T16:00:00.000000Z","2026-10-01T16:00:00.000000Z");};
            assertThrows(IllegalArgumentException.class,()->verify(command("succeeded",signed(wrong,HASH)),input(stage)));
        }
        assertThrows(IllegalArgumentException.class,()->verify(command("succeeded",signed(payload(Stage.AD_HOC_AI),HASH)),input(Stage.DAILY_REPORT)));
        var good=signed(payload(Stage.AD_HOC_AI),HASH);assertThrows(IllegalArgumentException.class,()->verify(command("succeeded",new ValidatedResult("1",HASH,good.payload(),"0".repeat(64))),input(Stage.AD_HOC_AI)));
    }
    @Test void StateEvidenceUsageAndUnicodeRemainClosed(){
        var original=input(Stage.AD_HOC_AI);var result=signed(payload(Stage.AD_HOC_AI),HASH);
        for(String state:List.of("unknown","failed"))assertThrows(IllegalArgumentException.class,()->verify(command(state,result),original));
        assertThrows(IllegalArgumentException.class,()->verify(command("succeeded",null),original));assertThrows(IllegalArgumentException.class,()->verify(command(null,null),original));
        for(List<String> refs:List.of(List.<String>of(),List.of("fictional-one","fictional-one"),Collections.nCopies(101,"fictional-many")))assertThrows(IllegalArgumentException.class,()->verify(command(ATTEMPT,35,"unknown","Fictional reason",refs,null,null),original));
        for(String reason:new String[]{" ","\ud800","x".repeat(2049)})assertThrows(IllegalArgumentException.class,()->verify(command(ATTEMPT,35,"unknown",reason,List.of("fictional-evidence"),null,null),original));
        for(Usage u:new Usage[]{new Usage(-1L,null,null),new Usage(null,CanonicalJson.MAX_SAFE_INTEGER+1,null),new Usage(null,null,"1e9"),new Usage(null,null,"01")})assertThrows(IllegalArgumentException.class,()->verify(command(ATTEMPT,35,"unknown","Fictional reason",List.of("fictional-evidence"),null,u),original));
        for(long revision:new long[]{-1,CanonicalJson.MAX_SAFE_INTEGER})assertThrows(IllegalArgumentException.class,()->verify(command(ATTEMPT,revision,"unknown","Fictional reason",List.of("fictional-evidence"),null,null),original));
        var accepted=verify(command(ATTEMPT,35,"unknown","😀".repeat(2048),List.of("fictional-evidence"),null,new Usage(null,2L,"-0.1")),original);assertTrue(CanonicalJson.parse(accepted.canonical()).get("usage").get("input_tokens").isNull());
    }
    @Test void OwnershipAndEveryCommandMeaningBindTheFrozenHash(){
        var original=input(Stage.AD_HOC_AI);var good=command("unknown",null);
        assertThrows(IdentityService.Rejected.class,()->OwnerExternalResolutionEvidence.verify(null,ROOT,good,original));assertThrows(IdentityService.Rejected.class,()->OwnerExternalResolutionEvidence.verify(new Actor("fictional-user","member"),ROOT,good,original));
        var baseline=verify(good,original);assertEquals(baseline.actionHash(),verify(good,original).actionHash());
        for(JobAction changed:List.of(command("failed",null),command(ATTEMPT,36,"unknown","Fictional verification",List.of("fictional-evidence"),null,null),command(ATTEMPT,35,"unknown","Fictional changed reason",List.of("fictional-evidence"),null,null),command(ATTEMPT,35,"unknown","Fictional verification",List.of("fictional-other-evidence"),null,null),command(ATTEMPT,35,"unknown","Fictional verification",List.of("fictional-evidence"),null,new Usage(0L,null,null))))assertNotEquals(baseline.actionHash(),verify(changed,original).actionHash());
        assertNotEquals(baseline.actionHash(),OwnerExternalResolutionEvidence.verify(new Actor("fictional-other-owner","owner"),ROOT,good,original).actionHash());
    }
    @Test void MutatingClientJsonAfterVerificationCannotChangePermanentMaterial(){
        var client=signed(payload(Stage.AD_HOC_AI),HASH);var proof=verify(command("succeeded",client),input(Stage.AD_HOC_AI));String original=proof.canonical();String hash=proof.actionHash();((ObjectNode)client.payload()).put("title","Fictional changed after verification");assertEquals(original,proof.canonical());assertEquals(hash,proof.actionHash());assertTrue(proof.result().payloadJson().contains("Fictional answer"));assertEquals("OwnerExternalResolutionEvidence[redacted]",proof.toString());
    }
}
