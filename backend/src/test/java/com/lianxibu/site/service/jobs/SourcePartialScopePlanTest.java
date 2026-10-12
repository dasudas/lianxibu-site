package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SourcePartialScopePlanTest {
    record Original(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe){ }
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    Original original(int count)throws Exception {
        var f=new SourceValuePartialStageContextTest(){@Override GenerationJdbcStoreTest database(){return SourcePartialScopePlanTest.this.database();}};f.setup(count);for(var chunk:f.f.plan.chunks())f.f.terminal((int)chunk.stagePart(),"failed");var dependency=f.dependency();String key=NewsValueSourcePlanBuilder.logicalKey(dependency),owner=JdbcFrozenInputs.stableId("intent",key+":1");var universe=f.f.f.read(()->f.f.f.p1.universes.readImmutable(f.f.plan.reference()));var ref=FrozenSourceUniverse.encode(owner,universe).reference();var scope=new SourcePartitionPlanner.Scope(f.day,f.cutoff(),new FrozenGenerationSettings(0,List.of(),List.of()),SourceValueStageContext.create(dependency,f.day,f.cutoff(),false));var decisions=universe.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).toList();return new Original(new SourcePartitionPlanner.Plan(GenerationFlowService.Stage.NEWS_VALUE,key,1,ref,decisions,List.of(),scope),universe);
    }
    static ObjectNode body(FrozenSourceStagePlan.Encoded encoded){var bytes=new ByteArrayOutputStream();for(String raw:encoded.payloads().subList(1,encoded.payloads().size()))bytes.writeBytes(Base64.getDecoder().decode(CanonicalJson.parse(raw).get("bytes_base64").asText()));return (ObjectNode)CanonicalJson.parse(bytes.toByteArray());}
    static FrozenSourceStagePlan.Encoded rehash(FrozenSourceStagePlan.Encoded old,Consumer<ObjectNode> change){
        var body=body(old);change.accept(body);byte[] bytes=CanonicalJson.bytes(body);String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,body);var index=(ObjectNode)CanonicalJson.parse(old.payloads().getFirst());index.put("plan_hash",hash).put("byte_count",bytes.length);var descriptors=index.putArray("parts");var parts=new ArrayList<String>();parts.add(null);
        for(int offset=0;offset<bytes.length;offset+=FrozenSourceStagePlan.CHUNK_BYTES){var chunk=JsonMapper.shared().createObjectNode().put("schema_version","source-stage-plan-chunk-v1").put("bytes_base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+FrozenSourceStagePlan.CHUNK_BYTES))));descriptors.addObject().put("part_no",parts.size()).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));parts.add(FrozenSourceDedupDependencyTest.canonical(chunk));}
        parts.set(0,FrozenSourceDedupDependencyTest.canonical(index));return new FrozenSourceStagePlan.Encoded(new FrozenSourceStagePlan.Reference(old.reference().ownerId(),hash,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index)),parts);
    }
    @Test void allFailedOriginalUniverseAndDecisionsSurviveExplicitScopeV3ZeroPlanArchive()throws Exception {
        for(int count:List.of(1,201)){var x=original(count);new SourcePartitionPlanner().validate(x.plan(),x.universe());var encoded=FrozenSourceStagePlan.encode(x.plan(),x.universe());assertEquals("source-stage-scope-v3",body(encoded).get("scope").get("schema_version").asText());assertEquals(x.plan(),FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),x.universe()));assertEquals(count,x.plan().decisions().size());assertTrue(x.plan().chunks().isEmpty());var context=CanonicalJson.parse(x.plan().scope().stageContextJson());assertEquals("partial",context.get("coverage_state").asText());assertEquals((count+99)/100,context.get("missing").size());assertEquals(count,context.get(FrozenSourceDedupDependency.FIELD).get("stage_result").get("unapplied_member_count").asInt());}
    }
    @Test void everyFailedOriginalMemberMustBeNotAiEvenIfCurrentFactsCouldAlreadyBeApplied()throws Exception {
        var x=original(1);for(var reason:List.of(SourcePartitionPlanner.Reason.ALREADY_APPLIED,SourcePartitionPlanner.Reason.PREFERENCE_BLOCKED,SourcePartitionPlanner.Reason.ELIGIBLE)){var changed=new SourcePartitionPlanner.Plan(x.plan().stage(),x.plan().logicalKey(),x.plan().generation(),x.plan().reference(),List.of(new SourcePartitionPlanner.Decision(x.plan().decisions().getFirst().itemId(),reason)),x.plan().chunks(),x.plan().scope());assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.encode(changed,x.universe()));}
    }
    @Test void scopeVersionAndInnerContextPairingsRejectEvenAfterAllArchiveHashesAreRecomputed()throws Exception {
        var x=original(1);var original=FrozenSourceStagePlan.encode(x.plan(),x.universe());for(String version:List.of("source-stage-scope-v2","source-stage-scope-v1","source-stage-scope-v4")){var changed=rehash(original,n->((ObjectNode)n.get("scope")).put("schema_version",version));assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(changed.reference(),changed.payloads(),x.universe()));}
        var mismatch=rehash(original,n->((ObjectNode)n.get("scope").get("stage_context")).put("schema_version","source-value-stage-context-v1"));assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(mismatch.reference(),mismatch.payloads(),x.universe()));var wrongMissing=rehash(original,n->((ObjectNode)n.get("scope").get("stage_context").get("missing").get(0)).put("reason","dedup_cancelled"));assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(wrongMissing.reference(),wrongMissing.payloads(),x.universe()));
    }
    @Test void originalScopeCannotBeAppliedToAnotherStageOrLogicalKey()throws Exception {
        var x=original(1);var wrongStage=new SourcePartitionPlanner.Plan(GenerationFlowService.Stage.NEWS_DEDUP,x.plan().logicalKey(),1,x.plan().reference(),x.plan().decisions(),List.of(),x.plan().scope());assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.encode(wrongStage,x.universe()));String key="fictional-other-value-key";var ref=FrozenSourceUniverse.encode(JdbcFrozenInputs.stableId("intent",key+":1"),x.universe()).reference();var wrongKey=new SourcePartitionPlanner.Plan(x.plan().stage(),key,1,ref,x.plan().decisions(),List.of(),x.plan().scope());assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.encode(wrongKey,x.universe()));
    }
    @Test void legacyExtendedScopesKeepTheExistingGoldenArchiveReferences() {
        var f=new NewsValueSourcePlanBuilderTest();var expected=Map.of("0:false","c47a02b4414635ce662c6fcd7630afcd5c6e54b2651806cf3d6c013fcc199559","0:true","02ffac2efdeb90b825edb2cbe856a1e9e4cb9a1cabee7a5fe4594e29e074fc9c","5:false","eb5f54d8291768839e66a048f750593bbd32ea14933e040702bd5471e803fd5d","5:true","ce692a575bba92453d4e0ec9ac8f5fc7748fee210cd2dc7f49b9aafd71bdbc70");for(int count:List.of(0,5)){var x=f.fixture(count);for(boolean partial:List.of(false,true)){var plan=new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(0,List.of(),List.of()),x.dependency(),1,partial);var encoded=FrozenSourceStagePlan.encode(plan,x.universe());assertEquals("source-stage-scope-v2",body(encoded).get("scope").get("schema_version").asText());assertEquals(expected.get(count+":"+partial),CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(encoded.reference().json())));assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),x.universe()));}}
    }
}
