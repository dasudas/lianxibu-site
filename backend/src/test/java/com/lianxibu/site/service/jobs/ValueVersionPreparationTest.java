package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcNewsValueEntities;
import com.lianxibu.site.service.articles.NewsValueEligibilityService;
import org.junit.jupiter.api.*;

class ValueVersionPreparationTest {
    NewsValueSourcePlanPreparationTest f;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return ValueVersionPreparationTest.this.fixture();}};f.setup();}
    static NewsValueSourcePlanPreparationService service(NewsValueSourcePlanPreparationTest f,String version){return new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.f.source),new JdbcFrozenSourceUniverses(f.f.source),f.p1.universes,f.plans,f.p1.collections,new NewsValueEligibilityService(new com.lianxibu.site.repository.articles.JdbcNewsDedupEligibility(f.f.source),new JdbcNewsValueEntities(f.f.source),f.p1.members,f.clock),f.dependencies,()->new FrozenGenerationSettings(1,java.util.List.of(),java.util.List.of()),f.clock,()->f.allowed,version);}
    @Test void freshV2WholeAndActualPartKeepOriginalVersionAndRequest() {
        f.service=service(f,"2");var plan=f.prepare();var chunk=plan.chunks().getFirst();assertEquals("site-value-material-v2",CanonicalJson.parse(chunk.requestJson()).get("prompt_version").asText());assertEquals("2",CanonicalJson.parse(chunk.snapshotJson()).get(FrozenApplicationContext.FIELD).get("value_result_version").asText());
        var part=f.p1.tx(()->new SourceStagePreparationService(f.f.source,f.plans,f.p1.universes,f.p1.collections::read,f.clock,"fictional-owner").preparePart(f.intent,0,"fictional-v2-part"));assertEquals(chunk.snapshotJson(),part.snapshotJson());assertEquals(chunk.requestJson(),part.requestJson());
        f.service=service(f,"1");f.allowed=false;assertEquals(plan,f.prepare());
    }
    @Test void restartingWithV2NeverUpgradesAFrozenV1Receipt() {var original=f.prepare();f.service=service(f,"2");f.allowed=false;var recovered=f.prepare();assertEquals(original,recovered);assertFalse(CanonicalJson.parse(recovered.chunks().getFirst().snapshotJson()).get(FrozenApplicationContext.FIELD).has("value_result_version"));assertEquals("site-value-material-v1",CanonicalJson.parse(recovered.chunks().getFirst().requestJson()).get("prompt_version").asText());}
    @Test void failedOuterTransactionLeavesNoV2ReceiptAndInvalidVersionsReject() {
        f.service=service(f,"2");int count=f.f.count("1=1");assertThrows(RuntimeException.class,()->f.p1.tx(()->{f.service.prepare(f.intent,"fictional-run",f.profile);throw new IllegalStateException("fictional rollback");}));assertEquals(count,f.f.count("1=1"));assertTrue(f.p1.tx(()->new JdbcFrozenSourcePlans(f.f.source).receipt(f.intent)==null));assertThrows(RuntimeException.class,()->service(f,"3"));assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder("3"));
    }
}
