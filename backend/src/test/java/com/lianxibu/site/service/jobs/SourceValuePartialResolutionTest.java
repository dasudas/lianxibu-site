package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real original input and preparation; terminal outcomes are fictional native fault injection. */
class SourceValuePartialResolutionTest {
    SourcePartialStagePreparationTest f;SourceDedupPartialDependencyPermitTest authority;SourceAnalysisParentStepTest original;GenerationJdbcStoreTest db;SourceStageResolutionService service;SourceValueStageProofTest outcomes;FrozenSourceStagePlan.Reference ref;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourcePartialStagePreparationTest planFixture(){return new SourcePartialStagePreparationTest(){@Override GenerationJdbcStoreTest database(){return SourceValuePartialResolutionTest.this.database();}};}
    void setup(boolean mixed)throws Exception {
        f=planFixture();f.setup(mixed?201:1,mixed);authority=f.f.f;original=authority.f.f;db=f.f.db;ref=original.reference(f.f.intent);service=provider(new SourceDedupDependencyService(authority.observations,authority.proven,authority.service));outcomes=new SourceValueStageProofTest();outcomes.f=db;outcomes.plan=f.plan;outcomes.original=original.read(()->original.p1.universes.readImmutable(f.plan.reference()));outcomes.clock=f.f.clock;outcomes.pure=new SourceValueStageResultTest();
    }
    SourceStageResolutionService provider(SourceDedupDependencyService dependencies){var results=new JdbcSourceDedupResults(db.source);return new SourceStageResolutionService(new JdbcSourceStageRoots(db.source),f.f.plans,original.p1.universes,f.service,new JdbcFrozenInputs(db.source),results,new SourceDedupResultProofService(results,f.f.plans,original.p1.universes),new SourceValueResultProofService(results,f.f.plans,original.p1.universes,dependencies),dependencies);}
    SourceStageResolutionService.Snapshot read(){return original.read(()->service.read(ref));}
    // Resolution fixture: exact native store and receipt, with pure command preparation outside the writer.
    // SourceStagePreparationService's full 201-member writer performance is a separate invariant.
    Frozen prepare(int part){
        var chunk=f.plan.chunks().get(part);var raw=CanonicalJson.parse(chunk.snapshotJson());
        var manifest=FrozenDependencyManifest.read(raw.get(FrozenDependencyManifest.FIELD));var evidence=new ArrayList<Evidence>();
        for(var member:raw.get("members"))evidence.add(new Evidence(member.get("id").asText(),member.get("kind").asText(),f.plan.scope().businessDate(),Instant.parse(member.get("completed_at").asText()),member.get("body_hash").asText(),JdbcFrozenInputs.canonical(member.get("body")),true,false,"accepted",member.get("references").valueStream().map(n->n.asText()).toList()));
        var command=new Prepare(f.plan.logicalKey(),f.plan.generation(),f.plan.stage(),f.plan.scope().businessDate(),original.p1.collection.facts().sourceId(),manifest.dependencies(),evidence,manifest.expectedIds(),false,f.plan.scope().settings().semanticHash(),FrozenApplicationContext.fromJson(raw.get(FrozenApplicationContext.FIELD)),List.of(),chunk.stagePart());
        var value=new Frozen(chunk.rootId(),f.plan.logicalKey(),f.plan.generation(),f.plan.stage(),f.plan.scope().cutoff(),raw.get("coverage_state").asText(),raw.get("missing").valueStream().map(n->n.asText()).toList(),chunk.snapshotJson(),chunk.inputHash(),chunk.requestJson(),f.plan.stage().priority(),GenerationFlowService.nextEligible(f.f.clock.instant()),"queued");
        var store=new GenerationJdbcStore(db.source,f.f.clock,"fictional-owner",chunk.rootId(),command,null,null,new SourceDedupDependencyService(authority.observations,authority.proven,authority.service),f.f.permit);String action=f.action(part);
        return db.writes.execute(new SharedWriteExecutor.ActionContext(action,chunk.rootId(),chunk.inputHash()),key->store.committed(key),context->{store.save(value,action);return store.committed(action);});
    }
    @Test void MissingPendingAppliedAndFailedReferOnlyToOriginalPartialSourceParts()throws Exception {
        setup(true);int count=db.count("1=1");var missing=assertDoesNotThrow(this::read);assertTrue(f.plan.chunks().size()>1);assertFalse(missing.settled());assertTrue(missing.fragments().stream().allMatch(p->p.state()==SourceStageResolutionService.State.MISSING));assertEquals(count,db.count("1=1"));prepare(0);var pending=read();assertEquals(SourceStageResolutionService.State.PENDING,pending.fragments().getFirst().state());assertFalse(pending.settled());for(int n=1;n<f.plan.chunks().size();n++)prepare(n);outcomes.complete(0);for(int n=1;n<f.plan.chunks().size();n++)db.jdbc.update("UPDATE jobs SET state='failed',call_count=3,revision=revision+1 WHERE id=?",f.plan.chunks().get(n).rootId());var settled=read();assertTrue(settled.settled());assertTrue(settled.hasFailures());assertNotNull(settled.fragments().getFirst().value());assertEquals(SourceStageResolutionService.State.APPLIED,settled.fragments().getFirst().state());assertTrue(settled.fragments().subList(1,settled.fragments().size()).stream().allMatch(p->p.state()==SourceStageResolutionService.State.FAILED));assertEquals(201,settled.plan().decisions().size());assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolved(settled));assertThrows(UnsupportedOperationException.class,()->settled.fragments().clear());
    }
    @Test void ZeroP2StillProvesOriginalFailedP1AndDoesNotPretendAnEmptyCollection()throws Exception {
        setup(false);int count=db.count("1=1");var zero=assertDoesNotThrow(this::read);assertTrue(zero.settled());assertFalse(zero.hasFailures());assertTrue(zero.fragments().isEmpty());assertEquals(1,zero.universe().accepted());assertEquals(1,zero.plan().decisions().size());assertEquals(1,CanonicalJson.parse(zero.plan().scope().stageContextJson()).get(FrozenSourceDedupDependency.FIELD).get("stage_result").get("unapplied_member_count").asInt());assertEquals(count,db.count("1=1"));service=provider(new SourceDedupDependencyService(authority.observations));assertThrows(RuntimeException.class,this::read);service=provider(new SourceDedupDependencyService(authority.observations,authority.proven,authority.service));db.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(authority.f.ref.ownerId()));assertThrows(RuntimeException.class,this::read);
    }
    @Test void OriginalAppliedAndFailedProofsRestartWithoutCurrentMemberRows()throws Exception {
        setup(true);for(int n=0;n<f.plan.chunks().size();n++)prepare(n);outcomes.complete(0);for(int n=1;n<f.plan.chunks().size();n++)db.jdbc.update("UPDATE jobs SET state='cancelled',revision=revision+1 WHERE id=?",f.plan.chunks().get(n).rootId());var first=read();int count=db.count("1=1");db.jdbc.execute("DROP TABLE source_run_items");db.jdbc.execute("DROP TABLE analyzed_entities");service=provider(new SourceDedupDependencyService(authority.observations,authority.proven,authority.service));var restarted=read();assertEquals(first.plan(),restarted.plan());assertEquals(first.fragments().stream().map(SourceStageResolutionService.Fragment::nativeRoot).toList(),restarted.fragments().stream().map(SourceStageResolutionService.Fragment::nativeRoot).toList());assertEquals(first.fragments().getFirst().value().proof().handoffHash(),restarted.fragments().getFirst().value().proof().handoffHash());assertEquals(count,db.count("1=1"));
    }
    @Test void TransactionsMissingPrepareReceiptAndDamagedOriginalInputCannotSettle()throws Exception {
        setup(true);assertThrows(RuntimeException.class,()->service.read(ref));assertThrows(RuntimeException.class,()->original.p1.tx(()->service.read(ref)));prepare(0);db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenInputs.stableId("prepare-action",f.action(0)));assertThrows(RuntimeException.class,this::read);
        setup(true);prepare(0);db.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input'",new byte[32],f.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,this::read);
    }
}
