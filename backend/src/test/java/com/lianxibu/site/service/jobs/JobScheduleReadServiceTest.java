package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JobScheduleReadServiceTest extends GenerationJdbcStoreTest {
    static final Clock PRECISE=Clock.fixed(Instant.parse("2026-01-01T21:00:00.123456Z"),ZoneOffset.UTC);
    JobScheduleReadService reader(){return reader(Clock.offset(PRECISE,Duration.ofDays(40)));}
    JobScheduleReadService reader(Clock observed){return new JobScheduleReadService(new JdbcJobSchedules(source),new JdbcFrozenInputs(source),new DataSourceTransactionManager(source),observed);}
    void queue(){store=new GenerationJdbcStore(source,PRECISE,"fictional-owner",OWNER,command(false));flow(store).prepare(command(false),action());}
    @Test void firstQueueProofSurvivesRestartAndDoesNotUseMutableCreationOrEligibility(){queue();jdbc.update("UPDATE jobs SET created_at=?,updated_at=?,eligible_at=? WHERE id=?",java.sql.Timestamp.from(PRECISE.instant().minusSeconds(500)),java.sql.Timestamp.from(PRECISE.instant().plusSeconds(900)),java.sql.Timestamp.from(PRECISE.instant().plusSeconds(86400)),OWNER);var value=reader().read(OWNER).orElseThrow();assertEquals(OWNER,value.jobId());assertEquals(PRECISE.instant(),value.firstQueuedAt());assertNull(value.period());assertEquals(value,reader().read(OWNER).orElseThrow());}
    @Test void reportPeriodsComeFromCompleteFrozenProfilesForAllThreeKinds(){for(Stage stage:List.of(Stage.DAILY_REPORT,Stage.WEEKLY_REPORT,Stage.MONTHLY_REPORT)){
        fixture();LocalDate end=stage==Stage.WEEKLY_REPORT?LocalDate.of(2026,1,4):stage==Stage.MONTHLY_REPORT?LocalDate.of(2026,1,31):LocalDate.of(2026,1,1),start=stage==Stage.WEEKLY_REPORT?end.minusDays(6):end.withDayOfMonth(1);
        Clock time=Clock.fixed(end.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC);
        var c=new Prepare("fictional-report-period",1,stage,end,null,List.of(),List.of(),List.of(),false,HASH);
        var bound=new GenerationJdbcStore(source,time,"fictional-owner",OWNER,c);
        var profiles=new ReportInputSnapshotService((command,cutoff)->new ReportInputSnapshotService.SourceProof(cutoff,start,end.plusDays(1),true,List.of(),List.of(),List.of(),List.of()));
        new GenerationFlowService(writes,bound,(s,input,hash)->{throw new AssertionError("empty report must not call AI");},time,()->true,profiles).prepare(c,action());
        var value=reader().read(OWNER).orElseThrow();assertEquals(start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),value.period().start());assertEquals(end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),value.period().end());assertEquals("Asia/Shanghai",value.period().timezone());assertNull(value.firstQueuedAt());
    }}
    @Test void missingOldProofIsUnknownWhileWrongHashOrOwnerIsUnavailable(){queue();jdbc.update("DELETE FROM jobs WHERE record_kind='disposition'");assertNull(reader().read(OWNER).orElseThrow().firstQueuedAt());fixture();queue();jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='generation_prepare'",new byte[32]);assertThrows(RuntimeException.class,()->reader().read(OWNER));fixture();queue();jdbc.update("UPDATE jobs SET snapshot_owner_id='fictional-other' WHERE record_kind='ai_attempt'");assertThrows(RuntimeException.class,()->reader().read(OWNER));}
    @Test void corruptFrozenChunkWrongRootStageAndFutureAdmissionNeverLookLikeUnknown(){queue();jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='input'",new byte[32]);assertThrows(RuntimeException.class,()->reader().read(OWNER));fixture();queue();jdbc.update("UPDATE jobs SET task_type='weekly_report' WHERE id=?",OWNER);assertThrows(RuntimeException.class,()->reader().read(OWNER));fixture();queue();assertThrows(RuntimeException.class,()->reader(Clock.offset(PRECISE,Duration.ofSeconds(-1))).read(OWNER));}
    @Test void missingAndCaseMismatchedJobDoNotBorrowAnotherJob(){assertTrue(reader().read("fictional-missing").isEmpty());queue();assertTrue(reader().read("fictional-root-other").isEmpty());if(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id='FICTIONAL-ROOT'",Integer.class)>0)assertThrows(RuntimeException.class,()->reader().read("FICTIONAL-ROOT"));else assertTrue(reader().read("FICTIONAL-ROOT").isEmpty());}
}
