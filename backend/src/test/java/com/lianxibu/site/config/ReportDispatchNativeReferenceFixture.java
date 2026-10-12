package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.ResultJdbcStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
/** Exposes the actual package-local assembly port to the native integration fixture. */
public final class ReportDispatchNativeReferenceFixture {
 private ReportDispatchNativeReferenceFixture(){}
 public static ResultJdbcStore.ReferenceProof create(DataSource source,PlatformTransactionManager tx){return new ResultApplicationFactory(source,tx,Clock.systemUTC()).referenceProof();}
}
