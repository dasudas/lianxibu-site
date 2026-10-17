package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcOperationalDeliveries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DelegatingDataSource;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OperationalLogDeliveryLocatorMySqlTest extends OperationalLogDeliveryLocatorTest {
    @Override protected boolean mysql(){return true;}
    @Test void deepUnrelatedAuditHistoryUsesTheExistingTargetHashIndexForBothLeaseLookups()throws Exception{
        try(var d=database()){
            d.jdbc.execute("ALTER TABLE jobs ADD COLUMN validity_revision BIGINT NOT NULL DEFAULT 0");
            d.jdbc.execute("CREATE INDEX fictional_ops_target_lookup ON jobs(target_key_hash,validity_revision,id)");
            for(int n=0;n<1500;n++)d.appendDisposition("fictional-history-"+n,"fictional-history-action-"+n,"fictional_history","{\"fixture\":\""+n+"\"}","{\"fictional\":true}");
            String first=d.notice('1'),second=d.notice('2');d.receipt(d.log(),first,"fictional-native-active",1,d.clock.instant().plusSeconds(60),false);
            d.jdbc.execute("ANALYZE TABLE jobs");var captured=new AtomicReference<String>();
            DataSource observed=new DelegatingDataSource(d.source){@Override public Connection getConnection()throws SQLException{
                Connection connection=super.getConnection();return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement")&&args!=null&&args[0]instanceof String sql&&sql.startsWith("SELECT n.id"))captured.set(sql);
                    try{return method.invoke(connection,args);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }};
            assertEquals(second,new JdbcOperationalDeliveries(observed).next(d.clock.instant()).id());String sql=captured.get();assertNotNull(sql);
            var plans=d.jdbc.queryForList("EXPLAIN "+sql,Timestamp.from(d.clock.instant()));var aliases=new HashMap<String,Map<String,Object>>();for(var row:plans)if(row.get("table")!=null)aliases.put(row.get("table").toString(),row);
            for(String alias:List.of("d","newer")){var row=aliases.get(alias);assertNotNull(row,"both receipt history associations must remain represented");assertNotEquals("ALL",row.get("type"),"receipt history must use its existing locator index");assertEquals("fictional_ops_target_lookup",row.get("key"));}
            assertEquals(first,d.log().next(d.clock.instant().plusSeconds(61)).id());
        }
    }
}
