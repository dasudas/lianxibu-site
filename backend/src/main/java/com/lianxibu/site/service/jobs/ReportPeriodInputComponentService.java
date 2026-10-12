package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Complete native daily-report universe for a natural week/month. No current body or fabricated no-update date. */
public final class ReportPeriodInputComponentService {
    public record Validity(boolean effectiveValid,boolean blocked) { }
    @FunctionalInterface public interface VersionEligibility { Validity read(ContentVersionKey version); }
    private record Fact(JdbcReportTaskHeaders.Header header,JdbcReportCompletionReceipts.Ready ready,Instant first,
                        ReportOriginalCompletionService.Completed original,Validity validity,ReportNoUpdateProofService.Available noUpdate) { }
    public static final class Captured {
        private final Object issuer;private final Stage stage;private final LocalDate first,end;private final Instant cutoff;
        private final Map<String,Fact> facts;private final List<Candidate> candidates;private final List<Dependency> dependencies;
        private final List<LocalDate> noUpdateDates;private final List<MissingInput> missing;private final Map<String,String> referenceTargets;private final long bytes;private final boolean supplement;
        private Captured(Object issuer,Stage stage,LocalDate first,LocalDate end,Instant cutoff,Map<String,Fact> facts,
                         List<Candidate> candidates,List<Dependency> dependencies,List<MissingInput> missing,List<LocalDate> noUpdateDates,Map<String,String> refs,long bytes,boolean supplement){
            this.issuer=issuer;this.stage=stage;this.first=first;this.end=end;this.cutoff=cutoff;this.facts=Map.copyOf(facts);
            this.candidates=List.copyOf(candidates);this.dependencies=List.copyOf(dependencies);this.missing=List.copyOf(missing);this.noUpdateDates=List.copyOf(noUpdateDates);
            this.referenceTargets=Map.copyOf(refs);this.bytes=bytes;this.supplement=supplement;
        }
        public SourceProof sourceProof(){return new SourceProof(cutoff,first,end,true,candidates,missing,noUpdateDates,dependencies.stream().map(Dependency::id).toList());}
        public Stage stage(){return stage;}public LocalDate first(){return first;}public LocalDate end(){return end;}public Instant cutoff(){return cutoff;}
        public List<Candidate> candidates(){return candidates;}public List<Dependency> dependencies(){return dependencies;}
        public List<MissingInput> missing(){return missing;}public Map<String,String> referenceTargets(){return referenceTargets;}
        public int originalRootCount(){return facts.size();}public long originalByteSize(){return bytes;}
        @Override public String toString(){return "OriginalReportPeriodComponent[redacted]";}
    }
    private final Object issuer=new Object();private final JdbcDailyReportRootPages pages;private final JdbcReportTaskHeaders headers;
    private final JdbcReportCompletionReceipts ready;private final JdbcVersionAvailability first;private final ReportOriginalCompletionService completions;
    private final ReportNoUpdateProofService noUpdates;private final VersionEligibility eligible;private final TransactionTemplate reads;private final long maximum;
    public ReportPeriodInputComponentService(DataSource source,ReportOriginalCompletionService completions,VersionEligibility eligible,PlatformTransactionManager manager){this(source,completions,eligible,manager,32L*1024*1024);}
    public ReportPeriodInputComponentService(DataSource source,ReportOriginalCompletionService completions,VersionEligibility eligible,PlatformTransactionManager manager,long maximum){
        this(source,completions,eligible,nativeNoUpdates(source,manager),manager,maximum);
    }
    public ReportPeriodInputComponentService(DataSource source,ReportOriginalCompletionService completions,VersionEligibility eligible,ReportNoUpdateProofService noUpdates,PlatformTransactionManager manager){this(source,completions,eligible,noUpdates,manager,32L*1024*1024);}
    public ReportPeriodInputComponentService(DataSource source,ReportOriginalCompletionService completions,VersionEligibility eligible,ReportNoUpdateProofService noUpdates,PlatformTransactionManager manager,long maximum){
        if(maximum<1024||maximum>32L*1024*1024)throw invalid();this.maximum=maximum;Objects.requireNonNull(source);this.noUpdates=Objects.requireNonNull(noUpdates);
        pages=new JdbcDailyReportRootPages(source);headers=new JdbcReportTaskHeaders(source);ready=new JdbcReportCompletionReceipts(source);
        first=new JdbcVersionAvailability(source);this.completions=Objects.requireNonNull(completions);this.eligible=Objects.requireNonNull(eligible);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(Stage stage,LocalDate last,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();period(stage,last,cutoff,false);
        return reads.execute(s->read(stage,last,cutoff,false));
    }
    /** Whole natural-period original daily facts at the explicit operation cutoff. */
    public Captured captureSupplement(Stage stage,LocalDate last,Instant cutoff){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();period(stage,last,cutoff,true);return reads.execute(s->read(stage,last,cutoff,true));}
    public Captured verifyObserved(Captured captured){
        readonly();if(captured==null||captured.issuer!=issuer)throw invalid();var fresh=read(captured.stage,captured.end.minusDays(1),captured.cutoff,captured.supplement);
        if(!captured.facts.equals(fresh.facts)||!captured.candidates.equals(fresh.candidates)||!captured.dependencies.equals(fresh.dependencies)
            ||!captured.missing.equals(fresh.missing)||!captured.noUpdateDates.equals(fresh.noUpdateDates)||!captured.referenceTargets.equals(fresh.referenceTargets)||captured.bytes!=fresh.bytes)throw invalid();return captured;
    }
    private Captured read(Stage stage,LocalDate last,Instant cutoff,boolean supplement){
        readonly();LocalDate start=period(stage,last,cutoff,supplement),end=last.plusDays(1);var facts=new LinkedHashMap<String,Fact>();
        String cursor=null;boolean more;long bytes=0;
        do{
            var page=pages.read(start,end,cursor);cursor=page.nextCursor();more=page.hasMore();
            for(String root:page.roots()){
                if(facts.containsKey(root))throw invalid();var header=headers.read(root);if(header==null||!"daily_report".equals(header.stage())||header.day().isBefore(start)||!header.day().isBefore(end))throw invalid();
                var receipt=ready.read(root);Instant observed=receipt==null?null:first.read(receipt.version(),receipt.inputHash());
                // Keep late original completions as excluded facts; the selection below still uses the real target cutoff.
                var original=completions.read(root,observed!=null&&observed.isAfter(cutoff)?observed:cutoff);
                var noUpdate=header.generation()==1&&("report:daily:"+header.day()).equals(header.logicalKey())?noUpdates.read(new ReportInputCaptureService.Scope(header.logicalKey(),1,Stage.DAILY_REPORT,header.day())):null;
                if(noUpdate!=null&&(!root.equals(noUpdate.rootId())||!header.inputHash().equals(noUpdate.inputHash())||receipt!=null||original!=null))throw invalid();
                Validity validity=original==null?null:Objects.requireNonNull(eligible.read(original.ready().version()));
                if(original!=null&&!header.equals(original.header()))throw invalid();
                bytes=Math.addExact(bytes,8192); // Conservative bounded reservation for every full typed native header/first locator.
                if(original!=null)bytes=Math.addExact(bytes,original.originalByteSize());
                else if(receipt!=null)bytes=Math.addExact(bytes,receipt.payloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                if(noUpdate!=null)bytes=Math.addExact(bytes,noUpdate.originalByteSize());
                if(bytes>maximum)throw new GenerationFlowService.CapacityGuarded();facts.put(root,new Fact(header,receipt,observed,original,validity,noUpdate));
            }
        }while(more);
        var candidates=new ArrayList<Candidate>();var dependencies=new ArrayList<Dependency>();var missing=new ArrayList<MissingInput>();var refs=new LinkedHashMap<String,String>();var noUpdateDates=new ArrayList<LocalDate>();
        for(LocalDate day=start;day.isBefore(end);day=day.plusDays(1)){
            LocalDate target=day;var chosen=facts.values().stream().filter(f->target.equals(f.header.day())&&(f.original!=null||f.noUpdate!=null)
                &&("report:daily:"+target).equals(f.header.logicalKey())&&!availableAt(f).isAfter(cutoff)&&!f.header.createdAt().isAfter(cutoff))
                .max(Comparator.comparingLong((Fact f)->f.header.generation()).thenComparing(ReportPeriodInputComponentService::availableAt).thenComparing(f->f.header.id())).orElse(null);
            if(chosen==null){missing.add(new MissingInput("daily_report",null,day,"no_proven_report_at_cutoff"));continue;}
            if(chosen.noUpdate!=null){noUpdateDates.add(day);continue;}
            var original=chosen.original;var version=original.ready().version();String ref=JdbcFrozenContentVersions.version(version);
            String id=JdbcFrozenInputs.stableId("report-period-input",ref);var body=CanonicalJson.parse(original.bodyJson());
            var metadata=new Metadata(new ResourceRef("report",version.contentId(),null),version.revision(),version.inputJobId(),version.bodyHash(),null,null,null,
                day,WireValues.text(body.get("title")),null,null);
            var evidence=new Evidence(id,"daily_report",day,original.availableAt(),version.bodyHash(),original.bodyJson(),chosen.validity.effectiveValid(),chosen.validity.blocked(),"accepted",List.of(version.inputJobId()));
            candidates.add(new Candidate(Group.UPSTREAM_REPORTS,metadata,evidence,"daily",true,true,null,null));
            dependencies.add(new Dependency(version.inputJobId(),Stage.DAILY_REPORT,"succeeded",original.availableAt()));refs.put(id,ref);
        }
        return new Captured(issuer,stage,start,end,cutoff,facts,candidates,dependencies,missing,noUpdateDates,refs,bytes,supplement);
    }
    private static Instant availableAt(Fact fact){return fact.noUpdate==null?fact.first:fact.noUpdate.availableAt();}
    /** The legacy constructor also uses actual native proof; it never grants a count-based fallback. */
    private static ReportNoUpdateProofService nativeNoUpdates(DataSource source,PlatformTransactionManager manager){return new ReportNoUpdateProofService(source,new ReportPreparedInputService(source,manager),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),manager),manager,Clock.systemUTC());}
    private static LocalDate period(Stage stage,LocalDate last,Instant cutoff,boolean supplement){
        if(last==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();Instant planned=last.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();if(cutoff.isBefore(planned)||!supplement&&!cutoff.equals(planned))throw invalid();
        return switch(stage==null?Stage.AD_HOC_AI:stage){case WEEKLY_REPORT->{if(last.getDayOfWeek()!=DayOfWeek.SUNDAY)throw invalid();yield last.minusDays(6);}case MONTHLY_REPORT->{if(last.getDayOfMonth()!=last.lengthOfMonth())throw invalid();yield last.withDayOfMonth(1);}default->throw invalid();};
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original report period unavailable");}
    @Override public String toString(){return "ReportPeriodInputComponentService[redacted]";}
}
