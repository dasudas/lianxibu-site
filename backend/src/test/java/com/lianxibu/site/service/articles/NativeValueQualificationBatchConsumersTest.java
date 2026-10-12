package com.lianxibu.site.service.articles;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.articles.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Full current native facts, exact query counts, late changes and cross-buffer corruption. */
class NativeValueQualificationBatchConsumersTest extends NewsValueEligibilityServiceTest {
 MemberUniverse.Universe many(int count){
  var members=new ArrayList<MemberUniverse.Member>();for(int n=0;n<count;n++){
   String suffix=String.format(java.util.Locale.ROOT,"%03d",n),id="fictional-batch-"+suffix,key="fictional-batch-key-"+suffix;
   f.jdbc.update("INSERT INTO source_run_items SELECT ?,run_id,source_id,?,entity_id,target_business_date,payload_hash,revision,category,created_at,terminal_state,state,projection_decision,effective_valid,evidence_json,evidence_hash FROM source_run_items WHERE id='fictional-item-0'",id,key);
   members.add(new MemberUniverse.Member(id,"fictional-batch-member-"+suffix,key,"fictional-batch-observation-"+suffix,f.HASH,null,n/100,"accepted"));
  }
  var parts=new ArrayList<MemberUniverse.Part>();for(int n=0;n<count;n+=100)parts.add(new MemberUniverse.Part(n/100,f.HASH,Math.min(100,count-n)));return new MemberUniverse.Universe(f.universe.runId(),f.universe.sourceId(),f.HASH,parts,members,count,0);
 }
 NewsValueEligibilityService counted(NativeValueQualificationBatchesTest.Counting source){return new NewsValueEligibilityService(new JdbcNewsDedupEligibility(source),new JdbcNewsValueEntities(source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)),Clock.fixed(f.cutoff,ZoneOffset.UTC));}
 @Test void TwoHundredOneNativeMembersHaveCompleteFactsWithTwentyTwoCurrentQueries(){
  var universe=many(201);var source=new NativeValueQualificationBatchesTest.Counting(f.source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var reader=counted(source);
  var selection=tx.execute(s->reader.capture(universe,f.day));assertEquals(201,selection.entries().size());assertEquals(22,source.selected);assertTrue(selection.entries().stream().allMatch(e->e.reason()==NewsValueEligibilityService.Reason.ELIGIBLE&&e.eligibleHighValue()&&e.fact().revision()==2&&e.entity().revision()==0));
  assertEquals(Boolean.TRUE,tx.execute(s->reader.matchesCurrent(universe,selection)));assertEquals(44,source.selected);
  f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1 WHERE id='fictional-new-entity'");assertEquals(Boolean.FALSE,tx.execute(s->reader.matchesCurrent(universe,selection)));assertEquals(66,source.selected);
 }
 @Test void OneHundredExactEvidenceMembersUseSevenQueriesAndKeepOriginalHashAndOrdering(){
  var universe=many(100);var ids=new TreeSet<String>();universe.members().forEach(m->ids.add(m.itemId()));var source=new NativeValueQualificationBatchesTest.Counting(f.source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var evidence=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));
  var facts=tx.execute(s->evidence.read(ids,f.cutoff));assertEquals(100,facts.size());assertEquals(7,source.selected);assertEquals(new ArrayList<>(ids),facts.stream().map(SourceMemberEvidenceService.Fact::id).toList());assertTrue(facts.stream().allMatch(row->row.revision()==2&&row.payloadJson().equals(facts.getFirst().payloadJson())));
  tx.setReadOnly(true);assertEquals(facts,tx.execute(s->evidence.readObserved(ids,f.cutoff)));assertEquals(14,source.selected);assertThrows(RuntimeException.class,()->tx.execute(s->evidence.read(ids,f.cutoff)));
 }
 @Test void EntityValuesFromAnEarlierUnrelatedBatchAreReadAgainWithinTheSameWriter(){
  var universe=many(301);for(int n=0;n<200;n++)entity("fictional-bounded-entity-"+n,f.day,0);
  for(int n=0;n<301;n++)f.jdbc.update("UPDATE source_run_items SET entity_id=? WHERE id=?","fictional-bounded-entity-"+(n==300?0:n%200),String.format(java.util.Locale.ROOT,"fictional-batch-%03d",n));
  var source=new NativeValueQualificationBatchesTest.Counting(f.source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var result=tx.execute(s->counted(source).capture(universe,f.day));
  assertEquals(301,result.entries().size());assertEquals(33,source.selected);assertTrue(result.entries().stream().allMatch(e->e.reason()==NewsValueEligibilityService.Reason.ELIGIBLE&&e.eligibleHighValue()));
  for(int n=0;n<301;n++)assertEquals("fictional-bounded-entity-"+(n==300?0:n%200),result.entries().get(n).entity().id());
 }
 @Test void LastBufferCorruptionAndMissingOriginalMemberFailWholeSelectionWithoutCapacityRelaxation(){
  var universe=many(201);var source=new NativeValueQualificationBatchesTest.Counting(f.source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var reader=counted(source);var original=tx.execute(s->reader.capture(universe,f.day));assertEquals(201,original.entries().size());
  f.jdbc.update("UPDATE source_run_items SET evidence_hash=? WHERE id='fictional-batch-200'",new byte[32]);assertThrows(RuntimeException.class,()->tx.execute(s->reader.capture(universe,f.day)));f.jdbc.update("UPDATE source_run_items SET evidence_hash=(SELECT t.evidence_hash FROM (SELECT evidence_hash FROM source_run_items WHERE id='fictional-item-0') t) WHERE id='fictional-batch-200'");
  var limited=new NewsValueEligibilityService(new JdbcNewsDedupEligibility(source),new JdbcNewsValueEntities(source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)),Clock.fixed(f.cutoff,ZoneOffset.UTC),1);assertThrows(NewsValueEligibilityService.CapacityGuarded.class,()->tx.execute(s->limited.capture(universe,f.day)));
  f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-batch-200'");assertThrows(RuntimeException.class,()->tx.execute(s->reader.capture(universe,f.day)));assertEquals(200,universe.members().stream().filter(m->!m.itemId().equals("fictional-batch-200")).count());assertEquals(412,f.jdbc.queryForObject("SELECT SUM(revision) FROM source_run_items",Integer.class));
 }
}
