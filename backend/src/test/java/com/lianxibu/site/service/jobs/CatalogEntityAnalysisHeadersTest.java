package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.articles.CatalogEntityAnalysisHeaderService;
import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import java.util.*;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual native entity metadata only; queued header fixtures do not fabricate generation intents. */
class CatalogEntityAnalysisHeadersTest extends CatalogVersionQualificationTest {
    CatalogEntityAnalysisHeaderService service(){return new CatalogEntityAnalysisHeaderService(f.source,f.tx,f.category);}
    <T>T snapshot(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    <T>T write(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-entity-header-writer","fictional-entity",null),k->null,a->work.get());}
    @Test void FourCategoryNativeIdentityAndStateObserveThenLockWithoutChangingAnything(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var service=service();var header=service.find(Set.of("fictional-entity")).get("fictional-entity");assertEquals(category,header.category());assertEquals(1,header.generation());assertEquals("analyzed",header.state());assertEquals(HexFormat.of().formatHex(JdbcEntityDedup.identityHash(category,header.identityKey())),header.identityHash());assertEquals(header,snapshot(()->service.findInSnapshot(Set.of("fictional-entity"))).get("fictional-entity"));assertEquals(header,write(()->service.lockInWriter(Set.of("fictional-entity"))).get("fictional-entity"));assertEquals(1,f.sends);assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
        }
    }
    @Test void EveryHundredNativeHeaderIsReturnedAndMissingAliasCategoryAndCapacityReject(){
        var service=service();f.jdbc.update("DELETE FROM analyzed_entities");var ids=new TreeSet<String>();for(int n=0;n<100;n++){String id="fictional-header-"+n;ids.add(id);String identity="{\"namespace\":\"repo\",\"key\":\"fictional-"+n+"\"}";f.jdbc.update("INSERT INTO analyzed_entities(id,category,identity_key,identity_hash,revision,analysis_generation,analysis_state,legacy_flag) VALUES(?,'agent',?,?,0,0,'pending',0)",id,identity,JdbcEntityDedup.identityHash("agent",identity));}var values=service.find(ids);assertEquals(100,values.size());assertEquals("pending",values.get("fictional-header-99").state());assertThrows(UnsupportedOperationException.class,values::clear);var tooMany=new HashSet<>(ids);tooMany.add("fictional-header-extra");assertThrows(CatalogEntityAnalysisHeaderService.CapacityRejected.class,()->service.find(tooMany));assertThrows(RuntimeException.class,()->service.find(Set.of("fictional-absent")));f.jdbc.update("UPDATE analyzed_entities SET category='model' WHERE id='fictional-header-99'");assertThrows(RuntimeException.class,()->service.find(ids));assertEquals(1,f.sends);
    }
    @Test void InvalidNativeIdentityStateLegacyAndWideKeyCannotGrantAnyHeader(){
        var service=service();var original=service.find(Set.of("fictional-entity")).get("fictional-entity");for(String update:List.of("identity_hash=NULL","identity_hash=UNHEX('00')","analysis_state='unknown'","analysis_generation=0","legacy_flag=1")){
            f.jdbc.update("UPDATE analyzed_entities SET "+update+" WHERE id='fictional-entity'");assertThrows(RuntimeException.class,()->service.find(Set.of("fictional-entity")));f.jdbc.update("UPDATE analyzed_entities SET identity_hash=?,analysis_state='analyzed',analysis_generation=1,legacy_flag=0 WHERE id='fictional-entity'",HexFormat.of().parseHex(original.identityHash()));
        }
        f.jdbc.execute(mysql()?"ALTER TABLE analyzed_entities MODIFY identity_key MEDIUMTEXT":"ALTER TABLE analyzed_entities ALTER COLUMN identity_key CLOB");f.jdbc.update("UPDATE analyzed_entities SET identity_key=?","F".repeat(4097));assertThrows(CatalogEntityAnalysisHeaderService.CapacityRejected.class,()->service.find(Set.of("fictional-entity")));assertEquals(1,f.sends);
    }
    @Test void ExactTransactionRolesAndNativeRepeatableReadAreKeptSeparateFromFreshLocks()throws Exception{
        var service=service();assertThrows(RuntimeException.class,()->service.findInSnapshot(Set.of("fictional-entity")));assertThrows(RuntimeException.class,()->service.lockInWriter(Set.of("fictional-entity")));assertThrows(RuntimeException.class,()->snapshot(()->service.lockInWriter(Set.of("fictional-entity"))));assertThrows(RuntimeException.class,()->write(()->service.findInSnapshot(Set.of("fictional-entity"))));assertThrows(RuntimeException.class,()->snapshot(()->service.find(Set.of("fictional-entity"))));
        var first=service.find(Set.of("fictional-entity")).get("fictional-entity");if(mysql())snapshot(()->{var a=service.findInSnapshot(Set.of("fictional-entity"));try(Connection connection=f.source.getConnection();var statement=connection.prepareStatement("UPDATE analyzed_entities SET revision=revision+1,analysis_generation=2,analysis_state='queued' WHERE id='fictional-entity'")){assertEquals(1,statement.executeUpdate());}catch(Exception failure){throw new AssertionError(failure);}assertEquals(a,service.findInSnapshot(Set.of("fictional-entity")));return null;});else f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1,analysis_generation=2,analysis_state='queued'");var fresh=write(()->service.lockInWriter(Set.of("fictional-entity"))).get("fictional-entity");assertNotEquals(first,fresh);assertEquals(2,fresh.generation());assertEquals("queued",fresh.state());assertEquals(1,f.sends);
    }
    @Test void ClosedCategoryConstructionDoesNoIo(){var source=mock(DataSource.class);var manager=mock(PlatformTransactionManager.class);for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogEntityAnalysisHeaderService(source,manager,category));verifyNoInteractions(source,manager);assertThrows(RuntimeException.class,()->new CatalogEntityAnalysisHeaderService(source,manager,"news_tech"));}
}
