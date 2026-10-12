package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceDayCohortBundleTest {
    final SourceDayCohortPlanTest fixture=new SourceDayCohortPlanTest();
    SourceDayCohortPlan plan(){return SourceDayCohortPlan.derive(fixture.frozen(List.of(fixture.source("fictional-source","news_tech","production",true,fixture.registered.plusSeconds(10)),fixture.source("fictional-legacy",null,"disabled",false,null))));}
    @Test void completeOriginalBundleSurvivesJsonDatabaseFormatting(){
        var plan=plan();assertEquals(plan.bundleHash(),SourceDayCohortPlan.readBundle(plan.bundleJson(),plan.bundleHash(),plan.directory()).bundleHash());
        String formatted=tools.jackson.databind.json.JsonMapper.shared().writerWithDefaultPrettyPrinter().writeValueAsString(CanonicalJson.parse(plan.bundleJson()));
        assertEquals(plan.bundleJson(),SourceDayCohortPlan.readBundle(formatted,plan.bundleHash(),plan.directory()).bundleJson());assertEquals(5,CanonicalJson.parse(plan.bundleJson()).get("cohorts").size());
    }
    @Test void rehashedMissingDuplicateOrWrongOriginalCohortCannotSealFullDirectory(){
        var plan=plan();var missing=(ObjectNode)CanonicalJson.parse(plan.bundleJson());((tools.jackson.databind.node.ArrayNode)missing.get("cohorts")).remove(0);reject(plan,missing);
        var duplicate=(ObjectNode)CanonicalJson.parse(plan.bundleJson());((tools.jackson.databind.node.ArrayNode)duplicate.get("cohorts")).set(1,duplicate.get("cohorts").get(0));reject(plan,duplicate);
        var changed=(ObjectNode)CanonicalJson.parse(plan.bundleJson());((ObjectNode)changed.get("cohorts").get(0).get("members").get(0)).put("run_id","FICTIONAL-alias");reject(plan,changed);
        var extra=(ObjectNode)CanonicalJson.parse(plan.bundleJson());extra.put("sealed",true);reject(plan,extra);
    }
    private void reject(SourceDayCohortPlan plan,ObjectNode node){String json=new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);assertThrows(RuntimeException.class,()->SourceDayCohortPlan.readBundle(json,CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,node),plan.directory()));}
    @Test void threeThousandConfiguredMembersAreKeptWithoutDroppingAnyPlannedRun(){
        var rows=new ArrayList<com.lianxibu.site.domain.sources.SourceDayRoster.RawSource>();for(int i=0;i<3000;i++)rows.add(fixture.source("fictional-"+i,"news_tech","production",true,fixture.registered.plusSeconds(10)));
        var plan=SourceDayCohortPlan.derive(fixture.frozen(rows));assertEquals(3000,plan.requests().size());assertEquals(3000,plan.cohort("news_tech").members().size());assertTrue(plan.bundleJson().getBytes(StandardCharsets.UTF_8).length<=2097152);
        var restored=SourceDayCohortPlan.readBundle(plan.bundleJson(),plan.bundleHash(),plan.directory());assertEquals(3000,restored.requests().size());assertEquals(plan.bundleHash(),restored.bundleHash());
    }
    @Test void physicalOversizeIsRejectedBeforeParsingAndAbsentBundleIsNotEmpty(){
        var plan=plan();assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDayCohortPlan.readBundle(" ".repeat(2097153),plan.bundleHash(),plan.directory()));
        assertThrows(RuntimeException.class,()->SourceDayCohortPlan.readBundle(null,plan.bundleHash(),plan.directory()));assertThrows(RuntimeException.class,()->SourceDayCohortPlan.readBundle(plan.bundleJson(),"b".repeat(64),plan.directory()));
    }
}
