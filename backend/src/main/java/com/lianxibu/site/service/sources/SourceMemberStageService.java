package com.lianxibu.site.service.sources;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.sources.JdbcSourceMemberStages;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Records applied batch locators without confusing a fragment with an entire source result. */
public final class SourceMemberStageService {
 public record Expected(String itemId,long revision,String category,String entityId,String state) { }
 public record Ready(String itemId,long revision,String category) { }
 public record Fact(String itemId,String runId,String sourceId,String sourceItemId,String category,String entityId,String state,long revision,LocalDate businessDate,LocalDate targetBusinessDate,long generation,String activationStage) { }
 private final JdbcSourceMemberStages store;private final Clock clock;
 public SourceMemberStageService(JdbcSourceMemberStages store,Clock clock){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
 public List<Fact> completeReady(String root,List<Ready> expected){
  if(expected==null||expected.isEmpty()||expected.size()>100)throw invalid();
  var batch=new ArrayList<Expected>();for(var value:expected){if(value==null)throw invalid();var row=store.member(value.itemId());if(row==null||!"ready".equals(row.state()))throw invalid();batch.add(new Expected(value.itemId(),value.revision(),value.category(),row.entityId(),"ready"));}
  return complete(root,batch);
 }
 public List<Fact> complete(String root,List<Expected> expected){
  if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("member stage requires result transaction");
  WireValues.id(root,false);if(expected==null||expected.isEmpty()||expected.size()>100)throw invalid();
  var sorted=new TreeMap<String,Expected>();
  for(var e:expected){if(e==null)throw invalid();WireValues.id(e.itemId(),false);if(e.entityId()!=null)WireValues.id(e.entityId(),false);if(e.revision()<0||e.revision()>=CanonicalJson.MAX_SAFE_INTEGER||!Set.of("news_tech","agent","skill","workflow","model").contains(e.category())||!Set.of("ready","needs_review").contains(e.state())||("needs_review".equals(e.state())!=(e.entityId()==null))||sorted.putIfAbsent(e.itemId(),e)!=null)throw invalid();}
  var members=new ArrayList<JdbcSourceMemberStages.Member>();var runIds=new TreeSet<String>();
  for(var e:sorted.values()){
   var row=store.member(e.itemId());if(row==null||!e.itemId().equals(row.id())||row.revision()!=e.revision()||!Objects.equals(row.category(),e.category())||!Objects.equals(row.entityId(),e.entityId())||!Objects.equals(row.state(),e.state())||!"accepted".equals(row.terminalState())||!row.valid()||(row.projection()==null?"news_tech".equals(row.category()):!Set.of("initialized","unchanged","promoted","historical").contains(row.projection())))throw invalid();
   WireValues.id(row.runId(),false);WireValues.id(row.sourceId(),true);if(row.sourceItemId()==null||row.sourceItemId().isEmpty()||row.sourceItemId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw invalid();members.add(row);runIds.add(row.runId());
  }
  var runs=new TreeMap<String,JdbcSourceMemberStages.Run>();var links=new TreeMap<String,String>();
  for(String id:runIds){
   var run=store.run(id);if(run==null||!id.equals(run.id())||run.businessDate()==null||run.generation()<1||run.generation()>CanonicalJson.MAX_SAFE_INTEGER||run.revision()<0||run.revision()>=CanonicalJson.MAX_SAFE_INTEGER||!Set.of("complete","partial").contains(run.deliveryState())||!Set.of("daily","historical_backfill","production_qualification","trial","correction").contains(run.kind())||!Set.of("production","trial").contains(run.activationStage())||run.jobRefs()==null||run.jobRefs().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw invalid();
   var refs=CanonicalJson.parse(run.jobRefs());if(!refs.isArray())throw invalid();var seen=new HashSet<String>();for(var ref:refs)if(!seen.add(WireValues.id(ref,false)))throw invalid();
   if(!seen.contains(root)){var next=((tools.jackson.databind.node.ArrayNode)refs).deepCopy();next.add(root);byte[] encoded=CanonicalJson.bytes(next);if(encoded.length>65536)throw invalid();links.put(id,new String(encoded,java.nio.charset.StandardCharsets.UTF_8));}runs.put(id,run);
  }
  var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var facts=new ArrayList<Fact>();
  for(var row:members){var run=runs.get(row.runId());if(!run.sourceId().equals(row.sourceId()))throw invalid();facts.add(new Fact(row.id(),run.id(),row.sourceId(),row.sourceItemId(),row.category(),row.entityId(),row.state(),row.revision()+1,run.businessDate(),row.targetBusinessDate(),run.generation(),run.activationStage()));}
  for(var row:members)store.complete(row,root,now);
  links.forEach((id,refs)->store.link(runs.get(id),refs));return List.copyOf(facts);
 }
 private static IllegalStateException invalid(){return new IllegalStateException("source member stage facts conflict");}
}
