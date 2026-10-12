package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Closed complete-stage identity; never treats a source fragment as the complete result. */
public final class JdbcSourceStageAvailability {
 private final JdbcCommittedProofAvailability ledger;
 public JdbcSourceStageAvailability(DataSource source){ledger=new JdbcCommittedProofAvailability(source,"source_stage_availability","source-stage-observer","Observed committed complete source stage","source-stage-observed:","source-stage-availability","committed-source-stage-observation-v1");}
 public Instant read(String target){validateTarget(target);return ledger.read(target);}
 public void save(String target,Instant observed){validateTarget(target);ledger.save(target,observed);}
 public static String key(String target){validateTarget(target);return "source-stage-observed:"+JdbcResultState.sha(target);}
 private static void validateTarget(String target){
  if(target==null||target.getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();var n=CanonicalJson.parse(target);
  var fields=Set.of("schema_version","owner_job_id","plan_hash","plan_index_hash","result_hash","stage","generation","business_date","universe_hash","universe_index_hash","run_id","source_id");WireValues.object(n,fields,fields);
  if(!target.equals(JdbcFrozenInputs.canonical(n))||!"source-dedup-stage-key-v1".equals(WireValues.text(n.get("schema_version")))||!Set.of("news_dedup","agent_dedup","skill_dedup","workflow_dedup","model_dedup").contains(WireValues.text(n.get("stage")))||WireValues.nonnegativeInteger(n.get("generation"))<1)throw invalid();
  WireValues.id(n.get("owner_job_id"),false);WireValues.id(n.get("run_id"),false);WireValues.id(n.get("source_id"),true);WireValues.businessDate(n.get("business_date"));
  for(String field:List.of("plan_hash","plan_index_hash","result_hash","universe_hash","universe_index_hash"))WireValues.sha256(n.get(field));
 }
 private static IllegalStateException invalid(){return new IllegalStateException("committed source stage observation unavailable");}
}
