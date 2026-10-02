package com.lianxibu.site.service.jobs;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/** The superclass validates the exact fictional loopback endpoint before calling this factory. */
final class PooledResultJdbcMySqlFixture extends ResultJdbcMySqlTest implements AutoCloseable {
 private HikariDataSource pool;
 @Override DataSource dataSource(String url){pool=new HikariDataSource();pool.setJdbcUrl(url);pool.setUsername("root");pool.setPassword("");pool.setMaximumPoolSize(5);pool.setMinimumIdle(1);pool.setConnectionTimeout(5000);return pool;}
 @Override public void close(){if(pool!=null)pool.close();}
}
final class PooledValidityMySqlFixture extends VersionValidityJdbcMySqlTest implements AutoCloseable {
 private PooledResultJdbcMySqlFixture pooled;
 @Override void setup(){pooled=new PooledResultJdbcMySqlFixture();pooled.fixture();base=new VersionValidityLedgerServiceTest();base.fixture=pooled;base.install();install();}
 @Override public void close(){if(pooled!=null)pooled.close();}
}
