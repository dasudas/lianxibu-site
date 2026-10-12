package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual native leaf qualifications. The parent source-plan facts are explicit fixture values, not a whole-handoff grant. */
class CatalogSourceVersionQualificationTest extends CatalogVersionSourcePlanTest {
    CatalogSourceVersionQualificationService provider(){return new CatalogSourceVersionQualificationService(f.source,f.tx,f.category);}
    Map<String,CatalogVersionQualificationProjection.Projection> capture(Frame frame){return provider().capture(frame.selection(),frame.history());}
    @Test void FourCategoriesRecoverExactIssuedProjectionsAndKnownFalse(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var frame=frame(1);var actual=capture(frame);assertEquals(frame.versions().keySet(),actual.keySet());
            actual.forEach((id,p)->assertEquals(frame.versions().get(id).hash(),p.hash()));assertTrue(actual.values().iterator().next().versionChanged());assertEquals(1,f.sends);
        }
        initialize("agent",false);current=f.catalogPayload();update();var frame=frame(1);assertFalse(capture(frame).values().iterator().next().versionChanged());
    }
    @Test void AllHundredAndOneNativeLeafQualificationsRemainCompleteAndImmutable(){
        var frame=frame(101);var actual=capture(frame);assertEquals(101,actual.size());assertEquals(frame.versions().get("fictional-version-plan-100").hash(),actual.get("fictional-version-plan-100").hash());
        assertThrows(UnsupportedOperationException.class,actual::clear);assertEquals(1,f.sends);assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
    }
    @Test void MissingOldContentOrCandidateIsUnknownButCurrentFactsMustStillBeExact(){
        var frame=frame(1);f.jdbc.update("DELETE FROM contents WHERE id='fictional-analysis'");assertTrue(capture(frame).isEmpty());
        var emptyHistory=new NewsCandidateRecall.CategoryHistory(f.category,new NewsCandidateRecall.History(frame.selection().cutoff(),List.of()));assertTrue(provider().capture(frame.selection(),emptyHistory).isEmpty());
        f.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id='fictional-version-plan-000'");assertThrows(RuntimeException.class,()->capture(frame));assertThrows(RuntimeException.class,()->provider().capture(frame.selection(),emptyHistory));assertEquals(1,f.sends);
    }
    @Test void LastCurrentBodyRunAndCapturedEntityRevisionMismatchRejectEntireCapture(){
        var frame=frame(2);var entries=new ArrayList<>(frame.selection().entries());var last=entries.getLast();var fact=last.fact();
        var changed=CanonicalJson.parse(fact.payloadJson()).deepCopy();((tools.jackson.databind.node.ObjectNode)changed).put("document_text","Fictional stale last body");
        entries.set(entries.size()-1,new CatalogDedupEligibilityService.Entry(last.itemId(),last.reason(),new SourceMemberEvidenceService.Fact(fact.id(),fact.revision(),fact.category(),fact.identityKey(),fact.title(),fact.summary(),JdbcFrozenInputs.canonical(changed)),last.businessDate(),last.completedAt()));
        var stale=new CatalogDedupEligibilityService.Selection(frame.selection().runId(),frame.selection().sourceId(),f.category,frame.selection().day(),frame.selection().cutoff(),entries);
        assertThrows(RuntimeException.class,()->provider().capture(stale,frame.history()));
        var wrongRun=new CatalogDedupEligibilityService.Selection("fictional-other-run",frame.selection().sourceId(),f.category,frame.selection().day(),frame.selection().cutoff(),frame.selection().entries());assertThrows(RuntimeException.class,()->provider().capture(wrongRun,frame.history()));
        f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");assertThrows(RuntimeException.class,()->capture(frame));assertEquals(1,f.sends);
    }
    @Test void OwnAndBorrowedSnapshotsEnforceActualReadonlyRepeatableRead(){
        var frame=frame(1);var provider=provider();assertThrows(RuntimeException.class,()->provider.captureInSnapshot(frame.selection(),frame.history()));var tx=new TransactionTemplate(f.tx);
        assertThrows(RuntimeException.class,()->tx.execute(s->provider.capture(frame.selection(),frame.history())));assertThrows(RuntimeException.class,()->tx.execute(s->provider.captureInSnapshot(frame.selection(),frame.history())));
        tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->tx.execute(s->provider.captureInSnapshot(frame.selection(),frame.history())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertEquals(1,tx.execute(s->provider.captureInSnapshot(frame.selection(),frame.history())).size());
        assertThrows(RuntimeException.class,()->new CatalogSourceVersionQualificationService(f.source,f.tx,"news_tech"));assertEquals(1,f.sends);
    }
}
