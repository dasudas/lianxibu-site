package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

class GenerationLineageServiceTest {
    OperationalPersistenceFixture f;GenerationLineageService service;
    @BeforeEach void setup(){f=new OperationalPersistenceFixture();f.jdbc.execute("ALTER TABLE jobs ADD supersedes_job_id VARCHAR(64)");service=new GenerationLineageService(new JdbcGenerationLineage(f.source),f.clock,"fictional-owner");}
    void seed(String id,long generation){f.writes.execute(new SharedWriteExecutor.ActionContext("seed:"+id,id,null),k->null,a->{var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent("intent-"+id,"fictional-key",generation,"daily_report",LocalDate.of(2026,1,1)),f.clock.instant());records.createRoot(new JdbcJobRecords.Root(id,"intent-"+id,"fictional-key",generation,0,"daily_report",11),f.clock.instant());return true;});}
    GenerationLineageService.Lineage append(String id){return f.writes.execute(new SharedWriteExecutor.ActionContext("lineage:"+id,id,null),k->null,a->service.record(id));}
    @Test void firstAndSuccessorKeepPermanentOriginalLinks(){seed("fictional-first",1);assertNull(append("fictional-first").supersedesJobId());seed("fictional-next",2);assertEquals("fictional-first",append("fictional-next").supersedesJobId());service=new GenerationLineageService(new JdbcGenerationLineage(f.source),f.clock,"fictional-other-reader");assertEquals(2,service.read("fictional-next").generation());assertNull(service.read("fictional-first").supersedesJobId());assertEquals("fictional-first",append("fictional-next").supersedesJobId());}
    @Test void missingPreviousProofAndSkippedGenerationAreRejected(){seed("fictional-first",1);seed("fictional-next",2);assertThrows(RuntimeException.class,()->append("fictional-next"));assertThrows(RuntimeException.class,()->service.read("fictional-first"));seed("fictional-gap",4);assertThrows(RuntimeException.class,()->append("fictional-gap"));}
    @Test void tamperingAndTransactionFailureCannotInventHistory(){seed("fictional-first",1);assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-rollback","fictional-first",null),k->null,a->{service.record("fictional-first");throw new IllegalStateException("fictional tail failure");}));assertThrows(RuntimeException.class,()->service.read("fictional-first"));append("fictional-first");f.jdbc.update("UPDATE jobs SET action_payload='{}' WHERE target_type='generation_lineage'");assertThrows(RuntimeException.class,()->service.read("fictional-first"));}
}
