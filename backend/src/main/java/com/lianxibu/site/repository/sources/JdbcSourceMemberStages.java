package com.lianxibu.site.repository.sources;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact post-handler CAS; the enclosing result transaction owns replay. */
public final class JdbcSourceMemberStages {
 public record Member(String id,String runId,String sourceId,String sourceItemId,String category,String entityId,String state,long revision,String terminalState,boolean valid,String projection,LocalDate targetBusinessDate) { }
 public record Run(String id,String sourceId,String kind,LocalDate businessDate,long generation,String activationStage,String deliveryState,String jobRefs,long revision) { }
 private final JdbcTemplate jdbc;
 public JdbcSourceMemberStages(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
 public Member member(String id){requireTx();var rows=jdbc.query("SELECT id,run_id,source_id,source_item_id,category,entity_id,state,revision,terminal_state,effective_valid,projection_decision,target_business_date FROM source_run_items WHERE id=? FOR UPDATE",(r,n)->new Member(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getString(9),r.getInt(10)==1,r.getString(11),r.getDate(12)==null?null:r.getDate(12).toLocalDate()),id);return rows.isEmpty()?null:rows.getFirst();}
 public Run run(String id){requireTx();var rows=jdbc.query("SELECT id,source_id,run_kind,business_date,generation,activation_stage,delivery_state,job_refs_json,revision FROM source_runs WHERE id=? FOR UPDATE",(r,n)->new Run(r.getString(1),r.getString(2),r.getString(3),r.getDate(4)==null?null:r.getDate(4).toLocalDate(),r.getLong(5),r.getString(6),r.getString(7),r.getString(8),r.getLong(9)),id);return rows.isEmpty()?null:rows.getFirst();}
 public void complete(Member row,String root,Instant now){requireTx();if(jdbc.update("UPDATE source_run_items SET analysis_job_id=?,applied_at=?,revision=revision+1 WHERE id=? AND revision=?",root,java.sql.Timestamp.from(now),row.id(),row.revision())!=1)throw conflict();}
 public void link(Run row,String refs){requireTx();if(jdbc.update("UPDATE source_runs SET job_refs_json=?,revision=revision+1 WHERE id=? AND revision=?",refs,row.id(),row.revision())!=1)throw conflict();}
 private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("member stage requires result transaction");}
 private static IllegalStateException conflict(){return new IllegalStateException("member stage version conflict");}
}
