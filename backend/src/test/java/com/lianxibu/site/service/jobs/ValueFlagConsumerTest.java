package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ValueFlagConsumerTest {
    ValueClassificationConsumerTest base;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    static ValueClassificationConsumerTest consumer(GenerationJdbcStoreTest fixture) {
        return new ValueClassificationConsumerTest(){
            @Override GenerationJdbcStoreTest fixture(){return fixture;}
            @Override void beforePrepare(){f.service=ValueVersionPreparationTest.service(f,"2");}
            @Override JsonNode payload(String category){var p=(ObjectNode)super.payload(category);p.put("value_result_version","2");((ObjectNode)p.get("items").get(0)).put("valuable",true);return p;}
        };
    }
    @BeforeEach void setup() throws Exception {base=consumer(fixture());base.setup();}
    @Test void originalValuableOrdinaryResultPersistsV3AndItsOriginalHandoff() {
        base.apply();var n=CanonicalJson.parse(base.f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,base.base.item));assertEquals("member-value-v3",n.get("schema_version").asText());assertTrue(n.get("valuable").booleanValue());assertFalse(n.get("high_value").booleanValue());
        var receipt=base.f.p1.tx(()->new JdbcMemberStageResults(base.f.f.source).read(base.input.rootId()));assertTrue(receipt.get("payload").get("items").get(0).get("valuable").booleanValue());assertEquals(base.base.entity,receipt.get("members").get(0).get("entityId").asText());
    }
    @Test void invalidHighMissingValuableAndVersionMismatchRefuseBeforeWrites() {
        var item=(ObjectNode)base.payload.get("items").get(0);item.put("valuable",false);item.put("high_value",true);assertThrows(RuntimeException.class,base::apply);base.empty();item.put("high_value",false);item.remove("valuable");assertThrows(RuntimeException.class,base::apply);base.empty();
    }
    @Test void callbackCannotReplaceOriginalValuableWithFalseOrUnknown() {
        base.apply();var old=base.values.getFirst();for(Boolean flag:new Boolean[]{Boolean.FALSE,null}){var wrong=new Value(old.itemId(),old.expectedRevision(),old.category(),old.highValue(),old.selected(),old.reason(),old.memberCategory(),old.entityId(),flag);assertThrows(RuntimeException.class,()->base.f.p1.tx(()->{base.downstream.afterApplied(base.input,base.result(),java.util.List.of(wrong));return true;}));}
    }
    @Test void failedPermanentReceiptRollsBackBothFlagsAndNativeLocators() {
        String id=JdbcFrozenInputs.stableId("member-stage","member-stage:"+base.input.rootId());base.f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_value_v3_failure CHECK(id<>'"+id+"')");assertThrows(RuntimeException.class,base::apply);base.empty();
    }
    @Test void replayKeepsOriginalValueAfterLaterRevisionAndBadObserverBlocksANewApply() {
        base.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,base::apply);base.empty();
        String raw=base.f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='source_stage_availability'",String.class);base.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw))));base.apply();
        base.f.f.jdbc.update("UPDATE source_run_items SET revision=20,analysis_job_id='fictional-later' WHERE id=?",base.base.item);int count=base.f.f.count("1=1");base.f.p1.tx(()->{base.downstream.afterApplied(base.input,base.result(),base.values);return true;});assertEquals(count,base.f.f.count("1=1"));assertEquals(20,base.f.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Integer.class,base.base.item));
    }
}
