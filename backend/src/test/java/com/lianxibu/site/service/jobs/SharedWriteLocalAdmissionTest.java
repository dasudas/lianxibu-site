package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
class SharedWriteLocalAdmissionTest {
 DataSource source;JdbcTemplate jdbc;CountingTx tx;
 DataSource source(){var s=new org.h2.jdbcx.JdbcDataSource();s.setURL("jdbc:h2:mem:fictional-admission-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");return s;}
 @BeforeEach void setup(){source=source();jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE IF NOT EXISTS worker_lock(id INT PRIMARY KEY)");if(jdbc.queryForObject("SELECT COUNT(*) FROM worker_lock",Integer.class)==0)jdbc.update("INSERT INTO worker_lock VALUES(1)");tx=new CountingTx(source);}
 SharedWriteExecutor executor(){return new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx,()->25,n->{});}
 SharedWriteExecutor.ActionContext action(){return new SharedWriteExecutor.ActionContext("fictional-admission-action","fictional-resource",null);}
 @Test void BusyLocalAdmissionStartsNoWriterTransactionOrBusinessWork()throws Exception{
  var calls=new AtomicInteger();var writer=executor();
  try(var pool=Executors.newSingleThreadExecutor();var held=new JdbcSharedWriteEntry(source).admit()){
   pool.submit(()->assertThrows(SharedWriteExecutor.WriteUnavailable.class,()->writer.execute(action(),k->null,k->{calls.incrementAndGet();return "fictional-result";}))).get(3,TimeUnit.SECONDS);
   assertEquals(0,tx.writers.get());assertEquals(1,tx.readers.get());assertEquals(0,calls.get());
  }
  assertEquals("fictional-result",writer.execute(action(),k->null,k->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return "fictional-result";}));assertEquals(1,tx.writers.get());
 }
 @Test void WorkFailureRollsBackAndReleasesAdmissionForAnotherThread()throws Exception{
  var writer=executor();assertThrows(IllegalArgumentException.class,()->writer.execute(action(),k->null,k->{throw new IllegalArgumentException("fictional-work-failure");}));
  try(var pool=Executors.newSingleThreadExecutor()){
   assertEquals("fictional-result",pool.submit(()->writer.execute(action(),k->null,k->{assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM worker_lock",Integer.class));return "fictional-result";})).get(3,TimeUnit.SECONDS));
  }
  assertEquals(2,tx.writers.get());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
 }
 @Test void DurableReceiptReplayDoesNotRequestLocalAdmissionOrWriter()throws Exception{
  var writer=executor();try(var held=new JdbcSharedWriteEntry(source).admit()){
   assertEquals("fictional-committed",writer.execute(action(),k->"fictional-committed",k->{fail("already committed");return null;}));assertEquals(0,tx.writers.get());
  }
 }
 static class CountingTx extends DataSourceTransactionManager {
  final AtomicInteger writers=new AtomicInteger(),readers=new AtomicInteger();CountingTx(DataSource source){super(source);}
  @Override protected void doBegin(Object transaction,TransactionDefinition definition){
   if(definition.isReadOnly())readers.incrementAndGet();else{assertEquals(1,definition.getTimeout());assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,definition.getIsolationLevel());writers.incrementAndGet();}
   super.doBegin(transaction,definition);
  }
 }
}
