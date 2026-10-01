package com.lianxibu.site.service.sources;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.sources.JdbcOwnerSourceReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class JdbcOwnerSourceReadsTest {
 @Test void actualJdbcSnapshotPreservesSqlNullWithoutReadingLegacySecrets(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:owner-source-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(16),category VARCHAR(16),draft_config_json CLOB,effective_config_json CLOB,config_valid_until TIMESTAMP)");jdbc.update("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?)","fictional-source","article","Fictional",0,0,"disabled","news_tech",null,null,null);var store=new JdbcOwnerSourceReads(ds,new DataSourceTransactionManager(ds));var reads=new OwnerSourceReadService(store);assertEquals(0L,reads.read(new Actor("fictional-owner","owner"),"fictional-source").get("revision"));jdbc.update("UPDATE sources SET revision=NULL WHERE id=?","fictional-source");assertNull(store.snapshot("fictional-source").revision());assertThrows(OwnerSourceReadService.MappingRequired.class,()->reads.read(new Actor("fictional-owner","owner"),"fictional-source"));assertNull(store.snapshot("fictional-missing"));}
}
