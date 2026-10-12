package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.sql.Connection;

/** Pure warmup is not authority; real native proofs and original receipts remain mandatory. */
class GenerationPureInputPrimingTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @Test void PurePrimingNeedsNoSqlAndCannotRunInsideAnyTransaction(){
        var f=database();f.fixture();var c=f.command(false);var value=f.flow(f.store).prepare(c,f.action());
        var forbidden=new AbstractDataSource(){public Connection getConnection(){throw new AssertionError("fictional forbidden SQL");}public Connection getConnection(String u,String p){return getConnection();}};
        var pure=new GenerationJdbcStore(forbidden,f.clock,"fictional-owner",GenerationJdbcStoreTest.OWNER,c);
        assertDoesNotThrow(()->pure.primePureInput(value));
        var reads=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source));reads.setReadOnly(true);
        assertThrows(RuntimeException.class,()->reads.execute(status->{pure.primePureInput(value);return true;}));
        var bad=new Frozen("fictional-another-root",value.logicalKey(),value.generation(),value.stage(),value.cutoff(),value.coverageState(),value.missing(),value.snapshotJson(),value.inputHash(),value.requestJson(),value.priority(),value.eligibleAt(),value.state());
        assertThrows(RuntimeException.class,()->pure.primePureInput(bad));
    }
    @Test void PurePrimingCannotCacheNativePermissionOrReplaceAnOriginalProfile()throws Exception{
        var f=new GenerationPartialJdbcStoreTest(){@Override GenerationJdbcStoreTest database(){return GenerationPureInputPrimingTest.this.database();}};f.setup();var store=f.provider(f.dependency(),f.f.permit);
        try(var structural=mockStatic(NewsValueInputProfile.class,CALLS_REAL_METHODS)){
            store.primePureInput(f.frozen);structural.verify(()->NewsValueInputProfile.complete(any()),times(1));
            String root=f.f.f.f.plan.chunks().getLast().rootId();long revision=f.f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,root);int count=f.f.db.count("1=1");
            f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);assertThrows(RuntimeException.class,()->f.save(store));assertEquals(count,f.f.db.count("1=1"));
            f.f.db.jdbc.update("UPDATE jobs SET revision=? WHERE id=?",revision,root);assertEquals(f.frozen,f.save(store));assertEquals(f.frozen,f.save(store));
            structural.verify(()->NewsValueInputProfile.complete(any()),times(1));
            var raw=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(f.chunk.snapshotJson());((tools.jackson.databind.node.ObjectNode)raw.get("members").get(0)).put("body_hash","0".repeat(64));
            var good=f.frozen;var bad=new Frozen(good.ownerId(),good.logicalKey(),good.generation(),good.stage(),good.cutoff(),good.coverageState(),good.missing(),JdbcFrozenInputs.canonical(raw),CanonicalJson.hash(CanonicalJson.Domain.INPUT,raw),good.requestJson(),good.priority(),good.eligibleAt(),good.state());assertThrows(RuntimeException.class,()->store.primePureInput(bad));assertEquals(f.frozen,f.save(store));
        }
    }
    @Test void ConstructorFreezesEveryCallerOwnedCommandListBeforeHashAndWarmup(){
        var f=database();f.fixture();var original=f.command(false);var frozen=f.flow(f.store).prepare(original,f.action());
        var deps=new ArrayList<Dependency>(original.dependencies());var expected=new ArrayList<String>(original.expectedDependencies());var refs=new ArrayList<String>();var evidence=new ArrayList<Evidence>();
        var e=original.evidence().getFirst();evidence.add(new Evidence(e.id(),e.kind(),e.businessDate(),e.completedAt(),e.bodyHash(),e.bodyJson(),e.effectiveValid(),e.blocked(),e.projectionDecision(),refs));
        var mutable=new Prepare(original.logicalKey(),original.generation(),original.stage(),original.businessDate(),original.sourceId(),deps,evidence,expected,original.explicitSupplement(),original.settingsHash(),original.applicationContext(),original.collectionProofs(),original.stagePart());
        var store=new GenerationJdbcStore(f.source,f.clock,"fictional-owner",GenerationJdbcStoreTest.OWNER,mutable);
        refs.add("fictional-altered-reference");deps.add(new Dependency("fictional-altered-dependency",Stage.NEWS_DEDUP,"failed",null));expected.add("fictional-altered-expected");evidence.clear();
        store.primePureInput(frozen);assertEquals(frozen,store.committed(GenerationJdbcStoreTest.ACTION));
    }
}
