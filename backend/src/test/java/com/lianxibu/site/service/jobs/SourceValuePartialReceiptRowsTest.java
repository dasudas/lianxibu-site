package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Native row primitives only: fictional payload is deliberately not a semantic partial seal. */
class SourceValuePartialReceiptRowsTest {
    SourceValuePartialResolutionTest f;JdbcSourceValuePartialReceipts store;String owner;Instant now;
    static final String PAYLOAD="{\"schema_version\":\"fictional-receipt-row-test-v1\"}";
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{f=new SourceValuePartialResolutionTest(){@Override GenerationJdbcStoreTest database(){return SourceValuePartialReceiptRowsTest.this.database();}};f.setup(false);owner=f.ref.ownerId();now=f.f.f.clock.instant();store=new JdbcSourceValuePartialReceipts(f.db.source);}
    void append(){f.original.p1.tx(()->{store.lockOwner(owner);store.append(owner,PAYLOAD,now);return true;});}
    JdbcSourceValuePartialReceipts.Raw read(){return f.original.read(()->store.read(owner));}
    @Test void OriginalValueRowHasIndependentIdentityAndExactNativeMetadata()throws Exception{
        var source=mock(DataSource.class);new JdbcSourceValuePartialReceipts(source);verifyNoInteractions(source);setup();assertNull(read());String state=f.db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,owner);int calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);append();var raw=read();assertEquals(JdbcSourceValuePartialReceipts.receiptId(owner),raw.id());assertEquals("disposition",raw.kind());assertEquals(JdbcSourceValuePartialReceipts.TYPE,raw.targetType());assertEquals(JdbcSourceValuePartialReceipts.ACTOR,raw.actor());assertEquals(JdbcSourceValuePartialReceipts.REASON,raw.reason());assertEquals(PAYLOAD,JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.payload())));assertEquals(now,raw.actedAt());assertArrayEquals(JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.target()))),raw.targetHash());assertArrayEquals(JdbcGenerationLineage.hash(PAYLOAD),raw.payloadHash());assertArrayEquals(JdbcGenerationLineage.hash(JdbcSourceValuePartialReceipts.action(owner)),raw.actionHash());assertNotEquals(JdbcSourceDedupPartialReceipts.receiptId(owner),raw.id());assertNull(f.original.read(()->new JdbcSourceDedupPartialReceipts(f.db.source).read(owner)));assertEquals(state,f.db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,owner));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void ExactReplayAndRawHashCopiesCannotChangeOriginalReceipt()throws Exception{
        setup();append();int count=f.db.count("1=1");append();assertEquals(count,f.db.count("1=1"));assertThrows(RuntimeException.class,()->f.original.p1.tx(()->{store.lockOwner(owner);store.append(owner,"{}",now);return true;}));assertEquals(PAYLOAD,JdbcFrozenInputs.canonical(CanonicalJson.parse(read().payload())));var raw=read();byte[] original=raw.payloadHash(),exposed=raw.payloadHash();exposed[0]=(byte)(exposed[0]^1);assertArrayEquals(original,raw.payloadHash());byte[] caller=raw.targetHash();var copied=new JdbcSourceValuePartialReceipts.Raw(raw.id(),raw.kind(),raw.targetType(),raw.target(),caller,raw.actor(),raw.reason(),raw.payload(),raw.payloadHash(),raw.actionHash(),raw.actedAt(),raw.targetBytes(),raw.payloadBytes());byte[] expected=caller.clone();caller[0]=(byte)(caller[0]^1);assertArrayEquals(expected,copied.targetHash());assertEquals(count,f.db.count("1=1"));assertFalse(raw.toString().contains("fictional-receipt-row-test"));
    }
    @Test void OversizedRawFieldsAreNotLoadedAndDualIdentityRowsAreRejected()throws Exception{
        setup();append();f.db.jdbc.update("UPDATE jobs SET action_payload=? WHERE id=?","\""+"F".repeat(8200)+"\"",JdbcSourceValuePartialReceipts.receiptId(owner));var raw=read();assertNull(raw.payload());assertTrue(raw.payloadBytes()>8192);f.db.jdbc.update("UPDATE jobs SET target_key_json=? WHERE id=?","\""+"F".repeat(2100)+"\"",JdbcSourceValuePartialReceipts.receiptId(owner));raw=read();assertNull(raw.target());assertTrue(raw.targetBytes()>2048);
        setup();append();String target=read().target();f.original.p1.tx(()->{store.lockOwner(owner);new JdbcJobRecords(f.db.source).appendDisposition(new JdbcJobRecords.Disposition("fictional-other-row","fictional-other-action",JdbcSourceValuePartialReceipts.TYPE,target,JdbcSourceValuePartialReceipts.ACTOR,JdbcSourceValuePartialReceipts.REASON,PAYLOAD),now);return true;});f.db.jdbc.update("UPDATE jobs SET action_key_hash=? WHERE id=?",HexFormat.of().parseHex("c".repeat(64)),JdbcSourceValuePartialReceipts.receiptId(owner));f.db.jdbc.update("UPDATE jobs SET action_key_hash=? WHERE id='fictional-other-row'",JdbcGenerationLineage.hash(JdbcSourceValuePartialReceipts.action(owner)));assertThrows(RuntimeException.class,this::read);
    }
    @Test void WriterNaturalOwnerMicrosecondTimeAndOuterRollbackStayStrict()throws Exception{
        setup();int count=f.db.count("1=1");assertThrows(RuntimeException.class,()->store.read(owner));assertThrows(RuntimeException.class,()->store.append(owner,PAYLOAD,now));assertThrows(RuntimeException.class,()->f.original.read(()->{store.lockOwner(owner);return true;}));assertThrows(RuntimeException.class,()->f.original.p1.tx(()->{store.lockOwner(f.authority.f.ref.ownerId());return true;}));assertThrows(RuntimeException.class,()->f.original.p1.tx(()->{store.lockOwner(owner);store.append(owner,"\""+"F".repeat(8200)+"\"",now);return true;}));assertThrows(RuntimeException.class,()->f.original.p1.tx(()->{store.lockOwner(owner);store.append(owner,PAYLOAD,now.plusNanos(1));return true;}));assertThrows(RuntimeException.class,()->f.original.p1.tx(()->{store.lockOwner(owner);store.append(owner,PAYLOAD,now);throw new IllegalStateException("fictional outer rollback");}));assertNull(read());assertEquals(count,f.db.count("1=1"));f.db.jdbc.update("UPDATE jobs SET task_type='news_dedup' WHERE id=?",owner);assertThrows(RuntimeException.class,()->f.original.p1.tx(()->{store.lockOwner(owner);return true;}));
    }
}
