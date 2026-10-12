package com.lianxibu.site.service.jobs;
import javax.sql.DataSource;

/** The superclass validates the exact fictional loopback endpoint before calling this factory. */
final class PooledResultJdbcMySqlFixture extends ResultJdbcMySqlTest implements AutoCloseable {
 @Override DataSource dataSource(String url){return ResultJdbcMySqlTest.isolatedSource(url);}
 // The JVM fixture owns the shared bounded pool; closing a borrow cannot end its lifetime.
 @Override public void close(){ }
}
final class PooledValidityMySqlFixture extends VersionValidityJdbcMySqlTest implements AutoCloseable {
 private PooledResultJdbcMySqlFixture pooled;
 @Override void setup(){pooled=new PooledResultJdbcMySqlFixture();pooled.fixture();base=new VersionValidityLedgerServiceTest();base.fixture=pooled;base.install();install();}
 @Override public void close(){if(pooled!=null)pooled.close();}
}
