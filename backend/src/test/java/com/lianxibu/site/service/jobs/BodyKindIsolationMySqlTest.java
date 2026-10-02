package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.VersionValidityService.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

/** Only the explicitly guarded loopback database is rebuilt, using inline fictional DDL. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class BodyKindIsolationMySqlTest {
 @Test void hundredOrdersKeepReplacementBodyAndExactKindDependentsSeparate() throws Exception {
  for(int seed=0;seed<100;seed++){try(var f=new PooledValidityMySqlFixture()){
   f.setup();f.publish();var old=f.base.content();
   assertEquals(1,old.path("revision").asLong());assertEquals(3,f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().revision());
   f.restart(new VersionValidityJdbcStore.DisplayCommand(old,3,"Fictional display withdrawal"));f.service.displayAdjustment(old,f.fixture.action("fictional-withdraw"));
   assertEquals(4,f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().revision());assertEquals(1,f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().bodyRevision());
   assertTrue(f.base.ledger.validity(old).effectiveValid());f.restart(null);
   f.seed("old-body",List.of(old));publish(f,"agent","fictional-agent-old-body","fictional-old-child");
   var reads=new ContentReadService(new JdbcContentReads(f.fixture.source),f.fixture.tx,new ReviewPublicationService(f.fixture.writes,new ReviewPublicationJdbcStore(null,f.base.writer,f.base.contents,f.audit,f.base.versions,f.base.ledger)));
   assertTrue(reads.published("agent","fictional-agent-old-body").isPresent());
   var bothReads=new CountDownLatch(2);var firstWrite=new CountDownLatch(1);boolean invalidFirst=seed%2==0;
   try(var pool=Executors.newFixedThreadPool(2)){
    var invalid=pool.submit(()->{f.base.versions.find(ContentVersionKey.fromJson(old)).orElseThrow();bothReads.countDown();await(bothReads);if(!invalidFirst)await(firstWrite);var result=f.invalidate(old,0,"fictional-old-body-invalid");if(invalidFirst)firstWrite.countDown();return result;});
    var edit=pool.submit(()->{assertEquals(1,f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().bodyRevision());bothReads.countDown();await(bothReads);if(invalidFirst)await(firstWrite);
     var row=f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow();String body=row.bodyJson().replace("Fictional text","Fictional replacement");
     var provider=new ReviewPublicationJdbcStore(new ReviewPublicationJdbcStore.Command("edit","report","fictional-report",row.revision(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(body)),"Fictional replacement body"),f.base.writer,f.base.contents,f.audit,f.base.versions,f.base.ledger);
     new ReviewPublicationService(f.fixture.writes,provider).edit("report","fictional-report",row.revision(),body,f.fixture.action("fictional-edit"));publish(f,"report","fictional-report","fictional-body-two");if(!invalidFirst)firstWrite.countDown();return true;
    });
    assertTrue(edit.get(15,TimeUnit.SECONDS));assertFalse(invalid.get(15,TimeUnit.SECONDS).effectiveValid());
   }catch(Exception e){throw new AssertionError("body identity seed "+seed,e);}
   assertEquals(0,bothReads.getCount());assertEquals(0,firstWrite.getCount());var latest=f.base.content();assertEquals(2,latest.path("revision").asLong());
   f.seed("new-body",List.of(latest));publish(f,"agent","fictional-agent-new-body","fictional-new-child");long latestRevision=f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().revision();f.drain("fictional-body-step-");
   assertTrue(reads.published("report","fictional-report").isPresent());assertEquals(latestRevision,f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().revision());assertTrue(reads.published("agent","fictional-agent-old-body").isEmpty());assertTrue(reads.published("agent","fictional-agent-new-body").isPresent());
   var agent=f.seed("same-agent",Stage.AGENT_ANALYSIS,"fictional-shared-id",List.of(f.base.article()));var model=f.seed("same-model",Stage.MODEL_ANALYSIS,"fictional-shared-id",List.of(f.base.article()));
   assertEquals(agent.path("content_id"),model.path("content_id"));assertEquals(agent.path("revision"),model.path("revision"));assertNotEquals(VersionValidityService.canonicalTarget(agent),VersionValidityService.canonicalTarget(model));
   publish(f,"agent","fictional-shared-id","fictional-agent-source");publish(f,"model","fictional-shared-id","fictional-model-source");
   f.seed("agent-child",List.of(agent));f.seed("model-child",List.of(model));publish(f,"agent","fictional-agent-agent-child","fictional-agent-child");publish(f,"agent","fictional-agent-model-child","fictional-model-child");
   var target=seed%2==0?agent:model;String blocked=seed%2==0?"agent":"model",kept=seed%2==0?"model":"agent";var disposition=f.invalidate(target,0,"fictional-specific-kind");f.drain("fictional-kind-step-");
   assertTrue(reads.published(blocked,"fictional-shared-id").isEmpty());assertTrue(reads.published(kept,"fictional-shared-id").isPresent());assertTrue(reads.published("agent","fictional-agent-"+blocked+"-child").isEmpty());assertTrue(reads.published("agent","fictional-agent-"+kept+"-child").isPresent());
   f.service.change(f.base.command(target,1,Action.REVERSE_INVALIDATION,disposition.dispositionId()),f.fixture.action("fictional-specific-reverse"));assertTrue(reads.published(blocked,"fictional-shared-id").isEmpty());publish(f,blocked,"fictional-shared-id","fictional-specific-review");assertTrue(reads.published(blocked,"fictional-shared-id").isPresent());assertEquals(1,f.fixture.sends);
   var legacy=target.deepCopy();((tools.jackson.databind.node.ObjectNode)legacy).putNull("input_job_id");assertFalse(f.base.ledger.exactVersionExists(legacy));assertThrows(Rejected.class,()->f.invalidate(legacy,0,"fictional-unproven-legacy"));}
  }
 }
 private static void publish(VersionValidityJdbcStoreTest f,String kind,String id,String key){f.review(kind,id,"approve",key+"-approve");f.review(kind,id,"publish",key+"-publish");}
 private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("fictional body barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
