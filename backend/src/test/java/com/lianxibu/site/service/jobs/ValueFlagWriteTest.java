package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import org.junit.jupiter.api.*;

class ValueFlagWriteTest {
    ValueClassificationWriteTest base;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {base=new ValueClassificationWriteTest(){@Override GenerationJdbcStoreTest fixture(){return ValueFlagWriteTest.this.fixture();}};base.setup();}
    Value value(Boolean valuable,boolean high,String entity){return new Value(base.item,base.revision,"agent",high,false,"Fictional evidence","news_tech",entity,valuable);}
    @Test void separateOrdinaryAndHighFlagsPersistWithExactEntityBinding() {
        for(boolean[] flags:new boolean[][]{{true,false},{false,false},{true,true}}){base.apply(value(flags[0],flags[1],base.entity),false);var n=CanonicalJson.parse(base.f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,base.item));assertEquals("member-value-v3",n.get("schema_version").asText());assertEquals(flags[0],n.get("valuable").booleanValue());assertEquals(flags[1],n.get("high_value").booleanValue());assertEquals(base.entity,n.get("entity_id").asText());assertEquals("news_tech",n.get("member_category").asText());base.revision++;}
    }
    @Test void highWithoutValueAndMissingEntityRefuseBeforeWrites() {assertThrows(RuntimeException.class,()->base.apply(value(false,true,base.entity),false));assertThrows(RuntimeException.class,()->base.apply(value(true,false,null),false));base.empty();}
    @Test void outerFailureRollsBackBothFlagsAndRevision() {assertThrows(RuntimeException.class,()->base.apply(value(true,false,base.entity),true));base.empty();}
    @Test void oldDecisionKeepsUnknownValueAndItsOriginalSchema() {base.apply(base.value("agent","news_tech",base.entity),false);var n=CanonicalJson.parse(base.f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,base.item));assertEquals("member-value-v2",n.get("schema_version").asText());assertFalse(n.has("valuable"));}
}
