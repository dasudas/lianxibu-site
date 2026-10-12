package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.JdbcSourceMemberStages;
import com.lianxibu.site.service.sources.SourceMemberStageService;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class MemberResultDownstreamTest {
 GenerationJdbcStoreTest f;ObjectNode snapshot,payload;ResultApplicationService.Input input;ResultApplicationService.Validated result;MemberResultDownstream downstream;
 GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){f=fixture();f.fixture();snapshot=FrozenMemberBindingsTest.snapshot();input=FrozenMemberBindingsTest.input(snapshot);var j=f.jdbc;
  for(String t:List.of("source_run_items","source_runs"))j.execute("DROP TABLE IF EXISTS "+t);
  j.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,activation_stage VARCHAR(16),delivery_state VARCHAR(32),job_refs_json "+jsonType()+",revision BIGINT)");
  j.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),source_item_id TEXT,category VARCHAR(16),entity_id VARCHAR(64),state VARCHAR(32),revision BIGINT,terminal_state VARCHAR(16),effective_valid INT,projection_decision VARCHAR(16),target_business_date DATE,analysis_job_id VARCHAR(64),applied_at TIMESTAMP(6),part_no BIGINT)");
  j.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','daily',?,1,'production','complete','[]',0)",java.sql.Date.valueOf("2026-01-01"));
  j.update("INSERT INTO source_run_items VALUES('fictional-item','fictional-run','fictional-source','fictional-original','news_tech','fictional-entity','ready',3,'accepted',1,'initialized',NULL,NULL,NULL,7)");
  payload=JsonMapper.shared().createObjectNode();payload.put("kind","dedup");payload.put("summary","Fictional summary");var item=payload.putArray("items").addObject();item.put("item_id","fictional-item");item.put("category","news_tech");item.put("decision","new");item.putNull("candidate_entity_id");item.put("reason","Fictional evidence");item.put("confidence","1");item.put("suggested_title","Fictional title");item.putNull("suggested_summary");item.put("reanalyze_recommended",false);
  downstream=new MemberResultDownstream(new JdbcFrozenInputs(f.source),new JdbcMemberStageResults(f.source),new SourceMemberStageService(new JdbcSourceMemberStages(f.source),f.clock),f.clock);
 }
 String jsonType(){return "CLOB";}
 void freeze(){input=FrozenMemberBindingsTest.input(snapshot);result=result(payload);f.writes.execute(action("fictional-prepare"),k->null,a->{var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent("fictional-intent","fictional-member-stage",input.generation(),input.stage().name().toLowerCase(),java.time.LocalDate.parse("2026-01-01")),f.clock.instant());records.createRoot(new JdbcJobRecords.Root(input.rootId(),"fictional-intent","fictional-member-stage",input.generation(),2,input.stage().name().toLowerCase(),input.stage().priority()),f.clock.instant());new JdbcFrozenInputs(f.source).save(input.rootId(),snapshot.toString(),input.inputHash(),f.clock.instant());return true;});}
 ResultApplicationService.Validated result(ObjectNode value){var envelope=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",input.inputHash());envelope.set("payload",value);try{var sha=java.security.MessageDigest.getInstance("SHA-256");sha.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));sha.update(CanonicalJson.bytes(envelope));envelope.put("result_hash",HexFormat.of().formatHex(sha.digest()));return ResultApplicationService.validate(input,envelope.toString());}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
 SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,input.rootId(),input.inputHash());}
 void dedup(){f.writes.execute(action("fictional-dedup"),k->null,a->{downstream.afterApplied(input,result,List.of(new Decision("fictional-item","fictional-entity","ready",true,1L)),Set.of());return true;});}
 int receipts(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='source_member_stage'",Integer.class);}
 @Test void dedupReceiptReplaysWithoutTouchingLaterMember(){freeze();dedup();assertEquals(1,receipts());assertEquals(4,f.jdbc.queryForObject("SELECT revision FROM source_run_items",Integer.class));f.jdbc.update("UPDATE source_run_items SET revision=20,analysis_job_id='fictional-later'");dedup();assertEquals(20,f.jdbc.queryForObject("SELECT revision FROM source_run_items",Integer.class));assertEquals("fictional-later",f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items",String.class));assertEquals(7,f.jdbc.queryForObject("SELECT part_no FROM source_run_items",Integer.class));}
 @Test void originalIdentityTamperAndDifferentResultCannotReuseReceipt(){freeze();dedup();payload.put("summary","Fictional changed summary");result=result(payload);assertThrows(RuntimeException.class,this::dedup);f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_member_stage'",new byte[32]);assertThrows(RuntimeException.class,this::dedup);assertEquals(1,receipts());}
 @Test void staleMemberAndReceiptFailureRollBackAllPointers(){freeze();f.jdbc.update("UPDATE source_run_items SET revision=9");assertThrows(RuntimeException.class,this::dedup);f.jdbc.update("UPDATE source_run_items SET revision=3");f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_batch_receipt_failure CHECK(target_type IS NULL OR target_type<>'source_member_stage')");assertThrows(RuntimeException.class,this::dedup);assertEquals(0,receipts());assertNull(f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items",String.class));assertEquals(0,f.jdbc.queryForObject("SELECT revision FROM source_runs",Integer.class));}
 @Test void valueCompletionBindsSelectedItemsAndPostWriterRevision(){
  ((ObjectNode)snapshot.get(FrozenApplicationContext.FIELD)).put("stage","news_value");payload.removeAll();payload.put("kind","value");payload.put("summary","Fictional value summary");payload.putArray("selected_item_ids");var item=payload.putArray("items").addObject();item.put("item_id","fictional-item");item.put("category","news_tech");item.put("high_value",false);item.put("reason","Fictional reason");freeze();
  f.writes.execute(action("fictional-value"),k->null,a->{downstream.afterApplied(input,result,List.of(new Value("fictional-item",2,"news_tech",false,false,"Fictional reason")));return true;});assertEquals(1,receipts());assertEquals(4,f.jdbc.queryForObject("SELECT revision FROM source_run_items",Integer.class));
 }
}
