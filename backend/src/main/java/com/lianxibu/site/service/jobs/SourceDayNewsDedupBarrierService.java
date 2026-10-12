package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.JdbcSourceDayDedupClosures;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Full original news day closure. This proof alone does not authorize a model call. */
public final class SourceDayNewsDedupBarrierService {
    public static final class Closed {
        private final SourceDayCohortSealService.Sealed original;private final SourceDedupBarrier.Closure closure;
        private Closed(SourceDayCohortSealService.Sealed original,SourceDedupBarrier.Closure closure){this.original=original;this.closure=closure;}
        public SourceDayCohortSealService.Sealed original(){return original;}public SourceDedupBarrier.Closure closure(){return closure;}
        @Override public String toString(){return "ClosedOriginalNewsDay[redacted]";}
    }
    private final SourceDayCohortSealService cohorts;private final SourceDayNewsDedupProgressService progress;
    private final JdbcSourceDayDedupClosures storage;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
    public SourceDayNewsDedupBarrierService(SourceDayCohortSealService cohorts,SourceDayNewsDedupProgressService progress,JdbcSourceDayDedupClosures storage,SharedWriteExecutor writes,PlatformTransactionManager manager,Clock clock){
        this.cohorts=Objects.requireNonNull(cohorts);this.progress=Objects.requireNonNull(progress);this.storage=Objects.requireNonNull(storage);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Closed find(LocalDate day){
        outside();var original=cohorts.find(day);var archive=reads.execute(s->storage.read(day,"news_tech"));
        if(archive==null)return null;return verify(archive,original);
    }
    public Closed close(LocalDate day){
        outside();var previous=find(day);if(previous!=null)return previous;
        var original=cohorts.find(day);if(original==null)return null;
        Instant observed=clock.instant().truncatedTo(ChronoUnit.MICROS);if(observed.isBefore(original.sealedAt()))throw invalid();
        var facts=new ArrayList<SourceDedupBarrier.Progress>();
        for(var request:original.plan().requests())if("news_tech".equals(request.source().category())){var fact=progress.read(request,original.sealedAt(),observed);if(fact!=null)facts.add(fact);}
        var closure=SourceDedupBarrier.close(original.plan().cohort("news_tech"),observed,facts);if(closure==null)return null;
        var envelope=JsonMapper.shared().createObjectNode().put("schema_version","source-day-dedup-seal-v1").put("business_date",day.toString()).put("category","news_tech")
                .put("directory_owner_id",original.plan().directory().ownerId()).put("bundle_hash",original.plan().bundleHash()).put("cohort_hash",closure.cohort().hash()).put("closure_hash",closure.hash());
        envelope.set("closure",CanonicalJson.parse(closure.json()));var prepared=JdbcSourceDayDedupClosures.prepare(new String(CanonicalJson.bytes(envelope),java.nio.charset.StandardCharsets.UTF_8));
        var archive=writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceDayDedupClosures.key(day,"news_tech"),original.plan().directory().ownerId(),closure.hash()),key->storage.read(day,"news_tech"),action->{
            if(clock.instant().truncatedTo(ChronoUnit.MICROS).isBefore(observed))throw invalid();return storage.save(prepared);
        });
        return verify(archive,original);
    }
    private Closed verify(JdbcSourceDayDedupClosures.Archive archive,SourceDayCohortSealService.Sealed original){
        if(original==null||!archive.day().equals(original.plan().directory().day())||!"news_tech".equals(archive.category())||!archive.ownerId().equals(original.plan().directory().ownerId())
                ||!archive.bundleHash().equals(original.plan().bundleHash())||!archive.cohortHash().equals(original.plan().cohort("news_tech").hash()))throw invalid();
        var closure=SourceDedupBarrier.readClosure(archive.closureJson(),archive.closureHash(),original.plan().cohort("news_tech"));
        var requests=new HashMap<String,SourceDayRunRegistration.Request>();for(var request:original.plan().requests())if("news_tech".equals(request.source().category()))requests.put(request.source().id(),request);
        for(var entry:closure.entries())if(entry.progress()!=null){var request=requests.get(entry.sourceId());if(request==null||!entry.progress().equals(progress.read(request,original.sealedAt(),closure.observedAt())))throw invalid();}
        return new Closed(original,closure);
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original full news day dedup barrier unavailable");}
}
