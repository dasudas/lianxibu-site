package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual whole analysis results discovered through original native attempts and receipts, never current contents. */
class CatalogDayAnalysisDiscoveryTest {
    boolean mysql(){return false;}
    CatalogDayAnalysisCompletionTest helper(){return new CatalogDayAnalysisCompletionTest(){@Override boolean mysql(){return CatalogDayAnalysisDiscoveryTest.this.mysql();}};}
    <T>T read(CatalogDayAnalysisCompletionTest.Fixture f,java.util.function.Supplier<T> work){var tx=new TransactionTemplate(f.f().tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    @Test void ActualNewAndVersionTwoDiscoverExactOriginalWithoutCurrentContentAfterRestart(){
        for(boolean alias:List.of(false,true)){var h=helper();var f=h.fixture(alias?"agent":"model",alias);var cutoff=f.clock().instant().plusSeconds(5);assertNull(f.service().find(f.day(),f.entity(),cutoff));
            h.observe(f,Clock.fixed(cutoff,ZoneOffset.UTC));var expected=f.service().find(f.day(),f.entity(),f.version(),cutoff);assertNotNull(expected);
            var discovered=f.service().find(f.day(),f.entity(),cutoff);assertEquals(expected.version(),discovered.version());assertEquals(expected.reservation().json(),discovered.reservation().json());assertEquals(expected.bodyJson(),discovered.bodyJson());
            int jobs=f.f().jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);for(String table:List.of("contents","analyzed_entities","sources","source_runs","source_run_items"))f.f().jdbc.execute("DROP TABLE "+table);
            var restarted=new CatalogDayAnalysisCompletionService(f.f().source,f.f().tx,Clock.systemUTC(),alias?"agent":"model");assertEquals(expected.version(),restarted.find(f.day(),f.entity(),cutoff).version());assertEquals(jobs,f.f().jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f().sends);
        }
    }
    @Test void DamagedNativeAttemptIdentityAndResultReceiptCannotBeUsedAsLocators(){
        var h=helper();var f=h.fixture("skill",false);h.observe(f,Clock.fixed(f.clock().instant().plusSeconds(5),ZoneOffset.UTC));var dao=new JdbcCatalogAnalysisResultVersions(f.f().source);
        assertEquals(List.of(f.version()),read(f,()->dao.read(f.version().inputJobId())));
        f.f().jdbc.update("UPDATE jobs SET state='running' WHERE id=?",f.version().inputJobId());assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),f.clock().instant().plusSeconds(10)));f.f().jdbc.update("UPDATE jobs SET state='succeeded' WHERE id=?",f.version().inputJobId());
        var original=f.f().jdbc.queryForObject("SELECT record_identity_hash FROM jobs WHERE id=?",byte[].class,f.built().identity().attemptId());f.f().jdbc.update("UPDATE jobs SET record_identity_hash=? WHERE id=?",new byte[32],f.built().identity().attemptId());
        assertThrows(RuntimeException.class,()->read(f,()->dao.read(f.version().inputJobId())));f.f().jdbc.update("UPDATE jobs SET record_identity_hash=? WHERE id=?",original,f.built().identity().attemptId());
        f.f().jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='result_application' AND target_key_hash=UNHEX(?)",new byte[32],JdbcResultState.sha(JdbcFrozenInputs.canonical(tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("root_id",f.version().inputJobId()).put("attempt_id",f.built().identity().attemptId()))));
        assertThrows(RuntimeException.class,()->f.service().find(f.day(),f.entity(),f.clock().instant().plusSeconds(10)));assertEquals(2,f.f().sends);
    }
    @Test void MissingOriginalReservationAndUnknownRootStayAbsentButWriterAndOutsideCannotRead(){
        var h=helper();var f=h.fixture("workflow",false);var dao=new JdbcCatalogAnalysisResultVersions(f.f().source);
        assertTrue(read(f,()->dao.read("fictional-unknown-root")).isEmpty());assertNull(f.service().find(f.day(),"fictional-unknown-entity",f.clock().instant()));
        assertThrows(RuntimeException.class,()->dao.read(f.version().inputJobId()));var writer=new TransactionTemplate(f.f().tx);writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writer.execute(s->{assertThrows(RuntimeException.class,()->dao.read(f.version().inputJobId()));assertThrows(RuntimeException.class,()->f.service().read(f.day(),f.entity(),f.clock().instant()));return null;});
        var source=mock(javax.sql.DataSource.class);new JdbcCatalogAnalysisResultVersions(source);verifyNoInteractions(source);
    }
}
