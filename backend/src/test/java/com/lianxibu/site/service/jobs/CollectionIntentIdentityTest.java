package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CollectionIntentIdentityTest {
    private static final LocalDate DAY=LocalDate.of(2026,1,1);
    // Independent Python hashlib vectors: sorted UTF-8 JSON and the published domain tags.
    @ParameterizedTest @CsvSource({
        "news_tech,news_dedup,1f7d786fd42ca000108d965eecd2bd79fb7644caac852aa83871068096135b9d,8b71786b16550b3118d91f5f0c573e5ee277bc6d225cf6e369d884f6479a50dd",
        "agent,agent_dedup,d4baf7058c86742f0b3bc2758b619cb9f6d8610c557772308f4605e21a3e9d7b,f2962b29bd116ebbb70aec60679ce981dc3c5db9365f99fc969c4ddc79bcfba3",
        "skill,skill_dedup,081b07e334736b5f65dddaa2eb263155ebed964059708296fa26b2224e24a0ff,6eb05ae9fb5031b0536906508f9b98b5fbd6c4b812b7f37df85999c124ef1d9d",
        "workflow,workflow_dedup,d6577d6a78f847b9c17f28bf00fdb1d3277b38dd72a245b14f3dc80cb5b9ad32,f58807627ed8a15242fe839967651f3e97fa3d0c7cc3530faeec8bfbd05b6ba2",
        "model,model_dedup,ee1a7c81dca5b5901a7a383cc5a1d2fa1491ddb75661b015c7cfdbaa6e5ec043,6e789d54abfc96e0cc7fb86e2b2e2bd3e02fad07af51ba9896786c8646986cfb"})
    void originalFiveCategoryIdentitiesMatchIndependentGoldenVectors(String category,String type,String logicalHash,String id) {
        var identity=CollectionIntentIdentity.create("fictional-source","daily",DAY,1,category);
        assertEquals("collection:"+logicalHash,identity.logicalKey());assertEquals(id,identity.id());assertEquals(type,identity.taskType());assertEquals(DAY,identity.day());assertEquals(1,identity.generation());
    }
    @Test void sourceDateKindCategoryAndGenerationKeepDistinctOriginalAddresses() {
        var values=List.of(CollectionIntentIdentity.create("fictional-source","daily",DAY,1,"news_tech"),CollectionIntentIdentity.create("fictional-other","daily",DAY,1,"news_tech"),CollectionIntentIdentity.create("fictional-source","daily",DAY.plusDays(1),1,"news_tech"),CollectionIntentIdentity.create("fictional-source","historical_backfill",DAY,1,"news_tech"),CollectionIntentIdentity.create("fictional-source","daily",DAY,1,"agent"),CollectionIntentIdentity.create("fictional-source","daily",DAY,2,"news_tech"));
        assertEquals(values.size(),new HashSet<>(values.stream().map(CollectionIntentIdentity.Address::id).toList()).size());assertEquals(values.getFirst().logicalKey(),values.getLast().logicalKey());assertFalse(values.getFirst().toString().contains("fictional-source"));
    }
    @Test void malformedInputsCannotPredictUsableIdentity() {
        for(String category:Arrays.asList(null,"rss","news","fictional-unknown"))assertThrows(RuntimeException.class,()->CollectionIntentIdentity.create("fictional-source","daily",DAY,1,category));
        for(String kind:Arrays.asList(null,"probe","fictional"))assertThrows(RuntimeException.class,()->CollectionIntentIdentity.create("fictional-source",kind,DAY,1,"news_tech"));
        for(long generation:new long[]{0,-1,9007199254740992L})assertThrows(RuntimeException.class,()->CollectionIntentIdentity.create("fictional-source","daily",DAY,generation,"news_tech"));
        assertThrows(RuntimeException.class,()->CollectionIntentIdentity.create("bad\nsource","daily",DAY,1,"news_tech"));assertThrows(RuntimeException.class,()->CollectionIntentIdentity.create("fictional-source","daily",null,1,"news_tech"));
    }
}
