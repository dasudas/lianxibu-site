package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class FrozenContentVersionSnapshotMySqlTest extends FrozenContentVersionSnapshotTest {@Override boolean mysql(){return true;}}
