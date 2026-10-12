package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.learning.JdbcLearningPackages;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class PackageJdbcStoreTest {
    private JdbcDataSource source;private JdbcTemplate jdbc;
    private static final Actor OWNER=new Actor("fictional-owner","owner");
    private PackageLifecycleService service(){return new PackageLifecycleService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source)),new PackageJdbcStore(new JdbcLearningPackages(source),OWNER.userId(),Clock.systemUTC()));}
    private PackageLifecycleService.Result action(long rev,PackageLifecycleService.Action op,String key){return service().action(OWNER,"fictional-package",rev,op,"Fictional review",new ActionContext(key,"fictional-operation",null));}
    @BeforeEach void setup(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:package-store-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),revision BIGINT,state VARCHAR(16),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),body_json CLOB,PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE learning_links(package_id VARCHAR(64),from_kind VARCHAR(16),from_id VARCHAR(64),relation_kind VARCHAR(24),to_kind VARCHAR(16),to_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.update("INSERT INTO learning_packages VALUES('fictional-package','Fictional',1,'draft',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO learning_items VALUES('fictional-package','module','module','Fictional','{}')");
        for(String id:new String[]{"n1","n2"}){jdbc.update("INSERT INTO learning_items VALUES('fictional-package','node',?,'Fictional','{}')",id);jdbc.update("INSERT INTO learning_links VALUES('fictional-package','module','module','contains','node',?)",id);}
    }
    @Test void approvePublishAndWithdrawKeepDurableProofAndReplay(){
        assertEquals("approved",action(1,PackageLifecycleService.Action.APPROVE,"approve").state());
        assertEquals("published",action(2,PackageLifecycleService.Action.PUBLISH,"publish").state());
        assertTrue(action(1,PackageLifecycleService.Action.APPROVE,"approve").replayed());
        assertThrows(IllegalArgumentException.class,()->action(3,PackageLifecycleService.Action.APPROVE,"approve"));
        assertEquals("withdrawn",action(3,PackageLifecycleService.Action.WITHDRAW,"withdraw").state());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM learning_items",Integer.class));
    }
    @Test void danglingAndCyclicDependenciesCannotBeApproved(){
        jdbc.update("INSERT INTO learning_links VALUES('fictional-package','node','n1','requires','node','absent')");
        assertThrows(PackageLifecycleService.Rejected.class,()->action(1,PackageLifecycleService.Action.APPROVE,"dangling"));
        jdbc.update("DELETE FROM learning_links WHERE relation_kind='requires'");
        jdbc.update("INSERT INTO learning_links VALUES('fictional-package','node','n1','requires','node','n2')");
        jdbc.update("INSERT INTO learning_links VALUES('fictional-package','node','n2','requires','node','n1')");
        assertThrows(PackageLifecycleService.Rejected.class,()->action(1,PackageLifecycleService.Action.APPROVE,"cycle"));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM learning_packages",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void publishRevalidatesAndReceiptFailureRollsBack(){
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT reject_failure CHECK(request_key <> 'fail')");
        assertThrows(RuntimeException.class,()->action(1,PackageLifecycleService.Action.APPROVE,"fail"));
        assertEquals("draft",jdbc.queryForObject("SELECT state FROM learning_packages",String.class));
        action(1,PackageLifecycleService.Action.APPROVE,"approve");
        jdbc.update("INSERT INTO learning_items VALUES('fictional-package','resource','bad','Fictional','{\"url\":\"http://example.invalid/plain\"}')");
        assertThrows(PackageLifecycleService.Rejected.class,()->action(2,PackageLifecycleService.Action.PUBLISH,"publish"));
        assertEquals("approved",jdbc.queryForObject("SELECT state FROM learning_packages",String.class));
    }
}
