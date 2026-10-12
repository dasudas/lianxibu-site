package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** The whole original day and every included native source remain locked until the caller commits. */
public final class CatalogDayAnalysisCommitPermitService {
    public static final class Captured {
        private final Object issuer;private final CatalogDayEntityEvidenceService.Captured evidence;
        private final JdbcSourceDayDedupClosures.Archive closure;private final List<CatalogSourceFinalCommitPermitService.Captured> sources;
        private Captured(Object issuer,CatalogDayEntityEvidenceService.Captured evidence,JdbcSourceDayDedupClosures.Archive closure,List<CatalogSourceFinalCommitPermitService.Captured> sources){this.issuer=issuer;this.evidence=evidence;this.closure=closure;this.sources=List.copyOf(sources);}
        public CatalogDayEntityEvidenceService.Captured evidence(){return evidence;}
        @Override public String toString(){return "OriginalCatalogDayCommitPermit[redacted]";}
    }
    private final Object issuer=new Object();private final String category;private final CatalogDayEntityEvidenceService evidence;
    private final CatalogSourceFinalCommitPermitService sources;private final JdbcSourceDayDedupClosures closures;private final TransactionTemplate reads;
    public CatalogDayAnalysisCommitPermitService(CatalogDayEntityEvidenceService evidence,CatalogSourceFinalCommitPermitService sources,JdbcSourceDayDedupClosures closures,PlatformTransactionManager manager,String category){
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};this.evidence=Objects.requireNonNull(evidence);this.sources=Objects.requireNonNull(sources);this.closures=Objects.requireNonNull(closures);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(LocalDate day){outside();return capture(evidence.capture(day));}
    public Captured captureOriginal(LocalDate day){outside();return capture(evidence.captureOriginal(day));}
    private Captured capture(CatalogDayEntityEvidenceService.Captured original){
        if(original==null)return null;if(!category.equals(original.original().category()))throw invalid();
        var closure=reads.execute(s->{evidence.verifyObserved(original);return archive(original);});var permits=new ArrayList<CatalogSourceFinalCommitPermitService.Captured>();
        for(var proof:original.original().sources()){
            var ref=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(proof.result().json()).get("plan_ref")));var permit=sources.capture(ref,proof.receipt().archiveRef());
            if(permit==null||!permit.proof().result().json().equals(proof.result().json()))throw invalid();permits.add(permit);
        }
        reads.execute(s->{evidence.verifyObserved(original);if(!Objects.equals(closure,archive(original)))throw invalid();return true;});return new Captured(issuer,original,Objects.requireNonNull(closure),permits);
    }
    /** Fresh original storage proof only. The caller must independently lock member/entity/intent eligibility. */
    public CatalogDayEntityEvidenceService.Captured verify(Captured captured){
        JdbcFrozenInputs.requireTx();if(captured==null||captured.issuer!=issuer||!category.equals(captured.evidence.original().category()))throw invalid();
        var closure=closures.lockAndRead(captured.evidence.original().day(),category);if(!captured.closure.equals(closure))throw invalid();
        if(captured.sources.size()!=captured.evidence.original().sources().size())throw invalid();for(int n=0;n<captured.sources.size();n++)if(!sources.verify(captured.sources.get(n)).result().json().equals(captured.evidence.original().sources().get(n).result().json()))throw invalid();return captured.evidence;
    }
    private JdbcSourceDayDedupClosures.Archive archive(CatalogDayEntityEvidenceService.Captured original){
        var actual=closures.read(original.original().day(),category);var closed=original.original().closed();if(actual==null||!actual.closureJson().equals(closed.closure().json())||!actual.closureHash().equals(closed.closure().hash())||!actual.bundleHash().equals(closed.original().plan().bundleHash())||!actual.ownerId().equals(closed.original().plan().directory().ownerId())||!actual.cohortHash().equals(closed.closure().cohort().hash()))throw invalid();return actual;
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog day writer permission unavailable");}
}
