package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Final committed output observation; structural P2 observations use a different closed identity. */
public final class JdbcCatalogSourceFinalAvailability {
    private final JdbcCommittedProofAvailability ledger;
    public JdbcCatalogSourceFinalAvailability(DataSource source){ledger=new JdbcCommittedProofAvailability(source,"catalog_final_availability","catalog-source-final-observer","Observed committed final catalog source output","catalog-source-final-observed:","catalog-source-final-availability","committed-catalog-source-final-observation-v1");}
    public Instant read(String target){validate(target);return ledger.read(target);}
    public void save(String target,Instant observed){validate(target);ledger.save(target,observed);}
    public static String key(String target){validate(target);return "catalog-source-final-observed:"+JdbcResultState.sha(target);}
    private static void validate(String target){
        if(target==null||target.getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();var n=CanonicalJson.parse(target);var fields=Set.of("schema_version","category","owner_job_id","plan_hash","plan_index_hash","result_hash","archive_index_hash","receipt_id","receipt_hash","stage","generation","business_date","run_id","source_id");WireValues.object(n,fields,fields);
        if(!target.equals(JdbcFrozenInputs.canonical(n))||!"catalog-source-final-key-v1".equals(WireValues.text(n.get("schema_version")))||!Set.of("agent","skill","workflow","model").contains(WireValues.text(n.get("category")))||!WireValues.text(n.get("category")).concat("_dedup").equals(WireValues.text(n.get("stage")))||WireValues.nonnegativeInteger(n.get("generation"))<1)throw invalid();
        for(String field:List.of("owner_job_id","receipt_id","run_id"))WireValues.id(n.get(field),false);WireValues.id(n.get("source_id"),true);WireValues.businessDate(n.get("business_date"));for(String field:List.of("plan_hash","plan_index_hash","result_hash","archive_index_hash","receipt_hash"))WireValues.sha256(n.get(field));
        if(!JdbcCatalogSourceFinalReceipts.receiptId(n.get("owner_job_id").asText()).equals(n.get("receipt_id").asText()))throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("committed final catalog source observation identity rejected");}
}
