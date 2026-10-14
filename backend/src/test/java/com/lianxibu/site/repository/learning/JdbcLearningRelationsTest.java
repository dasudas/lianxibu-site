package com.lianxibu.site.repository.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.learning.LearningRelations.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcLearningRelationsTest {
    DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:fictional-relations-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
    boolean foldedIdentifiers(){return false;}
    JdbcTemplate jdbc; CountingDataSource counted; JdbcLearningRelations store; TransactionTemplate reads;
    static String id(String kind,int n){return "fictional-"+kind+"-"+String.format("%05d",n);}
    @BeforeEach void setup() {
        counted=new CountingDataSource(source());jdbc=new JdbcTemplate(counted);
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),position_no INT,body_json "+(foldedIdentifiers()?"JSON":"VARCHAR(10000)")+",PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_links(package_id VARCHAR(64),from_kind VARCHAR(16),from_id VARCHAR(64),relation_kind VARCHAR(24),to_kind VARCHAR(16),to_id VARCHAR(64),position_no INT)");
        jdbc.update("DELETE FROM learning_links");jdbc.update("DELETE FROM learning_items");jdbc.update("DELETE FROM learning_packages");
        for(String pack:List.of("fictional-alpha","fictional-beta","fictional-draft")){
            jdbc.update("INSERT INTO learning_packages VALUES(?,?,?,1)",pack,pack,pack.endsWith("draft")?"draft":"published");
            item(pack,"module","fictional-module-a","Module A","{}");item(pack,"module","fictional-module-b","Module B","{}");
            item(pack,"resource","fictional-resource",pack+" resource","{\"url\":\"https://example.invalid/fictional\",\"original\":true}");
            for(int n=0;n<50;n++){
                item(pack,"route",id("route",n),"Route","{}");item(pack,"node",id("node",n),"Node","{}");
                link(pack,"route",id("route",n),"contains","module","fictional-module-a",0);
                link(pack,"route",id("route",n),"contains","module","fictional-module-b",1);
                link(pack,"module","fictional-module-a","contains","node",id("node",n),n);
                link(pack,"node",id("node",n),"requires","node",id("node",49),0);
                link(pack,"node",id("node",n),"requires","node",id("node",48),1);
                link(pack,"node",id("node",n),"references","resource","fictional-resource",0);
            }
            link(pack,"module","fictional-module-b","contains","node",id("node",0),1);
        }
        reads=new TransactionTemplate(new DataSourceTransactionManager(counted));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        store=new JdbcLearningRelations(counted);counted.calls.set(0);
    }
    void item(String pack,String kind,String id,String title,String body){jdbc.update("INSERT INTO learning_items VALUES(?,?,?,?,0,?)",pack,kind,id,title,body);}
    void link(String pack,String fromKind,String from,String relation,String toKind,String to,int position){jdbc.update("INSERT INTO learning_links VALUES(?,?,?,?,?,?,?)",pack,fromKind,from,relation,toKind,to,position);}
    <T>T read(Supplier<T> work){return reads.execute(tx->work.get());}
    static Selection select(String kind){return new Selection("fictional-alpha",IntStream.range(0,50).mapToObj(i->id(kind,i)).toList());}
    @Test void fiftyNodeFramesPreserveNativeValuesWithThreePreparedQueries() {
        String original=jdbc.queryForObject("SELECT body_json FROM learning_items WHERE package_id='fictional-alpha' AND kind='resource'",String.class);counted.calls.set(0);
        var result=read(()->store.nodes(select("node")));assertEquals(3,counted.calls.get());assertEquals(50,result.size());
        var first=result.get(id("node",0));assertEquals(List.of("fictional-module-a","fictional-module-b"),first.moduleIds());
        assertEquals(List.of(id("node",49),id("node",48)),first.dependencyIds());
        assertEquals("fictional-alpha resource",first.resources().getFirst().title());assertEquals(original,first.resources().getFirst().bodyJson());
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    @Test void fiftyRoutesPreserveModuleOrderAndDeduplicateExactNodeIdsWithTwoQueries() {
        var result=read(()->store.routes(select("route")));assertEquals(2,counted.calls.get());assertEquals(50,result.size());
        assertTrue(result.values().stream().allMatch(r->r.eligibleNodeCount()==50&&r.moduleIds().equals(List.of("fictional-module-a","fictional-module-b"))));
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    @Test void differentPackagesDraftAndUnknownAnchorsNeverBecomeAnEmptyHealthyFrame() {
        var beta=read(()->store.nodes(new Selection("fictional-beta",List.of(id("node",0)))));
        assertEquals("fictional-beta resource",beta.get(id("node",0)).resources().getFirst().title());
        assertTrue(read(()->store.nodes(new Selection("fictional-draft",List.of(id("node",0))))).isEmpty());
        assertTrue(read(()->store.routes(new Selection("fictional-draft",List.of(id("route",0))))).isEmpty());
        var mixed=read(()->store.nodes(new Selection("fictional-alpha",List.of(id("node",0),"fictional-absent"))));
        assertEquals(1,mixed.size());assertFalse(mixed.containsKey("fictional-absent"));
        jdbc.update("UPDATE learning_packages SET state='withdrawn' WHERE id='fictional-alpha'");
        assertTrue(read(()->store.nodes(select("node"))).isEmpty());assertTrue(read(()->store.routes(select("route"))).isEmpty());
    }
    @Test void missingResourcesAndMissingOrMultipleModulesRemainExplicitOriginalFacts() {
        link("fictional-alpha","node",id("node",0),"references","resource","fictional-missing",1);
        var first=read(()->store.nodes(new Selection("fictional-alpha",List.of(id("node",0))))).get(id("node",0));
        assertEquals(2,first.moduleIds().size());assertEquals(2,first.resources().size());
        assertNull(first.resources().getLast().title());assertNull(first.resources().getLast().bodyJson());
        jdbc.update("DELETE FROM learning_links WHERE package_id='fictional-alpha' AND relation_kind='contains' AND to_kind='node' AND to_id=?",id("node",1));
        assertEquals(List.of(),read(()->store.nodes(new Selection("fictional-alpha",List.of(id("node",1))))).get(id("node",1)).moduleIds());
    }
    @Test void invalidSelectionIsRejectedBeforeAnyPreparedQuery() {
        assertThrows(IllegalArgumentException.class,()->store.nodes(null));
        assertThrows(IllegalArgumentException.class,()->store.routes(null));
        assertThrows(IllegalArgumentException.class,()->store.nodes(new Selection("fictional-alpha",IntStream.range(0,51).mapToObj(i->id("node",i)).toList())));
        assertEquals(0,counted.calls.get());
    }
    @Test void constructionDoesNoIoAndNewReadsDoNotReuseAnOldResourceBody() {
        var prohibited=new AbstractDataSource(){
            @Override public Connection getConnection(){throw new AssertionError("constructor accessed SQL");}
            @Override public Connection getConnection(String user,String password){throw new AssertionError("constructor accessed SQL");}
        };
        assertDoesNotThrow(()->new JdbcLearningRelations(prohibited));
        var original=read(()->store.nodes(new Selection("fictional-alpha",List.of(id("node",2))))).get(id("node",2));
        jdbc.update("UPDATE learning_items SET title='fictional changed' WHERE package_id='fictional-alpha' AND kind='resource'");
        var current=read(()->store.nodes(new Selection("fictional-alpha",List.of(id("node",2))))).get(id("node",2));
        assertNotEquals(original.resources().getFirst().title(),current.resources().getFirst().title());
    }
    @Test void nativeCanonicalAnchorsAndExactStringEligibleCountingKeepOriginalCollationSemantics() {
        link("fictional-alpha","module","fictional-module-b","contains","node",id("node",0).toUpperCase(java.util.Locale.ROOT),2);
        assertEquals(51,read(()->store.routes(new Selection("fictional-alpha",List.of(id("route",0))))).get(id("route",0)).eligibleNodeCount());
        var aliases=read(()->store.nodes(new Selection("fictional-alpha",List.of(id("node",0).toUpperCase(java.util.Locale.ROOT)))));
        if(foldedIdentifiers())assertEquals(List.of(id("node",0)),aliases.keySet().stream().toList());
        else assertTrue(aliases.isEmpty());
    }
    static final class CountingDataSource extends AbstractDataSource {
        final DataSource delegate; final AtomicInteger calls=new AtomicInteger();
        CountingDataSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
        Connection wrap(Connection connection){
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement"))calls.incrementAndGet();
                try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
            });
        }
    }
}
