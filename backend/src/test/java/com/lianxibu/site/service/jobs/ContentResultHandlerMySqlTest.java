package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.content.JdbcGeneratedContents;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.service.content.GeneratedContentService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ContentResultHandlerMySqlTest {
    @Test void permanentResultAndBodyWithAckLossUseProductionHandlerExactlyOnce() {
        try(var f=new PooledResultJdbcMySqlFixture()) {
            f.fixture();var calls=new AtomicInteger();
            var handler=new ContentResultHandler(new GeneratedContentService(new JdbcGeneratedContents(f.source),f.clock),()->f.store.context(),(input,context,result,applied)->{
                calls.incrementAndGet();new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-handler-next","fictional-handler-key",1,"daily_report",LocalDate.of(2026,1,1)),f.clock.instant());
            });
            Handler unused=(a,b,c,d)->{throw new AssertionError("unassembled branch");};
            var service=new ResultApplicationService(f.writes,f.store,Map.of("report",handler,"analysis",handler,"dedup",unused,"value",unused,"ad_hoc",unused));
            f.tx.ackLost=true;var result=service.applyAutomatic(f.store.input(),f.raw(),f.action("fictional-real-handler"));
            assertEquals(Status.APPLIED,result.status());assertEquals(result,service.applyAutomatic(f.store.input(),f.raw(),f.action("fictional-real-handler")));
            assertEquals(1,calls.get());assertEquals(1,f.sends);assertEquals(1,f.count("contents"));
        }
    }
}
