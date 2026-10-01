package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.JdbcVersionDispositionReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.VersionValidityService;
import java.time.Instant;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class VersionDispositionReadServiceTest {
 private JdbcTemplate jdbc;private VersionDispositionReadService reads;private VersionValidityService.Store proof;private final Actor owner=new Actor("fictional-owner","owner");
 private final String target="{\"kind\":\"content_version\",\"content_kind\":\"report\",\"content_id\":\"fictional-content\",\"revision\":1,\"body_hash\":\""+"a".repeat(64)+"\",\"input_job_id\":null}";
 @BeforeEach void setup(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:fictional-version-dispositions-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,record_kind VARCHAR(32),target_type VARCHAR(32),target_key_json CLOB,target_key_hash VARBINARY(32),action_payload CLOB,action_payload_hash VARBINARY(32),reason VARCHAR(2048),created_at TIMESTAMP)");proof=mock(VersionValidityService.Store.class);when(proof.exactVersionExists(any())).thenReturn(true);when(proof.validity(any())).thenReturn(new VersionValidityService.Validity(4,false,Set.of("fictional-second","fictional-third")));row("fictional-first",1,null);row("fictional-second",2,null);row("fictional-reversal",3,"fictional-first");row("fictional-third",4,null);reads=new VersionDispositionReadService(new JdbcVersionDispositionReads(source),proof,new DataSourceTransactionManager(source));}
 @Test void completeChainFoldsBeforeCountAndPaginationAndDoesNotExposePrivatePayload(){var page=reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,1,1);assertEquals(2,page.total());assertEquals(4,page.validityRevision());assertFalse(page.effectiveValid());assertEquals("fictional-second",page.items().getFirst().id());assertEquals(1,page.target().revision());assertNull(page.target().inputJobId());assertEquals("fictional-third",reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,2,1).items().getFirst().id());assertTrue(reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,9,1).items().isEmpty());}
 @Test void sameIdOtherKindVersionOrSnapshotNeverBorrowDispositionsAndDeniedActorsNeverRead(){when(proof.validity(any())).thenReturn(new VersionValidityService.Validity(0,true,Set.of()));assertEquals(0,reads.list(owner,"model","fictional-content",1,"a".repeat(64),null,1,20).total());assertEquals(0,reads.list(owner,"report","fictional-content",2,"a".repeat(64),null,1,20).total());assertEquals(0,reads.list(owner,"report","fictional-content",1,"a".repeat(64),"fictional-job",1,20).total());reset(proof);jdbc.execute("DROP TABLE jobs");assertThrows(VersionDispositionReadService.Denied.class,()->reads.list(new Actor("fictional-user","user"),"report","fictional-content",1,"a".repeat(64),null,1,20));verifyNoInteractions(proof);}
 @Test void missingRowsDuplicateJsonAndProofMismatchFailClosedRatherThanInventingValidity(){jdbc.update("DELETE FROM jobs WHERE id=?","fictional-second");assertThrows(IllegalStateException.class,()->reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,1,20));row("fictional-second",2,null);jdbc.update("UPDATE jobs SET action_payload=? WHERE id=?","{\"schema_version\":\"content-validity-v1\",\"schema_version\":\"unknown\"}","fictional-third");assertThrows(IllegalStateException.class,()->reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,1,20));when(proof.exactVersionExists(any())).thenReturn(false);assertThrows(VersionDispositionReadService.Missing.class,()->reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,1,20));}
 @Test void malformedAuthoritativeProofIsUnavailableRatherThanBadQuery(){when(proof.validity(any())).thenThrow(new IllegalArgumentException("fictional corrupt proof"));assertThrows(IllegalStateException.class,()->reads.list(owner,"report","fictional-content",1,"a".repeat(64),null,1,20));}
 private void row(String id,long revision,String reversed){String payload="{\"schema_version\":\"content-validity-v1\",\"action\":\""+(reversed==null?"invalidate_conclusion":"reverse_invalidation")+"\",\"validity_revision\":"+revision+",\"invalidation_id\":"+(reversed==null?"null":"\""+reversed+"\"")+"}";jdbc.update("INSERT INTO jobs VALUES(?, 'disposition', 'content_version', ?, ?, ?, ?, ?, ?)",id,target,hash(target),payload,hash(payload),"Fictional conclusion reason",Timestamp.from(Instant.parse("2026-01-01T00:00:0"+revision+"Z")));}
 private static byte[] hash(String value){try{return MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(CanonicalJson.parse(value)));}catch(Exception error){throw new IllegalStateException(error);}}
}
