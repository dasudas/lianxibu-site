package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Cyclic bounded scan: UUID ordering is not a commit watermark. */
public final class VersionAvailabilityPoller {
    public record Outcome(int scanned,int observed,int failed) { }
    private final JdbcValidityPropagation candidates;
    private final VersionAvailabilityService observer;
    private final TransactionTemplate reads;
    private final int limit;
    private String cursor="";
    public VersionAvailabilityPoller(JdbcValidityPropagation candidates,VersionAvailabilityService observer,PlatformTransactionManager tx,int limit){
        this.candidates=Objects.requireNonNull(candidates);this.observer=Objects.requireNonNull(observer);
        if(limit<1||limit>50)throw new IllegalArgumentException("invalid observation batch");this.limit=limit;
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public synchronized Outcome poll(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("observation poll must own its transactions");
        var batch=Objects.requireNonNull(reads.execute(status->candidates.candidates(cursor,limit)));
        int observed=0,failed=0;
        for(var row:batch){
            try{
                var target=CanonicalJson.parse(row.target());var payload=CanonicalJson.parse(row.payload());
                if(!Arrays.equals(row.targetHash(),hash(JdbcFrozenInputs.canonical(target)))||!Arrays.equals(row.payloadHash(),hash(JdbcFrozenInputs.canonical(payload))))throw invalid();
                if("result_application".equals(row.type())){
                    if(!"result-application-receipt-v1".equals(payload.path("schema_version").asText()))throw invalid();
                    if(!"APPLIED".equals(payload.path("status").asText()))continue;
                    var version=payload.get("content_version");if(version==null||version.isNull())continue;
                    if(!Objects.equals(version.path("input_job_id").asText(),target.path("root_id").asText()))throw invalid();target=version;
                }else if(!"content_review_version".equals(row.type()))throw invalid();
                observer.capture(ContentVersionKey.fromJson(target));observed++;
            }catch(RuntimeException unavailable){failed++;}
        }
        cursor=batch.size()<limit?"":batch.getLast().id();
        return new Outcome(batch.size(),observed,failed);
    }
    private static byte[] hash(String value){return HexFormat.of().parseHex(JdbcResultState.sha(value));}
    private static IllegalStateException invalid(){return new IllegalStateException("version observation candidate unavailable");}
}
