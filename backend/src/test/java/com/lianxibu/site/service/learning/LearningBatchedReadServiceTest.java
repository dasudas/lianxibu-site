package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.service.identity.IdentityService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class LearningBatchedReadServiceTest {
    DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:fictional-reader-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
    boolean nativeJson(){return false;}
    JdbcTemplate jdbc; CountingDataSource counted; LearningReadService reader; TransactionTemplate reads;
    static String id(String kind,int n){return "fictional-"+kind+"-"+String.format("%05d",n);}
    static final LearningService.Query FIRST=new LearningService.Query(null,null,null,null,null,null,1,50);
    @BeforeEach void setup(){
        counted=new CountingDataSource(source());jdbc=new JdbcTemplate(counted);
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),position_no INT,body_json "+(nativeJson()?"JSON":"VARCHAR(10000)")+",answer_text VARCHAR(10000),answer_state VARCHAR(16),answer_generation_state VARCHAR(32),revision BIGINT,PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_links(package_id VARCHAR(64),from_kind VARCHAR(16),from_id VARCHAR(64),relation_kind VARCHAR(24),to_kind VARCHAR(16),to_id VARCHAR(64),position_no INT)");
        jdbc.update("DELETE FROM learning_links");jdbc.update("DELETE FROM learning_items");jdbc.update("DELETE FROM learning_packages");
        for(String pack:List.of("fictional-alpha","fictional-beta","fictional-draft")){
            jdbc.update("INSERT INTO learning_packages VALUES(?,?,?,1)",pack,pack,pack.endsWith("draft")?"draft":"published");
            item(pack,"module","fictional-module","Module","{}",0);
            item(pack,"resource","fictional-resource",pack+" resource","{\"url\":\"https://example.invalid/fictional\"}",0);
            for(int n=0;n<51;n++){
                item(pack,"node",id("node",n),pack+" node "+n,"{\"scope\":\"fictional\",\"goals_raw\":[{\"position\":2,\"value\":\"second\"},{\"position\":1,\"value\":\"first\"}],\"completion_criteria_raw\":[\"done\"]}",n);
                link(pack,"module","fictional-module","contains","node",id("node",n),n);
                link(pack,"node",id("node",n),"requires","node",id("node",50),0);
                link(pack,"node",id("node",n),"requires","node",id("node",49),1);
                link(pack,"node",id("node",n),"references","resource","fictional-resource",0);
                if(n<50){item(pack,"route",id("route",n),"Route","{}",n);link(pack,"route",id("route",n),"contains","module","fictional-module",0);}
            }
        }
        reads=new TransactionTemplate(new DataSourceTransactionManager(counted));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reader=new LearningReadService(new JdbcLearningReads(counted));counted.calls.set(0);
    }
    void item(String pack,String kind,String key,String title,String body,int position){jdbc.update("INSERT INTO learning_items VALUES(?,?,?,?,?,?,NULL,'draft','not_generated',7)",pack,kind,key,title,position,body);}
    void link(String pack,String fromKind,String from,String relation,String toKind,String to,int position){jdbc.update("INSERT INTO learning_links VALUES(?,?,?,?,?,?,?)",pack,fromKind,from,relation,toKind,to,position);}
    <T>T read(Supplier<T> work){return reads.execute(tx->work.get());}
    static void rejected(IdentityService.Refusal refusal,Runnable work){assertEquals(refusal,assertThrows(IdentityService.Rejected.class,work::run).refusal());}
    @Test void graphFiftyUsesFiveQueriesAndReusesOrderedDependenciesForEdges(){
        var graph=read(()->reader.graph("fictional-alpha",null,null));assertEquals(5,counted.calls.get());
        assertEquals(50,graph.nodes().size());assertEquals(id("node",49),graph.nextCursor());assertEquals(100,graph.edges().size());
        var node=graph.nodes().getFirst();assertEquals(List.of("first","second"),node.goals());assertEquals(List.of("done"),node.completionCriteria());assertEquals(7,node.revision());
        assertEquals(List.of(id("node",50),id("node",49)),node.dependencies());
        assertEquals("fictional-alpha resource",node.resources().getFirst().title());assertEquals("https://example.invalid/fictional",node.resources().getFirst().url());
        assertEquals(new LearningService.GraphEdge(node.id(),id("node",50),"prerequisite"),graph.edges().getFirst());
        assertEquals(new LearningService.GraphEdge(node.id(),id("node",49),"prerequisite"),graph.edges().get(1));
        counted.calls.set(0);var filtered=read(()->reader.graph("fictional-alpha","fictional-module",null));assertEquals(6,counted.calls.get());assertEquals(graph,filtered);
    }
    @Test void nodeAndRoutePagesHaveConstantQueriesAndOriginalEligibleCounts(){
        var nodes=read(()->reader.nodes("fictional-alpha",FIRST));assertEquals(6,counted.calls.get());assertEquals(51,nodes.total());assertEquals(50,nodes.items().size());
        counted.calls.set(0);var routes=read(()->reader.routes("fictional-alpha",FIRST));assertEquals(5,counted.calls.get());assertEquals(50,routes.total());
        assertTrue(routes.items().stream().allMatch(r->r.moduleIds().equals(List.of("fictional-module"))&&r.eligibleNodeCount()==51&&r.completedNodeCount()==0&&r.completionRate().equals("0")));
        link("fictional-alpha","module","fictional-module","contains","node",id("node",0).toUpperCase(java.util.Locale.ROOT),70);
        assertEquals(52,read(()->reader.routes("fictional-alpha",FIRST)).items().getFirst().eligibleNodeCount());
    }
    @Test void cursorAndEmptyPagesKeepOriginalLimitsWithoutRelationQueries(){
        var last=read(()->reader.graph("fictional-alpha",null,id("node",49)));assertEquals(List.of(id("node",50)),last.nodes().stream().map(LearningService.Node::id).toList());assertNull(last.nextCursor());
        counted.calls.set(0);var empty=read(()->reader.graph("fictional-alpha",null,id("node",50)));assertEquals(2,counted.calls.get());assertEquals(List.of(),empty.nodes());assertEquals(List.of(),empty.edges());assertNull(empty.nextCursor());
        counted.calls.set(0);assertTrue(read(()->reader.nodes("fictional-alpha",new LearningService.Query(null,null,null,null,null,null,3,50))).items().isEmpty());assertEquals(3,counted.calls.get());
    }
    @Test void missingAndMultipleModulesKeepUnavailableInsteadOfInventingNodes(){
        jdbc.update("DELETE FROM learning_links WHERE package_id='fictional-alpha' AND to_kind='node' AND relation_kind='contains' AND to_id=?",id("node",0));
        rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,()->read(()->reader.node("fictional-alpha",id("node",0))));
        link("fictional-alpha","module","fictional-module","contains","node",id("node",0),0);
        link("fictional-alpha","module","fictional-other-module","contains","node",id("node",0),1);
        rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,()->read(()->reader.graph("fictional-alpha",null,null)));
    }
    @Test void danglingAndMalformedResourcesAreRejectedAsBefore(){
        jdbc.update("DELETE FROM learning_items WHERE package_id='fictional-alpha' AND kind='resource'");
        rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,()->read(()->reader.node("fictional-alpha",id("node",0))));
        item("fictional-alpha","resource","fictional-resource","Broken","[]",0);
        rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,()->read(()->reader.nodes("fictional-alpha",FIRST)));
        jdbc.update("UPDATE learning_items SET body_json='{\"url\":42}' WHERE package_id='fictional-alpha' AND kind='resource'");
        rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,()->read(()->reader.node("fictional-alpha",id("node",0))));
    }
    @Test void packageQualificationDraftWithdrawalAndAmbiguousAliasesRemainEnforced(){
        assertEquals("fictional-beta resource",read(()->reader.node("fictional-beta",id("node",0))).resources().getFirst().title());
        rejected(IdentityService.Refusal.IDENTITY_CONFLICT,()->read(()->reader.resolve("node",id("node",0))));
        rejected(IdentityService.Refusal.NOT_FOUND,()->read(()->reader.nodes("fictional-draft",FIRST)));
        jdbc.update("UPDATE learning_packages SET state='withdrawn' WHERE id='fictional-alpha'");
        rejected(IdentityService.Refusal.NOT_FOUND,()->read(()->reader.routes("fictional-alpha",FIRST)));
        rejected(IdentityService.Refusal.NOT_FOUND,()->read(()->reader.graph("fictional-alpha",null,null)));
    }
    @Test void constructionDoesNoIoAndNextReadSeesCurrentOriginalValues(){
        var prohibited=new AbstractDataSource(){
            @Override public Connection getConnection(){throw new AssertionError("constructor SQL");}
            @Override public Connection getConnection(String u,String p){throw new AssertionError("constructor SQL");}
        };
        assertDoesNotThrow(()->new LearningReadService(new JdbcLearningReads(prohibited)));
        var first=read(()->reader.node("fictional-alpha",id("node",0)));
        jdbc.update("UPDATE learning_items SET title='fictional updated' WHERE package_id='fictional-alpha' AND kind='resource'");
        assertNotEquals(first.resources(),read(()->reader.node("fictional-alpha",id("node",0))).resources());
    }
    static final class CountingDataSource extends AbstractDataSource {
        final DataSource delegate;final AtomicInteger calls=new AtomicInteger();
        CountingDataSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String u,String p)throws SQLException{return wrap(delegate.getConnection(u,p));}
        Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement"))calls.incrementAndGet();
            try{return method.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}
        });}
    }
}
