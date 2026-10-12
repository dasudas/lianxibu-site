package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
class CatalogVersionGenerationAllocationTest extends CatalogOriginalVersionPermitTest {
    CatalogVersionGenerationAllocationService allocator(CatalogVersionCommitPermitService guard){return new CatalogVersionGenerationAllocationService(f.source,guard,new com.lianxibu.site.service.articles.EntityAnalysisStateService(new com.lianxibu.site.repository.articles.JdbcEntityAnalysisState(f.source)),f.category);}
    Instant now(){return f.clock.instant().truncatedTo(ChronoUnit.MICROS);}
    @Test void FourCategoriesAllocateOnceThenJoinOnlyActualPendingIntentWithoutAnotherRevision(){
        for(String category:List.of("agent","skill","workflow","model")){
            original(category);var guard=guard();var service=allocator(guard);var first=frozen(guard);var created=write(()->service.allocate(first,now()));assertEquals(2,created.generation());
            long revision=f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);int jobs=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
            var fresh=frozen(guard);var joined=write(()->service.allocate(fresh,now()));assertEquals(created,joined);assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));assertEquals(jobs,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(1,f.sends);
        }
    }
    @Test void MissingWrongRoleHashDateFrozenRootAndChangedFreshStateCannotJoinOrAllocateAgain(){
        for(String fault:List.of("missing","role","hash","date","frozen","root","fresh")){
            original("agent");var guard=guard();var service=allocator(guard);var initial=frozen(guard);var created=write(()->service.allocate(initial,now()));
            switch(fault){case "missing"->f.jdbc.update("DELETE FROM jobs WHERE id=?",created.intentId());case "role"->f.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",created.intentId());case "hash"->f.jdbc.update("UPDATE jobs SET logical_key_hash=? WHERE id=?",new byte[32],created.intentId());case "date"->f.jdbc.update("UPDATE jobs SET business_date='2026-01-02' WHERE id=?",created.intentId());case "frozen"->f.jdbc.update("UPDATE jobs SET state='queued' WHERE id=?",created.intentId());case "root"->write(()->{new JdbcJobRecords(f.source).createRoot(new JdbcJobRecords.Root("fictional-pending-root",created.intentId(),"fictional-analysis-key",2,0,"agent_analysis",7),now());return true;});case "fresh"->{}}
            var fresh=frozen(guard);if("fresh".equals(fault))f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");
            int jobs=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);long generation=f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class);
            assertThrows(RuntimeException.class,()->write(()->service.allocate(fresh,now())));assertEquals(jobs,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(generation,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));
        }
    }
    @Test void AllocationAndCallerFailureRollbackTogetherAndWrongIssuerOrTimeReject(){
        original("agent");var guard=guard();var service=allocator(guard);var captured=frozen(guard);long revision=f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);
        assertThrows(RuntimeException.class,()->service.allocate(captured,now()));assertThrows(RuntimeException.class,()->write(()->allocator(guard()).allocate(captured,now())));
        assertThrows(RuntimeException.class,()->write(()->service.allocate(captured,now().minusSeconds(1))));
        assertThrows(RuntimeException.class,()->write(()->{service.allocate(captured,now());throw new IllegalStateException("fictional downstream rollback");}));
        assertEquals(revision,f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Long.class));assertEquals(2,write(()->service.allocate(captured,now())).generation());
    }
}
