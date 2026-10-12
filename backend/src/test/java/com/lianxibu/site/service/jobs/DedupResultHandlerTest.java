package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import com.lianxibu.site.service.articles.EntityDedupService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.*;
import tools.jackson.databind.json.JsonMapper;

class DedupResultHandlerTest {
 final OperationalPersistenceFixture f=new OperationalPersistenceFixture();
 ObjectNode snapshot;Input input;Validated result;boolean failDownstream;int downstreamCalls;
 DedupResultHandler handler;
 @BeforeEach void setup() throws Exception {
  var jdbc=f.jdbc;
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,business_date DATE)");
        jdbc.update("INSERT INTO source_runs VALUES('run-one',DATE '2026-09-29')");
        jdbc.update("INSERT INTO source_runs VALUES('run-two',DATE '2026-09-30')");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "source_id VARCHAR(80),source_item_id VARCHAR(128),category VARCHAR(16),"
                + "entity_id VARCHAR(64),terminal_state VARCHAR(16),state VARCHAR(32),"
                + "projection_decision VARCHAR(16),effective_valid INT,revision BIGINT,"
                + "counted_occurrence_key BINARY(32) UNIQUE,created_at TIMESTAMP,validity_revision BIGINT,"
                + "is_new INT,dedup_json CLOB)");
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,category VARCHAR(16),identity_key CLOB,identity_hash BINARY(32),"
                + "title VARCHAR(512),summary CLOB,first_recorded_at TIMESTAMP,last_seen_at TIMESTAMP,"
                + "first_seen_business_date DATE,last_occurrence_at TIMESTAMP,occurrence_count BIGINT,"
                + "repeat_count BIGINT,analysis_state VARCHAR(32),analysis_generation BIGINT,"
                + "aliases_json CLOB,legacy_flag INT,UNIQUE(category,identity_hash),"
                + "CHECK(repeat_count=CASE WHEN occurrence_count=0 THEN 0 ELSE occurrence_count-1 END))");

  snapshot=FrozenMemberBindingsTest.snapshot();
  ((ArrayNode)snapshot.get(FrozenApplicationContext.FIELD).get("item_ids")).add("fictional-item-two");
  var members=(ArrayNode)snapshot.get(FrozenMemberBindings.FIELD).get("members");
  var second=(ObjectNode)CanonicalJson.parse(CanonicalJson.bytes(members.get(0)));second.put("item_id","fictional-item-two");
  second.put("new_entity_id",FrozenMemberBindings.entityId(FrozenMemberBindingsTest.ROOT,"fictional-item-two","news_tech","fictional-canonical-identity"));members.add(second);
  input=FrozenMemberBindingsTest.input(snapshot);
  for(String id:List.of("fictional-item","fictional-item-two"))jdbc.update("INSERT INTO source_run_items VALUES(?,?,?,?,?,NULL,'accepted','pending','initialized',1,2,NULL,?,0,NULL,NULL)",id,"run-one","fictional-source",id,"news_tech",Timestamp.from(f.clock.instant()));
  var payload=JsonMapper.shared().createObjectNode();payload.put("kind","dedup");payload.put("summary","Fictional batch");var values=payload.putArray("items");
  for(String id:List.of("fictional-item","fictional-item-two")) {var v=values.addObject();v.put("item_id",id);v.put("category","news_tech");v.put("decision","new");v.putNull("candidate_entity_id");v.put("reason","Fictional evidence");v.put("confidence","1");v.put("suggested_title","虚构标题".repeat(50));v.putNull("suggested_summary");v.put("reanalyze_recommended",false);}
  var envelope=JsonMapper.shared().createObjectNode();envelope.put("schema_version","1");envelope.put("input_hash",input.inputHash());envelope.set("payload",payload);
  var sha=java.security.MessageDigest.getInstance("SHA-256");sha.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));sha.update(CanonicalJson.bytes(envelope));envelope.put("result_hash",HexFormat.of().formatHex(sha.digest()));
  result=ResultApplicationService.validate(input,envelope.toString());
  var entities=new EntityDedupService(new JdbcEntityDedup(f.source),f.writes,new EntityDecisionJdbcLog(new JdbcEntityDecisions(f.source),"fictional-worker",f.clock),f.clock);
  handler=new DedupResultHandler(entities,(root,hash)->snapshot.toString(),(i,r,d,ids)->{downstreamCalls++;assertEquals(2,d.size());assertTrue(ids.isEmpty());if(failDownstream)throw new IllegalStateException("fictional downstream failure");});
 }
 Applied apply() {return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-apply",input.rootId(),input.inputHash()),key->null,a->handler.apply(input,result,new Current(1,input.inputHash(),1,null,true,false,null,result.resultHash(),input.attemptId(),true,null),false));}
 @Test void allMembersCommitAndReplayWithoutIncreasingCounts() {
  assertEquals(new Applied(null,null),apply());assertEquals(1,count("analyzed_entities"));assertEquals(2,count("jobs"));
  assertEquals(2,f.jdbc.queryForObject("SELECT occurrence_count FROM analyzed_entities",Integer.class));
  assertEquals(new Applied(null,null),apply());assertEquals(2,count("jobs"));assertEquals(2,f.jdbc.queryForObject("SELECT occurrence_count FROM analyzed_entities",Integer.class));
 }
 @Test void staleSecondMemberRollsBackFirstMemberAndItsReceipt() {
  f.jdbc.update("UPDATE source_run_items SET revision=3 WHERE id='fictional-item-two'");assertThrows(RuntimeException.class,this::apply);assertEmpty();assertEquals(0,downstreamCalls);
 }
 @Test void downstreamFailureRollsBackEveryMember() {failDownstream=true;assertThrows(RuntimeException.class,this::apply);assertEmpty();failDownstream=false;apply();assertEquals(1,count("analyzed_entities"));}
 @Test void inputTamperingAndMissingTransactionAreRejected() {
  assertThrows(IllegalStateException.class,()->handler.apply(input,result,null,false));
  ((ObjectNode)snapshot.get(FrozenMemberBindings.FIELD).get("members").get(0)).put("expected_revision",3);assertThrows(RuntimeException.class,this::apply);assertEmpty();
 }
 long count(String table){return f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 void assertEmpty(){assertEquals(0,count("analyzed_entities"));assertEquals(0,count("jobs"));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE entity_id IS NOT NULL",Integer.class));}
}
