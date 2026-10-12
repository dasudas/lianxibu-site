package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Raw native headers and exact locked identities only; this port grants no applied or sealed proof. */
class CatalogSourceStageRootsTest {
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 CatalogSourceDedupStageProofTest fixture(String category,int count){var f=new CatalogSourceDedupStageProofTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceStageRootsTest.this.database();}};f.install(category,count);f.f.f.jdbc.update("UPDATE jobs SET business_date=?,input_cutoff_at=?,input_state='ready' WHERE record_kind='logical_task' AND intent_id=?",f.f.plan.scope().businessDate().toString(),java.sql.Timestamp.from(f.f.plan.scope().cutoff()),f.ref.ownerId());return f;}
 @Test void allFourNativeCategoriesReadEveryOriginalHeaderAndLockTheSameFullIdentity(){
  for(String category:List.of("agent","skill","workflow","model")){var f=fixture(category,203);var store=new JdbcSourceStageRoots(f.f.f.source);var headers=f.f.readonly(()->f.f.plan.chunks().stream().map(k->store.find(k.rootId())).toList());assertEquals(2,headers.size());for(int n=0;n<headers.size();n++){var row=headers.get(n);var chunk=f.f.plan.chunks().get(n);assertEquals(category+"_dedup",row.type());assertEquals(f.ref.ownerId(),row.intent());assertEquals(chunk.rootId(),row.id());assertEquals(chunk.inputHash(),row.inputHash());assertEquals(chunk.stagePart(),row.part());assertEquals(f.f.plan.scope().cutoff(),row.cutoff());assertEquals(0,row.calls());}f.f.tx(()->{store.lockOriginal(headers);return true;});assertEquals(headers,f.f.readonly(()->headers.stream().map(k->store.find(k.id())).toList()));assertEquals(0,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));}
 }
 @Test void rawUnknownTypesAliasesAndIdentityHashesNeverBecomeNativeHeaders(){
  var f=fixture("agent",5);var store=new JdbcSourceStageRoots(f.f.f.source);String id=f.f.plan.chunks().getFirst().rootId();for(String type:List.of("AGENT_DEDUP","agent_dedup ","agent_analysis","report_daily","fictional")){f.f.f.jdbc.update("UPDATE jobs SET task_type=? WHERE id=?",type,id);assertThrows(RuntimeException.class,()->f.f.readonly(()->store.find(id)));}f.f.f.jdbc.update("UPDATE jobs SET task_type='agent_dedup',record_identity_hash=? WHERE id=?",new byte[32],id);assertThrows(RuntimeException.class,()->f.f.readonly(()->store.find(id)));
 }
 @Test void nativeCategoryChangedInsideWriterCannotMatchCapturedIdentityAndWholeWriterRollsBack(){
  var f=fixture("workflow",5);var store=new JdbcSourceStageRoots(f.f.f.source);String id=f.f.plan.chunks().getFirst().rootId();var row=f.f.readonly(()->store.find(id));assertThrows(RuntimeException.class,()->f.f.tx(()->{f.f.f.jdbc.update("UPDATE jobs SET task_type='skill_dedup' WHERE id=?",id);store.lockOriginal(List.of(row));return true;}));assertEquals(row,f.f.readonly(()->store.find(id)));
 }
 @Test void transactionRoleAndOriginalHundredRootWriterCapacityRemainMandatory(){
  var f=fixture("model",5);var store=new JdbcSourceStageRoots(f.f.f.source);String id=f.f.plan.chunks().getFirst().rootId();assertThrows(RuntimeException.class,()->store.find(id));assertThrows(RuntimeException.class,()->f.f.tx(()->store.find(id)));var row=f.f.readonly(()->store.find(id));assertThrows(RuntimeException.class,()->f.f.readonly(()->{store.lockOriginal(List.of(row));return true;}));assertThrows(JdbcSourceStageRoots.CapacityRejected.class,()->f.f.tx(()->{store.lockOriginal(Collections.nCopies(101,row));return true;}));assertThrows(RuntimeException.class,()->f.f.tx(()->{store.lockOriginal(List.of(row,row));return true;}));assertEquals(row,f.f.readonly(()->store.find(id)));
 }
 @Test void originalNativeDateAndUtcMicrosecondHeadersRemainExactAcrossFourDefaultTimeZones(){
  var f=fixture("skill",5);var store=new JdbcSourceStageRoots(f.f.f.source);String id=f.f.plan.chunks().getFirst().rootId();var original=f.f.readonly(()->store.find(id));var old=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){TimeZone.setDefault(TimeZone.getTimeZone(zone));var actual=f.f.readonly(()->store.find(id));assertEquals(f.f.plan.scope().businessDate(),actual.day(),zone);assertEquals(f.f.plan.scope().cutoff(),actual.cutoff(),zone);assertEquals(original.updatedAt(),actual.updatedAt(),zone);assertEquals(original,actual,zone);}}finally{TimeZone.setDefault(old);}
 }
}
