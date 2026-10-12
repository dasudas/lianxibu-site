package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerAdHocIntentCancelMySqlTest extends OwnerAdHocIntentCancelTest {
 @Override OwnerAdHocCreationTest fixtureSource(){return new OwnerAdHocCreationMySqlTest();}
 @Override boolean mysql(){return true;}
}
