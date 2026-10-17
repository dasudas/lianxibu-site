package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
class FrozenContentVersionSnapshotTest {
 ResultJdbcStoreTest fixture;ContentVersionKey version;DataSource counted;AtomicInteger queries;JdbcFrozenContentVersions store;DataSourceTransactionManager manager;
 boolean mysql(){return false;}
 @BeforeEach void setup(){fixture=mysql()?new ResultJdbcMySqlTest():new ResultJdbcStoreTest();fixture.fixture();fixture.apply();version=ContentVersionKey.fromJson(new JdbcResultState(fixture.source).receipt("fictional-apply","fictional-owner",ResultJdbcStoreTest.ROOT,fixture.identity.attemptId()).get("content_version"));queries=new AtomicInteger();counted=new DelegatingDataSource(fixture.source){@Override public Connection getConnection()throws java.sql.SQLException{var actual=super.getConnection();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{if(m.getName().equals("prepareStatement"))queries.incrementAndGet();try{return m.invoke(actual,args);}catch(InvocationTargetException e){throw e.getCause();}});}};manager=new DataSourceTransactionManager(counted);store=new JdbcFrozenContentVersions(counted);}
 <T>T read(boolean readonly,int isolation,int propagation,Supplier<T> work){var tx=new TransactionTemplate(manager);tx.setReadOnly(readonly);tx.setIsolationLevel(isolation);tx.setPropagationBehavior(propagation);return tx.execute(s->work.get());}
 <T>T rr(Supplier<T> work){return read(true,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRED,work);}
 @Test void SameRealSnapshotAcrossStoreInstancesReadsNativeProofOnceWithoutCallsOrWrites(){
  int rows=fixture.count("jobs");rr(()->{var first=store.read(version);assertNotNull(first);int once=queries.get();assertTrue(once>0);assertEquals(first,new JdbcFrozenContentVersions(counted).read(version));assertEquals(first,store.read(version));assertEquals(once,queries.get());return true;});assertEquals(rows,fixture.count("jobs"));assertEquals(1,fixture.sends);
 }
 @Test void RequiresNewSuspendsOwnFrameAndNewTransactionReloads(){
  rr(()->{var original=store.read(version);int first=queries.get();read(true,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRES_NEW,()->{assertEquals(original,store.read(version));int nested=queries.get();assertTrue(nested>first);assertEquals(original,store.read(version));assertEquals(nested,queries.get());return true;});int after=queries.get();assertEquals(original,store.read(version));assertEquals(after,queries.get());return true;});int prior=queries.get();rr(()->store.read(version));assertTrue(queries.get()>prior);
 }
 @Test void ReadCommittedAndWriterNeverReuseAndFreshSnapshotDetectsDamagedNativeReceipt(){
  for(boolean readonly:new boolean[]{true,false})read(readonly,TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.PROPAGATION_REQUIRED,()->{store.read(version);int first=queries.get();store.read(version);assertTrue(queries.get()>first);return true;});
  assertThrows(IllegalStateException.class,()->rr(()->{store.read(version);throw new IllegalStateException("fictional-rollback");}));fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='result_application'",new byte[32]);assertThrows(RuntimeException.class,()->rr(()->store.read(version)));
 }
 @Test void CompleteLargeProofBypassesMemoryBudgetAndStillReturnsEveryByte(){
  String body="{\"fictional\":\""+"F".repeat(600000)+"\"}";var large=new ContentVersionKey(version.contentKind(),version.contentId(),2,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(body)),version.inputJobId());var input=store.read(version).inputHash();fixture.writes.execute(fixture.action("fictional-large-review-proof"),k->null,a->{new JdbcContentReviewEvidence(fixture.source).archive(large,body,input,"fictional-owner",fixture.clock.instant());return true;});
  rr(()->{var first=store.read(large);assertEquals(600000,CanonicalJson.parse(first.bodyJson()).path("fictional").asText().length());assertEquals(large.bodyHash(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(first.bodyJson())));int once=queries.get();assertEquals(first,store.read(large));assertTrue(queries.get()>once);return true;});assertEquals(1,fixture.sends);
 }
 @Test void NativeEligibilityIsPureAndBoundToThisActualReadOnlyRepeatableSnapshot(){
  assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());
  rr(()->{int before=queries.get(),synchronizations=TransactionSynchronizationManager.getSynchronizations().size();assertTrue(store.canReuseImmutableProofInCurrentReadSnapshot());assertEquals(before,queries.get());assertEquals(synchronizations,TransactionSynchronizationManager.getSynchronizations().size());assertFalse(new JdbcFrozenContentVersions(new DelegatingDataSource(counted)).canReuseImmutableProofInCurrentReadSnapshot());return true;});
  for(boolean readonly:new boolean[]{true,false})read(readonly,TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.PROPAGATION_REQUIRED,()->{assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());return true;});
  read(false,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRED,()->{assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());return true;});
  assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());
 }
 @Test void EligibilityFollowsSuspensionAndRejectsAnUnboundDataSourceWithoutQuerying(){
  var alienSource=new DelegatingDataSource(counted);var alienManager=new DataSourceTransactionManager(alienSource);var alienRead=new TransactionTemplate(alienManager);alienRead.setReadOnly(true);alienRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  alienRead.execute(status->{int before=queries.get();assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());assertEquals(before,queries.get());return true;});
  rr(()->{assertTrue(store.canReuseImmutableProofInCurrentReadSnapshot());read(true,TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.PROPAGATION_REQUIRES_NEW,()->{assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());return true;});assertTrue(store.canReuseImmutableProofInCurrentReadSnapshot());return true;});
  assertFalse(store.canReuseImmutableProofInCurrentReadSnapshot());
 }

}
