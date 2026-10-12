package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

/** Native closed observation storage only; direct saves do not grant business completion. */
class JdbcCatalogSourceFinalAvailabilityTest {
 CatalogSourceFinalizationTest f;JdbcCatalogSourceFinalAvailability store;String target;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category)throws Exception{f=new CatalogSourceFinalizationTest(){@Override GenerationJdbcStoreTest database(){return JdbcCatalogSourceFinalAvailabilityTest.this.database();}};f.install(category,false);f.f.complete();f.service.finish(f.f.ref);store=new JdbcCatalogSourceFinalAvailability(f.f.f.source);target=f.read().observationKeyJson();}
 @BeforeEach void setup()throws Exception{install("agent");}
 Instant read(String key){return f.f.results.readonly(()->store.read(key));}
 void save(Instant observed){f.f.prepared.tx(()->{store.save(target,observed);return true;});}
 @Test void fourClosedKeysBindActualOriginalFinalReceiptsAndFirstNativeTime()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category);assertNull(read(target));Instant now=f.f.f.clock.instant();save(now);assertEquals(now,read(target));save(now);assertThrows(RuntimeException.class,()->save(now.plusSeconds(1)));assertEquals(1,f.f.f.count("target_type='catalog_final_availability'"));assertEquals("catalog-source-final-observed:"+JdbcResultState.sha(target),JdbcCatalogSourceFinalAvailability.key(target));}
 }
 @Test void aliasesCrossCategoryStageStructuralSchemaAndWideKeyRefuseBeforeWrite(){
  for(int mode=0;mode<5;mode++){var changed=(ObjectNode)CanonicalJson.parse(target);switch(mode){case 0->changed.put("category","AGENT");case 1->changed.put("stage","skill_dedup");case 2->changed.put("schema_version","source-dedup-stage-key-v1");case 3->changed.put("generation",0);case 4->changed.put("receipt_id","fictional-foreign-receipt");}String key=JdbcFrozenInputs.canonical(changed);assertThrows(RuntimeException.class,()->read(key));assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->{store.save(key,f.f.f.clock.instant());return true;}));}assertThrows(RuntimeException.class,()->read(target+" "));assertThrows(RuntimeException.class,()->read("{\"padding\":\""+"x".repeat(2049)+"\"}"));assertEquals(0,f.f.f.count("target_type='catalog_final_availability'"));assertThrows(RuntimeException.class,()->store.save(target,f.f.f.clock.instant()));assertThrows(RuntimeException.class,()->f.f.results.readonly(()->{store.save(target,f.f.f.clock.instant());return true;}));
 }
 @Test void nativeRoleTimePayloadAndTargetHashesCannotManufactureAnObservation(){
  Instant now=f.f.f.clock.instant();save(now);String id=f.f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='catalog_final_availability'",String.class);f.f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,()->read(target));f.f.f.jdbc.update("UPDATE jobs SET operator_id='catalog-source-final-observer',target_key_hash=? WHERE id=?",new byte[32],id);assertThrows(RuntimeException.class,()->read(target));f.f.f.jdbc.update("UPDATE jobs SET target_key_hash=?,action_payload_hash=? WHERE id=?",JdbcGenerationLineage.hash(target),new byte[32],id);assertThrows(RuntimeException.class,()->read(target));String body=f.f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=?,acted_at=? WHERE id=?",JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(body))),java.sql.Timestamp.from(now.plusSeconds(1)),id);assertThrows(RuntimeException.class,()->read(target));assertEquals(1,f.f.f.count("target_type='catalog_final_availability'"));
 }
}
