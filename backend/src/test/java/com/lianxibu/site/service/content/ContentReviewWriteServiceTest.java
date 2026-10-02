package com.lianxibu.site.service.content;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.util.*;
import org.junit.jupiter.api.*;

class ContentReviewWriteServiceTest {
 GeneratedContentServiceTest fixture;ContentReviewWriteService review;
 @BeforeEach void setup(){fixture=new GeneratedContentServiceTest();fixture.fixture();install();}
 void install(){review=new ContentReviewWriteService(new JdbcReviewPublication(fixture.source),fixture.service,fixture.clock);fixture.apply("fictional-initial",fixture.command("fictional-report",null,GeneratedContentServiceTest.BODY));}
 void write(String key,Runnable work){fixture.writes.execute(new ActionContext(key,"fictional-report","a".repeat(64)),k->null,a->{work.run();return true;});}
 JdbcContentReads.Row row(){return new JdbcContentReads(fixture.source).current("report","fictional-report");}
 @Test void approvalAndPublicationOnlyAdvanceManagementVersionEditAdvancesBothAndClearsQualification(){
  fixture.jdbc.update("UPDATE contents SET review_hash=?",new byte[32]);var original=row();
  write("fictional-approve",()->review.approve(review.lock("report","fictional-report"),new ReviewPublicationService.Stamp("[]","a".repeat(64)),"fictional-stamp",new byte[32]));assertEquals(2,row().revision());assertEquals(1,row().bodyRevision());assertEquals("approved",row().state());assertNull(row().publishedAt());assertArrayEquals(original.bodyHashV1(),row().reviewHashV1());
  write("fictional-publish",()->review.publish(review.lock("report","fictional-report")));assertEquals("published",row().state());assertEquals(3,row().revision());assertEquals(1,row().bodyRevision());assertNotNull(row().publishedAt());
  String body=GeneratedContentServiceTest.BODY.replace("Fictional text","Fictional edited");write("fictional-edit",()->review.edit(review.lock("report","fictional-report"),body,hash(body)));assertEquals(4,row().revision());assertEquals(2,row().bodyRevision());assertEquals("draft",row().state());assertNull(row().reviewHashV1());assertNull(row().reviewStampHash());assertNull(row().reviewStampId());assertNull(row().reviewedInputHash());assertNull(row().publishedAt());assertEquals(original.naturalKey(),row().naturalKey());assertArrayEquals(new byte[32],fixture.jdbc.queryForObject("SELECT review_hash FROM contents",byte[].class));
 }
 @Test void staleCasWrongPeriodAndMalformedBodyNeverChangeContent(){var old=row();write("fictional-approve",()->review.approve(review.lock("report","fictional-report"),new ReviewPublicationService.Stamp("[]","a".repeat(64)),"fictional-stamp",new byte[32]));assertThrows(RuntimeException.class,()->write("fictional-stale",()->review.approve(old,new ReviewPublicationService.Stamp("[]","b".repeat(64)),"fictional-other-stamp",new byte[32])));String bad=GeneratedContentServiceTest.BODY.replace("daily","unknown");assertThrows(IllegalArgumentException.class,()->write("fictional-invalid",()->review.edit(review.lock("report","fictional-report"),bad,hash(bad))));String wrong=GeneratedContentServiceTest.BODY.replace("2025-12-31T16","2025-12-30T16");assertThrows(ReviewPublicationService.Conflict.class,()->write("fictional-period",()->review.edit(review.lock("report","fictional-report"),wrong,hash(wrong))));assertEquals(2,row().revision());assertEquals(old.bodyJson(),row().bodyJson());assertThrows(IllegalStateException.class,()->review.lock("report","fictional-report"));}
 @Test void outerTransactionFailureRollsBackReviewAndCompositeIdentityRemainsExact(){assertThrows(RuntimeException.class,()->write("fictional-rollback",()->{review.approve(review.lock("report","fictional-report"),new ReviewPublicationService.Stamp("[]","a".repeat(64)),"fictional-stamp",new byte[32]);throw new IllegalStateException("fictional receipt failure");}));assertEquals(1,row().revision());assertNull(row().reviewStampHash());write("fictional-other-kind",()->assertNull(review.lock("agent","fictional-report")));}
 static byte[] hash(String body){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(body)));}
}
