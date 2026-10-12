package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import org.junit.jupiter.api.*;

class NonContentManualFactoryTest extends NonContentManualStoreTest {
    @Override ResultApplicationService core(ResultApplicationService.Store ignored){var graph=new SourceAnalysisProofGraph(f.source,f.tx,f.clock,()->false);var factory=new ResultApplicationFactory(f.source,f.tx,f.clock,graph.dependencies(),graph.dedupPermits());var app=factory.forManual(f.identity,owner);assertEquals(store.input(),app.input());return app.service();}
    @Test void LegacyDefaultFactoryCannotGrantNoContentManualPermission(){var legacy=new ResultApplicationFactory(f.source,f.tx,f.clock);assertThrows(ResultApplicationService.Rejected.class,()->legacy.forManual(f.identity,owner));assertEquals(0,f.count("contents"));}
    @Test void ActualFactoryOwnerGatePrecedesAnyNativeInputLookup(){var graph=new SourceAnalysisProofGraph(f.source,f.tx,f.clock,()->false);var factory=new ResultApplicationFactory(f.source,f.tx,f.clock,graph.dependencies(),graph.dedupPermits());f.jdbc.execute("DROP TABLE jobs");assertThrows(com.lianxibu.site.service.identity.IdentityService.Rejected.class,()->factory.forManual(f.identity,null));assertThrows(com.lianxibu.site.service.identity.IdentityService.Rejected.class,()->factory.forManual(f.identity,new Actor("fictional-reader","member")));}
    @Test void ActualFactoryAndProofGraphConstructWithZeroDatabaseCalls(){var source=mock(javax.sql.DataSource.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);var graph=new SourceAnalysisProofGraph(source,tx,f.clock,()->false);assertNotNull(new ResultApplicationFactory(source,tx,f.clock,graph.dependencies(),graph.dedupPermits()));verifyNoInteractions(source);}
}
