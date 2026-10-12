package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The resolution fixture uses real original archives and native rows; final values grant no seal authority. */
class CatalogSourceFinalPartialTest {
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 CatalogSourceStageResolutionTest fixture(String category,int count){var f=new CatalogSourceStageResolutionTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceFinalPartialTest.this.database();}};f.install(category,count);return f;}
 CatalogSourceFinalResult result(CatalogSourceStageResolutionTest fixture){var resolution=fixture.read();var whole=SourceDedupStageResult.createResolved(resolution);return CatalogSourceFinalResult.createPartial(resolution.plan(),resolution.universe(),whole,fixture.f.catalog.collection);}
 @Test void actualOriginal203MemberSparseResultsKeepBothResolutionsAndAllDecisions(){
  for(String category:List.of("agent","skill","workflow","model")){var f=fixture(category,203);f.complete(0);f.terminal(1,"failed");var result=result(f);var body=CanonicalJson.parse(result.json());assertEquals("catalog-source-final-result-v2",body.path("schema_version").asText());assertEquals(203,body.path("stage_result").path("decisions").size());assertEquals(100,body.path("applied_member_count").asInt());assertEquals(2,body.path("unapplied_member_count").asInt());assertEquals(2,body.path("stage_result").path("resolutions").size());assertEquals("partial",result.coverageState());assertTrue(result.missing().stream().anyMatch(m->m.reason().equals("dedup_failed")&&m.id().equals(f.f.plan.chunks().getLast().rootId())));assertEquals(0,f.f.f.count("target_type='source_stage_availability'"));assertEquals(0,f.f.f.count("target_type='source_dedup_partial_seal'"));assertEquals(4,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));}
 }
 @Test void allTerminalFailuresAreExplicitAndNeverLookLikeZeroOriginalInput(){
  for(String state:List.of("failed","cancelled","discarded","superseded")){var f=fixture("skill",5);f.terminal(0,state);var result=result(f);var body=CanonicalJson.parse(result.json());assertEquals(3,body.path("eligible_count").asInt());assertEquals(0,body.path("applied_member_count").asInt());assertEquals(3,body.path("unapplied_member_count").asInt());assertTrue(result.summary().contains("尚未获得已应用"));assertFalse(result.summary().contains("没有本次待去重输入"));assertTrue(result.missing().stream().anyMatch(m->m.reason().equals("dedup_"+state)));assertEquals(0,body.path("stage_result").path("fragments").size());}
 }
 @Test void actualPendingCannotManufacturePartialAndCompleteCannotMasqueradeAsSparse(){
  var f=fixture("workflow",5);assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolved(f.read()));f.complete(0);var resolution=f.read();var whole=SourceDedupStageResult.createResolved(resolution);assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.createPartial(resolution.plan(),resolution.universe(),whole,f.f.catalog.collection));assertNotNull(CatalogSourceFinalResult.create(resolution.plan(),resolution.universe(),whole,f.f.catalog.collection));
 }
}
