package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** Native durable acceptance lookup; scan hints alone never authorize a root. */
class OwnerAdHocIntentScanTest {
    OwnerAdHocCreationTest c;JdbcAdHocIntentScan scan;TransactionTemplate reads;
    OwnerAdHocCreationTest fixture(){return new OwnerAdHocCreationTest();}
    @BeforeEach void setup(){c=fixture();c.setup();scan=new JdbcAdHocIntentScan(c.f.source);reads=new TransactionTemplate(new DataSourceTransactionManager(c.f.source));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    JdbcOwnerAdHocCreation.Accepted create(int n){return c.create.create(c.owner,"fictional-scan-"+n,"Fictional durable question "+n,"Fictional scan reason","fictional-scan-key-"+n);}
    @Test void BoundedOrderedPagesExcludePreparedIntentsAndRetainEveryOtherOriginal(){
        var originals=List.of(create(1),create(2),create(3));var ids=originals.stream().map(x->x.command().intentId()).sorted().toList();
        var first=reads.execute(s->scan.page(null,2));assertEquals(ids.subList(0,2),first.intentIds());assertEquals(ids.get(1),first.nextCursor());var last=reads.execute(s->scan.page(first.nextCursor(),2));assertEquals(ids.subList(2,3),last.intentIds());assertNull(last.nextCursor());assertThrows(UnsupportedOperationException.class,()->first.intentIds().add("fictional"));
        var original=originals.getFirst();new OwnerAdHocPreparationService(c.f.source,c.accepted,c.f.writes,new DataSourceTransactionManager(c.f.source),c.f.clock,()->true).prepare(original.actionKey());assertEquals(2,reads.execute(s->scan.page(null,50)).intentIds().size());assertEquals(1,c.f.count("record_kind='logical_task'"));
    }
    @Test void IntentLookupReturnsCompleteOriginalEvenWhenLaterStateAndRevisionChange(){
        var original=create(1);c.f.jdbc.update("UPDATE jobs SET state='queued',revision=9 WHERE id=?",original.command().intentId());var recovered=reads.execute(s->c.accepted.originalIntent(original.command().intentId()));assertEquals(original.json(),recovered.json());assertEquals(original.response(),recovered.response());assertEquals(original.command().prompt(),recovered.command().prompt());assertNull(reads.execute(s->c.accepted.originalIntent("fictional-unknown")));
    }
    @Test void AValidScanHintWithDamagedNativeArchiveCannotAuthorizeAnyRoot(){
        var original=create(1);assertEquals(1,c.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='owner_ad_hoc_create'",new byte[32]));assertEquals(List.of(original.command().intentId()),reads.execute(s->scan.page(null,1)).intentIds());assertThrows(RuntimeException.class,()->reads.execute(s->c.accepted.originalIntent(original.command().intentId())));assertEquals(0,c.f.count("record_kind='logical_task' OR record_kind='ai_attempt'"));
    }
    @Test void OversizedHintAndOversizedOriginalAreRejectedBeforeNativeRecovery(){
        var original=create(1);c.f.jdbc.update("UPDATE jobs SET logical_key=? WHERE id=?","x".repeat(4097),original.command().intentId());assertThrows(RuntimeException.class,()->reads.execute(s->c.accepted.originalIntent(original.command().intentId())));
        c.f.jdbc.update("UPDATE jobs SET logical_key=? WHERE id=?",original.command().logicalKey(),original.command().intentId());c.f.jdbc.update("UPDATE jobs SET action_payload=? WHERE target_type='owner_ad_hoc_create'","{\"fictional_oversized\":\""+"x".repeat(524289)+"\"}");assertThrows(RuntimeException.class,()->reads.execute(s->c.accepted.originalIntent(original.command().intentId())));assertEquals(2,c.f.count("1=1"));
    }
    @Test void ConstructorsPerformNoIoAndReadOnlyTransactionIsMandatory(){
        var forbidden=new org.springframework.jdbc.datasource.AbstractDataSource(){@Override public java.sql.Connection getConnection(){throw new AssertionError("fictional constructor SQL");}@Override public java.sql.Connection getConnection(String u,String p){return getConnection();}};
        assertDoesNotThrow(()->new JdbcAdHocIntentScan(forbidden));assertDoesNotThrow(()->new JdbcOwnerAdHocCreation(forbidden));assertThrows(RuntimeException.class,()->scan.page(null,1));assertThrows(RuntimeException.class,()->c.accepted.originalIntent("fictional-intent"));
        var writer=new TransactionTemplate(new DataSourceTransactionManager(c.f.source));assertThrows(RuntimeException.class,()->writer.execute(s->scan.page(null,1)));assertThrows(RuntimeException.class,()->reads.execute(s->scan.page(null,51)));assertThrows(RuntimeException.class,()->reads.execute(s->scan.page("",1)));assertEquals(0,c.f.count("1=1"));
    }
}
