package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ContentApplicationVersionTest {
    GeneratedContentServiceTest f;ContentReadService reads;
    GeneratedContentServiceTest fixture(){return new GeneratedContentServiceTest();}
    @BeforeEach void setup(){f=fixture();f.fixture();f.apply("fictional-header-create",f.command("fictional-report",null,GeneratedContentServiceTest.BODY));reads=new ContentReadService(new JdbcContentReads(f.source),new DataSourceTransactionManager(f.source),ContentReadService.denyUnverifiedReferences());}
    @Test void revisionOnlyChecksDoNotReturnLargeBodyOrUnrelatedReviewJson(){
        String large="{\"fictional\":\""+"X".repeat(1048576)+"\"}";f.jdbc.update("UPDATE contents SET body_json=?,review_dependency_stamp=?,blocking_summary_json=?,revision=7",large,large,large);
        var value=reads.applicationVersion("report","fictional-report").orElseThrow();assertEquals(7,value.revision());assertEquals(1,value.bodyRevision());assertNull(value.bodyJson());assertNotNull(value.bodyHash());
        assertThrows(RuntimeException.class,()->reads.comparisonVersion("report","fictional-report"));
    }
    @Test void compositeIdentityLegacyMetadataAndImmutableHashKeepTheirExistingMeaning(){
        var value=reads.applicationVersion("report","fictional-report").orElseThrow();byte[] hash=value.bodyHash();hash[0]^=1;assertNotEquals(hash[0],value.bodyHash()[0]);
        assertTrue(reads.applicationVersion("agent","fictional-report").isEmpty());assertTrue(reads.applicationVersion("report","fictional-absent").isEmpty());
        f.jdbc.update("UPDATE contents SET body_revision=NULL,input_job_id=NULL,body_hash_v1=NULL,content_key_json=NULL,revision=8");value=reads.applicationVersion("report","fictional-report").orElseThrow();assertEquals(8,value.bodyRevision());assertNull(value.bodyHash());assertNull(value.inputJobId());assertNull(value.bodyJson());assertFalse(reads.comparisonVersion("report","fictional-report").orElseThrow().proven());
    }
    @Test void nativeMetadataReadsJoinActualWriterWithoutChangingBodyOrRevision(){
        String body=f.jdbc.queryForObject("SELECT body_json FROM contents",String.class);var writer=new TransactionTemplate(new DataSourceTransactionManager(f.source));writer.execute(s->{assertEquals(1,reads.applicationVersion("report","fictional-report").orElseThrow().revision());return true;});assertEquals(body,f.jdbc.queryForObject("SELECT body_json FROM contents",String.class));assertEquals(1,f.jdbc.queryForObject("SELECT revision FROM contents",Long.class));
    }
}
