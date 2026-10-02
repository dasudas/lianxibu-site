package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.VersionValidityService.*;
import java.util.HexFormat;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Each seed uses real archived bodies, permanent validity receipts and review stamps. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ReviewValidityCompetitionMySqlTest {
 @Test void hundredDualBarrierOrdersPreserveNewReviewAndRejectOldQualification() throws Exception {
  for(int seed=0;seed<100;seed++){try(var f=new PooledValidityMySqlFixture()){
   f.setup();f.publish();var row=f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow();
   var old=new Dependent(f.base.content(),row.revision(),row.inputJobId(),HexFormat.of().formatHex(row.reviewStampHash()));
   var publicReads=new ContentReadService(new JdbcContentReads(f.fixture.source),f.fixture.tx,new ReviewPublicationService(f.fixture.writes,new ReviewPublicationJdbcStore(null,f.base.writer,f.base.contents,f.audit,f.base.versions,f.base.ledger)));
   assertTrue(publicReads.published("report","fictional-report").isPresent());
   var invalid=f.invalidate(f.base.article(),0,"fictional-invalid");hidden(publicReads);
   f.service.change(f.base.command(f.base.article(),1,Action.REVERSE_INVALIDATION,invalid.dispositionId()),f.fixture.action("fictional-reverse"));hidden(publicReads);
   var firstRead=new CountDownLatch(2);var firstWrite=new CountDownLatch(1);boolean oldFirst=seed%2==0;var provider=f.store;
   try(var pool=Executors.newFixedThreadPool(2)){
    var oldBatch=pool.submit(()->{
     assertEquals(row.bodyRevision(),f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().bodyRevision());firstRead.countDown();await(firstRead);
     if(!oldFirst)await(firstWrite);
     boolean applied=f.fixture.writes.execute(f.fixture.action("fictional-stale-batch"),k->null,a->provider.blockIfCurrent(old,invalid.dispositionId()));
     if(oldFirst)firstWrite.countDown();return applied;
    });
    var newReview=pool.submit(()->{
     assertEquals(row.bodyRevision(),f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().bodyRevision());firstRead.countDown();await(firstRead);
     if(oldFirst)await(firstWrite);
     f.review("report","fictional-report","approve","fictional-new-approve");
     if(!oldFirst)firstWrite.countDown();f.review("report","fictional-report","publish","fictional-new-publish");return true;
    });
    assertTrue(newReview.get(15,TimeUnit.SECONDS));assertEquals(oldFirst,oldBatch.get(15,TimeUnit.SECONDS),"old captured stamp seed "+seed);
   }catch(Exception e){throw new AssertionError("review validity seed "+seed,e);}
   assertEquals(0,firstRead.getCount());assertEquals(0,firstWrite.getCount());
   var current=f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow();assertEquals(1,current.bodyRevision());assertEquals("published",current.state());
   assertTrue(publicReads.published("report","fictional-report").isPresent());assertEquals(1,publicReads.publishedPage("report",1,20).total());
   long revision=current.revision();f.drain("fictional-late-work-");assertEquals(revision,f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().revision());
   var one=f.invalidate(f.base.article(),2,"fictional-second-invalidation");var two=f.invalidate(f.base.article(),3,"fictional-third-invalidation");hidden(publicReads);
   f.service.change(f.base.command(f.base.article(),4,Action.REVERSE_INVALIDATION,one.dispositionId()),f.fixture.action("fictional-partial-reverse"));hidden(publicReads);
   assertEquals(java.util.Set.of(two.dispositionId()),f.base.ledger.validity(f.base.article()).activeDispositionIds());
   f.service.change(f.base.command(f.base.article(),5,Action.REVERSE_INVALIDATION,two.dispositionId()),f.fixture.action("fictional-final-reverse"));hidden(publicReads);
   f.review("report","fictional-report","approve","fictional-final-approve");f.review("report","fictional-report","publish","fictional-final-publish");
   f.drain("fictional-final-work-");assertTrue(publicReads.published("report","fictional-report").isPresent());assertEquals(1,f.fixture.sends);}
  }
 }
 private static void hidden(ContentReadService reads){assertTrue(reads.published("report","fictional-report").isEmpty());assertEquals(0,reads.publishedPage("report",1,20).total());assertEquals(0,reads.publishedPage(null,1,50).total());}
 private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("fictional review barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
