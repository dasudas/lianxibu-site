package com.lianxibu.site.repository.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcSharedWriteAdmissionTest {
 @Test void SameDataSourceContendersAreBoundedWithoutConnectionsAndReleaseAllowsNext() throws Exception {
  var source=mock(DataSource.class);var first=new JdbcSharedWriteEntry(source);var other=new JdbcSharedWriteEntry(source);
  try(var pool=Executors.newSingleThreadExecutor()){
   try(var lease=first.admit()){
    var result=pool.submit(()->assertThrows(CannotAcquireLockException.class,other::admit));result.get(2,TimeUnit.SECONDS);
    assertThrows(IllegalStateException.class,first::admit);verifyNoInteractions(source);
   }
   pool.submit(()->{try(var lease=other.admit()){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}}).get(2,TimeUnit.SECONDS);
  }
 }
 @Test void DifferentSourcesAreIndependentAndCurrentTransactionCannotAdmit(){
  var a=new JdbcSharedWriteEntry(mock(DataSource.class));var b=new JdbcSharedWriteEntry(mock(DataSource.class));
  try(var one=a.admit();var two=b.admit()){
   TransactionSynchronizationManager.setActualTransactionActive(true);
   try{assertThrows(IllegalStateException.class,()->new JdbcSharedWriteEntry(mock(DataSource.class)).admit());}
   finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
  }
 }
 @Test void InterruptedContenderKeepsInterruptAndCannotReceivePermission()throws Exception{
  var source=mock(DataSource.class);var a=new JdbcSharedWriteEntry(source);var interrupted=new AtomicBoolean();
  try(var pool=Executors.newSingleThreadExecutor();var lease=a.admit()){
   pool.submit(()->{Thread.currentThread().interrupt();try{assertThrows(CannotAcquireLockException.class,()->new JdbcSharedWriteEntry(source).admit());interrupted.set(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}).get(2,TimeUnit.SECONDS);
  }
  assertTrue(interrupted.get());verifyNoInteractions(source);
 }
 @Test void OnlyOwnerCanReleaseAndOwnerCloseIsIdempotent()throws Exception{
  var source=mock(DataSource.class);var a=new JdbcSharedWriteEntry(source);var lease=a.admit();
  try(var pool=Executors.newSingleThreadExecutor()){
   pool.submit(()->assertThrows(IllegalStateException.class,lease::close)).get(2,TimeUnit.SECONDS);
   pool.submit(()->assertThrows(CannotAcquireLockException.class,()->new JdbcSharedWriteEntry(source).admit())).get(2,TimeUnit.SECONDS);
   lease.close();lease.close();try(var again=a.admit()){verifyNoInteractions(source);}
  }finally{lease.close();}
 }
}
