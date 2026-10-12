package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FrozenDependencyIndexServiceTest {
 GenerationJdbcStoreTest fixture; Frozen frozen; FrozenDependencyIndexService service;
 GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){
  fixture=fixture();fixture.fixture();
  fixture.writes.execute(new SharedWriteExecutor.ActionContext("fictional-seed","fictional-upstream",fixture.HASH),k->null,a->{
   var records=new JdbcJobRecords(fixture.source);records.ensureIntent(new JdbcJobRecords.Intent("fictional-upstream-intent","fictional-upstream-key",1,"news_dedup",LocalDate.of(2026,1,1)),fixture.clock.instant());records.createRoot(new JdbcJobRecords.Root("fictional-upstream","fictional-upstream-intent","fictional-upstream-key",1,0,"news_dedup",1),fixture.clock.instant());return true;
  });
  var c=fixture.command(false);var command=new Prepare(c.logicalKey(),1,c.stage(),c.businessDate(),null,List.of(new Dependency("fictional-upstream",Stage.NEWS_DEDUP,"succeeded",Instant.parse("2026-01-01T20:00:00Z"))),c.evidence(),List.of("fictional-upstream","fictional-missing"),true,c.settingsHash());
  frozen=fixture.flow(new GenerationJdbcStore(fixture.source,fixture.clock,"fictional-owner",fixture.OWNER,command)).prepare(command,fixture.action());
  service=new FrozenDependencyIndexService(new JdbcFrozenInputs(fixture.source),new JdbcFrozenDependencies(fixture.source));
 }
 @Test void completeIndexSurvivesRestartAndRetainsOriginalStates(){
  var result=service.read(frozen.ownerId(),frozen.inputHash());assertEquals(List.of("fictional-missing","fictional-upstream"),result.expectedIds());assertEquals("succeeded",result.dependencies().getFirst().state());
  fixture.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id='fictional-upstream'");assertEquals(result,new FrozenDependencyIndexService(new JdbcFrozenInputs(fixture.source),new JdbcFrozenDependencies(fixture.source)).read(frozen.ownerId(),frozen.inputHash()));
 }
 @Test void deletedIndexCannotBecomeEmptyDependencySet(){fixture.jdbc.update("DELETE FROM jobs WHERE purpose='dependency'");assertThrows(RuntimeException.class,()->service.read(frozen.ownerId(),frozen.inputHash()));}
 @Test void locallyRehashedDifferentStateCannotReplaceFrozenFact(){
  var node=CanonicalJson.parse(fixture.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE purpose='dependency'",String.class));((tools.jackson.databind.node.ObjectNode)node).put("state","failed");fixture.jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE purpose='dependency'",JdbcFrozenInputs.canonical(node),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,node)));assertThrows(RuntimeException.class,()->service.read(frozen.ownerId(),frozen.inputHash()));
 }
 @Test void exactIdentityOwnerStampAndPartSequenceAreRequired(){
  for(String assignment:List.of("dependency_id='FICTIONAL-upstream'","owner_job_id='FICTIONAL-root'","stamp_id='fictional-other'","part_no=2")){
   fixture.jdbc.update("UPDATE jobs SET "+assignment+" WHERE purpose='dependency'");assertThrows(RuntimeException.class,()->service.read(frozen.ownerId(),frozen.inputHash()));fixture.jdbc.update("UPDATE jobs SET dependency_id='fictional-upstream',owner_job_id='fictional-root',stamp_id='input',part_no=0 WHERE purpose='dependency'");
  }
 }
 @Test void surplusAndWrongUpstreamTypeCannotPass(){
  fixture.writes.execute(new SharedWriteExecutor.ActionContext("fictional-extra","fictional-root",fixture.HASH),k->null,a->{var n=CanonicalJson.parse(fixture.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE purpose='dependency'",String.class));new JdbcJobRecords(fixture.source).putPart(new JdbcJobRecords.Part("fictional-extra",fixture.OWNER,"dependency","input",1,JdbcFrozenInputs.canonical(n),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,n)),fixture.clock.instant());return true;});assertThrows(RuntimeException.class,()->service.read(frozen.ownerId(),frozen.inputHash()));fixture.jdbc.update("DELETE FROM jobs WHERE id='fictional-extra'");fixture.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id='fictional-upstream'");assertThrows(RuntimeException.class,()->service.read(frozen.ownerId(),frozen.inputHash()));
 }
}
