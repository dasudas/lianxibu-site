package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Real bounded writer, original seals and exact immutable inputs; fictional retained outcomes. */
class GenerationPartialProfileReuseTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @Test void FormalTwoHundredOneMemberPartsStillUseTheOriginalOneSecondWriter()throws Exception{
        var fixture=new SourcePartialStagePreparationTest(){@Override GenerationJdbcStoreTest database(){return GenerationPartialProfileReuseTest.this.database();}};fixture.setup(201,true);assertEquals(201,fixture.plan.decisions().size());assertTrue(fixture.plan.chunks().size()>1);int calls=fixture.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);
        for(int part=0;part<fixture.plan.chunks().size();part++){var frozen=fixture.prepare(part);assertEquals(fixture.plan.chunks().get(part).snapshotJson(),frozen.snapshotJson());assertEquals(fixture.plan.chunks().get(part).requestJson(),frozen.requestJson());assertEquals(part+1,fixture.progress().nextPart());}
        assertTrue(fixture.progress().complete());assertEquals(calls,fixture.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(fixture.plan.chunks().size(),fixture.f.db.count("record_kind='logical_task' AND task_type='news_value'"));
    }
    @Test void ReusedStructureNeverCachesNativeAuthorityOrAcceptsAnotherDeclaredProfile()throws Exception{
        var fixture=new GenerationPartialJdbcStoreTest(){@Override GenerationJdbcStoreTest database(){return GenerationPartialProfileReuseTest.this.database();}};fixture.setup();var store=fixture.provider(fixture.dependency(),fixture.f.permit);assertEquals(fixture.frozen,fixture.save(store));String root=fixture.f.f.f.plan.chunks().getLast().rootId();long revision=fixture.f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,root);int count=fixture.f.db.count("1=1");fixture.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);assertThrows(RuntimeException.class,()->fixture.save(store));assertEquals(count,fixture.f.db.count("1=1"));fixture.f.db.jdbc.update("UPDATE jobs SET revision=? WHERE id=?",revision,root);assertEquals(fixture.frozen,fixture.save(store));
        var raw=(ObjectNode)CanonicalJson.parse(fixture.chunk.snapshotJson());var profile=(ObjectNode)raw.get(FrozenSourceDedupDependency.FIELD);var full=(ObjectNode)profile.get("stage_result");((ObjectNode)full.get("fragments").get(0).get("payload")).put("summary","Fictional alternate declared profile");((ObjectNode)profile.get("reports").get(0)).put("summary","Fictional alternate declared profile");((ObjectNode)profile.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full));assertDoesNotThrow(()->NewsValueInputProfile.complete(raw));var alternate=fixture.value(raw);assertThrows(RuntimeException.class,()->fixture.f.f.f.f.p1.tx(()->{store.save(alternate,"fictional-alternate-profile");return true;}));assertEquals(count,fixture.f.db.count("1=1"));assertEquals(fixture.frozen,fixture.save(store));
        ((ObjectNode)raw.get("members").get(0)).put("body_hash","0".repeat(64));var good=fixture.frozen;var invalid=new GenerationFlowService.Frozen(good.ownerId(),good.logicalKey(),good.generation(),good.stage(),good.cutoff(),good.coverageState(),good.missing(),JdbcFrozenInputs.canonical(raw),CanonicalJson.hash(CanonicalJson.Domain.INPUT,raw),good.requestJson(),good.priority(),good.eligibleAt(),good.state());assertThrows(RuntimeException.class,()->fixture.f.f.f.f.p1.tx(()->{store.save(invalid,"fictional-invalid-profile");return true;}));assertEquals(fixture.frozen,fixture.save(store));assertEquals(count,fixture.f.db.count("1=1"));
    }
}
