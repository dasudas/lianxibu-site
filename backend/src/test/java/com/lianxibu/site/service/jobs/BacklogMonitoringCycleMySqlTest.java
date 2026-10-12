package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import com.lianxibu.site.repository.jobs.*;import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;import org.junit.jupiter.api.Test;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class BacklogMonitoringCycleMySqlTest extends BacklogMonitoringCycleTest{
 @Override Fixture fixture(){var base=new PooledResultJdbcMySqlFixture();base.fixture();base.jdbc.update("DELETE FROM jobs");base.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");base.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");return new Fixture(base.source);}
 @Test void actualMysqlLostCommitAckReturnsBothNativeHistoryAndPermanentAlarmWithoutDuplicate(){try(var base=new PooledResultJdbcMySqlFixture()){
  base.fixture();base.jdbc.update("DELETE FROM jobs");base.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");base.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");var f=new Fixture(base.source);try(var scope=JdbcBacklogMutationWitness.open(f.source,f.clock)){
   var history=new JdbcBacklogHistory(f.source,scope);var root=root(f,history);var source=source(f,history,mock(DispatchReadinessService.class),settings(false));var store=store(f);f.clock.now=f.clock.now.plusSeconds(7*86400L);var cycle=new BacklogMonitoringCycle(KEY,source,store,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),base.writes,f.clock);cycle.start();base.tx.ackLost=true;
   assertEquals(BacklogMonitoringCycle.Outcome.SAMPLED,cycle.tick());assertEquals(1,store.read(KEY).revision());assertEquals(Level.CRITICAL,store.read(KEY).level());assertEquals(1,rr(f,()->history.read(root)).revision());assertEquals(1,rr(f,history::mutation).sequence());assertEquals(1,f.sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='ops_rule'",Integer.class));assertEquals(BacklogMonitoringCycle.Outcome.ALREADY_SAMPLED,cycle.tick());
  }
 }}
}
