package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.JdbcGeneratedContents;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.service.content.GeneratedContentService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Inherits permanent result retention/replay/CAS/rollback tests with the production handler. */
class ContentResultHandlerTest extends ResultJdbcStoreTest {
    @Override ResultApplicationService applicationService(Store applicationStore) {
        var handler=new ContentResultHandler(new GeneratedContentService(new JdbcGeneratedContents(source),clock),()->store.context(),(input,context,result,applied)->{
            handlerCalls++;new JdbcJobRecords(source).ensureIntent(new JdbcJobRecords.Intent("fictional-downstream","fictional-downstream-key",1,"daily_report",LocalDate.of(2026,1,1)),clock.instant());
            if(failDownstream)throw new IllegalStateException("fictional downstream failure");
        });
        Handler unused=(a,b,c,d)->{throw new AssertionError("unassembled branch");};
        return new ResultApplicationService(writes,applicationStore,Map.of("report",handler,"analysis",handler,"dedup",unused,"value",unused,"ad_hoc",unused));
    }
    @Test void interpretationKindsUseExactFrozenTargetsAndOutsideTransactionIsRejected() {
        for(var stage:List.of(GenerationFlowService.Stage.AGENT_ANALYSIS,GenerationFlowService.Stage.MODEL_ANALYSIS,GenerationFlowService.Stage.SKILL_ANALYSIS,GenerationFlowService.Stage.WORKFLOW_ANALYSIS)) {
            var json=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(stage));
            ((tools.jackson.databind.node.ObjectNode)json.get("target_content")).putNull("base_revision");var context=FrozenApplicationContext.fromJson(json);
            var input=context.input(ROOT,identity.attemptId(),identity.inputHash(),1);
            var body=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();body.put("title","Fictional analysis");body.putArray("sections");body.putArray("citation_ids");body.put("category",context.contentKind());body.put("entity_id",input.targetEntityId());
            var payload=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();payload.put("kind","analysis");payload.set("body",body);
            var result=new Validated("analysis",payload.toString(),"a".repeat(64),input.inputHash());
            var current=new Current(1,input.inputHash(),1,null,true,false,null,null,input.attemptId(),true,null);
            var handler=new ContentResultHandler(new GeneratedContentService(new JdbcGeneratedContents(source),clock),()->context,(i,c,r,a)->handlerCalls++);
            assertThrows(IllegalStateException.class,()->handler.apply(input,result,current,false));
            var written=writes.execute(action("fictional-handler-"+stage),key->null,c->handler.apply(input,result,current,false));
            assertEquals(context.contentId(),written.contentId());assertEquals(1,written.contentRevision());
            int before=handlerCalls;
            writes.execute(action("fictional-handler-conflict-"+stage),key->null,c->{assertNull(handler.apply(input,result,current,false));return true;});
            assertEquals(before,handlerCalls);
            var wrong=new Validated("report",payload.toString(),"a".repeat(64),input.inputHash());
            assertThrows(Rejected.class,()->writes.execute(action("fictional-handler-wrong-"+stage),key->null,c->handler.apply(input,wrong,current,false)));
        }
    }
}
