package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicLong;import org.junit.jupiter.api.Test;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SharedWriteLockTelemetryMySqlTest extends SharedWriteLockTelemetryTest {
 @Override javax.sql.DataSource source(){return new CollectorNodeFactsNativeMySqlTest().source();}
 @Test void actualNativeMySqlContentionHasMeasuredOneSecondFailureWithoutChangingTimeout()throws Exception{
  try(var held=source.getConnection()){held.setAutoCommit(false);try(var statement=held.createStatement();var row=statement.executeQuery("SELECT id FROM worker_lock FOR UPDATE")){assertTrue(row.next());}var observed=new AtomicLong(-1);var entry=new JdbcSharedWriteEntry(source,System::nanoTime,observed::set);var pool=Executors.newSingleThreadExecutor();try{var waiting=pool.submit(()->tx.execute(s->{entry.lock();return null;}));assertThrows(ExecutionException.class,()->waiting.get(5,TimeUnit.SECONDS));assertTrue(observed.get()>=500000000L);assertTrue(observed.get()<5000000000L);}finally{held.rollback();pool.shutdownNow();}}
 }
}
