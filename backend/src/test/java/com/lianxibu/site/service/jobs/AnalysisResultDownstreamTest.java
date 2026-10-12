package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.articles.JdbcEntityAnalysisState;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.node.ObjectNode;

class AnalysisResultDownstreamTest {
    GenerationJdbcStoreTest fixture;FrozenApplicationContext context;Input input;Validated result;ContentResultHandler handler;AnalysisResultDownstream downstream;
    String body="{\"title\":\"Fictional analysis\",\"sections\":[],\"citation_ids\":[],\"category\":\"agent\",\"entity_id\":\"fictional-entity\"}";
    SharedWriteExecutor.ActionContext action=new SharedWriteExecutor.ActionContext("fictional-apply","fictional-root","a".repeat(64));
    @BeforeEach void setup() {
        fixture=new GenerationJdbcStoreTest();fixture.fixture();var jdbc=fixture.jdbc;
        jdbc.execute(ResultJdbcStoreTest.CONTENT_DDL);
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),revision BIGINT,analysis_generation BIGINT,analysis_state VARCHAR(16),legacy_flag INT)");
        jdbc.update("INSERT INTO analyzed_entities VALUES('fictional-entity','agent',5,1,'queued',0)");
        context=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AGENT_ANALYSIS)));
        var evidence=new Evidence("fictional-item","catalog",LocalDate.of(2026,1,1),Instant.parse("2026-01-01T20:00:00Z"),"a".repeat(64),"{\"fictional\":true}",true,false,"accepted",List.of());
        var cmd=new Prepare("fictional-analysis",1,Stage.AGENT_ANALYSIS,LocalDate.of(2026,1,1),null,List.of(),List.of(evidence),List.of(),true,"a".repeat(64),context);
        var store=new GenerationJdbcStore(fixture.source,fixture.clock,"fictional-owner","fictional-root",cmd);
        var frozen=fixture.flow(store).prepare(cmd,new SharedWriteExecutor.ActionContext("fictional-prepare","fictional-root","a".repeat(64)));
        input=context.input("fictional-root","fictional-attempt",frozen.inputHash(),1);
        var payload=CanonicalJson.parse("{\"kind\":\"analysis\",\"body\":"+body+"}");
        var core=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",input.inputHash());core.set("payload",payload);
        try{var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update(CanonicalJson.bytes(core));core.put("result_hash",HexFormat.of().formatHex(hash.digest()));}catch(Exception e){throw new AssertionError(e);}
        result=ResultApplicationService.validate(input,core.toString());
        var reads=new ContentReadService(new JdbcContentReads(fixture.source),new DataSourceTransactionManager(fixture.source),ContentReadService.denyUnverifiedReferences());
        downstream=new AnalysisResultDownstream(new JdbcFrozenInputs(fixture.source),reads,new EntityAnalysisStateService(new JdbcEntityAnalysisState(fixture.source)));
        handler=new ContentResultHandler(new GeneratedContentService(new JdbcGeneratedContents(fixture.source),fixture.clock),()->context,downstream);
    }
    Applied apply(){return fixture.writes.execute(action,key->null,c->handler.apply(input,result,new Current(1,input.inputHash(),1,null,true,false,null,null,input.attemptId(),true,null),false));}
    String state(){return fixture.jdbc.queryForObject("SELECT analysis_state FROM analyzed_entities",String.class);}
    int bodies(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM contents",Integer.class);}
    @Test void realFrozenInputAndBodyCommitWithSameGenerationCompletion() {
        var applied=apply();assertEquals(context.contentId(),applied.contentId());assertEquals("analyzed",state());assertEquals(6,fixture.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));
        assertEquals("draft",new JdbcContentReads(fixture.source).current("agent",context.contentId()).state());
    }
    @Test void staleGenerationRollsBackBodyWithoutOverwritingEntity() {
        fixture.jdbc.update("UPDATE analyzed_entities SET analysis_generation=2,revision=9");assertThrows(RuntimeException.class,this::apply);assertEquals(0,bodies());assertEquals("queued",state());assertEquals(9,fixture.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));
    }
    @Test void outerFailureRollsBackBothAndKnownResultCanRetry() {
        assertThrows(IllegalStateException.class,()->fixture.writes.execute(action,key->null,c->{handler.apply(input,result,new Current(1,input.inputHash(),1,null,true,false,null,null,input.attemptId(),true,null),false);throw new IllegalStateException("Fictional receipt failure");}));
        assertEquals(0,bodies());assertEquals("queued",state());assertEquals(5,fixture.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));assertNotNull(apply());
    }
    @Test void wrongAppliedIdentityMissingBodyAndOutsideTransactionReject() {
        assertThrows(RuntimeException.class,()->downstream.afterApplied(input,context,result,new Applied(context.contentId(),1L)));
        assertThrows(RuntimeException.class,()->fixture.writes.execute(action,key->null,c->{downstream.afterApplied(input,context,result,new Applied(context.contentId(),1L));return true;}));
        var bad=new ContentResultHandler(new GeneratedContentService(new JdbcGeneratedContents(fixture.source),fixture.clock),()->context,(i,c,r,a)->downstream.afterApplied(i,c,r,new Applied("fictional-other",a.contentRevision())));
        assertThrows(RuntimeException.class,()->fixture.writes.execute(action,key->null,c->bad.apply(input,result,new Current(1,input.inputHash(),1,null,true,false,null,null,input.attemptId(),true,null),false)));assertEquals(0,bodies());assertEquals("queued",state());
    }
}
