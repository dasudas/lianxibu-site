package com.lianxibu.site.service.jobs;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class FrozenContentProofReuseMySqlTest extends FrozenContentProofReuseTest {
    @Override boolean mysql(){return true;}
}
