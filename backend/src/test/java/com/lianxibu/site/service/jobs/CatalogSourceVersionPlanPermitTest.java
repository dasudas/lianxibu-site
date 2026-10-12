package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual native leaf proof and fresh locks. A source plan and whole-handoff remain separate consumers. */
class CatalogSourceVersionPlanPermitTest extends CatalogSourceVersionQualificationTest {
    CatalogSourceVersionPlanPermitService permits(){return new CatalogSourceVersionPlanPermitService(f.source,f.tx,f.category);}
    <T>T write(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-source-versions","fictional-run",null),k->null,c->work.get());}
    @Test void FourCategoryNativeProjectionsAreVerifiedAndKnownFalseRemainsPresent(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var frame=frame(1);var service=permits();var captured=service.capture(frame.selection(),frame.history());assertEquals(frame.versions().keySet(),captured.projections().keySet());
            assertTrue(write(()->{service.verify(captured);return true;}));assertEquals(frame.versions().values().iterator().next().hash(),captured.projections().values().iterator().next().hash());assertEquals(1,f.sends);
        }
        initialize("agent",false);current=f.catalogPayload();update();var frame=frame(1);var service=permits();var captured=service.capture(frame.selection(),frame.history());assertFalse(captured.projections().values().iterator().next().versionChanged());write(()->{service.verify(captured);return true;});
    }
    @Test void CompleteHundredAndOnePermissionsKeepLastMemberAndLastMutationRejectsTheWriter(){
        var frame=frame(101);var service=permits();var captured=service.capture(frame.selection(),frame.history());assertEquals(101,captured.projections().size());assertThrows(UnsupportedOperationException.class,()->captured.projections().clear());
        assertTrue(write(()->{service.verify(captured);return true;}));f.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id='fictional-version-plan-100'");long before=f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);
        assertThrows(RuntimeException.class,()->write(()->{service.verify(captured);f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");return true;}));assertEquals(before,f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));assertEquals(1,f.sends);
    }
    @Test void OriginalInputAndConfigurationChangesRefuseAllFuturePlanMutations(){
        for(String fault:List.of("input","config","content")){
            initialize("agent",false);var frame=frame(1);var service=permits();var captured=service.capture(frame.selection(),frame.history());
            switch(fault){case "input"->f.jdbc.update("UPDATE jobs SET payload_json=? WHERE purpose='input' AND owner_job_id=?","{\"fictional_changed\":true}",frame.versions().values().iterator().next().rootId());case "config"->f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{\"fictional_changed\":true}'");case "content"->f.jdbc.update("UPDATE contents SET revision=revision+1");}
            assertThrows(RuntimeException.class,()->write(()->{service.verify(captured);return true;}));assertEquals(1,f.sends);
        }
    }
    @Test void UnknownEmptySetsStillRequireWriterRoleAndTokensStayInstanceBound(){
        var frame=frame(1);f.jdbc.update("DELETE FROM contents WHERE id='fictional-analysis'");var service=permits();var captured=service.capture(frame.selection(),frame.history());assertTrue(captured.projections().isEmpty());
        assertThrows(RuntimeException.class,()->service.verify(captured));assertThrows(RuntimeException.class,()->write(()->{permits().verify(captured);return true;}));assertTrue(write(()->{service.verify(captured);return true;}));
        var ro=new TransactionTemplate(f.tx);ro.setReadOnly(true);ro.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertThrows(RuntimeException.class,()->ro.execute(s->{service.verify(captured);return true;}));
        var raw=new CatalogVersionCommitPermitService(f.source,f.tx,f.category);assertThrows(RuntimeException.class,()->raw.captureInSnapshot("fictional-entity","fictional-analysis","fictional-current-item",f.clock.instant()));
        assertThrows(RuntimeException.class,()->write(()->raw.captureInSnapshot("fictional-entity","fictional-analysis","fictional-current-item",f.clock.instant())));assertEquals(1,f.sends);
    }
}
