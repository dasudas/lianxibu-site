package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcEntityDecisions;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import org.junit.jupiter.api.Test;

class EntityDecisionJdbcLogTest {
    final OperationalPersistenceFixture f=new OperationalPersistenceFixture();
    final String hash="a".repeat(64);
    final Decision decision=new Decision("fictional-item","fictional-entity","ready",true,1L);
    EntityDecisionJdbcLog log(String actor) { return new EntityDecisionJdbcLog(new JdbcEntityDecisions(f.source),actor,f.clock); }
    void save(EntityDecisionJdbcLog log) { log.append("fictional-key",hash,decision,"new","fictional-item",null,"fictional-entity","Fictional decision"); }
    <T>T write(java.util.function.Supplier<T> body) {return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-outer","fictional-id",null),key->null,c->body.get());}
    @Test void permanentActorReceiptReturnsOriginalDecisionAfterRestart() {
        write(()->{save(log("fictional-owner"));return true;});
        assertEquals(decision,log("fictional-owner").committed("fictional-key",hash));
        assertNull(log("fictional-other-owner").committed("fictional-key",hash));
        assertThrows(RuntimeException.class,()->log("fictional-owner").committed("fictional-key","b".repeat(64)));
        write(()->{save(log("fictional-owner"));return true;});
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT call_count FROM jobs",Integer.class));
    }
    @Test void dispositionFailureAndOuterFailureLeaveNoPartialWrite() {
        assertThrows(IllegalStateException.class,()->save(log("fictional-owner")));
        assertThrows(IllegalStateException.class,()->write(()->{save(log("fictional-owner"));throw new IllegalStateException("fictional later failure");}));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT reject_entity CHECK(target_type IS NULL OR target_type<>'entity_decision')");
        assertThrows(RuntimeException.class,()->write(()->{save(log("fictional-owner"));return true;}));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
    @Test void alteredPayloadAndTargetAreNeverReplayed() {
        write(()->{save(log("fictional-owner"));return true;});
        String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs",String.class);
        f.jdbc.update("UPDATE jobs SET action_payload='{}'");
        assertThrows(RuntimeException.class,()->log("fictional-owner").committed("fictional-key",hash));
        f.jdbc.update("UPDATE jobs SET action_payload=?,target_key_json='{}'",original);
        assertThrows(RuntimeException.class,()->log("fictional-owner").committed("fictional-key",hash));
    }
    @Test void mismatchedDecisionCannotCreateReceipt() {
        assertThrows(RuntimeException.class,()->write(()->{log("fictional-owner").append("fictional-key",hash,decision,"new","fictional-other-item",null,"fictional-entity","Fictional reason");return true;}));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
}
