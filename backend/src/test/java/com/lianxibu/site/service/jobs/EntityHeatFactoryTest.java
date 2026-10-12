package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.repository.jobs.JdbcEntityHeatSnapshots;
import com.lianxibu.site.service.articles.EntityDedupService;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** Native count business services constructed by the actual result factory; does not bypass claims in production. */
class EntityHeatFactoryTest extends EntityHeatBusinessProducerTest {
    @Override @BeforeEach void setup(){super.setup();var tx=new DataSourceTransactionManager(f.source);var graph=new SourceAnalysisProofGraph(f.source,tx,f.clock,()->false);var catalogs=new CatalogDayReportProofConfigurationTest().graphs(f.source,tx,f.clock);var factory=new ResultApplicationFactory(f.source,tx,f.clock,graph.dependencies(),graph.dedupPermits(),catalogs.resultPermissions(),new JdbcEntityHeatSnapshots(f.source));var handler=ReflectionTestUtils.getField(factory,"dedup");business=(EntityDedupService)ReflectionTestUtils.getField(handler,"entities");assertNotNull(business);}
    @Test void ExplicitFactoryNativeProducerIsColdAndMandatory(){var source=mock(javax.sql.DataSource.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);var clock=mock(Clock.class);var graph=new SourceAnalysisProofGraph(source,tx,clock,()->{throw new AssertionError("cold native gate");});var catalogs=new CatalogDayReportProofConfigurationTest().graphs(source,tx,clock);var heat=new JdbcEntityHeatSnapshots(source);var factory=new ResultApplicationFactory(source,tx,clock,graph.dependencies(),graph.dedupPermits(),catalogs.resultPermissions(),heat);var entity=ReflectionTestUtils.getField(ReflectionTestUtils.getField(factory,"dedup"),"entities");assertSame(heat,ReflectionTestUtils.getField(ReflectionTestUtils.getField(entity,"log"),"heat"));assertThrows(NullPointerException.class,()->new ResultApplicationFactory(source,tx,clock,graph.dependencies(),graph.dedupPermits(),catalogs.resultPermissions(),null));verifyNoInteractions(source,tx,clock);}
}
