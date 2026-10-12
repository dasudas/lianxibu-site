package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.learning.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;

class LearningImportJdbcStoreTest {
    @TempDir Path temporary;
    private JdbcDataSource source;private JdbcTemplate jdbc;private LearningImportService service;
    @BeforeEach void setup(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:learning-import-store-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT,content_hash BINARY(32),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),position_no INT,body_json CLOB,answer_text CLOB,answer_state VARCHAR(16),revision BIGINT,answer_generation_state VARCHAR(32),answer_revision BIGINT,answer_review_hash_v1 BINARY(32),PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE learning_links(package_id VARCHAR(64),from_kind VARCHAR(16),from_id VARCHAR(64),relation_kind VARCHAR(24),to_kind VARCHAR(16),to_id VARCHAR(64),position_no INT,PRIMARY KEY(package_id,from_kind,from_id,relation_kind,to_kind,to_id))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.execute("ALTER TABLE learning_links ADD CONSTRAINT fictional_import_link_from FOREIGN KEY(package_id,from_kind,from_id) REFERENCES learning_items(package_id,kind,id)");
        jdbc.execute("ALTER TABLE learning_links ADD CONSTRAINT fictional_import_link_to FOREIGN KEY(package_id,to_kind,to_id) REFERENCES learning_items(package_id,kind,id)");
        service=new LearningImportService(new FileLearningImportStages(temporary),new LearningImportJdbcStore(new JdbcLearningImports(source),new JdbcLearningPackages(source),"fixture-owner",Clock.systemUTC()),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source)),Clock.systemUTC());
    }
    private static JsonNode module(){return CanonicalJson.parse("{\"package_id\":\"temporary-package\",\"id\":\"m\",\"title\":\"Fictional module\",\"route_ids\":[]}");}
    private static JsonNode node(String dependency){return CanonicalJson.parse("{\"package_id\":\"temporary-package\",\"id\":\"n\",\"title\":\"Fictional node\",\"module_id\":\"m\",\"scope\":\"Fictional\",\"goals\":[],\"completion_criteria\":[],\"dependencies\":"+dependency+",\"resources\":[],\"revision\":1}");}
    private LearningService.ImportFinalize stage(String id,String target,List<JsonNode> items){
        String hash=LearningImportService.partHash(id,0,1,items);
        service.stage("fixture-owner",target,new LearningService.ImportChunk(id,0,1,hash,items));
        return new LearningService.ImportFinalize(id,"Fictional package","Fictional description",1,LearningImportService.manifestHash(id,List.of(new LearningImportService.Part(0,1,hash,items))));
    }
    @Test void fullFileStageToCommitRetainsUnspecifiedObjectsAndPermanentMetadata(){
        var manifest=stage("first",null,List.of(module(),node("[]")));
        var action=new ActionContext("create","fictional-package",null);
        assertEquals(1,service.commit("fixture-owner",null,null,manifest,action).revision());
        assertTrue(service.commit("fixture-owner",null,null,manifest,action).replayed());
        assertEquals("draft",jdbc.queryForObject("SELECT state FROM learning_packages",String.class));
        String metadata=new JdbcLearningImports(source).metadata("fictional-package",1);assertEquals("Fictional description",CanonicalJson.parse(metadata).get("description").asText());
        jdbc.update("UPDATE learning_packages SET state='published'");
        var update=stage("second","fictional-package",List.of(node("[]")));
        assertEquals(2,service.commit("fixture-owner","fictional-package",1L,update,new ActionContext("update","ignored-id",null)).revision());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM learning_items",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM learning_items WHERE kind='module'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT revision FROM learning_items WHERE kind='node'",Integer.class));
        assertEquals("draft",jdbc.queryForObject("SELECT state FROM learning_packages",String.class));
        var read=new LearningReadService(new JdbcLearningReads(source));
        jdbc.update("UPDATE learning_packages SET state='published'");assertEquals("m",read.node("fictional-package","n").moduleId());
    }
    @Test void danglingReferencesAndReceiptFailureRollbackEntireNewPackage(){
        var broken=stage("broken",null,List.of(module(),node("[\"missing\"]")));
        assertEquals("package_validation_failed",assertThrows(LearningImportService.Rejected.class,()->service.commit("fixture-owner",null,null,broken,new ActionContext("broken","fictional-broken",null))).getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learning_packages",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learning_items",Integer.class));
    }
    @Test void failedReceiptRollsBackNewPackageAndLinks(){
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT reject_failure CHECK(request_key <> 'fail')");
        var valid=stage("valid",null,List.of(module(),node("[]")));
        assertThrows(RuntimeException.class,()->service.commit("fixture-owner",null,null,valid,new ActionContext("fail","fictional-broken",null)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learning_packages",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learning_links",Integer.class));
    }

    @Test void missingFromEndpointLeavesNoReceipt(){rejectMissingEndpoint("from_id");}
    @Test void missingToEndpointLeavesNoReceipt(){rejectMissingEndpoint("to_id");}
    private void rejectMissingEndpoint(String missing){
        {
            var edge=CanonicalJson.parse("{\"package_id\":\"temporary-package\",\"from_id\":\"n\",\"to_id\":\"n\",\"kind\":\"related\"}");
            ((tools.jackson.databind.node.ObjectNode)edge).put(missing,"missing");
            String id="missing-"+missing;
            var command=stage(id,null,List.of(module(),node("[]"),edge));
            assertEquals("package_validation_failed",assertThrows(LearningImportService.Rejected.class,
                ()->service.commit("fixture-owner",null,null,command,new ActionContext(id,"fictional-"+id,null))).getMessage());
            for(String table:List.of("learning_packages","learning_items","learning_links","request_receipts"))
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            assertEquals(1,new FileLearningImportStages(temporary).load("fixture-owner",null,id,java.time.Instant.now()).parts().size());
        }
    }
}
