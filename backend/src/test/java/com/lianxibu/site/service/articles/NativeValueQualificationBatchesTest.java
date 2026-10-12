package com.lianxibu.site.service.articles;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Native exact rows, storage limits and transaction modes before consumer integration. */
class NativeValueQualificationBatchesTest extends NewsValueEligibilityServiceTest {
 static final class Counting extends DelegatingDataSource {
  int selected;Counting(DataSource source){super(source);}
  @Override public Connection getConnection()throws java.sql.SQLException{return wrap(super.getConnection());}
  @Override public Connection getConnection(String user,String password)throws java.sql.SQLException{return wrap(super.getConnection(user,password));}
  private Connection wrap(Connection delegate){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{if(m.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.startsWith("SELECT"))selected++;try{return m.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}});}
 }
 Set<String> selectedIds(){return Set.of("fictional-item-0","fictional-item-2","fictional-missing");}
 TransactionTemplate transaction(Counting counted){return new TransactionTemplate(new DataSourceTransactionManager(counted));}
 static String tree(Object row){return com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(com.lianxibu.site.domain.protocol.CanonicalJson.parse(JsonMapper.shared().writeValueAsString(row)));}
 @Test void ExactMetadataAndFlagsPreserveEveryOriginalNativeFieldAndMissingId(){
  var counted=new Counting(f.source);var tx=transaction(counted);var flags=new JdbcNewsValueEntities(counted);var metadata=new JdbcNewsDedupEligibility(counted);
  tx.execute(s->{var current=metadata.lockBatch(selectedIds());var nativeFlags=flags.memberFlagsBatch(selectedIds());assertEquals(Set.of("fictional-item-0","fictional-item-2"),current.keySet());assertEquals(current.keySet(),nativeFlags.keySet());assertEquals(2,counted.selected);
   for(var id:current.keySet()){assertEquals(tree(metadata.lock(id)),tree(current.get(id)));assertEquals(tree(flags.memberFlags(id)),tree(nativeFlags.get(id)));}return true;});
  f.jdbc.update("UPDATE source_run_items SET effective_valid=NULL,revision=NULL WHERE id='fictional-item-0'");tx.execute(s->{var nativeFlag=flags.memberFlagsBatch(Set.of("fictional-item-0")).get("fictional-item-0");assertNull(nativeFlag.valid());assertNull(nativeFlag.revision());return true;});
 }
 @Test void ExactEntitiesPreserveIdentityAndByteBoundsInsteadOfReturningUnknownAliases(){
  var counted=new Counting(f.source);var tx=transaction(counted);var entities=new JdbcNewsValueEntities(counted);var ids=Set.of("fictional-new-entity","fictional-entity","fictional-missing");
  tx.execute(s->{var current=entities.entitiesBatch(ids);assertEquals(Set.of("fictional-new-entity","fictional-entity"),current.keySet());assertEquals(1,counted.selected);for(var id:current.keySet())assertEquals(tree(entities.entity(id)),tree(current.get(id)));return true;});
  f.jdbc.update("UPDATE analyzed_entities SET identity_key=? WHERE id='fictional-new-entity'","x".repeat(4097));assertThrows(RuntimeException.class,()->tx.execute(s->entities.entitiesBatch(Set.of("fictional-new-entity"))));
 }
 @Test void BatchBoundsAndActualWriterModesRejectBeforeEveryNativeQuery(){
  var counted=new Counting(f.source);var tx=transaction(counted);var entities=new JdbcNewsValueEntities(counted);var metadata=new JdbcNewsDedupEligibility(counted);var evidence=new JdbcSourceMemberEvidence(counted);Set<String> big=new HashSet<>();for(int n=0;n<101;n++)big.add("fictional-id-"+n);
  assertThrows(RuntimeException.class,()->metadata.lockBatch(Set.of("fictional-item-0")));assertThrows(RuntimeException.class,()->entities.memberFlagsBatch(Set.of("fictional-item-0")));assertThrows(RuntimeException.class,()->evidence.observedBatch(Set.of("fictional-item-0")));
  tx.execute(s->{assertThrows(RuntimeException.class,()->metadata.lockBatch(big));assertThrows(RuntimeException.class,()->entities.memberFlagsBatch(big));assertThrows(RuntimeException.class,()->entities.entitiesBatch(big));assertThrows(RuntimeException.class,()->evidence.lockBatch(big));assertThrows(RuntimeException.class,()->evidence.observedBatch(Set.of("fictional-item-0")));assertThrows(RuntimeException.class,()->metadata.lockBatch(Set.of()));assertThrows(RuntimeException.class,()->entities.entitiesBatch(Set.of("x".repeat(65))));return true;});
  tx.setReadOnly(true);tx.execute(s->{assertThrows(RuntimeException.class,()->metadata.lockBatch(Set.of("fictional-item-0")));assertThrows(RuntimeException.class,()->entities.memberFlagsBatch(Set.of("fictional-item-0")));assertThrows(RuntimeException.class,()->entities.entitiesBatch(Set.of("fictional-new-entity")));assertThrows(RuntimeException.class,()->evidence.lockBatch(Set.of("fictional-item-0")));return true;});assertEquals(0,counted.selected);
 }
 @Test void SixteenRetainedRowsUseOneQueryAndReadOnlyObservationsCannotGrantWriterAuthority(){
  Set<String> ids=new TreeSet<>();for(int n=0;n<16;n++){String id="fictional-retained-"+n;ids.add(id);f.jdbc.update("INSERT INTO source_run_items SELECT ?,run_id,source_id,source_item_id,entity_id,target_business_date,payload_hash,revision,category,created_at,terminal_state,state,projection_decision,effective_valid,evidence_json,evidence_hash FROM source_run_items WHERE id='fictional-item-0'",id);}
  var counted=new Counting(f.source);var tx=transaction(counted);var evidence=new JdbcSourceMemberEvidence(counted);
  var locked=tx.execute(s->evidence.lockBatch(ids));assertEquals(ids,locked.keySet());assertEquals(1,counted.selected);var seventeen=new TreeSet<>(ids);seventeen.add("fictional-item-0");assertThrows(RuntimeException.class,()->tx.execute(s->evidence.lockBatch(seventeen)));assertEquals(1,counted.selected);
  tx.setReadOnly(true);var observed=tx.execute(s->evidence.observedBatch(ids));assertEquals(2,counted.selected);assertEquals(tree(locked),tree(observed));assertThrows(RuntimeException.class,()->tx.execute(s->evidence.lockBatch(ids)));assertEquals(2,counted.selected);
 }
 @Test void RetainedPayloadAndConfigurationOverflowFailWholeBatch(){
  var counted=new Counting(f.source);var tx=transaction(counted);var evidence=new JdbcSourceMemberEvidence(counted);String payload=JsonMapper.shared().createObjectNode().put("padding","x".repeat(262144)).toString();
  f.jdbc.update("UPDATE source_run_items SET evidence_json=? WHERE id='fictional-item-0'",payload);assertThrows(RuntimeException.class,()->tx.execute(s->evidence.lockBatch(Set.of("fictional-item-0","fictional-item-2"))));
  f.jdbc.update("UPDATE source_run_items SET evidence_json=? WHERE id='fictional-item-0'",f.payload(0,java.time.Instant.parse("2026-01-01T08:00:00Z")));f.jdbc.update("UPDATE source_runs SET config_snapshot_json=?",JsonMapper.shared().createObjectNode().put("padding","x".repeat(65536)).toString());assertThrows(RuntimeException.class,()->tx.execute(s->evidence.lockBatch(Set.of("fictional-item-0","fictional-item-2"))));
 }
}
