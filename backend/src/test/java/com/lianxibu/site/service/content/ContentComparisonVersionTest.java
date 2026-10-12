package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ContentComparisonVersionTest {
    GeneratedContentServiceTest f;
    ContentReadService reads;
    @BeforeEach void setup(){f=new GeneratedContentServiceTest();f.fixture();ready();}
    void ready(){
        f.apply("fictional-comparison-create",f.command("fictional-report",null,GeneratedContentServiceTest.BODY));
        reads=new ContentReadService(new JdbcContentReads(f.source),new DataSourceTransactionManager(f.source),ContentReadService.denyUnverifiedReferences());
    }
    ContentReadService.ComparisonVersion read(){return reads.comparisonVersion("report","fictional-report").orElseThrow();}
    @Test void nativeBodyRevisionIsNeverTheLaterManagementRevisionAndHashIsImmutable(){
        f.jdbc.update("UPDATE contents SET revision=7 WHERE id='fictional-report'");
        var value=read();assertEquals(7,value.revision());assertEquals(1,value.bodyRevision());assertTrue(value.proven());
        assertEquals("fictional-input",value.inputJobId());byte[] hash=value.bodyHash();hash[0]^=1;assertNotEquals(hash[0],value.bodyHash()[0]);
        assertFalse(value.toString().contains("Fictional"));assertEquals(7,reads.administrativeVersion("report","fictional-report").orElseThrow().revision());
    }
    @Test void legacyNullMetadataRemainsUnprovenAndCompositeIdentityIsExact(){
        f.jdbc.update("UPDATE contents SET revision=9,body_revision=NULL,content_key_json=NULL WHERE id='fictional-report'");
        var value=read();assertNull(value.bodyRevision());assertFalse(value.proven());assertEquals(9,value.revision());
        assertTrue(reads.comparisonVersion("agent","fictional-report").isEmpty());assertTrue(reads.comparisonVersion("report","fictional-absent").isEmpty());
        assertEquals(9,reads.administrativeVersion("report","fictional-report").orElseThrow().bodyRevision());
    }
    @Test void completeHashKeyAndOriginalInputOwnerDamageRefuse(){
        f.jdbc.update("UPDATE contents SET body_hash_v1=? WHERE id='fictional-report'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        setup();f.jdbc.update("UPDATE contents SET content_key_json=REPLACE(content_key_json,'fictional-report','fictional-alias')");assertThrows(RuntimeException.class,this::read);
        setup();f.jdbc.update("UPDATE contents SET input_job_id='fictional-other' WHERE id='fictional-report'");assertThrows(RuntimeException.class,this::read);
        setup();f.jdbc.update("UPDATE contents SET body_revision=0 WHERE id='fictional-report'");assertThrows(RuntimeException.class,this::read);
    }
    @Test void sqlBoundsRefuseOversizedBodyAndVersionKeyBeforeParsing(){
        f.jdbc.update("UPDATE contents SET body_json=? WHERE id='fictional-report'","{\"fictional\":\""+"X".repeat(262145)+"\"}");assertThrows(RuntimeException.class,this::read);
        setup();f.jdbc.update("UPDATE contents SET content_key_json=? WHERE id='fictional-report'","{\"fictional\":\""+"X".repeat(16385)+"\"}");assertThrows(RuntimeException.class,this::read);
    }
    @Test void requiresOneReadonlyRepeatableSnapshotAcrossIndependentWriter() throws Exception {
        var tx=new DataSourceTransactionManager(f.source);var writer=new TransactionTemplate(tx);
        assertThrows(RuntimeException.class,()->writer.execute(s->read()));
        var wrong=new TransactionTemplate(tx);wrong.setReadOnly(true);wrong.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->wrong.execute(s->read()));
        var snapshot=new TransactionTemplate(tx);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try(var pool=Executors.newSingleThreadExecutor()){
            snapshot.execute(s->{var first=read();try{pool.submit(()->f.jdbc.update("UPDATE contents SET revision=revision+1 WHERE id='fictional-report'")).get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException(failure);}var second=read();assertEquals(first.revision(),second.revision());assertEquals(first.bodyJson(),second.bodyJson());return true;});
        }
        assertEquals(2,read().revision());assertEquals(1,read().bodyRevision());
    }
}
