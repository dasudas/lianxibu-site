package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class DispatchSourcePlanTest {
    FrozenSourceStagePlanServiceTest f;DispatchReadinessService readiness;SourceMemberEvidenceService members;
    FrozenSourceStagePlanServiceTest fixture(){return new FrozenSourceStagePlanServiceTest();}
    @BeforeEach void setup() {
        f=fixture();f.setup();var records=new JdbcJobRecords(f.fixture.source);
        f.fixture.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",f.universeRef.ownerId());
        f.plan=new SourcePartitionPlanner().plan(Stage.NEWS_VALUE,SourcePartitionPlannerTest.KEY,1,f.original,f.universeRef,
                SourcePartitionPlannerTest.decisions(f.original),FrozenSourceStagePlanTest.scope(),(ids,n,root)->{
                    var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(f.universeRef,ids,n,root,"Fictional value evidence"));
                    node.put("stage","news_value");((ObjectNode)node.get(FrozenApplicationContext.FIELD)).put("stage","news_value");NewsValueInputProfileTest.installMaterialView(node,"fictional-source");return JdbcFrozenInputs.canonical(node);
                });
        f.freeze();members=mock(SourceMemberEvidenceService.class);
        var facts=new ArrayList<SourceMemberEvidenceService.Fact>();
        for(var chunk:f.plan.chunks()) {
            var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
            var bindings=FrozenMemberBindings.read(snapshot,context.input(chunk.rootId(),"fictional-prepare",chunk.inputHash(),0));
            for(var item:snapshot.get("members")){var binding=bindings.get(item.path("id").asText());facts.add(new SourceMemberEvidenceService.Fact(binding.itemId(),binding.expectedRevision(),binding.category(),binding.identityKey(),binding.title(),binding.summary(),JdbcFrozenInputs.canonical(item.get("body"))));}
            var command=new Prepare(f.plan.logicalKey(),1,Stage.NEWS_VALUE,f.plan.scope().businessDate(),"fictional-source",List.of(),List.of(),List.of(),false,f.plan.scope().settings().semanticHash(),context,List.of(),chunk.stagePart());
            var store=new GenerationJdbcStore(f.fixture.source,f.fixture.clock,"fictional-owner",chunk.rootId(),command);
            f.tx(()->{store.save(new Frozen(chunk.rootId(),f.plan.logicalKey(),1,Stage.NEWS_VALUE,f.plan.scope().cutoff(),"complete",List.of(),chunk.snapshotJson(),chunk.inputHash(),chunk.requestJson(),2,f.fixture.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),"queued"),"fictional-chunk-prepare-"+chunk.stagePart());return true;});
        }
        when(members.read(anySet(),any())).thenAnswer(invocation->{Set<String> ids=invocation.getArgument(0);return facts.stream().filter(fact->ids.contains(fact.id())).toList();});
        readiness=readiness(true,new SourcePartitionProofService(f.service));
    }
    DispatchReadinessService readiness(boolean strict,SourcePartitionProofService plans) {
        var source=f.fixture.source;var inputs=new JdbcFrozenInputs(source);
        return new DispatchReadinessService(new JdbcDispatchInputs(source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(source)),new GenerationLineageService(new JdbcGenerationLineage(source),f.fixture.clock,"fictional-worker"),mock(ContentReadService.class),members,target->true,run->{throw new AssertionError("value must not use a synthetic collection proof");},plans,strict);
    }
    boolean ready(DispatchReadinessService service,String id){return f.tx(()->service.ready(id));}
    @Test void bothOriginalPartsRequireTheCompleteArchiveAlongsideTheirOwnInputAndCurrentMembers() {
        int before=f.count();for(var chunk:f.plan.chunks())assertTrue(ready(readiness,chunk.rootId()));assertEquals(before,f.count());
        assertEquals(0,f.fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        f.fixture.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_plan'");
        assertThrows(RuntimeException.class,()->ready(readiness,f.plan.chunks().getFirst().rootId()));
    }
    @Test void validLookingRehashedSubsetCannotBeDispatchedOutsideTheFrozenPlan() {
        var chunk=f.plan.chunks().getFirst();var snapshot=(ObjectNode)CanonicalJson.parse(chunk.snapshotJson());((ObjectNode)snapshot.get("members").get(0)).put("id","fictional-replaced-item");
        String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);
        f.tx(()->{f.fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND purpose='input'",chunk.rootId());f.fixture.jdbc.update("UPDATE jobs SET input_snapshot_json=NULL WHERE id=?",chunk.rootId());new JdbcFrozenInputs(f.fixture.source).save(chunk.rootId(),JdbcFrozenInputs.canonical(snapshot),hash,f.fixture.clock.instant());return true;});
        assertThrows(RuntimeException.class,()->ready(readiness,chunk.rootId()));assertEquals(0,new JdbcJobRecords(f.fixture.source).byId(chunk.rootId()).callCount());
    }
    @Test void aPartitionAlwaysRequiresItsPermanentProviderEvenInLegacyCompatibilityMode() {
        var chunk=f.plan.chunks().getFirst();assertThrows(RuntimeException.class,()->ready(readiness(false,null),chunk.rootId()));
        assertThrows(RuntimeException.class,()->readiness(true,null));
    }
    @Test void strictModeRejectsLegacySourceInputWithoutAWholePlan() {
        var legacy=new DispatchReadinessServiceTest(){@Override GenerationJdbcStoreTest fixture(){return DispatchSourcePlanTest.this.f.fixture();}};
        legacy.setup();legacy.prepare(Stage.NEWS_VALUE);
        var source=legacy.f.source;var inputs=new JdbcFrozenInputs(source);
        var strict=new DispatchReadinessService(new JdbcDispatchInputs(source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(source)),new GenerationLineageService(new JdbcGenerationLineage(source),legacy.f.clock,"fictional-worker"),legacy.contents,legacy.members,target->true,run->{throw new AssertionError();},new SourcePartitionProofService(f.service),true);
        assertThrows(RuntimeException.class,()->legacy.f.writes.execute(legacy.f.action(),k->null,a->strict.ready(legacy.f.OWNER)));
        assertEquals(0,new JdbcJobRecords(source).byId(legacy.f.OWNER).callCount());
    }
}
