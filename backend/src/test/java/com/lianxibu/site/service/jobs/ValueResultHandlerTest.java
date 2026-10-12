package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class ValueResultHandlerTest {
 ValueApplicationServiceTest base;ObjectNode snapshot,payload;Input input;ValueResultHandler handler;boolean fail;int downstream;
 @BeforeEach void setup() throws Exception {
  base=new ValueApplicationServiceTest();base.setup();snapshot=base.fixture.snapshot;
  ((ObjectNode)snapshot.get(FrozenApplicationContext.FIELD)).put("stage","news_value");
  snapshot.get(FrozenMemberBindings.FIELD).get("members").forEach(m->((ObjectNode)m).put("expected_revision",3));input=FrozenMemberBindingsTest.input(snapshot);
  payload=JsonMapper.shared().createObjectNode();payload.put("kind","value");payload.put("summary","Fictional value judgment");payload.putArray("selected_item_ids").add("fictional-item");
  var items=payload.putArray("items");for(String id:List.of("fictional-item","fictional-item-two")){var item=items.addObject();item.put("item_id",id);item.put("category","news_tech");item.put("high_value",id.equals("fictional-item"));item.put("reason","Fictional assessment");}
  handler=new ValueResultHandler(base.service,(r,h)->snapshot.toString(),(i,r,v)->{downstream++;assertEquals(2,v.size());if(fail)throw new IllegalStateException("fictional downstream failure");});
 }
 Validated result() throws Exception {
  var envelope=JsonMapper.shared().createObjectNode();envelope.put("schema_version","1");envelope.put("input_hash",input.inputHash());envelope.set("payload",payload);
  var sha=java.security.MessageDigest.getInstance("SHA-256");sha.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));sha.update(CanonicalJson.bytes(envelope));envelope.put("result_hash",HexFormat.of().formatHex(sha.digest()));return ResultApplicationService.validate(input,envelope.toString());
 }
 Applied apply() throws Exception {var result=result();return base.fixture.f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-value-apply",input.rootId(),input.inputHash()),key->null,a->handler.apply(input,result,new Current(1,input.inputHash(),1,null,true,false,null,result.resultHash(),input.attemptId(),true,null),false));}
 @Test void realFrozenValueBatchAppliesThenCallsDownstream() throws Exception {assertEquals(new Applied(null,null),apply());assertEquals(1,downstream);assertEquals(1,base.fixture.f.jdbc.queryForObject("SELECT high_value FROM source_run_items WHERE id='fictional-item'",Integer.class));}
 @Test void categoryMismatchCannotUseAnOtherwiseValidModelResult(){((ObjectNode)payload.get("items").get(0)).put("category","agent");assertThrows(RuntimeException.class,this::apply);base.assertEmpty();assertEquals(0,downstream);}
 @Test void downstreamFailureAndFrozenInputTamperLeaveNoValues() throws Exception {fail=true;assertThrows(IllegalStateException.class,this::apply);base.assertEmpty();fail=false;((ObjectNode)snapshot.get(FrozenMemberBindings.FIELD).get("members").get(0)).put("expected_revision",4);assertThrows(RuntimeException.class,this::apply);base.assertEmpty();}
}
