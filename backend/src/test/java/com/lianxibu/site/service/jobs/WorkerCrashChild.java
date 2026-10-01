package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.zaxxer.hikari.HikariDataSource;
import java.net.*;import java.time.*;import java.util.Set;import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Dedicated child JVM for crash tests. It can only join one disposable loopback fixture. */
public final class WorkerCrashChild {
    private WorkerCrashChild() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=2||!Set.of("claim","authorize").contains(args[0])||!args[1].matches("fictional-child-[a-z]+")||!"true".equals(System.getenv("V03_MYSQL")))throw new IllegalArgumentException("fictional child mode required");
        String url=System.getenv("B40_MYSQL_URL");if(url==null||!url.startsWith("jdbc:"))throw new IllegalArgumentException("isolated database required");var endpoint=URI.create(url.substring(5));
        if(!"mysql".equals(endpoint.getScheme())||endpoint.getPort()!=13336||endpoint.getHost()==null||!InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()||!"/fictional_v03_worker".equals(endpoint.getPath()))throw new IllegalArgumentException("dedicated loopback database required");
        try(var db=new HikariDataSource()){
            db.setJdbcUrl(url);db.setUsername(System.getenv().getOrDefault("V03_MYSQL_USER","root"));db.setPassword(System.getenv().getOrDefault("V03_MYSQL_PASSWORD",""));db.setMaximumPoolSize(3);db.setMinimumIdle(1);
            var jdbc=new JdbcTemplate(db);if(!"8.0.36".equals(jdbc.queryForObject("SELECT VERSION()",String.class))||!"utf8mb4_0900_ai_ci".equals(jdbc.queryForObject("SELECT @@collation_database",String.class)))throw new IllegalStateException("fixture database version mismatch");
            var state=new JdbcWorkerState(db);var records=new JdbcJobRecords(db);var clock=Clock.fixed(Instant.parse("2026-01-01T18:00:00Z"),ZoneId.of("UTC"));var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(db),new DataSourceTransactionManager(db));
            var worker=new WorkerAuthorizationService(writes,new WorkerJdbcStore(state,records,args[1],()->false,root->true,clock),clock,()->true);var claim=worker.claim(new ActionContext("child-claim-"+args[0],"fictional-child-receipt",null));if(claim.state()!=WorkerAuthorizationService.State.CLAIMED)throw new IllegalStateException("fixture claim missing");
            Runnable barrier=()->{System.out.println("FICTIONAL_COMMITTED|"+args[0]+"|"+claim.permit().attemptId()+"|"+claim.permit().identity().rootId()+"|"+args[1]);System.out.flush();try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("child barrier interrupted");}};
            if("claim".equals(args[0]))barrier.run();else worker.dispatch(claim.permit(),request->{barrier.run();throw new AssertionError("child must be killed before HTTP");});
        }
    }
}
