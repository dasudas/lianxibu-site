package com.lianxibu.site.service.sources;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.sources.JdbcSourceRunReads;
import com.lianxibu.site.service.sources.SourcesService.RunCreate;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceRunReadUtcTest extends OwnerRunCreateServiceTest {
    SourceRunReadService reader(){var ds=jdbc.getDataSource();return new SourceRunReadService(new JdbcSourceRunReads(ds),new DataSourceTransactionManager(ds));}
    @Test void originalWindowDatePageAndPermanentReplayAreStableAcrossFourDefaultTimeZones(){
        var before=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){
            TimeZone.setDefault(TimeZone.getTimeZone(zone));setup();var command=command("historical_backfill");
            var original=service.create(actor,command,"fictional-utc-original");assertEquals(command.windowStart(),original.windowStart());assertEquals(command.windowEnd(),original.windowEnd());assertEquals(command.businessDate(),original.businessDate());assertNull(original.barrierClosedAt());
            assertEquals(original,reader().run(actor,original.runId()));var page=reader().listRuns(actor,command.sourceId(),command.businessDate(),1,50);assertEquals(1,page.total());assertEquals(original,page.items().getFirst());
            jdbc.update("UPDATE source_runs SET revision=revision+1,delivery_state='claimed' WHERE id=?",original.runId());allowed=false;assertEquals(original,service.create(actor,command,"fictional-utc-original"));allowed=true;
        }}finally{TimeZone.setDefault(before);}
    }
    @Test void utcMicrosecondsAndOptionalBarrierRemainExactAfterDefaultTimeZoneChanges(){
        var before=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));setup();var c=command("historical_backfill");
            var exact=new RunCreate(c.sourceId(),c.businessDate(),c.windowStart().plusNanos(123456000),c.windowEnd().minusNanos(1000),c.runKind(),c.configVersion(),c.expectedRevision(),c.reason());
            var original=service.create(actor,exact,"fictional-utc-micros");assertEquals(exact.windowStart(),original.windowStart());assertEquals(exact.windowEnd(),original.windowEnd());
            Instant barrier=Instant.parse("2026-01-04T00:00:00.654321Z");jdbc.update(connection->{var statement=connection.prepareStatement("UPDATE source_runs SET barrier_closed_at=? WHERE id=?");statement.setTimestamp(1,java.sql.Timestamp.from(barrier),Calendar.getInstance(TimeZone.getTimeZone("UTC")));statement.setString(2,original.runId());return statement;});
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));var current=reader().run(actor,original.runId());assertEquals(barrier,current.barrierClosedAt());assertEquals(exact.windowStart(),current.windowStart());assertEquals(exact.businessDate(),current.businessDate());assertEquals(original,service.create(actor,exact,"fictional-utc-micros"));
        }finally{TimeZone.setDefault(before);}
    }
}
