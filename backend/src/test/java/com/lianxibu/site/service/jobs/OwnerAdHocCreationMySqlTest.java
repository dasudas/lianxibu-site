package com.lianxibu.site.service.jobs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** Reuses the existing independently guarded owned loopback MySQL fixture. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerAdHocCreationMySqlTest extends OwnerAdHocCreationTest {
 @Override GenerationJdbcStoreTest fixtureSource(){return new GenerationJdbcStoreMySqlTest();}
}
