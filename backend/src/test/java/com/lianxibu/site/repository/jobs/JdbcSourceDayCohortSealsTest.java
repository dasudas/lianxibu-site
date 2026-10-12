package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.SourceDayCohortPlan;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

class JdbcSourceDayCohortSealsTest extends JdbcSourceDayRostersTest {
    JdbcSourceDayCohortSeals seals;
    SourceDayCohortPlan plan;
    final Instant sealed=registered.plusNanos(1000);
    @BeforeEach void sealSetup(){
        for(String column:List.of("action_key_hash BINARY(32)","target_type VARCHAR(32)","target_key_json CLOB","target_key_hash BINARY(32)","operator_id VARCHAR(64)","reason VARCHAR(2048)","acted_at TIMESTAMP(6)","action_payload CLOB","action_payload_hash BINARY(32)"))ddl("ALTER TABLE jobs ADD "+column);
        seals=new JdbcSourceDayCohortSeals(source);
    }
    void setupPlan(){plan=SourceDayCohortPlan.derive(save(evidence(10)));}
    JdbcSourceDayCohortSeals.Prepared frame(){return JdbcSourceDayCohortSeals.prepare(plan.bundleJson(),plan.bundleHash(),sealed);}
    JdbcSourceDayCohortSeals.Archive seal(){var prepared=frame();return tx.execute(s->seals.save(prepared));}
    JdbcSourceDayCohortSeals.Archive archive(){return ro.execute(s->new JdbcSourceDayCohortSeals(source).read(day));}
    @Test void originalBundleAndPhysicalUtcSurviveRestartAndCannotBeReplaced(){
        setupPlan();var old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));var first=seal();
            assertEquals(sealed,first.sealedAt());assertEquals(sealed,archive().sealedAt());assertEquals(plan.bundleJson(),archive().bundleJson());assertEquals(plan.bundleHash(),archive().bundleHash());
            int rows=sql.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);seal();assertEquals(rows,sql.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='disposition'",Integer.class));assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind IN('logical_task','ai_attempt')",Integer.class));
            var changed=JdbcSourceDayCohortSeals.prepare(plan.bundleJson(),plan.bundleHash(),sealed.plusNanos(1000));assertThrows(RuntimeException.class,()->tx.execute(s->seals.save(changed)));
            assertEquals(sealed,archive().sealedAt());
        }finally{TimeZone.setDefault(old);}
    }
    @Test void absentAndBrokenReceiptsAreDifferentAndNeverRepaired(){
        assertNull(archive());setupPlan();seal();
        sql.update("UPDATE jobs SET action_payload='{}' WHERE record_kind='disposition'");assertThrows(RuntimeException.class,this::archive);assertThrows(RuntimeException.class,this::seal);
        sql.update("DELETE FROM jobs WHERE record_kind='disposition'");seal();sql.update("UPDATE jobs SET id='fictional-alias' WHERE record_kind='disposition'");assertThrows(RuntimeException.class,this::archive);
        sql.update("DELETE FROM jobs WHERE record_kind='disposition'");seal();sql.update("UPDATE jobs SET action_key_hash=? WHERE record_kind='disposition'",new byte[32]);assertThrows(RuntimeException.class,this::archive);
        sql.update("DELETE FROM jobs WHERE record_kind='disposition'");seal();sql.update("DELETE FROM jobs WHERE record_kind='generation_intent'");assertThrows(RuntimeException.class,this::archive);assertThrows(RuntimeException.class,this::seal);
    }
    @Test void preparationAndStorageRefuseWrongBoundariesAndOverflow(){
        var untouched=mock(DataSource.class);var provider=new JdbcSourceDayCohortSeals(untouched);verifyNoInteractions(untouched);assertThrows(IllegalStateException.class,()->provider.read(day));verifyNoInteractions(untouched);
        setupPlan();var prepared=frame();assertThrows(IllegalStateException.class,()->seals.save(prepared));assertThrows(IllegalStateException.class,()->ro.execute(s->seals.save(prepared)));
        assertThrows(IllegalStateException.class,()->tx.execute(s->frame()));
        assertThrows(RuntimeException.class,()->JdbcSourceDayCohortSeals.prepare(null,plan.bundleHash(),sealed));assertThrows(RuntimeException.class,()->JdbcSourceDayCohortSeals.prepare(plan.bundleJson(),"0".repeat(64),sealed));
        assertThrows(RuntimeException.class,()->JdbcSourceDayCohortSeals.prepare(plan.bundleJson(),null,sealed));
        assertThrows(RuntimeException.class,()->JdbcSourceDayCohortSeals.prepare(plan.bundleJson(),plan.bundleHash(),registered.minusNanos(1000)));
        assertThrows(RuntimeException.class,()->JdbcSourceDayCohortSeals.prepare(plan.bundleJson(),plan.bundleHash(),Instant.parse("2026-09-20T20:30:00.000000Z")));
        assertThrows(RuntimeException.class,()->JdbcSourceDayCohortSeals.prepare(" ".repeat(2097153),plan.bundleHash(),sealed));
        seal();sql.update("UPDATE jobs SET action_payload=? WHERE record_kind='disposition'","{\"padding\":\""+"a".repeat(3145729)+"\"}");assertThrows(RuntimeException.class,this::archive);
    }
    @Test void wholeSealRollsBackAndFormattedJsonPreservesOriginalHash(){
        setupPlan();var prepared=frame();assertThrows(RuntimeException.class,()->tx.execute(s->{seals.save(prepared);throw new IllegalStateException("fictional rollback");}));assertNull(archive());
        seal();String formatted=tools.jackson.databind.json.JsonMapper.shared().writerWithDefaultPrettyPrinter().writeValueAsString(CanonicalJson.parse(plan.bundleJson()));
        sql.update("UPDATE jobs SET action_payload=? WHERE record_kind='disposition'",formatted);assertEquals(plan.bundleJson(),archive().bundleJson());
        assertEquals(plan.bundleHash(),archive().bundleHash());assertFalse(archive().toString().contains(plan.bundleJson()));assertFalse(prepared.toString().contains(plan.bundleJson()));
    }
}
