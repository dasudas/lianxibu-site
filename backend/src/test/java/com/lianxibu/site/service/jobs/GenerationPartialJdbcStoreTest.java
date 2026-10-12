package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Actual original partial proof and P2 plan in owned databases; no external calls. */
class GenerationPartialJdbcStoreTest {
    NewsValuePartialPlanPreparationTest f;SourcePartitionPlanner.Plan plan;SourcePartitionPlanner.Chunk chunk;Prepare command;Frozen frozen;String action="fictional-partial-store-prepare";
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{
        f=new NewsValuePartialPlanPreparationTest(){@Override GenerationJdbcStoreTest database(){return GenerationPartialJdbcStoreTest.this.database();}};f.setup(true);plan=f.commit(f.build());chunk=plan.chunks().getFirst();var raw=CanonicalJson.parse(chunk.snapshotJson());var manifest=FrozenDependencyManifest.read(raw.get(FrozenDependencyManifest.FIELD));var evidence=new ArrayList<Evidence>();for(var member:raw.get("members"))evidence.add(new Evidence(member.get("id").asText(),"source_member",plan.scope().businessDate(),Instant.parse(member.get("completed_at").asText()),member.get("body_hash").asText(),JdbcFrozenInputs.canonical(member.get("body")),true,false,"accepted",List.of()));
        command=new Prepare(plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().businessDate(),f.f.f.f.p1.collection.facts().sourceId(),manifest.dependencies(),evidence,manifest.expectedIds(),false,plan.scope().settings().semanticHash(),FrozenApplicationContext.fromJson(raw.get(FrozenApplicationContext.FIELD)),List.of(),chunk.stagePart());frozen=value(raw);
    }
    Frozen value(JsonNode raw){String snapshot=JdbcFrozenInputs.canonical(raw),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,raw);return new Frozen(chunk.rootId(),plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().cutoff(),"partial",raw.get("missing").valueStream().map(n->n.asText()).toList(),snapshot,hash,new GenerationRequestBuilder().build(plan.stage(),snapshot,hash),plan.stage().priority(),GenerationFlowService.nextEligible(f.clock.instant()),"queued");}
    SourceDedupDependencyService dependency(){return new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service);}
    GenerationJdbcStore provider(SourceDedupDependencyService dependency,SourceDedupPartialDependencyPermitService.Captured permit){return new GenerationJdbcStore(f.db.source,f.clock,"fictional-owner",chunk.rootId(),command,null,null,dependency,permit);}
    Frozen save(GenerationJdbcStore store){return f.db.writes.execute(new SharedWriteExecutor.ActionContext(action,chunk.rootId(),frozen.inputHash()),key->store.committed(key),context->{store.save(frozen,action);return store.committed(action);});}
    @Test void ActualOpaqueStoreSavesAndReadonlyRestartKeepsExactOriginalWithoutCurrentTables()throws Exception{
        setup();var store=provider(dependency(),f.permit);int calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);assertEquals(frozen,save(store));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(1,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id=? AND call_count=0 AND input_state='ready'",Integer.class,chunk.rootId()));int count=f.db.count("1=1");f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");var replay=provider(dependency(),null);assertEquals(frozen,f.f.f.f.read(()->replay.committed(action)));assertEquals(count,f.db.count("1=1"));assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->replay.committed(action)));assertEquals(frozen,save(store));
    }
    @Test void DefaultsAndMissingPermitCannotBorrowReadonlyPartialAuthorityToWrite()throws Exception{
        setup();int count=f.db.count("1=1");var legacy=new GenerationJdbcStore(f.db.source,f.clock,"fictional-owner",chunk.rootId(),command);assertThrows(RuntimeException.class,()->save(legacy));assertThrows(RuntimeException.class,()->save(provider(dependency(),null)));assertThrows(RuntimeException.class,()->provider(dependency(),f.permit).save(frozen,action));assertThrows(RuntimeException.class,()->f.f.f.f.read(()->{provider(dependency(),f.permit).save(frozen,action);return true;}));assertEquals(count,f.db.count("1=1"));
    }
    @Test void NativeChangesAndARehashedDeclaredProfileCannotReplaceCapturedAuthority()throws Exception{
        setup();var store=provider(dependency(),f.permit);int count=f.db.count("1=1");String root=f.f.f.plan.chunks().getLast().rootId();long revision=f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,root);f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);assertThrows(RuntimeException.class,()->save(store));assertEquals(count,f.db.count("1=1"));f.db.jdbc.update("UPDATE jobs SET revision=? WHERE id=?",revision,root);
        var raw=(ObjectNode)CanonicalJson.parse(chunk.snapshotJson());var profile=(ObjectNode)raw.get(FrozenSourceDedupDependency.FIELD);var full=(ObjectNode)profile.get("stage_result");((ObjectNode)full.get("fragments").get(0).get("payload")).put("summary","Fictional declared replacement");((ObjectNode)profile.get("reports").get(0)).put("summary","Fictional declared replacement");((ObjectNode)profile.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full));assertDoesNotThrow(()->NewsValueInputProfile.complete(raw));var fake=value(raw);assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->{store.save(fake,action);return true;}));assertEquals(count,f.db.count("1=1"));assertEquals(frozen,save(store));
    }
    @Test void OuterRollbackLostAckAndCapacityRemainHonest()throws Exception{
        setup();var store=provider(dependency(),f.permit);int count=f.db.count("1=1");assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->{store.save(frozen,action);throw new IllegalStateException("fictional outer failure");}));assertEquals(count,f.db.count("1=1"));var capped=spy(f.f.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).verify(any(),any());assertThrows(GenerationFlowService.CapacityGuarded.class,()->save(provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,capped),f.permit)));assertEquals(count,f.db.count("1=1"));
        var fault=new DataSourceTransactionManager(f.db.source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional partial store ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.db.source),fault,()->25,n->{});assertEquals(frozen,writes.execute(new SharedWriteExecutor.ActionContext(action,chunk.rootId(),frozen.inputHash()),key->store.committed(key),context->{store.save(frozen,action);return store.committed(action);}));assertEquals(frozen,save(store));assertEquals(1,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id=?",Integer.class,JdbcFrozenInputs.stableId("prepare-action",action)));
    }
}
