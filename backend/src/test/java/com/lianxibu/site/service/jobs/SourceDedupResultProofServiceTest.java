package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupResultProofServiceTest {
 GenerationJdbcStoreTest f;SourceDedupFragmentProofTest pure;com.lianxibu.site.domain.sources.MemberUniverse.Universe original;SourcePartitionPlanner.Plan plan;SourcePartitionPlanner.Chunk chunk;
 FrozenSourceStagePlanService archives;FrozenSourceUniverseService universes;SourceDedupResultProofService service;int actions;
 GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
 com.lianxibu.site.domain.sources.MemberUniverse.Universe universe(){return FrozenSourceUniverseTest.universe(5,false);}
 SourcePartitionPlanner.Plan plan(com.lianxibu.site.domain.sources.MemberUniverse.Universe universe){return pure.plan(universe);}
 @BeforeEach void setup(){
  f=fixture();f.fixture();boolean mysql=!(f.source instanceof org.h2.jdbcx.JdbcDataSource);String type=mysql?"JSON":"CLOB";
  if(!mysql)f.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");
  f.jdbc.execute("ALTER TABLE jobs ADD result_hash BINARY(32)");f.jdbc.execute("ALTER TABLE jobs ADD result_json "+type);f.jdbc.execute("ALTER TABLE jobs ADD application_state VARCHAR(32)");
  f.jdbc.execute(mysql?"ALTER TABLE jobs MODIFY applied_at TIMESTAMP(6) NULL":"ALTER TABLE jobs ALTER COLUMN applied_at TIMESTAMP(6)");
  pure=new SourceDedupFragmentProofTest();original=universe();plan=plan(original);if(plan.scope()==null)plan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),plan.decisions(),plan.chunks(),FrozenSourceStagePlanTest.scope());chunk=plan.chunks().isEmpty()?null:plan.chunks().getFirst();
  var writerUniverses=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->original,f.clock);
  tx(()->{var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent(plan.reference().ownerId(),plan.logicalKey(),plan.generation(),"news_dedup",plan.scope().businessDate()),f.clock.instant());
   assertEquals(plan.reference(),writerUniverses.freeze(plan.reference().ownerId(),original.runId()));new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),writerUniverses,f.clock,"fictional-owner").freeze(plan);
   for(var c:plan.chunks()){records.createRoot(new JdbcJobRecords.Root(c.rootId(),plan.reference().ownerId(),plan.logicalKey(),plan.generation(),c.stagePart(),"news_dedup",1),f.clock.instant());new JdbcFrozenInputs(f.source).save(c.rootId(),c.snapshotJson(),c.inputHash(),f.clock.instant());}return true;});
  universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->{throw new AssertionError("must not read current source");},f.clock);
  archives=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");service=provider();
 }
 SourceDedupResultProofService provider(){return new SourceDedupResultProofService(new JdbcSourceDedupResults(f.source),archives,universes);}
 <T>T tx(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-fragment-"+actions++,"fictional-owner",null),k->null,a->work.get());}
 <T>T readonly(java.util.function.Supplier<T> work){var t=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source));t.setReadOnly(true);return t.execute(s->work.get());}
 String attempt(){return JdbcFrozenInputs.stableId("attempt",chunk.rootId()+":1");}
 String part(){return JdbcFrozenInputs.stableId("retained-result",chunk.rootId()+":"+ResultApplicationService.validate(pure.input(chunk),pure.envelope(chunk)).resultHash());}
 String receipt(){return JdbcFrozenInputs.stableId("member-stage","member-stage:"+chunk.rootId());}
 void complete(){tx(()->{
  var records=new JdbcJobRecords(f.source);records.prepareAttempt(new JdbcJobRecords.Attempt(attempt(),chunk.rootId(),1,chunk.requestJson(),chunk.rootId(),chunk.inputHash()),f.clock.instant());
  f.jdbc.update("UPDATE jobs SET state='running' WHERE id=?",attempt());f.jdbc.update("UPDATE jobs SET state='running',call_count=1 WHERE id=?",chunk.rootId());
  var state=new JdbcResultState(f.source);var result=ResultApplicationService.validate(pure.input(chunk),pure.envelope(chunk));state.retain(state.root(chunk.rootId()),attempt(),result.resultHash(),pure.envelope(chunk),f.clock.instant());
  new JdbcMemberStageResults(f.source).save(chunk.rootId(),pure.handoff(plan,original,chunk),f.clock.instant());
  f.jdbc.update("UPDATE jobs SET state='succeeded',application_state='APPLIED',applied_at=? WHERE id=?",Timestamp.from(f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),chunk.rootId());return true;
 });}
 SourceDedupFragmentProof read(){return readonly(()->service.read(chunk.rootId()));}
 @Test void actualOriginalArchivesRetainedResultAndHandoffReadWithoutCurrentTablesOrNewWrites(){
  assertNull(read());complete();int count=f.count("1=1");var proof=read();assertEquals(chunk.rootId(),proof.rootId());assertEquals(3,proof.members().size());assertEquals(3,proof.members().getFirst().revision());
  tx(()->new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",plan.logicalKey()+":2"),plan.logicalKey(),2,"news_dedup",plan.scope().businessDate()),f.clock.instant()));
  var replay=readonly(()->provider().read(chunk.rootId()));assertEquals(proof.members(),replay.members());assertEquals(proof.result(),replay.result());assertEquals(count+1,f.count("1=1"));assertEquals(1,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void onlyReadonlyTransactionsAndExactRawRootIdentityCanReadAppliedProof(){
  complete();assertThrows(RuntimeException.class,()->service.read(chunk.rootId()));assertThrows(RuntimeException.class,()->tx(()->service.read(chunk.rootId())));
  assertThrows(RuntimeException.class,()->readonly(()->service.read(chunk.rootId().toUpperCase(Locale.ROOT))));assertThrows(RuntimeException.class,()->readonly(()->service.read("fictional-missing")));assertNotNull(read());
 }
 @Test void appliedMarkersOriginalRootIdentityBudgetAndHashesMustAllAgree(){
  complete();String root=chunk.rootId();Object[][] modes={{"state","running","succeeded"},{"application_state","INVALID_EVIDENCE","APPLIED"},{"applied_at",null,Timestamp.from(f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS))},{"intent_id","fictional-other",plan.reference().ownerId()},{"logical_key_hash",new byte[32],JdbcGenerationLineage.hash(plan.logicalKey())},{"input_hash",new byte[32],HexFormat.of().parseHex(chunk.inputHash())},{"generation",2L,1L},{"stage_part",1L,0L},{"task_type","news_value","news_dedup"},{"call_count",0,1}};
  for(var m:modes){f.jdbc.update("UPDATE jobs SET "+m[0]+"=? WHERE id=?",m[1],root);assertThrows(RuntimeException.class,this::read,m[0].toString());f.jdbc.update("UPDATE jobs SET "+m[0]+"=? WHERE id=?",m[2],root);assertNotNull(read());}
  f.jdbc.update("UPDATE jobs SET result_json=? WHERE id=?","{\"schema_version\":\"wrong\"}",root);assertThrows(RuntimeException.class,this::read);
 }
 @Test void missingOrRehashedRetainedEnvelopeAndPermanentHandoffCannotBeReconstructed(){
  complete();f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],part());assertThrows(RuntimeException.class,this::read);
  f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(pure.envelope(chunk)))),part());assertNotNull(read());
  f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],receipt());assertThrows(RuntimeException.class,this::read);
  var h=pure.handoff(plan,pure.universe,chunk);((ObjectNode)h.get("members").get(0)).put("runId","fictional-other-run");String raw=JdbcFrozenInputs.canonical(h);
  f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),receipt());assertThrows(RuntimeException.class,this::read);
  f.jdbc.update("DELETE FROM jobs WHERE id=?",receipt());assertThrows(RuntimeException.class,this::read);assertEquals(1,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void sourceArchiveDamageAndBoundedBodiesFailClosedWithoutGrantingPendingOrphans(){
  tx(()->{new JdbcMemberStageResults(f.source).save(chunk.rootId(),pure.handoff(plan,pure.universe,chunk),f.clock.instant());return true;});assertThrows(RuntimeException.class,this::read);f.jdbc.update("DELETE FROM jobs WHERE id=?",receipt());complete();
  f.jdbc.update("UPDATE jobs SET payload_json=? WHERE id=?","{\"fictional\":\""+"F".repeat(524289)+"\"}",part());assertThrows(RuntimeException.class,this::read);
  f.jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE id=?",pure.envelope(chunk),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(pure.envelope(chunk)))),part());assertNotNull(read());
  f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",plan.reference().ownerId(),JdbcFrozenSourcePlans.stamp(plan.reference().ownerId()));assertThrows(RuntimeException.class,this::read);
 }
 @Test void permanentRowsRequireTheirOriginalOwnersKindsRolesAndBoundedPayloads(){
  complete();Object[][] parts={{"owner_job_id","fictional-other",chunk.rootId()},{"purpose","input","result"},{"stamp_id","fictional-other",ResultApplicationService.validate(pure.input(chunk),pure.envelope(chunk)).resultHash()}};
  for(var m:parts){f.jdbc.update("UPDATE jobs SET "+m[0]+"=? WHERE id=?",m[1],part());assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE jobs SET "+m[0]+"=? WHERE id=?",m[2],part());assertNotNull(read());}
  Object[][] receipts={{"operator_id","fictional-other","analysis-worker"},{"reason","Fictional other reason","Source member fragment applied"},{"target_type","entity_decision","source_member_stage"}};
  for(var m:receipts){f.jdbc.update("UPDATE jobs SET "+m[0]+"=? WHERE id=?",m[1],receipt());assertThrows(RuntimeException.class,this::read);f.jdbc.update("UPDATE jobs SET "+m[0]+"=? WHERE id=?",m[2],receipt());assertNotNull(read());}
  String oversized="{\"fictional\":\""+"F".repeat(524289)+"\"}";f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",oversized,JdbcGenerationLineage.hash(oversized),receipt());assertThrows(RuntimeException.class,this::read);
 }
}
