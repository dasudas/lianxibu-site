package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcJobSchedules;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.content.ReportInputProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Read snapshot of scheduling facts; no fallback to mutable creation/update/eligibility. */
public final class JobScheduleReadService {
    public record Period(Instant start,Instant end,String timezone){}
    public record Schedule(String jobId,long jobRevision,Period period,Instant firstQueuedAt,Instant observedAt){}
    private final JdbcJobSchedules store;private final JdbcFrozenInputs inputs;private final Clock clock;private final TransactionTemplate reads;
    public JobScheduleReadService(JdbcJobSchedules store,JdbcFrozenInputs inputs,PlatformTransactionManager tx,Clock clock){this.store=Objects.requireNonNull(store);this.inputs=Objects.requireNonNull(inputs);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Optional<Schedule> read(String id){WireValues.id(id,false);return reads.execute(status->{
        var job=store.job(id);if(job==null)return Optional.empty();if(!id.equals(job.id())||job.revision()<0||job.revision()>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();
        Instant observed=clock.instant().truncatedTo(ChronoUnit.MICROS);Period period=null;Instant queued=null;
        if("logical_task".equals(job.kind())){
            JsonNode frozen=null;
            if(job.manifest()!=null){if(job.inputHash()==null||job.inputHash().length!=32)throw invalid();frozen=CanonicalJson.parse(inputs.read(id,HexFormat.of().formatHex(job.inputHash())));if(!Objects.equals(job.taskType(),WireValues.text(frozen.get("stage")))||!Objects.equals(job.logicalKey(),WireValues.text(frozen.get("logical_key")))||!Objects.equals(job.businessDate(),WireValues.businessDate(frozen.get("business_date")))||!Objects.equals(job.generation(),WireValues.nonnegativeInteger(frozen.get("generation"))))throw invalid();}
            if(frozen!=null&&frozen.has(ReportInputProfile.FIELD)){
                ReportInputProfile.validated(frozen);var p=frozen.get(ReportInputProfile.FIELD);period=new Period(WireValues.utcMicroseconds(p.get("period_start")),WireValues.utcMicroseconds(p.get("period_end")),WireValues.text(p.get("timezone")));
            }
            var target=tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();target.put("kind","logical_task");target.put("id",id);String targetJson=JdbcFrozenInputs.canonical(target);byte[] targetHash=sha(CanonicalJson.bytes(target));var admissions=store.admissions(targetJson,targetHash);if(admissions.size()>100)throw invalid();
            for(var admission:admissions){
                if(!target.equals(CanonicalJson.parse(admission.target()))||!Arrays.equals(targetHash,admission.targetHash()))throw invalid();
                JsonNode proof=CanonicalJson.parse(admission.payload());if(!Arrays.equals(sha(CanonicalJson.bytes(proof)),admission.payloadHash()))throw invalid();
                Set<String> fields=Set.of("schema_version","command_hash","owner_id","cutoff","coverage_state","missing","input_hash","attempt_id","request_hash","eligible_at","state");WireValues.object(proof,fields,fields);
                if(!"generation-prepare-receipt-v1".equals(WireValues.text(proof.get("schema_version")))||!id.equals(WireValues.text(proof.get("owner_id"))))throw invalid();WireValues.sha256(proof.get("command_hash"));
                if(!Set.of("queued","waiting_input","no_input","coverage_only","no_updates").contains(WireValues.text(proof.get("state")))||!proof.get("missing").isArray())throw invalid();
                if(!"queued".equals(WireValues.text(proof.get("state")))){if(!proof.get("attempt_id").isNull()||!proof.get("request_hash").isNull())throw invalid();continue;}
                String hash=WireValues.sha256(proof.get("input_hash")),attemptId=WireValues.id(proof.get("attempt_id"),false);if(frozen==null||!hash.equals(HexFormat.of().formatHex(job.inputHash())))throw invalid();
                var attempt=store.attempt(attemptId);if(attempt==null||!attemptId.equals(attempt.id())||!id.equals(attempt.root())||!id.equals(attempt.owner())||!Arrays.equals(job.inputHash(),attempt.inputHash())||attempt.request()==null)throw invalid();
                JsonNode request=CanonicalJson.parse(attempt.request());if(!CanonicalJson.hash(CanonicalJson.Domain.ACTION,request).equals(WireValues.sha256(proof.get("request_hash")))||!hash.equals(WireValues.sha256(request.get("input_hash"))))throw invalid();
                Instant admitted=admission.actedAt();if(admitted==null||admitted.getNano()%1000!=0||admitted.isAfter(observed))throw invalid();if(queued==null||admitted.isBefore(queued))queued=admitted;
            }
        }
        return Optional.of(new Schedule(id,job.revision(),period,queued,observed));
    });}
    private static byte[] sha(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception error){throw new IllegalStateException(error);}}
    private static IllegalStateException invalid(){return new IllegalStateException("job scheduling proof unavailable");}
}
