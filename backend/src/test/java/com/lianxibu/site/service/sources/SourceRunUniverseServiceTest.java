package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.sources.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public class SourceRunUniverseServiceTest {
    protected DataSource source;protected JdbcTemplate jdbc;protected TransactionTemplate tx;
    protected DataSource database(){var s=new JdbcDataSource();s.setURL("jdbc:h2:mem:source-universe-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");return s;}
    @BeforeEach protected void setup() {
        source=database();jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));String json=source instanceof JdbcDataSource?"CLOB":"JSON";
        for(String table:List.of("source_run_items","request_receipts","source_runs","sources"))jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,activation_stage VARCHAR(16),config_version BIGINT,config_snapshot_json "+json+",manifest_hash BINARY(32),delivery_state VARCHAR(32),fetch_state VARCHAR(32),expected_count BIGINT,accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,revision BIGINT,job_refs_json "+json+",part_count BIGINT,result_json "+json+")");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),member_id VARCHAR(64),source_item_id VARCHAR(4096),observation_id VARCHAR(64),payload_hash BINARY(32),replaces_observation_id VARCHAR(64),part_no BIGINT,terminal_state VARCHAR(16))");
        jdbc.execute("CREATE TABLE request_receipts(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),scope VARCHAR(128),receipt_kind VARCHAR(32),control_operation VARCHAR(32),ack_json "+json+",business_hash BINARY(32))");
        jdbc.update("INSERT INTO sources VALUES('fictional-source','news_tech')");
        create(false);
    }
    void create(boolean empty) { create(empty,false); }
    void create(boolean empty,boolean unspecified) {
        var parts=new ArrayList<RunManifestService.Part>();
        if(!empty)for(int part=0;part<2;part++) {
            var members=List.of(new RunManifestService.Member("fictional-member-"+part,unspecified?null:"fictional-key-"+part,"fictional-observation-"+part,"a".repeat(64),null));
            String hash=RunManifestService.partHash("fictional-run",part,members);parts.add(new RunManifestService.Part("fictional-run",part,hash,members));
            var p=JsonMapper.shared().createObjectNode().put("run_id","fictional-run").put("part_no",part).put("part_hash",hash);var array=p.putArray("members");
            for(var member:members)array.addObject().put("member_id",member.memberId()).put("source_item_id",member.sourceItemId()).put("observation_id",member.observationId()).put("payload_hash",member.payloadHash()).putNull("replaces_observation_id");
            var action=JsonMapper.shared().createObjectNode().put("operation","manifest");action.set("business",p);
            jdbc.update("INSERT INTO request_receipts VALUES(?,?,?,'collector_control','manifest',?,?)","fictional-part-"+part,"fictional-run","manifest:fictional-run:"+part,p.toString(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,action)));
            jdbc.update("INSERT INTO source_run_items VALUES(?,?,?,?,?,?,?,?,?,?)","fictional-item-"+part,"fictional-run","fictional-source",members.getFirst().memberId(),members.getFirst().sourceItemId(),members.getFirst().observationId(),HexFormat.of().parseHex(members.getFirst().payloadHash()),null,part,part==0?"accepted":"excluded");
        }
        long expected=empty?0:2;String hash=RunManifestService.manifestHash("fictional-run",expected,parts);
        var result=JsonMapper.shared().createObjectNode().put("run_id","fictional-run").put("manifest_hash",hash).put("part_count",parts.size()).put("expected_count",expected).put("fetch_outcome","succeeded");result.putObject("coverage");
        jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','daily','2026-01-01',1,'production',1,'{}',?,?,'succeeded',?,?,?,0,7,'[]',?,?)",HexFormat.of().parseHex(hash),empty?"empty_success":"partial",expected,empty?0:1,empty?0:1,parts.size(),result.toString());
    }
    SourceRunUniverseService service(){return new SourceRunUniverseService(new JdbcSourceRunUniverse(source),new CollectionHandoffService(new JdbcCollectionHandoff(source),facts->{throw new AssertionError("read must never create intention");}));}
    com.lianxibu.site.domain.sources.MemberUniverse.Universe read(){return tx.execute(status->service().read("fictional-run"));}
    @Test void allOriginalPartsAndExcludedMembersVerifyWithoutMutationAndReconstruct() {
        var universe=read();assertEquals(2,universe.parts().size());assertEquals(2,universe.members().size());assertEquals(1,universe.accepted());assertEquals(1,universe.excluded());assertEquals(universe,read());
        assertEquals(7,jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));assertEquals("[]",CanonicalJson.parse(jdbc.queryForObject("SELECT job_refs_json FROM source_runs",String.class)).toString());
    }
    @Test void emptyRunUsesItsActualManifestAndTrialDoesNotBecomeProduction() {
        jdbc.update("DELETE FROM source_run_items");jdbc.update("DELETE FROM request_receipts");jdbc.update("DELETE FROM source_runs");create(true);
        assertTrue(read().members().isEmpty());jdbc.update("UPDATE source_runs SET activation_stage='trial'");assertTrue(read().members().isEmpty());assertEquals("trial",jdbc.queryForObject("SELECT activation_stage FROM source_runs",String.class));
    }
    @Test void missingExtraAndSameCountReplacementCannotPretendCompleteCoverage() {
        jdbc.update("UPDATE source_run_items SET observation_id='fictional-substituted' WHERE id='fictional-item-0'");assertThrows(RuntimeException.class,this::read);
        jdbc.update("UPDATE source_run_items SET observation_id='fictional-observation-0' WHERE id='fictional-item-0'");
        jdbc.update("DELETE FROM source_run_items WHERE id='fictional-item-1'");assertThrows(RuntimeException.class,this::read);
        jdbc.update("INSERT INTO source_run_items VALUES('fictional-extra','fictional-run','fictional-source','fictional-extra-member','fictional-key','fictional-observation',?,NULL,0,'excluded')",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void badReceiptsRootCountsAndIdentityAliasesFailBeforeReturningUniverse() {
        jdbc.update("UPDATE request_receipts SET business_hash=? WHERE id='fictional-part-0'",new byte[32]);assertThrows(RuntimeException.class,this::read);
        jdbc.update("DELETE FROM request_receipts WHERE id='fictional-part-0'");assertThrows(RuntimeException.class,this::read);
        jdbc.update("UPDATE source_runs SET pending_count=1");assertThrows(RuntimeException.class,this::read);
        assertThrows(RuntimeException.class,()->tx.execute(status->service().read("FICTIONAL-RUN")));
    }
    @Test void immutableManifestMembershipExcludesUnmanifestedRowsAndRequiresWriterTransaction() {
        jdbc.update("INSERT INTO source_run_items VALUES('fictional-unmanifested','fictional-run','fictional-source','fictional-orphan','fictional-key','fictional-observation',?,NULL,NULL,'pending')",new byte[32]);assertEquals(2,read().members().size());
        assertThrows(IllegalStateException.class,()->service().read("fictional-run"));tx.setReadOnly(true);assertThrows(IllegalStateException.class,this::read);
    }
    @Test void oversizedPartBodyAndUnknownFieldsReject() {
        jdbc.update("UPDATE request_receipts SET ack_json=? WHERE id='fictional-part-0'","{\"padding\":\""+"x".repeat(262145)+"\"}");assertThrows(RuntimeException.class,this::read);
        jdbc.update("UPDATE request_receipts SET ack_json='{}' WHERE id='fictional-part-0'");assertThrows(RuntimeException.class,this::read);
    }
    @Test void originalOptionalIdentityCanBeFilledWithoutRehashingManifestOrMutatingRows(){
        jdbc.update("DELETE FROM source_run_items");jdbc.update("DELETE FROM request_receipts");jdbc.update("DELETE FROM source_runs");create(false,true);
        String before=jdbc.queryForObject("SELECT ack_json FROM request_receipts WHERE id='fictional-part-0'",String.class);
        byte[] hash=jdbc.queryForObject("SELECT manifest_hash FROM source_runs",byte[].class);
        jdbc.update("UPDATE source_run_items SET source_item_id='fictional-derived-key' WHERE id='fictional-item-0'");
        var universe=read();assertEquals(2,universe.members().size());assertEquals("fictional-derived-key",universe.members().getFirst().sourceItemId());
        assertEquals(CanonicalJson.parse(before),CanonicalJson.parse(jdbc.queryForObject("SELECT ack_json FROM request_receipts WHERE id='fictional-part-0'",String.class)));
        assertArrayEquals(hash,jdbc.queryForObject("SELECT manifest_hash FROM source_runs",byte[].class));assertEquals(7,jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));
        jdbc.update("UPDATE source_run_items SET observation_id='fictional-substituted' WHERE id='fictional-item-0'");assertThrows(RuntimeException.class,this::read);
    }
    @Test void explicitOriginalIdentityStillRejectsAChangedProjection(){
        jdbc.update("UPDATE source_run_items SET source_item_id='fictional-alias' WHERE id='fictional-item-0'");assertThrows(RuntimeException.class,this::read);
    }

}
