package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogVersionCommitPermitMySqlTest extends CatalogVersionCommitPermitTest {
    @Override boolean mysql(){return true;}
    @Test void ActualOriginalWriterHoldsCurrentMemberAndConfigurationLocksUntilCommit()throws Exception{NativeLocksKeepAnIndependentSourceConfigWriterBlockedUntilTheOriginalCommit();}
}
