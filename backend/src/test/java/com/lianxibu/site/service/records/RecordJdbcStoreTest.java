package com.lianxibu.site.service.records;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.repository.records.JdbcRecordWrites;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.*;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class RecordJdbcStoreTest {
    JdbcDataSource ds;JdbcTemplate jdbc;
    final Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup(){
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:record-store-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),package_id VARCHAR(64),object_kind VARCHAR(16),object_id VARCHAR(64),revision BIGINT,status VARCHAR(32),self_assessment VARCHAR(32),note CLOB,artifact_url VARCHAR(2000),response_text CLOB,confirmed_body_revision BIGINT,updated_at TIMESTAMP,is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN,PRIMARY KEY(user_id,object_kind,package_id,object_id))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) PRIMARY KEY,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),UNIQUE(actor_id,scope,key_hash))");
    }
    PrivateRecordWriteService service(){
        var tx=new DataSourceTransactionManager(ds);
        var store=new RecordJdbcStore(new JdbcRecordWrites(ds),new RecordJdbcStore.Visibility(){
            public Long bodyRevision(String pkg,String kind,String id){return "fixture-node".equals(id)?3L:null;}
            public List<String> routeNodes(String pkg,String id){return List.of("fixture-node");}
        });
        return new PrivateRecordWriteService(store,new RecordReadService(new JdbcRecordReads(ds),tx),tx,clock);
    }
    RecordsService.RecordWrite command(long revision,String note){return new RecordsService.RecordWrite(revision,"learning","partial",note,null,"fixture-package","node",null,null,null,null,null);}
    RecordsService.Record write(String user,long revision,String key,String note){return service().write(new Actor(user,"user"),"node","fixture-node",command(revision,note),key,"fixture-csrf","https://site.example.test");}
    @Test void permanentReceiptsRemainUserScopedAcrossServiceRestarts(){
        var alice=write("fixture-alice",0,"same-key","Alice note");
        var bob=write("fixture-bob",0,"same-key","Bob note");
        assertEquals(alice,write("fixture-alice",0,"same-key","Alice note"));
        assertEquals(bob,write("fixture-bob",0,"same-key","Bob note"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_records",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE expires_at IS NULL",Integer.class));
        assertThrows(RuntimeException.class,()->write("fixture-alice",0,"same-key","Changed note"));
    }
    @Test void staleRevisionAndReceiptFailureCannotChangeRecord(){
        write("fixture-alice",0,"create","Original");
        assertThrows(RuntimeException.class,()->write("fixture-alice",0,"stale","Wrong"));
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT reject_update CHECK(request_key <> 'fail')");
        assertThrows(RuntimeException.class,()->write("fixture-alice",1,"fail","Must roll back"));
        assertEquals("Original",jdbc.queryForObject("SELECT note FROM user_records",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM user_records",Long.class));
        assertEquals(2,write("fixture-alice",1,"valid","Updated").revision());
    }
}
