package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.net.InetAddress;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Opt-in destructive fixtures, restricted to one dedicated loopback test database. */
@EnabledIfEnvironmentVariable(named="V03_MYSQL",matches="true")
class SharedWriteMySqlTest {
    private DataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach void fixture() throws Exception {
        String url=System.getenv("V03_MYSQL_URL");
        if(url==null||!url.startsWith("jdbc:"))throw new IllegalStateException("isolated MySQL required");
        URI endpoint=URI.create(url.substring(5));
        if(!"mysql".equals(endpoint.getScheme())||endpoint.getPort()!=13336
                ||endpoint.getHost()==null||!InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()
                ||!"/fictional_v03_shared".equals(endpoint.getPath()))
            throw new IllegalStateException("dedicated loopback database required");
        source=new DriverManagerDataSource(url,System.getenv().getOrDefault("V03_MYSQL_USER","root"),
                System.getenv().getOrDefault("V03_MYSQL_PASSWORD",""));
        jdbc=new JdbcTemplate(source);
        assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));
        assertEquals("utf8mb4_0900_ai_ci",jdbc.queryForObject("SELECT @@collation_database",String.class));
        assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        jdbc.execute("CREATE TABLE IF NOT EXISTS worker_lock(id INT PRIMARY KEY,slot_state VARCHAR(16),slot_revision BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fictional_business(id INT PRIMARY KEY,value_no INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fictional_receipts(action_key VARCHAR(128) PRIMARY KEY,result_text VARCHAR(64)) ENGINE=InnoDB");
        reset();
    }
    private void reset(){jdbc.update("DELETE FROM fictional_receipts");jdbc.update("DELETE FROM fictional_business");jdbc.update("DELETE FROM worker_lock");jdbc.update("INSERT INTO worker_lock VALUES(1,'occupied',7)");jdbc.update("INSERT INTO fictional_business VALUES(1,0)");}
    private String receipt(String key){List<String> r=jdbc.query("SELECT result_text FROM fictional_receipts WHERE action_key=?",(rs,n)->rs.getString(1),key);return r.isEmpty()?null:r.getFirst();}
    private String work(ActionContext action){jdbc.update("UPDATE fictional_business SET value_no=value_no+1 WHERE id=1");jdbc.update("INSERT INTO fictional_receipts VALUES(?,?)",action.actionKey(),"committed");return "committed";}
    private int value(){return jdbc.queryForObject("SELECT value_no FROM fictional_business WHERE id=1",Integer.class);}
    private SharedWriteExecutor executor(DataSourceTransactionManager tx){return new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx,()->25,ignored->{});}
    @Test void sameKeyCompetitionRechecksDurableReceiptAfterSharedLockFor100Seeds() throws Exception {
        var executor=executor(new DataSourceTransactionManager(source));
        for(int seed=0;seed<100;seed++){
            reset();var initialReads=new CountDownLatch(2);var firstWork=new CountDownLatch(1);var callbacks=new AtomicInteger();final String preferred=seed%2==0?"fictional-a":"fictional-b";
            var action=new ActionContext("fictional-seed-"+seed,"fictional-resource-"+seed,"a".repeat(64));
            SharedWriteExecutor.CommittedResult<String> lookup=key->{String existing=receipt(key);
                if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()){
                    initialReads.countDown();try{if(!initialReads.await(5,TimeUnit.SECONDS))throw new AssertionError("initial lookup barrier timeout");if(!Thread.currentThread().getName().equals(preferred)&&!firstWork.await(5,TimeUnit.SECONDS))throw new AssertionError("preferred write barrier timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                }return existing;};
            try(var pool=Executors.newFixedThreadPool(2)){
                var first=pool.submit(()->{Thread.currentThread().setName("fictional-a");return executor.execute(action,lookup,a->{callbacks.incrementAndGet();firstWork.countDown();assertEquals(preferred,Thread.currentThread().getName());assertEquals(action,a);return work(a);});});
                var second=pool.submit(()->{Thread.currentThread().setName("fictional-b");return executor.execute(action,lookup,a->{callbacks.incrementAndGet();firstWork.countDown();assertEquals(preferred,Thread.currentThread().getName());assertEquals(action,a);return work(a);});});
                if(seed%2==0){assertEquals("committed",first.get(10,TimeUnit.SECONDS),"seed "+seed+" forward");assertEquals("committed",second.get(10,TimeUnit.SECONDS),"seed "+seed+" forward");}
                else{assertEquals("committed",second.get(10,TimeUnit.SECONDS),"seed "+seed+" reverse");assertEquals("committed",first.get(10,TimeUnit.SECONDS),"seed "+seed+" reverse");}
            }catch(Exception e){throw new AssertionError("failed deterministic seed "+seed+", callbacks="+callbacks.get(),e);}
            assertEquals(1,callbacks.get(),"seed "+seed);assertEquals(1,value(),"seed "+seed);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fictional_receipts",Integer.class));
            assertEquals("occupied",jdbc.queryForObject("SELECT slot_state FROM worker_lock",String.class));
            assertEquals(7L,jdbc.queryForObject("SELECT slot_revision FROM worker_lock",Long.class));
        }
    }
    @Test void committedAckLossUsesRealDurableReceiptWithoutRepeatingBusiness(){
        var tx=new FaultCommitManager(source);var executor=executor(tx);var callbacks=new AtomicInteger();
        var action=new ActionContext("fictional-ack-loss","fictional-resource","a".repeat(64));
        assertEquals("committed",executor.execute(action,this::receipt,a->{callbacks.incrementAndGet();String result=work(a);tx.afterCommit.set(true);return result;}));
        assertEquals(1,callbacks.get());assertEquals(1,value());assertEquals("committed",receipt(action.actionKey()));
    }
    @Test void injectedDeadlockRollsBackRealTransactionAndRetriesOnlyStableDatabaseWork(){
        var tx=new FaultCommitManager(source);var executor=executor(tx);var callbacks=new AtomicInteger();
        var action=new ActionContext("fictional-rollback","fictional-resource","a".repeat(64));
        assertEquals("committed",executor.execute(action,this::receipt,a->{assertEquals(action,a);int call=callbacks.incrementAndGet();String result=work(a);if(call==1)tx.beforeCommit.set(true);return result;}));
        assertEquals(2,callbacks.get());assertEquals(1,value());assertEquals("committed",receipt(action.actionKey()));
    }
    @Test void uniqueExistingWorkerIdIsPreservedAndMissingOrMultipleRowsFailClosed(){
        jdbc.update("UPDATE worker_lock SET id=7");
        var executor=executor(new DataSourceTransactionManager(source));
        var action=new ActionContext("fictional-non-default-row","fictional-resource","a".repeat(64));
        assertEquals("committed",executor.execute(action,this::receipt,this::work));
        assertEquals(7,jdbc.queryForObject("SELECT id FROM worker_lock",Integer.class));
        assertEquals("occupied",jdbc.queryForObject("SELECT slot_state FROM worker_lock",String.class));
        assertEquals(7L,jdbc.queryForObject("SELECT slot_revision FROM worker_lock",Long.class));
        reset();jdbc.update("DELETE FROM worker_lock");
        assertThrows(RuntimeException.class,()->executor.execute(action,this::receipt,this::work));
        assertEquals(0,value());assertNull(receipt(action.actionKey()));
        reset();jdbc.update("INSERT INTO worker_lock VALUES(7,'free',0)");
        assertThrows(RuntimeException.class,()->executor.execute(action,this::receipt,this::work));
        assertEquals(0,value());assertNull(receipt(action.actionKey()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM worker_lock",Integer.class));
    }
    private static final class FaultCommitManager extends DataSourceTransactionManager {
        final AtomicBoolean beforeCommit=new AtomicBoolean(),afterCommit=new AtomicBoolean();
        FaultCommitManager(DataSource source){super(source);}
        @Override protected void doCommit(DefaultTransactionStatus status){
            if(beforeCommit.getAndSet(false)){super.doRollback(status);throw new TransactionSystemException("fictional injected deadlock",new SQLException("fictional deadlock","40001",1213));}
            super.doCommit(status);
            if(afterCommit.getAndSet(false))throw new TransactionSystemException("fictional commit ACK lost",new SQLException("fictional connection lost","08006"));
        }
    }
}
