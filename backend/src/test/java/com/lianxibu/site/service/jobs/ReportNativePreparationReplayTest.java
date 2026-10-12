package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ReportInputProfile;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.node.ObjectNode;

/** Actual native INPUT/root/intent/preparation receipt atomicity. Source and no-update evidence genesis are fictional ports. */
class ReportNativePreparationReplayTest {
    GenerationJdbcStoreTest h;GenerationJdbcStore store;GenerationFlowService flow;Prepare command;
    int sourceReads,requestBuilds;boolean allowed=true,missing,content,noUpdate;
    static final LocalDate DAY=LocalDate.parse("2026-01-01");
    Clock clock=Clock.fixed(Instant.parse("2026-01-01T21:00:00.123456Z"),ZoneOffset.UTC);
    GenerationJdbcStoreTest fixture(){var f=new GenerationJdbcStoreTest(){@Override void fixture(){var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:native-report-replay-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL;INIT=SET TIME ZONE 'UTC'");source=ds;jdbc=new JdbcTemplate(ds);schema();writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source),()->25,n->{});}};f.fixture();return f;}
    @BeforeEach void setup(){h=fixture();allowed=true;missing=false;content=false;noUpdate=false;sourceReads=requestBuilds=0;
        var ctx=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.DAILY_REPORT));((ObjectNode)ctx.get("target_content")).putNull("base_revision");
        for(String field:List.of("item_ids","candidates_by_item","eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets"))ctx.putArray(field);
        command=new Prepare("report:daily:"+DAY,1,Stage.DAILY_REPORT,DAY,null,List.of(),List.of(),List.of(),true,"a".repeat(64),FrozenApplicationContext.fromJson(ctx));compose();
    }
    SharedWriteExecutor.ActionContext action(){return new SharedWriteExecutor.ActionContext("fictional-report-prepare","fictional-report-root","a".repeat(64));}
    void compose(){store=new GenerationJdbcStore(h.source,clock,"fictional-owner","fictional-report-root",command);
        flow=new GenerationFlowService(h.writes,store,(stage,snapshot,hash)->{requestBuilds++;return "{\"provider\":\"fictional\",\"model\":\"fictional\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional\"}],\"response_format\":{},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}";},clock,()->allowed,new ReportInputSnapshotService((cmd,t)->{
            sourceReads++;var candidates=new ArrayList<Candidate>();if(content){String raw="{\"fictional_body\":true}";String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(raw));var m=new Metadata(new ResourceRef("source_analysis","fictional-source-result",null),1L,"fictional-version",hash,"fictional-source","fictional-run",null,DAY,"Fictional title","Fictional summary",null);var e=new Evidence("fictional-body","source_analysis",DAY,t.minusSeconds(1),hash,raw,true,false,"accepted",List.of());candidates.add(new Candidate(Group.SOURCE_SUMMARIES,m,e,"news_tech",true,true,null,null));}
            return new SourceProof(t,DAY,DAY.plusDays(1),true,candidates,missing?List.of(new MissingInput("source_analysis",null,DAY,"fictional-source-unproven")):List.of(),noUpdate?List.of(DAY):List.of(),List.of());
        }));
    }
    @Test void ActualKnownEmptyFrozenReportReplaysOriginalNoInputWithoutSourceOrRequestRebuild(){var first=flow.prepare(command,action());assertEquals("no_input",first.state());assertEquals("no_input",first.coverageState());assertNotNull(first.snapshotJson());ReportInputProfile.validated(CanonicalJson.parse(first.snapshotJson()));assertEquals(0,requestBuilds);assertEquals(0,h.count("record_kind='ai_attempt'"));assertEquals(0,new JdbcJobRecords(h.source).byId(first.ownerId()).callCount());allowed=false;content=true;compose();assertEquals(first,flow.prepare(command,action()));assertEquals(1,sourceReads);assertEquals(0,requestBuilds);assertEquals(1,h.count("record_kind='logical_task'"));}
    @Test void MissingEvidenceIsStillOriginalNoInputAndNeverBecomesACompleteNoUpdateClaim(){missing=true;var first=flow.prepare(command,action());assertEquals("no_input",first.state());assertEquals("no_input",first.coverageState());assertEquals(1,first.missing().size());var profile=CanonicalJson.parse(first.snapshotJson()).get(ReportInputProfile.FIELD);assertTrue(profile.get("coverage").get("no_update_dates").isEmpty());assertEquals(1,profile.get("coverage").get("missing_inputs").size());missing=false;allowed=false;compose();assertEquals(first,flow.prepare(command,action()));assertEquals(1,sourceReads);assertEquals(0,requestBuilds);}
    @Test void CoverageOnlyReplaysPersistedNoUpdateDatesWithoutInventingAModelAttempt(){noUpdate=true;var first=flow.prepare(command,action());assertEquals("coverage_only",first.state());assertEquals("complete",first.coverageState());assertEquals(1,CanonicalJson.parse(first.snapshotJson()).get(ReportInputProfile.FIELD).get("coverage").get("no_update_dates").size());noUpdate=false;allowed=false;compose();assertEquals(first,flow.prepare(command,action()));assertEquals(0,requestBuilds);assertEquals(0,h.count("record_kind='ai_attempt'"));}
    @Test void OriginalQueuedReportKeepsCompleteRequestAndSnapshotOnNativeReplay(){content=true;var first=flow.prepare(command,action());assertEquals("queued",first.state());assertEquals("complete",first.coverageState());assertNotNull(first.requestJson());content=false;allowed=false;compose();assertEquals(first,flow.prepare(command,action()));assertEquals(1,sourceReads);assertEquals(1,requestBuilds);assertEquals(1,h.count("record_kind='ai_attempt'"));assertEquals(0,new JdbcJobRecords(h.source).byId(first.ownerId()).callCount());}
    @Test void RehashedReceiptCoverageCannotReplaceTheOriginalFullProfile(){flow.prepare(command,action());var body=(ObjectNode)CanonicalJson.parse(h.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='generation_prepare'",String.class));body.put("coverage_state","complete");String raw=JdbcFrozenInputs.canonical(body);h.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='generation_prepare'",raw,JdbcGenerationLineage.hash(raw));assertThrows(RuntimeException.class,()->flow.prepare(command,action()));assertEquals(1,sourceReads);}
    @Test void NativeReceiptFailureRollsBackRootIntentAndFullNoInputParts(){h.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_report_no_input_failure CHECK(target_type IS NULL OR target_type<>'generation_prepare')");assertThrows(RuntimeException.class,()->flow.prepare(command,action()));assertEquals(0,h.count("1=1"));assertEquals(0,requestBuilds);}
}
