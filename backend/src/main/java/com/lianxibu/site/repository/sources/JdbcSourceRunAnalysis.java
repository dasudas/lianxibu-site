package com.lianxibu.site.repository.sources;

import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only the current analysis projection is writable; collector proof and coverage remain owned by collection. */
public final class JdbcSourceRunAnalysis {
    public record Run(String id,String sourceId,String sourceIdentity,String category,String kind,LocalDate businessDate,long generation,String manifestHash,
            long expected,long accepted,long excluded,long pending,String activationStage,String deliveryState,String analysisState,long revision,String refs,String dedupSummary,String analysisSummary){
        @Override public String toString(){return "SourceRunAnalysisRow[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcSourceRunAnalysis(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Run locked(String id){
        requireWriter();var rows=jdbc.query("SELECT r.id,r.source_id,s.id,s.category,r.run_kind,r.business_date,r.generation,r.manifest_hash,r.expected_count,r.accepted_count,r.excluded_count,r.pending_count,r.activation_stage,r.delivery_state,r.analysis_state,r.revision,"
            +"CASE WHEN OCTET_LENGTH(r.job_refs_json) BETWEEN 1 AND 65536 THEN r.job_refs_json ELSE NULL END,CASE WHEN OCTET_LENGTH(r.dedup_summary)<=16777215 THEN r.dedup_summary ELSE NULL END,CASE WHEN OCTET_LENGTH(r.analysis_summary)<=16777215 THEN r.analysis_summary ELSE NULL END,OCTET_LENGTH(r.job_refs_json),OCTET_LENGTH(r.dedup_summary),OCTET_LENGTH(r.analysis_summary)"
            +" FROM source_runs r JOIN sources s ON s.id=r.source_id WHERE r.id=? FOR UPDATE",(r,n)->{
                for(int c=7;c<=16;c++)if(Set.of(7,9,10,11,12,16).contains(c)&&r.getObject(c)==null)throw invalid();
                Long refs=r.getObject(20,Long.class),dedup=r.getObject(21,Long.class),analysis=r.getObject(22,Long.class);if(refs==null||refs<1||refs>65536||dedup!=null&&(dedup<0||dedup>16777215)||analysis!=null&&(analysis<0||analysis>16777215))throw invalid();
                var day=r.getDate(6);var hash=r.getBytes(8);if(day==null||hash==null||hash.length!=32)throw invalid();return new Run(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),day.toLocalDate(),r.getLong(7),HexFormat.of().formatHex(hash),r.getLong(9),r.getLong(10),r.getLong(11),r.getLong(12),r.getString(13),r.getString(14),r.getString(15),r.getLong(16),r.getString(17),r.getString(18),r.getString(19));
            },id);if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    public void project(Run run,String dedup,String analysis,String state,String refs){
        requireWriter();if(jdbc.update("UPDATE source_runs SET dedup_summary=?,analysis_summary=?,analysis_state=?,job_refs_json=?,revision=revision+1 WHERE id=? AND revision=?",dedup,analysis,state,refs,run.id(),run.revision())!=1)throw invalid();
    }
    public static void requireWriter(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("source run analysis projection unavailable");}
}
