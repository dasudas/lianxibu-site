package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SourceStageRootsTest {
    SourceAnalysisParentStepTest f;JdbcSourceStageRoots store;String root;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=new SourceAnalysisParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceStageRootsTest.this.database();}};
        f.newSource();f.step.step(f.candidate(f.p1.collection.intentId()));root=f.read(()->f.plans.readImmutable(f.reference(f.p1.collection.intentId()))).chunks().getFirst().rootId();store=new JdbcSourceStageRoots(f.db.source);
    }
    JdbcSourceStageRoots.Native read(String id){return f.read(()->store.find(id));}
    @Test void originalPreparedP1AndActualP2HeadersSurviveAReadOnlyRestart() throws Exception {
        var actual=read(root);assertEquals(root,actual.id());assertEquals(f.p1.collection.intentId(),actual.intent());assertEquals("news_dedup",actual.type());assertEquals("queued",actual.state());assertEquals(0,actual.calls());assertEquals(3,actual.maximum());assertEquals("ready",actual.inputState());assertNotNull(actual.inputHash());assertEquals(f.p1.collection.facts().businessDate(),actual.day());assertNotNull(actual.cutoff());assertNotNull(actual.updatedAt());int count=f.db.count("1=1");store=new JdbcSourceStageRoots(f.db.source);assertEquals(actual,read(root));assertEquals(count,f.db.count("1=1"));assertEquals("SourceStageNativeRoot[redacted]",actual.toString());
        f=new SourceAnalysisParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceStageRootsTest.this.database();}};f.completedP1();f.step.step(f.candidate(f.handoffs.ref.ownerId()));var child=f.handoffs.read().p2Owner();f.step.step(f.candidate(child));var plan=f.read(()->f.plans.readImmutable(f.reference(child)));store=new JdbcSourceStageRoots(f.db.source);var p2=read(plan.chunks().getFirst().rootId());assertEquals(child,p2.intent());assertEquals("news_value",p2.type());assertEquals(plan.scope().cutoff(),p2.cutoff());assertEquals(plan.chunks().getFirst().inputHash(),p2.inputHash());assertEquals(1,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void nativeStatesRemainDistinctWithoutDeclaringAnyBusinessOutcome(){
        for(String state:List.of("waiting_input","prepared","queued","running","waiting_retry","cancel_requested","unknown_external","blocked","blocked_configuration","result_conflict","succeeded","failed","cancelled","discarded","superseded")){
            f.db.jdbc.update("UPDATE jobs SET state=?,call_count=? WHERE id=?",state,state.equals("failed")?3:0,root);assertEquals(state,read(root).state());
        }
        f.db.jdbc.update("UPDATE jobs SET state='failed',call_count=3 WHERE id=?",root);assertEquals(3,read(root).calls());assertNull(read(root).appliedAt());assertNull(read(root).resultHash());
        for(String application:List.of("APPLIED","CONTENT_REVISION_CONFLICT","SUPERSEDED","INVALID_EVIDENCE","DISCARDED")){f.db.jdbc.update("UPDATE jobs SET application_state=? WHERE id=?",application,root);assertEquals(application,read(root).application());}
    }
    @Test void missingIsNotWrongRoleCaseAliasOrAnUnknownState(){
        assertNull(read("b".repeat(64)));assertThrows(RuntimeException.class,()->read(f.p1.collection.intentId()));assertThrows(RuntimeException.class,()->read(root.toUpperCase(Locale.ROOT)));
        f.db.jdbc.update("UPDATE jobs SET state='fictional_unknown' WHERE id=?",root);assertThrows(RuntimeException.class,()->read(root));f.db.jdbc.update("UPDATE jobs SET state='queued',application_state='fictional_unknown' WHERE id=?",root);assertThrows(RuntimeException.class,()->read(root));
    }
    @Test void corruptedNativeIdentityOrKeyNeverBecomesMissing(){
        for(String field:List.of("record_identity_hash","logical_key_hash")){
            byte[] original=f.db.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",byte[].class,root);f.db.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",new byte[32],root);assertThrows(RuntimeException.class,()->read(root));f.db.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",original,root);
        }
        String intent=read(root).intent();f.db.jdbc.update("UPDATE jobs SET intent_id=? WHERE id=?","c".repeat(64),root);assertThrows(RuntimeException.class,()->read(root));f.db.jdbc.update("UPDATE jobs SET intent_id=? WHERE id=?",intent,root);
        String key=read(root).key();f.db.jdbc.update("UPDATE jobs SET logical_key=?,logical_key_hash=? WHERE id=?","测".repeat(1366),JdbcGenerationLineage.hash("测".repeat(1366)),root);assertThrows(RuntimeException.class,()->read(root));f.db.jdbc.update("UPDATE jobs SET logical_key=?,logical_key_hash=? WHERE id=?",key,JdbcGenerationLineage.hash(key),root);assertNotNull(read(root));
    }
    @Test void unsafeOrNullNativeNumbersAndTypesAreRejected(){
        for(String field:List.of("generation","stage_part","logical_key_hash","intent_id","task_type")){
            Object before=f.db.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",Object.class,root);f.db.jdbc.update("UPDATE jobs SET "+field+"=NULL WHERE id=?",root);assertThrows(RuntimeException.class,()->read(root));f.db.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",before,root);
        }
        for(var mutation:List.of("revision=-1","revision=9007199254740992","call_count=-1","generation=0","stage_part=10000","task_type='daily_report'")){
            var before=read(root);f.db.jdbc.update("UPDATE jobs SET "+mutation+" WHERE id=?",root);assertThrows(RuntimeException.class,()->read(root));f.db.jdbc.update("UPDATE jobs SET revision=?,call_count=?,generation=?,stage_part=?,task_type=? WHERE id=?",before.revision(),before.calls(),before.generation(),before.part(),before.type(),root);
        }
    }
    @Test void constructorsAndInvalidParametersDoNotTouchConnectionsAndWritersAreRefused(){
        var ds=mock(javax.sql.DataSource.class);var empty=new JdbcSourceStageRoots(ds);verifyNoInteractions(ds);
        for(String id:List.of("","UPPER","x".repeat(64),"b".repeat(65)))assertThrows(RuntimeException.class,()->f.read(()->empty.find(id)));verifyNoInteractions(ds);
        assertThrows(RuntimeException.class,()->store.find(root));assertThrows(RuntimeException.class,()->f.p1.tx(()->store.find(root)));
    }
}
