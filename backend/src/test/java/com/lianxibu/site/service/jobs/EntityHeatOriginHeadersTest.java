package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.JdbcEntityHeatOriginHeaders;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class EntityHeatOriginHeadersTest {
    EntityHeatBusinessProducerTest h;JdbcEntityHeatOriginHeaders rows;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){h=new EntityHeatBusinessProducerTest(){@Override GenerationJdbcStoreTest fixture(){return EntityHeatOriginHeadersTest.this.fixture();}};h.setup();h.item("fictional-one","fictional-source","fictional-item-key");rows=new JdbcEntityHeatOriginHeaders(h.f.source);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(h.f.source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    @Test void NativeItemRunDayAndCurrentAssociationAreOnlyOriginLocatorFacts(){var initial=rr(()->rows.read("fictional-one"));assertEquals("fictional-run",initial.runId());assertEquals("fictional-source",initial.sourceId());assertEquals("fictional-item-key",initial.sourceItemId());assertEquals(LocalDate.of(2026,1,1),initial.day());assertEquals("pending",initial.state());assertNull(initial.entityId());h.business.applyAutomatic(h.command("fictional-one","new",null),h.action("fictional-create","fictional-entity"));var applied=rr(()->rows.read("fictional-one"));assertEquals("fictional-entity",applied.entityId());assertEquals("ready",applied.state());assertEquals(1,applied.revision());assertTrue(applied.effectiveValid());assertFalse(applied.toString().contains(applied.itemId()));assertNull(rr(()->rows.read("fictional-missing")));}
    @Test void ChangedCurrentValidityAndAssociationDoNotInventAnImmutableOriginalBody(){var original=rr(()->rows.read("fictional-one"));h.f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-other',state='excluded',revision=2,effective_valid=0 WHERE id='fictional-one'");var changed=rr(()->rows.read("fictional-one"));assertNotEquals(original,changed);assertEquals("fictional-other",changed.entityId());assertFalse(changed.effectiveValid());assertEquals("excluded",changed.state());assertEquals(original.sourceItemId(),changed.sourceItemId());assertEquals(original.createdAt(),changed.createdAt());}
    @Test void NativeUtcCreationIsStableAcrossJvmZones(){var expected=rr(()->rows.read("fictional-one")).createdAt();var previous=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/New_York")){TimeZone.setDefault(TimeZone.getTimeZone(zone));assertEquals(expected,rr(()->rows.read("fictional-one")).createdAt());}}finally{TimeZone.setDefault(previous);}}
    @Test void WrongNativeRolesUnknownRunAndOversizedSourceItemKeyFailClosed(){for(String change:List.of("terminal_state='excluded'","category='agent'","state='fictional-invalid'","effective_valid=2","revision=-1","created_at=NULL","run_id='fictional-unknown'")){setup();h.f.jdbc.update("UPDATE source_run_items SET "+change+" WHERE id='fictional-one'");assertThrows(RuntimeException.class,()->rr(()->rows.read("fictional-one")));}setup();h.f.jdbc.execute(h.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE source_run_items ALTER COLUMN source_item_id VARCHAR(8192)":"ALTER TABLE source_run_items MODIFY source_item_id VARCHAR(8192)");h.f.jdbc.update("UPDATE source_run_items SET source_item_id=? WHERE id='fictional-one'","F".repeat(4097));assertThrows(RuntimeException.class,()->rr(()->rows.read("fictional-one")));}
    @Test void ExplicitBorrowedReadonlyRrAndColdNativePortAreRequired(){assertThrows(RuntimeException.class,()->rows.read("fictional-one"));var rc=new TransactionTemplate(new DataSourceTransactionManager(h.f.source));rc.setReadOnly(true);rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->rc.execute(s->rows.read("fictional-one")));assertThrows(RuntimeException.class,()->h.f.writes.execute(h.action("fictional-role","fictional-resource"),k->null,c->rows.read("fictional-one")));var source=mock(javax.sql.DataSource.class);new JdbcEntityHeatOriginHeaders(source);verifyNoInteractions(source);assertThrows(NullPointerException.class,()->new JdbcEntityHeatOriginHeaders(null));}
}
