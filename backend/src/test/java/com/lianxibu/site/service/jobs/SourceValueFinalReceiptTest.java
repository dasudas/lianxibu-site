package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceValueFinalReceiptTest {
    SourceValueStageProofTest f;SourceValueFinalReceiptService service;SourceValueFinalReceiptService.Stored original;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueFinalReceiptTest.this.fixture();}};f.install(5);f.complete(0);service=new SourceValueFinalReceiptService(new JdbcSourceValueFinalReceipts(f.f.source));var proofs=new SourceValueFinalProofService(f.service,f.plans,f.universes);var result=f.readonly(()->proofs.read(f.ref));var ref=FrozenSourceValueFinal.encode(result).reference();var position=new SourceRunAnalysisCommand.Position(f.ref.ownerId(),f.plan.logicalKey(),1,f.plan.scope().cutoff(),result.hash(),SourceValueFinalReceiptService.receiptId(f.ref.ownerId()));var command=new SourceRunAnalysisCommand(f.original.runId(),f.original.sourceId(),f.plan.scope().businessDate(),1,f.original.manifestHash(),f.original.accepted(),f.original.excluded(),position,"Fictional sealed dedup summary",result.summary(),result.coverageState());original=SourceValueFinalReceiptService.Stored.create(command,ref,new SourceRunAnalysisService.Outcome("applied",3),f.clock.instant());}
    SourceValueFinalReceiptService.Stored read(){return f.readonly(()->service.read(f.ref.ownerId()));}
    SourceValueFinalReceiptService.Stored append(){return f.tx(()->service.append(original));}
    int receipts(){return f.f.count("target_type='source_value_final_result'");}
    @Test void exactNativeReceiptReplaysAndSuppliesOnlyItsOriginalSourcePosition(){
        assertNull(read());append();assertEquals(original.json(),read().json());assertEquals(original.position(),f.tx(()->service.prior(f.original.runId(),List.of(f.ref.ownerId(),f.plan.chunks().getFirst().rootId(),original.position().receiptId()))));int count=f.f.count("1=1");append();assertEquals(count,f.f.count("1=1"));assertEquals(1,receipts());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertFalse(read().toString().contains("Fictional sealed dedup"));
    }
    @Test void roleRehashedWrongGenerationAndNativeTimeCannotSubstituteOriginal(){
        append();String id=original.position().receiptId();f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET operator_id='source-value-finalizer' WHERE id=?",id);
        var payload=(ObjectNode)CanonicalJson.parse(original.json());((ObjectNode)payload.get("position")).put("generation",2);String raw=JdbcFrozenInputs.canonical(payload);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",original.json(),JdbcGenerationLineage.hash(original.json()),id);f.f.jdbc.update("UPDATE jobs SET acted_at=? WHERE id=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)),id);assertThrows(RuntimeException.class,this::read);
    }
    @Test void appendRollbackAndActualCommittedAckLossPreserveOneOriginalReceipt(){
        f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_final_receipt_failure CHECK(target_type IS NULL OR target_type<>'source_value_final_result')");assertThrows(RuntimeException.class,this::append);assertEquals(0,receipts());f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_final_receipt_failure":"ALTER TABLE jobs DROP CHECK fictional_final_receipt_failure");var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional final receipt ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var result=writes.execute(new SharedWriteExecutor.ActionContext(SourceValueFinalReceiptService.action(f.ref.ownerId()),original.position().receiptId(),original.position().resultHash()),k->service.read(f.ref.ownerId()),a->service.append(original));assertEquals(original.json(),result.json());assertEquals(1,receipts());assertEquals(original.json(),read().json());
    }
    @Test void readonlyAppendMissingRefsCrossRunAndCollationAliasAreRejected(){
        assertThrows(RuntimeException.class,()->service.append(original));assertThrows(RuntimeException.class,()->f.readonly(()->service.append(original)));append();assertThrows(RuntimeException.class,()->f.tx(()->service.prior("fictional-wrong-run",List.of(original.position().receiptId()))));assertThrows(RuntimeException.class,()->f.tx(()->service.prior(f.original.runId(),List.of("fictional-missing-ref",original.position().receiptId()))));assertThrows(RuntimeException.class,()->f.readonly(()->service.prior(f.original.runId(),List.of(original.position().receiptId()))));assertThrows(RuntimeException.class,()->f.readonly(()->service.read(f.ref.ownerId().toUpperCase(Locale.ROOT))));assertEquals(1,receipts());
    }
}
