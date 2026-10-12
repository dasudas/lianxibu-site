package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class CatalogVersionQualificationMySqlTest extends CatalogVersionQualificationTest {
    @Override boolean mysql(){return true;}
    @org.junit.jupiter.api.Test void ActualNativeConsistentSnapshotAcrossPreviouslyUnreadCurrentMemberAndIndependentWriter()throws Exception{OneRepeatableReadSnapshotKeepsOldEntityAndCurrentMemberConsistentAcrossIndependentWriter();}
}
