package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
/** Closed original fragment identity; the shared ledger owns its bounded receipt verification. */
public final class JdbcSourceFragmentAvailability {
 private final JdbcCommittedProofAvailability ledger;
 public JdbcSourceFragmentAvailability(DataSource source){ledger=new JdbcCommittedProofAvailability(source,"source_fragment_availability","fragment-observer","Observed committed source fragment","fragment-observed:","fragment-availability","committed-source-fragment-observation-v1");}
 public Instant read(String target){validateTarget(target);return ledger.read(target);}
 public void save(String target,Instant observed){validateTarget(target);ledger.save(target,observed);}
 public static String key(String target){validateTarget(target);return "fragment-observed:"+JdbcResultState.sha(target);}
 private static void validateTarget(String target){
  if(target==null||target.getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();var n=CanonicalJson.parse(target);var fields=Set.of("schema_version","root_id","input_hash","result_hash","member_handoff_hash","stage","generation","stage_part");WireValues.object(n,fields,fields);
  if(!target.equals(JdbcFrozenInputs.canonical(n))||!"source-fragment-key-v1".equals(WireValues.text(n.get("schema_version")))||!"news_dedup".equals(WireValues.text(n.get("stage")))||WireValues.nonnegativeInteger(n.get("generation"))<1||WireValues.nonnegativeInteger(n.get("stage_part"))>=10000)throw invalid();
  WireValues.id(n.get("root_id"),false);for(String field:List.of("input_hash","result_hash","member_handoff_hash"))WireValues.sha256(n.get(field));
 }
 private static IllegalStateException invalid(){return new IllegalStateException("committed source fragment observation unavailable");}
}
