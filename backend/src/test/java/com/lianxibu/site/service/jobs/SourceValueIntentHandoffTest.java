package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual collection and retained fictional P1; no external provider calls. */
class SourceValueIntentHandoffTest {
    NewsValueSourcePlanPreparationTest f;FrozenSourceStagePlan.Reference ref;SourceDedupStageProofService proofs;SourceDedupStageAvailabilityService observer;
    SourceValueIntentReceiptService metadata;JdbcSourceValueIntents nativeStore;SourceValueIntentService service;boolean allowed=true;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueIntentHandoffTest.this.database();}};f.setup();f.f.jdbc.update("DELETE FROM jobs WHERE id=?",f.intent);
        ref=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(f.profile.get("stage_result").get("plan_ref")));install();
    }
    void install(){proofs=new SourceDedupStageProofService(f.p1.plans,f.p1.universes,new SourceDedupResultProofService(new JdbcSourceDedupResults(f.f.source),f.p1.plans,f.p1.universes));var ledger=new JdbcSourceStageAvailability(f.f.source);observer=new SourceDedupStageAvailabilityService(proofs,ledger,f.f.writes,new DataSourceTransactionManager(f.f.source),f.clock);nativeStore=new JdbcSourceValueIntents(f.f.source);metadata=new SourceValueIntentReceiptService(nativeStore,ledger);service=provider(f.f.writes,f.clock);}
    SourceValueIntentService provider(SharedWriteExecutor writes,Clock clock){return new SourceValueIntentService(proofs,observer,f.p1.plans,f.p1.universes,metadata,f.p1.collections,nativeStore,sourceLinks(),writes,new DataSourceTransactionManager(f.f.source),clock,()->allowed);}
    SourceValueIntentService.SourceLinks sourceLinks(){var nativeLinks=new JdbcSourceValueIntentLinks(f.f.source);return new SourceValueIntentService.SourceLinks(){
        public SourceValueIntentService.Header read(String run){return header(nativeLinks.read(run));}
        public SourceValueIntentService.Header locked(String run){return header(nativeLinks.locked(run));}
        public long link(String run,String p1,String p2){return nativeLinks.link(run,p1,p2);}
        SourceValueIntentService.Header header(JdbcSourceValueIntentLinks.Header h){return new SourceValueIntentService.Header(h.runId(),h.sourceId(),h.sourceIdentity(),h.category(),h.kind(),h.day(),h.generation(),h.manifestHash(),h.expected(),h.accepted(),h.excluded(),h.pending(),h.activation(),h.delivery(),h.revision(),h.refs());}
    };}
    SourceValueIntentReceiptService.Handoff read(){return f.readonly(()->service.readCommitted(ref));}
    int children(){return f.f.count("record_kind='generation_intent' AND task_type='news_value'");}
    int relations(){return f.f.count("target_type='source_value_intent'");}
    String state(){return f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,ref.ownerId());}
    long sourceRevision(){return f.f.jdbc.queryForObject("SELECT revision FROM source_runs WHERE id='fictional-run'",Long.class);}
    @Test void actualOriginalP1AndCollectionCompleteOneDurableChildAndSourceReference(){
        assertNull(read());int roots=f.f.count("record_kind IN ('logical_task','ai_attempt')");long revision=sourceRevision();var value=service.handoff(ref);assertEquals(value.json(),read().json());assertEquals("succeeded",state());assertEquals(1,children());assertEquals(1,relations());assertEquals(revision+1,sourceRevision());assertEquals(roots,f.f.count("record_kind IN ('logical_task','ai_attempt')"));
        assertTrue(f.readonly(()->new JdbcSourceValueIntentLinks(f.f.source).read("fictional-run")).refs().contains(value.p2Owner()));int count=f.f.count("1=1");allowed=false;service=provider(f.f.writes,Clock.fixed(f.clock.instant().plusSeconds(100),ZoneOffset.UTC));assertEquals(value.json(),service.handoff(ref).json());assertEquals(count,f.f.count("1=1"));assertEquals(revision+1,sourceRevision());
    }
    @Test void onlyMetadataIsIncompleteButCanResumeItsOriginalChildWithoutAllocatingAnother(){
        var proof=f.readonly(()->proofs.read(ref));var plan=f.readonly(()->f.p1.plans.readImmutable(ref));var universe=f.readonly(()->f.p1.universes.readImmutable(plan.reference()));Instant observed=FrozenSourceDedupDependency.read(f.profile,f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)).observedAt();var reserved=SourceValueIntentReceiptService.Handoff.create(proof,universe,observed,1,f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        f.p1.tx(()->metadata.reserve(reserved));assertNull(read());assertEquals("queued",state());assertEquals(reserved.json(),service.handoff(ref).json());assertEquals(reserved.json(),read().json());assertEquals(1,children());assertEquals(1,relations());
    }
    @Test void failureInSourceLinkOrParentCompletionRollsBackEveryNewBusinessWrite(){
        long revision=sourceRevision();f.f.jdbc.execute("ALTER TABLE source_runs ADD CONSTRAINT fictional_parent_source_failure CHECK(revision<="+revision+")");assertThrows(RuntimeException.class,()->service.handoff(ref));assertNull(read());assertEquals(0,children());assertEquals(0,relations());assertEquals("queued",state());assertEquals(revision,sourceRevision());drop("source_runs","fictional_parent_source_failure");
        f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_parent_state_failure CHECK(id<>'"+ref.ownerId()+"' OR state<>'succeeded')");assertThrows(RuntimeException.class,()->service.handoff(ref));assertEquals(0,children());assertEquals(0,relations());assertEquals("queued",state());assertEquals(revision,sourceRevision());drop("jobs","fictional_parent_state_failure");assertNotNull(service.handoff(ref));
    }
    void drop(String table,String name){f.f.jdbc.execute("ALTER TABLE "+table+(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?" DROP CONSTRAINT ":" DROP CHECK ")+name);}
    @Test void actualCommitAckLossRecoversTheCompleteOriginalBusinessRelationship(){
        long revision=sourceRevision();var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional complete parent ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var value=provider(writes,f.clock).handoff(ref);assertEquals(value.json(),read().json());assertEquals(value.json(),service.handoff(ref).json());assertEquals(revision+1,sourceRevision());assertEquals(1,children());assertEquals(1,relations());
    }
    @Test void gatesCancellationAndBrokenNativeCollectionCannotCreateAChild(){
        allowed=false;assertThrows(RuntimeException.class,()->service.handoff(ref));assertEquals(0,children());allowed=true;f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",ref.ownerId());assertThrows(RuntimeException.class,()->service.handoff(ref));assertEquals(0,children());f.f.jdbc.update("UPDATE jobs SET state='waiting_input' WHERE id=?",ref.ownerId());
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.p1.collection.dispositionId());assertThrows(RuntimeException.class,()->service.handoff(ref));assertEquals(0,children());assertEquals(0,relations());assertThrows(RuntimeException.class,()->f.p1.tx(()->service.handoff(ref)));
    }
    @Test void anActualEmptyOriginalCollectionEndsP1WithoutCreatingAnyAiRoot(){
        f.f.jdbc.update("DELETE FROM jobs");f.f.jdbc.update("DELETE FROM source_run_items WHERE run_id='fictional-run'");f.f.jdbc.update("DELETE FROM request_receipts WHERE run_id='fictional-run'");
        String manifest=com.lianxibu.site.service.sources.RunManifestService.manifestHash("fictional-run",0,List.of());var result=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("run_id","fictional-run").put("manifest_hash",manifest).put("part_count",0).put("expected_count",0).put("fetch_outcome","succeeded");result.putObject("coverage");
        f.f.jdbc.update("UPDATE source_runs SET expected_count=0,accepted_count=0,excluded_count=0,pending_count=0,part_count=0,manifest_hash=?,result_json=?,delivery_state='empty_success',fetch_state='succeeded',job_refs_json='[]' WHERE id='fictional-run'",HexFormat.of().parseHex(manifest),JdbcFrozenInputs.canonical(result));
        var sourceHandoff=new CollectionHandoffService(new JdbcCollectionHandoff(f.f.source),new CollectionIntentService(f.f.source,f.p1.f.clock)::ensure);f.p1.tx(()->{sourceHandoff.terminal("fictional-run");return true;});f.p1.collection=f.p1.tx(()->f.p1.collections.read("fictional-run"));var plan=f.p1.prepare();ref=FrozenSourceStagePlan.encode(plan,f.p1.tx(()->f.p1.universes.read(plan.reference()))).reference();f.clock=Clock.fixed(f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(1),ZoneOffset.UTC);install();
        assertNull(read());var value=service.handoff(ref);assertEquals(0,value.stageParts());assertEquals("no_input",state());assertEquals(value.json(),read().json());assertEquals(1,children());assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
}
