package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdHocResultHandlerTest {
    final OperationalPersistenceFixture f=new OperationalPersistenceFixture();
    String snapshot(){var root=JsonMapper.shared().createObjectNode();root.set(FrozenApplicationContext.FIELD,CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AD_HOC_AI)));return root.toString();}
    Input input(){var root=CanonicalJson.parse(snapshot());return FrozenApplicationContext.fromJson(root.get(FrozenApplicationContext.FIELD)).input("fictional-root","fictional-attempt",CanonicalJson.hash(CanonicalJson.Domain.INPUT,root),1);}
    Validated result(){var payload=CanonicalJson.parse("{\"kind\":\"ad_hoc\",\"title\":\"Fictional answer\",\"sections\":[],\"citation_ids\":[]}");var envelope=JsonMapper.shared().createObjectNode();envelope.put("schema_version","1");envelope.put("input_hash",input().inputHash());envelope.set("payload",payload);envelope.put("result_hash",hash(envelope));return ResultApplicationService.validate(input(),envelope.toString());}
    static String hash(tools.jackson.databind.JsonNode core){try{var sha=java.security.MessageDigest.getInstance("SHA-256");sha.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));sha.update(CanonicalJson.bytes(core));return java.util.HexFormat.of().formatHex(sha.digest());}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    Current current(){return new Current(1,input().inputHash(),1,null,true,false,null,null,"fictional-attempt",true,"pending");}
    Applied apply(AdHocResultHandler handler,Validated result){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-apply","fictional-root",null),key->null,a->handler.apply(input(),result,current(),false));}
    @Test void validResultHasNoPublicContentSideEffect(){var handler=new AdHocResultHandler((owner,hash)->snapshot());assertEquals(new Applied(null,null),apply(handler,result()));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));}
    @Test void forgedResultSnapshotAndMissingTransactionFail(){var handler=new AdHocResultHandler((owner,hash)->snapshot());assertThrows(RuntimeException.class,()->handler.apply(input(),result(),current(),false));var original=result();assertThrows(RuntimeException.class,()->apply(handler,new Validated(original.kind(),original.payloadJson(),"a".repeat(64),original.inputHash())));assertThrows(RuntimeException.class,()->apply(new AdHocResultHandler((owner,hash)->"{}"),original));}
}
