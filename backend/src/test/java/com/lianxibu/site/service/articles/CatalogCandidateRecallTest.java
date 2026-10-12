package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CatalogCandidateRecallTest {
    static final Instant CUT=Instant.parse("2026-01-02T00:00:00.123456Z");
    static String identity(String category,String key){String namespace=switch(category){case "agent","workflow"->"github_repository";case "skill"->"github_skill";case "model"->"model_registry";default->"fictional";};return new String(CanonicalJson.bytes(CanonicalJson.parse("{\"namespace\":\""+namespace+"\",\"key\":\""+key+"\"}")),StandardCharsets.UTF_8);}
    static NewsCandidateRecall.Entity entity(String category,String id,String key,String title){return new NewsCandidateRecall.Entity(id,identity(category,key),title,"Fictional retained category summary",3,CUT.minusSeconds(1),CUT,"pending",false);}
    static NewsCandidateRecall.CategoryHistory history(String category,List<NewsCandidateRecall.Entity> facts){return new NewsCandidateRecall.CategoryHistory(category,new NewsCandidateRecall.History(CUT,facts));}
    @Test void allFourCatalogCategoriesBindOriginalScopeAndSelectedFacts(){
        for(String category:List.of("agent","skill","workflow","model")){
            var fact=entity(category,"fictional-entity","fictional/identity","Fictional catalog title");var query=new NewsCandidateRecall.Query("fictional-item",fact.identityKey(),"Unrelated title");
            var selected=NewsCandidateRecall.select(history(category,List.of(fact)),List.of(query));var n=CanonicalJson.parse(selected.json());
            assertEquals(category,n.path("category").asText());assertEquals(NewsCandidateRecall.FIELD,n.path("schema_version").asText());assertEquals(NewsCandidateRecall.VERSION,n.path("retrieval_version").asText());
            assertEquals(CUT.toString(),n.path("cutoff").asText());assertEquals(1,n.path("scanned_entities").asInt());assertEquals(List.of(fact),selected.candidates().get(query.itemId()));
            assertEquals(fact.summary(),n.path("members").get(0).path("candidates").get(0).path("entity_summary").asText());assertFalse(history(category,List.of(fact)).toString().contains("Fictional"));
        }
    }
    @Test void sameRepositoryFactsInDifferentCategoriesCannotShareAHistoryHash(){
        var fact=entity("agent","fictional-entity","fictional/repository","Fictional repository");var query=new NewsCandidateRecall.Query("fictional-item",fact.identityKey(),fact.title());
        var agent=NewsCandidateRecall.select(history("agent",List.of(fact)),List.of(query));var workflow=NewsCandidateRecall.select(history("workflow",List.of(fact)),List.of(query));
        assertNotEquals(agent.historyHash(),workflow.historyHash());assertNotEquals(agent.json(),workflow.json());assertEquals(agent.candidates(),workflow.candidates());
        var modified=new NewsCandidateRecall.Entity(fact.id(),fact.identityKey(),fact.title(),"Fictional changed original summary",4,fact.firstRecordedAt(),CUT,"analyzed",false);
        assertNotEquals(agent.historyHash(),NewsCandidateRecall.select(history("agent",List.of(modified)),List.of(query)).historyHash());
    }
    @Test void foreignNamespacesAndUnknownCategoriesRefuseBeforeCreatingAnyProfile(){
        var skill=entity("skill","fictional-skill","fictional/path/SKILL.md","Fictional skill");var model=entity("model","fictional-model","fictional/model","Fictional model");
        for(String category:List.of("news_tech","AGENT","unknown"))assertThrows(RuntimeException.class,()->history(category,List.of()));
        assertThrows(RuntimeException.class,()->history("agent",List.of(skill)));assertThrows(RuntimeException.class,()->history("skill",List.of(model)));
        var query=new NewsCandidateRecall.Query("fictional-query",model.identityKey(),"Fictional model");assertThrows(RuntimeException.class,()->NewsCandidateRecall.select(history("skill",List.of(skill)),List.of(query)));
        assertThrows(RuntimeException.class,()->NewsCandidateRecall.select((NewsCandidateRecall.CategoryHistory)null,List.of()));
    }
    @Test void exactIdentityCannotBeLostAtCandidateLimitAndRankingIsDeterministic(){
        var entities=new ArrayList<NewsCandidateRecall.Entity>();entities.add(entity("skill","fictional-exact","same","Unrelated exact identity"));
        for(int n=0;n<30;n++)entities.add(entity("skill",String.format("fictional-%02d",n),"path-"+n,"ＦＯＯ 😀技能发布"));
        var query=new NewsCandidateRecall.Query("fictional-item",identity("skill","same"),"foo 😀技能发布");var first=NewsCandidateRecall.select(history("skill",entities),List.of(query));
        assertEquals(20,first.candidates().get(query.itemId()).size());assertEquals("fictional-exact",first.candidates().get(query.itemId()).getFirst().id());assertEquals("fictional-00",first.candidates().get(query.itemId()).get(1).id());
        Collections.reverse(entities);assertEquals(first,NewsCandidateRecall.select(history("skill",entities),List.of(query)));
    }
    @Test void provenEmptyCatalogIsDifferentFromMissingOrTruncatedHistory(){
        var query=new NewsCandidateRecall.Query("fictional-item",identity("model","fictional/new"),"Fictional model");var empty=NewsCandidateRecall.select(history("model",List.of()),List.of(query));
        assertEquals(List.of(),empty.candidates().get(query.itemId()));assertEquals(0,CanonicalJson.parse(empty.json()).path("scanned_entities").asInt());
        var facts=new ArrayList<NewsCandidateRecall.Entity>();for(int n=0;n<10001;n++)facts.add(entity("model","fictional-"+n,"fictional/"+n,"Fictional model"));
        assertThrows(NewsCandidateRecall.CapacityGuarded.class,()->history("model",facts));assertThrows(RuntimeException.class,()->NewsCandidateRecall.select(history("model",List.of()),List.of(query,query)));
        assertThrows(RuntimeException.class,()->new NewsCandidateRecall.CategoryHistory("model",null));
    }
    @Test void oldP1ExactOriginalProfileRemainsCompatible(){
        var entity=new NewsCandidateRecall.Entity("fictional-old",identity("news_tech","fictional-old"),"Fictional original P1",null,2,CUT.minusSeconds(1),CUT,"pending",false);
        var original=new NewsCandidateRecall.History(CUT,List.of(entity));var query=new NewsCandidateRecall.Query("fictional-item",identity("news_tech","fictional-new"),"Fictional original P1");
        var selected=NewsCandidateRecall.select(original,List.of(query));assertEquals("news_tech",CanonicalJson.parse(selected.json()).path("category").asText());
        assertEquals("9d30fc8959f9e915118eaf904ce583b173622e4dcb359cd7961e87f966c7d153",selected.historyHash());assertEquals("c60f47ceb29f9a0a126be2957de586f55736b8fee410bfd01d0ab56ead504909",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(selected.json())));
    }
}
