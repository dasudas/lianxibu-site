package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class CapturedSourcePlanNativeProofTest {
 SourceDedupResultProofServiceTest f;FrozenSourceStagePlanService.OriginalPlan captured;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){
  f=new SourceDedupResultProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return CapturedSourcePlanNativeProofTest.this.database();}};f.setup();
  captured=f.readonly(()->f.archives.readImmutableOriginal(f.plan.reference().ownerId()));
 }
 FrozenSourceStagePlanService.OriginalPlan write(){return f.tx(()->f.archives.verifyCapturedOriginal(captured));}
 FrozenSourceStagePlanService.OriginalPlan read(){return f.readonly(()->f.archives.verifyCapturedImmutableOriginal(captured));}
 String owner(){return f.plan.reference().ownerId();}
 String lastPart(){return f.readonly(()->new JdbcFrozenSourcePlans(f.f.source).immutableParts(owner())).getLast().id();}
 @Test void actualOriginalNativeCaptureClosesWithoutRebuildingCompleteRequests(){
  int count=f.f.count("1=1");
  try(var pure=mockStatic(FrozenSourceStagePlan.class,CALLS_REAL_METHODS)){
   pure.when(()->FrozenSourceStagePlan.decode(any(),anyList(),any())).thenThrow(new AssertionError("complete pure decoding must stay in actual capture"));
   assertSame(captured,write());assertSame(captured,read());
  }
  assertEquals(f.plan,captured.plan());assertEquals(f.original,captured.universe());assertEquals(count,f.f.count("1=1"));
 }
 @Test void privateIssuerAndActualTransactionRolesCannotBeSubstituted(){
  assertThrows(RuntimeException.class,()->f.archives.verifyCapturedOriginal(captured));assertThrows(RuntimeException.class,()->f.archives.verifyCapturedImmutableOriginal(captured));
  assertThrows(RuntimeException.class,()->f.readonly(()->f.archives.verifyCapturedOriginal(captured)));
  var other=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.f.source),f.universes,f.f.clock,"fictional-owner");
  assertThrows(RuntimeException.class,()->f.tx(()->other.verifyCapturedOriginal(captured)));assertThrows(RuntimeException.class,()->f.readonly(()->other.verifyCapturedImmutableOriginal(captured)));
  assertSame(captured,write());
 }
 @Test void completeNativeBlockIdentityStoredHashAndLastPartAreFreshEveryTime(){
  String id=lastPart();Object[][] modes={{"payload_hash",new byte[32],f.f.jdbc.queryForObject("SELECT payload_hash FROM jobs WHERE id=?",byte[].class,id)},
   {"owner_job_id","fictional-other",owner()},{"purpose","dependency","input"},{"stamp_id","fictional-other",JdbcFrozenSourcePlans.stamp(owner())},
   {"part_no",999L,f.f.jdbc.queryForObject("SELECT part_no FROM jobs WHERE id=?",Long.class,id)}};
  for(var mode:modes){f.f.jdbc.update("UPDATE jobs SET "+mode[0]+"=? WHERE id=?",mode[1],id);assertThrows(RuntimeException.class,this::write,mode[0].toString());assertThrows(RuntimeException.class,this::read,mode[0].toString());f.f.jdbc.update("UPDATE jobs SET "+mode[0]+"=? WHERE id=?",mode[2],id);assertSame(captured,write());}
  f.f.jdbc.update("DELETE FROM jobs WHERE id=?",id);assertThrows(RuntimeException.class,this::write);assertThrows(RuntimeException.class,this::read);
 }
 @Test void aChangedAndRehashedNativeBodyDoesNotBecomeTheOriginalCapture(){
  String id=lastPart();var body=(ObjectNode)CanonicalJson.parse(f.f.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE id=?",String.class,id));body.put("fictional_extra",true);
  f.f.jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE id=?",JdbcFrozenInputs.canonical(body),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body)),id);
  assertThrows(RuntimeException.class,this::write);assertThrows(RuntimeException.class,this::read);
 }
 @Test void originalUniverseAndFullReceiptCannotBeReplacedByPlanHeaderAlone(){
  var rows=f.readonly(()->new JdbcFrozenSourceUniverses(f.f.source).immutableParts(owner(),JdbcFrozenInputs.stableId("source-universe",f.original.runId())));String id=rows.getLast().id();byte[] original=rows.getLast().hash();
  f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],id);assertThrows(RuntimeException.class,this::write);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",original,id);assertSame(captured,write());
  f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-changed-actor' WHERE id=?",JdbcFrozenSourcePlans.receiptId(owner()));assertThrows(RuntimeException.class,this::write);assertThrows(RuntimeException.class,this::read);
 }
 @Test void immutableOwnerIdentityStaysExactWhileLaterStateDoesNotRewriteTheArchive(){
  f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",owner());assertSame(captured,read());
  f.f.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",owner());assertThrows(RuntimeException.class,this::write);assertThrows(RuntimeException.class,this::read);
 }
 @Test void nativeChangesWithinOneWriterCannotReuseAnEarlierSuccessfulVerification(){
  assertThrows(RuntimeException.class,()->f.tx(()->{assertSame(captured,f.archives.verifyCapturedOriginal(captured));f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],lastPart());return f.archives.verifyCapturedOriginal(captured);}));
  assertSame(captured,write());
 }
}
