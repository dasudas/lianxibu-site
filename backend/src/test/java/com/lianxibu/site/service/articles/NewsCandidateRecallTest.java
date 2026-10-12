package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsCandidateRecallTest {
    static final Instant CUT=Instant.parse("2026-01-02T00:00:00Z");
    static String identity(String value){return "{\"namespace\":\"fictional\",\"key\":\""+value+"\"}";}
    static NewsCandidateRecall.Entity entity(String id,String key,String title,Instant recorded){return new NewsCandidateRecall.Entity(id,identity(key),title,"Fictional retained entity summary",2,recorded,CUT,"pending",false);}
    static NewsCandidateRecall.History history(List<NewsCandidateRecall.Entity> items){return new NewsCandidateRecall.History(CUT,items);}
    @Test void exactIdentitySurvivesCandidateLimitAndUnicodeRankingIsStable(){
        var entities=new ArrayList<NewsCandidateRecall.Entity>();entities.add(entity("fictional-exact","same","Other old title",CUT.minusSeconds(99999)));
        for(int n=0;n<30;n++)entities.add(entity(String.format("fictional-similar-%02d",n),"key-"+n,"ＦＯＯ 😀助手发布",CUT.minusSeconds(1)));
        var query=new NewsCandidateRecall.Query("fictional-item",identity("same"),"foo 😀助手发布");
        var result=NewsCandidateRecall.select(history(entities),List.of(query));var selected=result.candidates().get(query.itemId());
        assertEquals(20,selected.size());assertEquals("fictional-exact",selected.getFirst().id());assertEquals("fictional-similar-00",selected.get(1).id());
        Collections.reverse(entities);assertEquals(result,NewsCandidateRecall.select(history(entities),List.of(query)));assertFalse(result.toString().contains("Fictional retained"));
    }
    @Test void scopeOriginalQueriesAndSelectedFactsAreFrozenIntoTheProfile(){
        var entity=entity("fictional-candidate","old","Fictional Java release",CUT.minusSeconds(2));var query=new NewsCandidateRecall.Query("fictional-item",identity("new"),"Fictional Java release");
        var result=NewsCandidateRecall.select(history(List.of(entity)),List.of(query));var profile=CanonicalJson.parse(result.json());
        assertEquals("entity-recall-title-trigram-v1",profile.path("retrieval_version").asText());assertEquals("news_tech",profile.path("category").asText());assertEquals(CUT.toString(),profile.path("cutoff").asText());
        assertEquals(1,profile.path("scanned_entities").asInt());assertEquals("fictional-item",profile.path("members").get(0).path("item_id").asText());
        assertEquals("Fictional retained entity summary",profile.path("members").get(0).path("candidates").get(0).path("entity_summary").asText());
        var originalCandidate=profile.path("members").get(0).path("candidates").get(0);assertEquals(CUT.toString(),originalCandidate.path("metadata_as_of").asText());assertFalse(originalCandidate.has("updated_at"));
        var changed=entity("fictional-candidate","old","Changed original title",CUT.minusSeconds(2));assertNotEquals(result.historyHash(),NewsCandidateRecall.select(history(List.of(changed)),List.of(query)).historyHash());
        var changedQuery=new NewsCandidateRecall.Query(query.itemId(),query.identityKey(),"Changed query title");assertNotEquals(result.json(),NewsCandidateRecall.select(history(List.of(entity)),List.of(changedQuery)).json());
    }
    @Test void emptyHistoryAndUnrelatedTitlesProduceProvenEmptyPolicyWindows(){
        var query=new NewsCandidateRecall.Query("fictional-item",identity("new"),"云计算");assertTrue(NewsCandidateRecall.select(history(List.of()),List.of(query)).candidates().get(query.itemId()).isEmpty());
        var other=entity("fictional-other","other","Database engine",CUT.minusSeconds(2));assertTrue(NewsCandidateRecall.select(history(List.of(other)),List.of(query)).candidates().get(query.itemId()).isEmpty());
        var shortTitle=entity("fictional-short","other","ＡＩ",CUT.minusSeconds(2));assertEquals(List.of(shortTitle),NewsCandidateRecall.select(history(List.of(shortTitle)),List.of(new NewsCandidateRecall.Query(query.itemId(),query.identityKey(),"ai"))).candidates().get(query.itemId()));
    }
    @Test void duplicateIdentityIdsLateUpdatesBadPrecisionAndMissingValuesRefuseTheWholeHistory(){
        var a=entity("fictional-a","same","Fictional title",CUT.minusSeconds(2));
        assertThrows(RuntimeException.class,()->history(List.of(a,a)));assertThrows(RuntimeException.class,()->history(List.of(a,entity("fictional-b","same","Another title",CUT.minusSeconds(1)))));
        assertThrows(RuntimeException.class,()->history(List.of(new NewsCandidateRecall.Entity(a.id(),a.identityKey(),a.title(),null,2,a.firstRecordedAt(),CUT.plusSeconds(1),"pending",false))));
        assertThrows(RuntimeException.class,()->history(List.of(new NewsCandidateRecall.Entity(a.id(),a.identityKey(),a.title(),null,2,a.firstRecordedAt(),CUT.minusSeconds(1),"pending",false))));
        assertThrows(RuntimeException.class,()->history(List.of(entity("fictional-late","late","Title",CUT.plusSeconds(1)))));
        assertThrows(RuntimeException.class,()->new NewsCandidateRecall.Entity(a.id(),a.identityKey(),a.title(),null,2,CUT.plusNanos(1),CUT,"pending",false));
        assertThrows(RuntimeException.class,()->new NewsCandidateRecall.Query("fictional-item","not json","Title"));assertThrows(RuntimeException.class,()->new NewsCandidateRecall.Query("fictional-item",identity("new"),""));
        var query=new NewsCandidateRecall.Query("fictional-item",identity("new"),"Fictional title");assertThrows(RuntimeException.class,()->NewsCandidateRecall.select(history(List.of(a)),List.of(query,query)));
    }
    @Test void oversizedHistoryAndQueriesAreCapacityGuardedWithoutTruncating(){
        var items=new ArrayList<NewsCandidateRecall.Entity>();for(int n=0;n<10001;n++)items.add(entity("fictional-"+n,"key-"+n,"Fictional title",CUT.minusSeconds(1)));
        assertThrows(NewsCandidateRecall.CapacityGuarded.class,()->history(items));
        var large=new ArrayList<NewsCandidateRecall.Entity>();for(int n=0;n<2100;n++)large.add(new NewsCandidateRecall.Entity("fictional-"+n,identity("key-"+n),"Fictional title","x".repeat(16384),1,CUT,CUT,"pending",false));
        assertThrows(NewsCandidateRecall.CapacityGuarded.class,()->history(large));
        var queries=new ArrayList<NewsCandidateRecall.Query>();for(int n=0;n<10001;n++)queries.add(new NewsCandidateRecall.Query("fictional-"+n,identity("new"),"Fictional title"));
        assertThrows(NewsCandidateRecall.CapacityGuarded.class,()->NewsCandidateRecall.select(history(List.of()),queries));
    }
}
