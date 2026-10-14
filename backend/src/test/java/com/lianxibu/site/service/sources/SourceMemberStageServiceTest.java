package com.lianxibu.site.service.sources;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.sources.JdbcSourceMemberStages;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class SourceMemberStageServiceTest {
 DataSource source;JdbcTemplate jdbc;SharedWriteExecutor writes;SourceMemberStageService service;
 final Clock clock=Clock.fixed(Instant.parse("2026-01-03T01:00:00.123456Z"),ZoneOffset.UTC);
 DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:member-stage-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
 @BeforeEach void setup(){source=source();jdbc=new JdbcTemplate(source);
  for(String table:List.of("source_run_items","source_runs","worker_lock"))jdbc.execute("DROP TABLE IF EXISTS "+table);
  jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
  jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,activation_stage VARCHAR(16),delivery_state VARCHAR(32),job_refs_json "+(source instanceof DriverManagerDataSource d&&d.getUrl().startsWith("jdbc:h2:")?"CLOB":"JSON")+",revision BIGINT)");
  jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),source_item_id TEXT,category VARCHAR(16),entity_id VARCHAR(64),state VARCHAR(32),revision BIGINT,terminal_state VARCHAR(16),effective_valid INT,projection_decision VARCHAR(16),target_business_date DATE,analysis_job_id VARCHAR(64),applied_at TIMESTAMP(6),part_no BIGINT)");
  for(int i=1;i<=2;i++){jdbc.update("INSERT INTO source_runs VALUES(?,?,?,? ,1,'production','complete',?,2)","fictional-run-"+i,"fictional-source","daily",java.sql.Date.valueOf("2026-01-01"),json("[]"));jdbc.update("INSERT INTO source_run_items VALUES(?,?,?,?,?,?,'ready',3,'accepted',1,'initialized',NULL,NULL,NULL,7)","fictional-item-"+i,"fictional-run-"+i,"fictional-source","fictional-original-"+i,"news_tech","fictional-entity");}
  writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source));service=new SourceMemberStageService(new JdbcSourceMemberStages(source),clock);
 }
 Object json(String value){return value;}
 List<SourceMemberStageService.Expected> expected(){return List.of(new SourceMemberStageService.Expected("fictional-item-1",3,"news_tech","fictional-entity","ready"),new SourceMemberStageService.Expected("fictional-item-2",3,"news_tech","fictional-entity","ready"));}
 List<SourceMemberStageService.Fact> apply(){return writes.execute(new SharedWriteExecutor.ActionContext("fictional-completion","fictional-root",null),k->null,a->service.complete("fictional-root",expected()));}
 @Test void preservesOriginalRunFactsAndLinksEveryAffectedRun(){jdbc.update("UPDATE source_run_items SET source_item_id=? WHERE id='fictional-item-1'","fictional-"+"x".repeat(300));var facts=apply();assertEquals(310,facts.getFirst().sourceItemId().length());assertEquals(2,facts.size());assertEquals("fictional-run-1",facts.getFirst().runId());assertEquals(LocalDate.parse("2026-01-01"),facts.getFirst().businessDate());assertNull(facts.getFirst().targetBusinessDate());assertEquals("production",facts.getFirst().activationStage());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id='fictional-root' AND revision=4 AND part_no=7",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE revision=3",Integer.class));assertEquals(clock.instant(),jdbc.queryForObject("SELECT applied_at FROM source_run_items WHERE id='fictional-item-1'",java.sql.Timestamp.class).toInstant());}
 @Test void staleMemberRollsBackTheWholeBatch(){jdbc.update("UPDATE source_run_items SET revision=4 WHERE id='fictional-item-2'");assertThrows(RuntimeException.class,this::apply);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id IS NOT NULL",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE revision<>2",Integer.class));}
 @Test void invalidEvidenceAndProbeAreRejected(){jdbc.update("UPDATE source_run_items SET effective_valid=0 WHERE id='fictional-item-2'");assertThrows(RuntimeException.class,this::apply);jdbc.update("UPDATE source_run_items SET effective_valid=1");jdbc.update("UPDATE source_runs SET run_kind='probe' WHERE id='fictional-run-2'");assertThrows(RuntimeException.class,this::apply);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id IS NOT NULL",Integer.class));}
 @Test void outerReceiptFailureRollsBackPointersAndAssociations(){assertThrows(RuntimeException.class,()->writes.execute(new SharedWriteExecutor.ActionContext("fictional-failed-completion","fictional-root",null),k->null,a->{service.complete("fictional-root",expected());throw new IllegalStateException("fictional receipt failure");}));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id IS NOT NULL",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE revision<>2",Integer.class));assertThrows(IllegalStateException.class,()->service.complete("fictional-root",expected()));}
 @Test void malformedRunReferencesRejectAllAndExistingLinkDoesNotDuplicate(){jdbc.update("UPDATE source_runs SET job_refs_json=? WHERE id='fictional-run-2'",json("{}"));assertThrows(RuntimeException.class,this::apply);jdbc.update("UPDATE source_runs SET job_refs_json=?",json("[\"fictional-root\"]"));apply();assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE revision=2",Integer.class));}
 @Test void databaseCollationAliasCannotSubstituteAnotherExactMember(){assertThrows(RuntimeException.class,()->writes.execute(new SharedWriteExecutor.ActionContext("fictional-alias","fictional-root",null),k->null,a->service.complete("fictional-root",List.of(new SourceMemberStageService.Expected("FICTIONAL-item-1",3,"news_tech","fictional-entity","ready")))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id IS NOT NULL",Integer.class));}
 List<SourceMemberStageService.Fact> categoryCompletion(String category){var values=List.of(new SourceMemberStageService.Expected("fictional-item-1",3,category,"fictional-entity","ready"),new SourceMemberStageService.Expected("fictional-item-2",3,category,"fictional-entity","ready"));return writes.execute(new SharedWriteExecutor.ActionContext("fictional-catalog-completion","fictional-root",null),k->null,a->service.complete("fictional-root",values));}
 @Test void FourCatalogCategoriesCompleteWithNoNewsProjectionAndNeverFillIt(){
  for(String category:List.of("agent","skill","workflow","model")){setup();jdbc.update("UPDATE source_run_items SET category=?,projection_decision=NULL",category);var facts=categoryCompletion(category);assertEquals(2,facts.size());assertTrue(facts.stream().allMatch(v->category.equals(v.category())&&v.businessDate().equals(LocalDate.of(2026,1,1))&&v.revision()==4));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE projection_decision IS NULL AND analysis_job_id='fictional-root' AND part_no=7",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE revision=3",Integer.class));}
 }
 @Test void NewsNullAndUnknownNonNullCatalogProjectionCannotBecomeCompletedMembers(){
  setup();jdbc.update("UPDATE source_run_items SET projection_decision=NULL");assertThrows(RuntimeException.class,this::apply);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id IS NOT NULL",Integer.class));
  for(String category:List.of("agent","skill","workflow","model")){setup();jdbc.update("UPDATE source_run_items SET category=?,projection_decision='fictional_bad'",category);assertThrows(RuntimeException.class,()->categoryCompletion(category));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE analysis_job_id IS NOT NULL OR revision<>3",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE revision<>2",Integer.class));}
 }

}
