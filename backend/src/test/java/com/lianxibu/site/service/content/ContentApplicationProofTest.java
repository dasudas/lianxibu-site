package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ContentApplicationProofTest extends ContentComparisonVersionTest {
    ContentReadService.ComparisonVersion proven(){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-native-application-proof","fictional-report",null),key->null,c->reads.provenApplicationVersion("report","fictional-report").orElseThrow());}
    @Test void actualSharedWriterUsesNativeBodyVersionAndImmutableProofWithoutChangingContent(){
        f.jdbc.update("UPDATE contents SET revision=8");String original=f.jdbc.queryForObject("SELECT body_json FROM contents",String.class);var result=proven();assertTrue(result.proven());assertEquals(8,result.revision());assertEquals(1,result.bodyRevision());assertEquals("fictional-input",result.inputJobId());assertEquals(original,result.bodyJson());byte[] hash=result.bodyHash();hash[0]^=1;assertNotEquals(hash[0],result.bodyHash()[0]);assertEquals(8,read().revision());
        assertTrue(f.writes.<Boolean>execute(new SharedWriteExecutor.ActionContext("fictional-missing-native-application","fictional-missing",null),key->null,c->reads.provenApplicationVersion("report","fictional-missing").isEmpty()));
    }
    @Test void strictProviderRefusesOutsideReadonlyAndWrongWriterIsolation(){
        assertThrows(RuntimeException.class,()->reads.provenApplicationVersion("report","fictional-report"));var tx=new DataSourceTransactionManager(f.source);var ro=new TransactionTemplate(tx);ro.setReadOnly(true);ro.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertThrows(RuntimeException.class,()->ro.execute(s->reads.provenApplicationVersion("report","fictional-report")));var wrong=new TransactionTemplate(tx);wrong.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertThrows(RuntimeException.class,()->wrong.execute(s->reads.provenApplicationVersion("report","fictional-report")));assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-read-route-in-writer","fictional-report",null),key->null,c->read()));
    }
    @Test void legacyMetadataCannotGrantManualProofAndDamageOrCapacityStillRefuses(){
        f.jdbc.update("UPDATE contents SET revision=9,body_revision=NULL,content_key_json=NULL");assertFalse(read().proven());assertEquals(9,reads.administrativeVersion("report","fictional-report").orElseThrow().bodyRevision());assertThrows(RuntimeException.class,this::proven);
        setup();f.jdbc.update("UPDATE contents SET body_hash_v1=?",new byte[32]);assertThrows(RuntimeException.class,this::proven);
        setup();f.jdbc.update("UPDATE contents SET input_job_id='fictional-wrong-input'");assertThrows(RuntimeException.class,this::proven);
        setup();f.jdbc.update("UPDATE contents SET content_key_json=REPLACE(content_key_json,'fictional-report','fictional-wrong-content')");assertThrows(RuntimeException.class,this::proven);
        setup();f.jdbc.update("UPDATE contents SET body_json=?","{\"fictional\":\""+"X".repeat(262145)+"\"}");assertThrows(RuntimeException.class,this::proven);
    }
    @Test void failedNativeProofJoinsTheOriginalWriterAndRollsBackItsTentativeChanges(){
        var before=read();assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-proof-original-rollback","fictional-report",null),key->null,c->{f.jdbc.update("UPDATE contents SET revision=revision+1,body_revision=NULL");return reads.provenApplicationVersion("report","fictional-report").orElseThrow();}));var after=read();assertEquals(before.revision(),after.revision());assertEquals(before.bodyRevision(),after.bodyRevision());assertTrue(after.proven());assertEquals(before.bodyJson(),after.bodyJson());
    }
}
