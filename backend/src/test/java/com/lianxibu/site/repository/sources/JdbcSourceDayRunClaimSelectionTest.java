package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class JdbcSourceDayRunClaimSelectionTest {
    JdbcSourceDayRunRegistrationsTest fixture;JdbcSourceRunStore store;
    JdbcSourceDayRunRegistrationsTest fixture(){return new JdbcSourceDayRunRegistrationsTest();}
    @BeforeEach void setup()throws Exception{
        fixture=fixture();fixture.setup();
        for(String column:List.of("claim_node_id VARCHAR(64)","claim_token_hash BINARY(32)","claim_token_ciphertext BLOB","claim_key_id VARCHAR(64)","lease_until TIMESTAMP(6)","node_id VARCHAR(64)","started_at TIMESTAMP(6)"))fixture.ddl("ALTER TABLE source_runs ADD "+column);
        store=new JdbcSourceRunStore(fixture.data);fixture.reserve();
    }
    List<String> sources(){return List.of(fixture.request.source().id());}
    JdbcSourceRunStore.Row choose(List<String> allowed){return fixture.tx.execute(s->store.claimable(sources(),"run",fixture.reserved,allowed));}
    @Test void onlyApprovedDailyCanBeSelectedAndUnapprovedCannotStarveOtherRunKinds(){
        assertEquals(Set.of(fixture.day),store.dailyClaimDays(sources(),fixture.reserved));assertNull(choose(List.of()));assertNull(choose(List.of("fictional-other")));assertEquals(fixture.request.runId(),choose(List.of(fixture.request.runId())).id());
        fixture.tx.execute(s->{store.insert(new JdbcSourceRunStore.NewRun("fictional-history",fixture.request.source().id(),"historical_backfill",fixture.day,1,fixture.day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),fixture.day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),null,3,"{}","production","materialize","historical_backfill",fixture.reserved));return null;});assertEquals("fictional-history",choose(List.of()).id());
    }
    @Test void currentConfigExpirationAndTransactionBoundariesAreFailClosed(){
        fixture.sql.update("UPDATE sources SET config_revision=4");assertTrue(store.dailyClaimDays(sources(),fixture.reserved).isEmpty());assertNull(choose(List.of(fixture.request.runId())));
        fixture.sql.update("UPDATE sources SET config_revision=3,activation_stage='disabled'");assertTrue(store.dailyClaimDays(sources(),fixture.reserved).isEmpty());assertNull(choose(List.of(fixture.request.runId())));
        fixture.sql.update("UPDATE sources SET activation_stage='production'");assertTrue(store.dailyClaimDays(sources(),fixture.until).isEmpty());assertNull(fixture.tx.execute(s->store.claimable(sources(),"run",fixture.until,List.of(fixture.request.runId()))));
        assertThrows(IllegalStateException.class,()->store.claimable(sources(),"run",fixture.reserved,List.of()));assertThrows(IllegalStateException.class,()->fixture.tx.execute(s->store.dailyClaimDays(sources(),fixture.reserved)));
        assertThrows(RuntimeException.class,()->store.dailyClaimDays(Collections.nCopies(1001,"fictional"),fixture.reserved));
    }
    @Test void utcOriginalWindowsAndLeaseWritesStayExactAcrossJvmTimezones(){
        var old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));var row=choose(List.of(fixture.request.runId()));assertEquals(fixture.day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),row.windowStart());assertEquals(fixture.day,row.businessDate());
            Instant lease=fixture.reserved.plusSeconds(150);byte[] hash=new byte[32];assertEquals(Boolean.TRUE,fixture.tx.execute(s->store.claim(row,"fictional-node",hash,new byte[]{1},"fictional-key",lease,fixture.reserved)));assertEquals(lease,store.byId(row.id()).leaseUntil());
            Timestamp physical=fixture.sql.query("SELECT lease_until FROM source_runs",(rs,index)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC")))).getFirst();assertEquals(lease,physical.toInstant());assertNull(choose(List.of(row.id())));assertTrue(store.dailyClaimDays(sources(),fixture.reserved).isEmpty());
            var claimed=store.byId(row.id());assertEquals(Boolean.TRUE,fixture.tx.execute(s->store.acknowledge(claimed,"fictional-node",hash,fixture.reserved)));assertEquals(Boolean.TRUE,fixture.tx.execute(s->store.renew(store.byId(row.id()),"fictional-node",hash,lease.plusSeconds(10),fixture.reserved)));assertEquals(lease.plusSeconds(10),store.byId(row.id()).leaseUntil());
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));assertEquals(lease.plusSeconds(10),store.byId(row.id()).leaseUntil());assertEquals(fixture.day,store.natural(fixture.request.source().id(),"daily",fixture.day,1).businessDate());assertEquals(Boolean.TRUE,fixture.tx.execute(s->store.release(store.byId(row.id()),"fictional-node",hash,fixture.reserved)));assertNotNull(choose(List.of(row.id())));
        }finally{TimeZone.setDefault(old);}
    }
    @Test void excessDaysAndRunIdsCannotBeTruncatedAndOversizeSnapshotIsNotLoaded(){
        for(int i=1;i<=32;i++){var day=fixture.day.minusDays(i);var start=day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();fixture.tx.execute(s->{store.insert(new JdbcSourceRunStore.NewRun("fictional-day-"+day,fixture.request.source().id(),"daily",day,1,start,start.plusSeconds(86400),null,3,"{}","production","daily_delta","daily",fixture.reserved));return null;});}
        assertThrows(JdbcSourceRunStore.CapacityGuarded.class,()->store.dailyClaimDays(sources(),fixture.reserved));
        var tooMany=java.util.stream.IntStream.range(0,1001).mapToObj(i->"fictional-run-"+i).toList();assertThrows(JdbcSourceRunStore.CapacityGuarded.class,()->choose(tooMany));
        fixture.sql.update("UPDATE source_runs SET config_snapshot_json=? WHERE id=?","{\"padding\":\""+"a".repeat(65537)+"\"}",fixture.request.runId());assertNull(choose(List.of(fixture.request.runId())).configSnapshotJson());
    }
}
