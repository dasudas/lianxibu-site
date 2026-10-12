package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.JdbcValueWrites;
import com.lianxibu.site.service.articles.ValueApplicationService;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import java.util.*;
import org.junit.jupiter.api.*;

class ValueClassificationWriteTest {
    NewsValueSourcePlanPreparationTest f;ValueApplicationService service;String item,entity;long revision;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return ValueClassificationWriteTest.this.fixture();}};f.setup();
        f.f.jdbc.execute("ALTER TABLE source_run_items ADD high_value INT");f.f.jdbc.execute("ALTER TABLE source_run_items ADD value_json "+(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON"));
        f.f.jdbc.execute("ALTER TABLE analyzed_entities ADD occurrence_count BIGINT DEFAULT 2");
        item=f.f.jdbc.queryForObject("SELECT id FROM source_run_items WHERE terminal_state='accepted'",String.class);entity=f.f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id=?",String.class,item);revision=f.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Long.class,item);
        service=new ValueApplicationService(new JdbcValueWrites(f.f.source));
    }
    Value value(String classification,String memberCategory,String expectedEntity){return new Value(item,revision,classification,false,false,"Fictional classification evidence",memberCategory,expectedEntity);}
    void apply(Value value,boolean fail){f.p1.tx(()->{service.applyInTransaction("fictional-value-root","a".repeat(64),"b".repeat(64),List.of(value));if(fail)throw new IllegalStateException("fictional outer failure");return true;});}
    void empty(){assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE high_value IS NOT NULL OR value_json IS NOT NULL",Integer.class));assertEquals(revision,f.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Long.class,item));}
    @Test void allFiveClassificationsPreserveNativeEntityIdentityAndOccurrenceCount(){
        for(String classification:List.of("news_tech","agent","skill","workflow","model")){
            apply(value(classification,"news_tech",entity),false);var saved=CanonicalJson.parse(f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,item));
            assertEquals("member-value-v2",saved.get("schema_version").asText());assertEquals(classification,saved.get("category").asText());assertEquals("news_tech",saved.get("member_category").asText());assertEquals(entity,saved.get("entity_id").asText());
            assertEquals("news_tech",f.f.jdbc.queryForObject("SELECT category FROM source_run_items WHERE id=?",String.class,item));assertEquals(entity,f.f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id=?",String.class,item));assertEquals(2,f.f.jdbc.queryForObject("SELECT occurrence_count FROM analyzed_entities WHERE id=?",Integer.class,entity));revision++;
        }
    }
    @Test void differentActualEntityCannotBeWrittenEvenWithTheSameRevisionAndCategory(){
        assertThrows(RuntimeException.class,()->apply(value("agent","news_tech","fictional-other-entity"),false));empty();
        f.f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-swapped-entity' WHERE id=?",item);assertThrows(RuntimeException.class,()->apply(value("agent","news_tech",entity),false));empty();
    }
    @Test void aModelClassificationCannotSubstituteForTheFrozenPhysicalCategory(){
        f.f.jdbc.update("UPDATE source_run_items SET category='agent' WHERE id=?",item);assertThrows(RuntimeException.class,()->apply(value("agent","news_tech",entity),false));empty();
    }
    @Test void missingOriginalBindingOrIllegalCategoryCannotGrantAClassificationChange(){
        assertThrows(RuntimeException.class,()->apply(value("agent","news_tech",null),false));assertThrows(RuntimeException.class,()->apply(value("unknown","news_tech",entity),false));assertThrows(RuntimeException.class,()->apply(value("agent","",entity),false));empty();
    }
    @Test void outerFailureAndStaleOrInvalidMemberLeaveNoValueWrites(){
        assertThrows(RuntimeException.class,()->apply(value("model","news_tech",entity),true));empty();
        f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0 WHERE id=?",item);assertThrows(RuntimeException.class,()->apply(value("model","news_tech",entity),false));empty();
    }
}
