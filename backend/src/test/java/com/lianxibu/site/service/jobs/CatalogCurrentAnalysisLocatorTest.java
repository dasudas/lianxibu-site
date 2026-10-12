package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.JdbcCatalogCurrentAnalysisContents;
import com.lianxibu.site.service.articles.CatalogCurrentAnalysisContentService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual native content locators, without treating them as successful-analysis or publication authority. */
class CatalogCurrentAnalysisLocatorTest extends CatalogVersionQualificationTest {
    CatalogCurrentAnalysisContentService locator(){return new CatalogCurrentAnalysisContentService(f.source,f.tx,f.category);}
    @Test void FourCategoriesFindActualCurrentContentAndEmptyOrAbsentNeverManufacturesAnAnalysis(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var locator=locator();assertEquals(Map.of("fictional-entity","fictional-analysis"),locator.find(Set.of("fictional-entity","fictional-absent")));assertTrue(locator.find(Set.of()).isEmpty());assertTrue(locator.find(Set.of("fictional-absent")).isEmpty());assertEquals(1,f.sends);
        }
    }
    @Test void ExactHundredNativeLocatorsAreCompleteAndHundredAndOneRejectsWithoutTruncation(){
        var expected=new TreeMap<String,String>();
        for(int n=0;n<100;n++){
            String entity=String.format(Locale.ROOT,"fictional-locator-entity-%03d",n),content=String.format(Locale.ROOT,"fictional-locator-content-%03d",n);
            f.jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision,body_revision,input_job_id,entity_id,body_hash_v1,updated_at) SELECT kind,?,?,title,body_json,state,revision,body_revision,input_job_id,?,body_hash_v1,updated_at FROM contents WHERE id='fictional-analysis'",content,content,entity);expected.put(entity,content);
        }
        var full=locator().find(expected.keySet());assertEquals(expected,full);assertTrue(full.containsKey("fictional-locator-entity-099"));assertThrows(RuntimeException.class,full::clear);var tooMany=new HashSet<>(expected.keySet());tooMany.add("fictional-entity");assertThrows(CatalogCurrentAnalysisContentService.CapacityRejected.class,()->locator().find(tooMany));assertEquals(1,f.sends);
    }
    @Test void MultipleContentsForOneEntityAreRejectedInsteadOfGuessingLatest(){
        f.jdbc.update("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision,body_revision,input_job_id,entity_id,body_hash_v1,updated_at) SELECT kind,'fictional-second-analysis','fictional-second-analysis',title,body_json,state,revision+1,body_revision,input_job_id,entity_id,body_hash_v1,updated_at FROM contents WHERE id='fictional-analysis'");assertThrows(RuntimeException.class,()->locator().find(Set.of("fictional-entity")));assertEquals(1,f.sends);
    }
    @Test void BorrowedSnapshotRejectsWriterReadCommittedAndNestedOwnRead(){
        var own=locator();assertThrows(RuntimeException.class,()->own.findInSnapshot(Set.of()));var writer=new TransactionTemplate(f.tx);assertThrows(RuntimeException.class,()->writer.execute(s->own.find(Set.of())));assertThrows(RuntimeException.class,()->writer.execute(s->own.findInSnapshot(Set.of())));
        var read=new TransactionTemplate(f.tx);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->read.execute(s->own.findInSnapshot(Set.of())));read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);assertEquals(Map.of("fictional-entity","fictional-analysis"),read.execute(s->own.findInSnapshot(Set.of("fictional-entity"))));
    }
    @Test void NativeCaseAliasCannotReturnAnUnrequestedEntity(){
        f.jdbc.update("UPDATE contents SET entity_id='FICTIONAL-ENTITY' WHERE id='fictional-analysis'");if(mysql())assertThrows(RuntimeException.class,()->locator().find(Set.of("fictional-entity")));else assertTrue(locator().find(Set.of("fictional-entity")).isEmpty());assertEquals(1,f.sends);
    }
}
