package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.articles.SourceMemberEvidenceServiceTest;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceMemberInputEnricherTest {
    SourceMemberEvidenceServiceTest facts;
    GenerationEnrichmentTest flow;
    @BeforeEach void setup() throws Exception { facts=new SourceMemberEvidenceServiceTest();facts.setup();flow=new GenerationEnrichmentTest(); }
    ObjectNode snapshot() {
        var root=FrozenMemberBindingsTest.snapshot();root.remove(FrozenMemberBindings.FIELD);
        var member=root.putArray("members").addObject();member.put("id","fictional-item");
        var payload=CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);
        member.set("body",payload);member.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload));return root;
    }
    @Test void bindsVerifiedStoredRevisionAndPayloadToInput() {
        var root=snapshot();var enricher=new SourceMemberInputEnricher(facts.service);
        var extension=facts.tx.execute(status->enricher.fields(FrozenMemberBindingsTest.ROOT,flow.command(),facts.cutoff,root));
        root.set(FrozenMemberBindings.FIELD,extension.get(FrozenMemberBindings.FIELD));
        var binding=FrozenMemberBindings.read(root,FrozenMemberBindingsTest.input(root)).get("fictional-item");
        assertEquals(2,binding.expectedRevision());assertEquals("Fictional source title",binding.title());
        assertEquals("Fictional summary",binding.summary());assertTrue(binding.identityKey().contains("example.invalid"));
    }
    @Test void cannotAttachForgedOrIncompleteSelectedEvidence() {
        var enricher=new SourceMemberInputEnricher(facts.service);
        for(String mode:new String[]{"body","hash","missing","duplicate"}) {
            var root=snapshot();var members=(tools.jackson.databind.node.ArrayNode)root.get("members");var member=(ObjectNode)members.get(0);
            switch(mode){case "body" -> {((ObjectNode)member.get("body")).put("title","Fictional forged title");member.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,member.get("body")));}case "hash" -> member.put("body_hash","a".repeat(64));case "missing" -> members.removeAll();case "duplicate" -> members.add(member.deepCopy());}
            assertThrows(RuntimeException.class,()->facts.tx.execute(status->enricher.fields(FrozenMemberBindingsTest.ROOT,flow.command(),facts.cutoff,root)));
        }
    }
    @Test void actualPreparationFreezesDatabaseFactsBeforeBuildingRequest() {
        facts.jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");facts.jdbc.update("INSERT INTO worker_lock VALUES(1)");
        var writes=new SharedWriteExecutor(new com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry(facts.source),new org.springframework.jdbc.datasource.DataSourceTransactionManager(facts.source));
        var payload=CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);
        var date=java.time.LocalDate.of(2026,1,1);var context=flow.command().applicationContext();
        var collection=new CollectionHandoffProofService.Proof("fictional-intent","fictional-handoff","a".repeat(64),
            new com.lianxibu.site.service.sources.CollectionHandoffService.Facts("fictional-run","fictional-source","daily",date,1,
                "news_tech","production",1,"a".repeat(64),"a".repeat(64),"complete","succeeded",1,1,0));
        var command=new GenerationFlowService.Prepare(CollectionHandoffProofService.logicalKey(collection.facts()),1,
            GenerationFlowService.Stage.NEWS_DEDUP,date,"fictional-source",java.util.List.of(),
            java.util.List.of(new GenerationFlowService.Evidence("fictional-item","article_revision",date,facts.cutoff.minusSeconds(1),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload),payload.toString(),true,false,"accepted",java.util.List.of())),
            java.util.List.of(),false,flow.settings.semanticHash(),context,java.util.List.of(collection));
        GenerationFlowService.Frozen[] saved={null};
        var service=new GenerationFlowService(writes,new GenerationFlowService.FrozenStore(){public GenerationFlowService.Frozen committed(String key){return null;}public void save(GenerationFlowService.Frozen value,String key){saved[0]=value;}},
            (stage,json,hash)->{var input=CanonicalJson.parse(json);assertEquals(2,input.get(FrozenMemberBindings.FIELD).get("members").get(0).get("expected_revision").asInt());var request=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();request.put("provider","fictional-provider");request.put("model","fictional-model");request.put("input_hash",hash);request.putArray("messages").addObject().put("content",json);request.putObject("parameters");request.putObject("response_format").put("type","json_object");request.put("schema_version","fictional-v1");request.put("prompt_version","fictional-v1");return request.toString();},
            java.time.Clock.fixed(facts.cutoff,java.time.ZoneOffset.UTC),()->true,null,new SourceMemberInputEnricher(facts.service),()->flow.settings,
            run->{assertEquals("fictional-run",run);assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return collection;});
        var frozen=service.prepare(command,new SharedWriteExecutor.ActionContext("fictional-prepare",FrozenMemberBindingsTest.ROOT,null));
        assertEquals(frozen,saved[0]);assertEquals("queued",frozen.state());
        var original=CanonicalJson.parse(frozen.snapshotJson());facts.jdbc.update("UPDATE source_run_items SET revision=9");
        assertEquals(java.util.List.of(collection),FrozenCollectionManifest.read(original.get(FrozenCollectionManifest.FIELD)));
        assertEquals(2,FrozenMemberBindings.read(original,context.input(FrozenMemberBindingsTest.ROOT,"fictional-attempt",frozen.inputHash(),0)).get("fictional-item").expectedRevision());
    }
}
