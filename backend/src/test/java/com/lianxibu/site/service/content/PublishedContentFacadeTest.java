package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.ContentService.*;
import com.lianxibu.site.service.identity.IdentityService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublishedContentFacadeTest {
    JdbcTemplate jdbc; DataSourceTransactionManager tx; ContentReadService reads;
    Map<String,ContentBody> bodies=new HashMap<>(); AtomicInteger qualifications=new AtomicInteger();
    static final Instant TIME=Instant.parse("2026-01-01T00:00:00Z");
    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:fictional-public-"+System.nanoTime());
        // Retain the fictional in-memory database between short read transactions.
        source.setURL(source.getURL()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);tx=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE contents(kind VARCHAR(16),id VARCHAR(64),natural_key VARCHAR(255),"
                +"title VARCHAR(512),body_json CLOB,state VARCHAR(16),revision BIGINT,body_revision BIGINT,"
                +"published_at TIMESTAMP,input_job_id VARCHAR(64),body_hash_v1 BINARY(32),review_hash_v1 BINARY(32),"
                +"review_dependency_stamp CLOB,review_stamp_hash BINARY(32),review_stamp_id VARCHAR(64),"
                +"reviewed_input_hash BINARY(32),blocking_summary_json CLOB,PRIMARY KEY(kind,id))");
        reads=new ContentReadService(new JdbcContentReads(source),tx,row->{qualifications.incrementAndGet();return !row.id().startsWith("hidden");});
    }
    ContentBody insert(String kind,String id,String type) {
        ContentBody body="report".equals(kind)
                ?new ReportBody("Fictional",List.of(),List.of(),type,TIME,TIME.plusSeconds(86400),"UTC",null)
                :new InterpretationBody("Fictional",List.of(),List.of(),kind,"fictional-entity");
        var json=PublishedContentFacade.body(body);var hash=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json));
        jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision,body_revision,published_at,"
                +"input_job_id,body_hash_v1,review_hash_v1,review_dependency_stamp,review_stamp_hash,review_stamp_id,reviewed_input_hash)"
                +" VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",kind,id,"fictional-"+id,"Fictional",json.toString(),"published",3L,1L,
                Timestamp.from(TIME),"fictional-owner",hash,hash,"[]",hash,"fictional-stamp",hash);
        bodies.put(kind+":"+id,body);return body;
    }
    ContentView project(PublishedContentFacade.Version v) {
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        return new ContentView(v.id(),v.kind(),v.title(),v.revision(),v.bodyRevision(),"published",v.publishedAt(),
                v.inputOwner(),1L,TIME,new Coverage("complete",List.of(),List.of(),List.of()),false,List.of(),false,null,
                null,bodies.get(v.kind()+":"+v.id()),List.of());
    }
    PublishedContentFacade facade(){return new PublishedContentFacade(reads,this::project,tx);}
    @Test void filtersBeforeCountAndPaginationWithOneQualificationPass() {
        for(int n=0;n<105;n++)insert("report",String.format("fictional-%03d",n),n%2==0?"daily":"weekly");
        insert("report","hidden-daily","daily");insert("agent","fictional-agent",null);
        var page=facade().reports("daily",2,50);
        assertEquals(53,page.total());assertEquals(3,page.items().size());assertEquals("fictional-100",page.items().getFirst().id());
        assertEquals(106,qualifications.get());assertEquals(1,facade().interpretations(null,1,10).total());
    }
    @Test void qualificationFailureNeverProjectsAndMissingOwnerCannotBecomePublic() {
        insert("report","hidden-report","daily");insert("report","fictional-no-owner","daily");
        jdbc.update("UPDATE contents SET input_job_id=NULL WHERE id='fictional-no-owner'");
        var facade=new PublishedContentFacade(reads,v->{fail("unqualified projection");return null;},tx);
        assertThrows(IdentityService.Rejected.class,()->facade.report("hidden-report"));
        assertThrows(IdentityService.Rejected.class,()->facade.report("fictional-no-owner"));assertEquals(0,facade.reports(null,1,10).total());
    }
    @Test void bodyTamperingAndMissingProjectionFailClosedWithoutRemovingRows() {
        insert("report","fictional-report","daily");
        var missing=new PublishedContentFacade(reads,v->null,tx);
        assertThrows(IdentityService.Rejected.class,()->missing.reports(null,1,10));
        bodies.put("report:fictional-report",new ReportBody("Changed",List.of(),List.of(),"daily",TIME,TIME.plusSeconds(86400),"UTC",null));
        assertThrows(IdentityService.Rejected.class,()->facade().report("fictional-report"));
        insert("report","fictional-second","daily");jdbc.update("UPDATE contents SET body_json='{}' WHERE id='fictional-second'");
        assertThrows(IdentityService.Rejected.class,()->facade().report("fictional-second"));
    }
    @Test void crossKindIdentityCollisionIsRejectedAndTypedListRemainsDeterministic() {
        insert("agent","fictional-same",null);insert("model","fictional-same",null);
        assertThrows(IdentityService.Rejected.class,()->facade().interpretation("fictional-same"));
        assertEquals("agent",facade().interpretations("agent",1,10).items().getFirst().kind());
        assertEquals(2,facade().interpretations(null,1,10).total());
    }
    @Test void detailRechecksCurrentQualificationAndCommandsAreUnavailable() {
        insert("report","fictional-report","daily");assertEquals(1,facade().report("fictional-report").bodyRevision());
        jdbc.update("UPDATE contents SET state='withdrawn'");assertThrows(IdentityService.Rejected.class,()->facade().report("fictional-report"));
        assertThrows(IdentityService.Rejected.class,()->facade().adminContent(null,"report","fictional-report"));
        assertThrows(IllegalArgumentException.class,()->facade().reports("unknown",1,10));
        assertThrows(IllegalArgumentException.class,()->facade().interpretations("report",1,10));
    }
    PublishedContentFacade nativeFacade(){return new PublishedContentFacade(reads,this::project,tx,null,null,jdbc.getDataSource());}
    <T>T nativeRead(boolean readonly,int isolation,org.springframework.transaction.PlatformTransactionManager manager,java.util.function.Supplier<T> work){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);transaction.setReadOnly(readonly);transaction.setIsolationLevel(isolation);return transaction.execute(status->work.get());
    }
    <T>T rr(java.util.function.Supplier<T> work){return nativeRead(true,java.sql.Connection.TRANSACTION_REPEATABLE_READ,tx,work);}
    @Test void NativeHomePageAlwaysRunsFullQualificationAndNewTransactionsSeeWithdrawal(){
        insert("report","fictional-home-report","daily");insert("report","hidden-home-report","daily");var facade=nativeFacade();
        rr(()->{var first=facade.homeReportsInCurrentSnapshot(jdbc.getDataSource());assertEquals(1,first.size());assertEquals("fictional-home-report",first.getFirst().id());assertThrows(UnsupportedOperationException.class,first::clear);assertEquals(1,facade.homeReportsInCurrentSnapshot(jdbc.getDataSource()).size());assertEquals(4,qualifications.get());return true;});
        jdbc.update("UPDATE contents SET state='withdrawn' WHERE id='fictional-home-report'");
        assertTrue(rr(()->facade.homeReportsInCurrentSnapshot(jdbc.getDataSource())).isEmpty());assertEquals(5,qualifications.get());
    }
    @Test void NativeHomeScopeRejectsBeforeQualificationAndCannotBorrowAnotherDataSource(){
        insert("report","fictional-home-report","daily");var facade=nativeFacade();var source=jdbc.getDataSource();
        assertThrows(IdentityService.Rejected.class,()->facade.homeReportsInCurrentSnapshot(source));
        for(boolean readonly:new boolean[]{true,false})nativeRead(readonly,java.sql.Connection.TRANSACTION_READ_COMMITTED,tx,()->{assertThrows(IdentityService.Rejected.class,()->facade.homeReportsInCurrentSnapshot(source));return true;});
        nativeRead(false,java.sql.Connection.TRANSACTION_REPEATABLE_READ,tx,()->{assertThrows(IdentityService.Rejected.class,()->facade.homeReportsInCurrentSnapshot(source));return true;});
        var alien=new JdbcDataSource();alien.setURL("jdbc:h2:mem:fictional-alien-home-"+System.nanoTime());
        rr(()->{assertThrows(IdentityService.Rejected.class,()->facade.homeReportsInCurrentSnapshot(alien));assertThrows(IdentityService.Rejected.class,()->facade().homeReportsInCurrentSnapshot(source));assertThrows(IdentityService.Rejected.class,()->new PublishedContentFacade(reads,this::project,tx,null,null,alien).homeReportsInCurrentSnapshot(alien));return true;});
        nativeRead(true,java.sql.Connection.TRANSACTION_REPEATABLE_READ,new DataSourceTransactionManager(alien),()->{assertThrows(IdentityService.Rejected.class,()->facade.homeReportsInCurrentSnapshot(source));return true;});
        assertEquals(0,qualifications.get());
    }
    @Test void NativeHomePageNeverKeepsAFormerProjectionAfterItsProofChanges(){
        insert("report","fictional-home-report","daily");var facade=nativeFacade();assertEquals(1,rr(()->facade.homeReportsInCurrentSnapshot(jdbc.getDataSource())).size());
        bodies.put("report:fictional-home-report",new ReportBody("Fictional changed",List.of(),List.of(),"daily",TIME,TIME.plusSeconds(86400),"UTC",null));
        assertThrows(IdentityService.Rejected.class,()->rr(()->facade.homeReportsInCurrentSnapshot(jdbc.getDataSource())));assertEquals(2,qualifications.get());
    }

}
