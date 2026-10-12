package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** All four daily groups are native-qualified and rechecked against the same original directory and RR snapshot. */
public final class DailyReportInputComponentService {
    private record Facts(SourceProof proof,List<GenerationFlowService.Dependency> dependencies,Map<String,List<String>> targets,long bytes){ }
    public static final class Captured {
        private final Object issuer;private final LocalDate day;private final Instant cutoff;private final NewsDayReportComponentService.Captured news;private final CatalogDayReportAggregateService.Captured catalog;private final EntityHeatReportComponentService.Captured heat;private final Facts facts;private final boolean supplement;
        private Captured(Object issuer,LocalDate day,Instant cutoff,NewsDayReportComponentService.Captured news,CatalogDayReportAggregateService.Captured catalog,EntityHeatReportComponentService.Captured heat,Facts facts,boolean supplement){this.issuer=issuer;this.day=day;this.cutoff=cutoff;this.news=news;this.catalog=catalog;this.heat=heat;this.facts=facts;this.supplement=supplement;}
        public LocalDate day(){return day;}public Instant cutoff(){return cutoff;}public SourceProof sourceProof(){return facts.proof();}public List<GenerationFlowService.Dependency> dependencies(){return facts.dependencies();}public Map<String,List<String>> referenceTargetsByEvidence(){return facts.targets();}public long originalByteSize(){return facts.bytes();}
        @Override public String toString(){return "CompleteOriginalDailyReportInputs[redacted]";}
    }
    private final Object issuer=new Object();private final NewsDayReportComponentService news;private final CatalogDayReportAggregateService catalog;private final EntityHeatReportComponentService heat;private final TransactionTemplate reads;private final int budget;
    public DailyReportInputComponentService(NewsDayReportComponentService news,CatalogDayReportAggregateService catalog,EntityHeatReportComponentService heat,PlatformTransactionManager tx){this(news,catalog,heat,tx,EntityHeatCutoffProofService.MAX_ORIGINAL_BYTES);}
    public DailyReportInputComponentService(NewsDayReportComponentService news,CatalogDayReportAggregateService catalog,EntityHeatReportComponentService heat,PlatformTransactionManager tx,int budget){this.news=Objects.requireNonNull(news);this.catalog=Objects.requireNonNull(catalog);this.heat=Objects.requireNonNull(heat);if(budget<1024||budget>EntityHeatCutoffProofService.MAX_ORIGINAL_BYTES)throw invalid();this.budget=budget;reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Captured capture(LocalDate day,Instant cutoff){return capture(day,cutoff,false);}
    /** Whole source facts at the explicit new operation cutoff. This grants no generation or no-update authority. */
    public Captured captureSupplement(LocalDate day,Instant cutoff){return capture(day,cutoff,true);}
    private Captured capture(LocalDate day,Instant cutoff,boolean supplement){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();qualified(day,cutoff,supplement);var n=news.capture(day,cutoff);if(n==null)return null;var c=catalog.capture(day,cutoff);if(c==null)return null;var h=supplement?heat.captureSupplement(cutoff):heat.capture(cutoff);if(h==null)throw invalid();return reads.execute(s->new Captured(issuer,day,cutoff,n,c,h,build(day,cutoff,n,c,h,supplement),supplement));}

    public Captured verifyObserved(Captured captured){readonly();if(captured==null||captured.issuer!=issuer||!captured.facts.equals(build(captured.day,captured.cutoff,captured.news,captured.catalog,captured.heat,captured.supplement)))throw invalid();return captured;}
    private Facts build(LocalDate day,Instant cutoff,NewsDayReportComponentService.Captured n,CatalogDayReportAggregateService.Captured c,EntityHeatReportComponentService.Captured h,boolean supplement){readonly();qualified(day,cutoff,supplement);news.verifyObserved(n);catalog.verifyObserved(c);heat.verifyObserved(h);if(!day.equals(n.day())||!day.equals(c.day())||!cutoff.equals(n.cutoff())||!cutoff.equals(c.cutoff())||!cutoff.equals(h.cutoff())||!n.directory().equals(c.directory()))throw invalid();long bytes=Math.addExact(Math.addExact(n.originalByteSize(),c.originalByteSize()),h.originalByteSize());if(bytes>budget)throw new GenerationFlowService.CapacityGuarded();
        var candidates=new ArrayList<Candidate>();var ids=new HashSet<String>();append(candidates,ids,n.candidates(),Set.of(Group.SOURCE_SUMMARIES,Group.HIGH_VALUE_ITEMS));append(candidates,ids,c.candidates(),Set.of(Group.NEW_CATALOG_ANALYSIS));append(candidates,ids,h.candidates(),Set.of(Group.HISTORICAL_HEAT));
        var dependencies=new TreeMap<String,GenerationFlowService.Dependency>();for(var list:List.of(n.dependencies(),c.dependencies(),h.dependencies()))for(var value:list){if(value==null||value.completedAt()==null||value.completedAt().isAfter(cutoff)||!"succeeded".equals(value.state()))throw invalid();var previous=dependencies.putIfAbsent(value.id(),value);if(previous!=null&&!previous.equals(value))throw invalid();}
        var targets=new TreeMap<String,List<String>>();for(var map:List.of(n.referenceTargetsByEvidence(),h.referenceTargetsByEvidence()))for(var entry:map.entrySet())put(targets,entry.getKey(),entry.getValue());for(var ref:c.references())put(targets,ref.evidenceId(),List.of(ref.targetJson()));if(!ids.equals(targets.keySet()))throw invalid();for(var candidate:candidates)if(candidate.evidence()!=null&&!dependencies.keySet().containsAll(candidate.evidence().references()))throw invalid();
        var omissions=new LinkedHashSet<MissingInput>();omissions.addAll(n.missing());omissions.addAll(c.missing());omissions.addAll(h.missing());var proof=new SourceProof(cutoff,day,day.plusDays(1),true,candidates,List.copyOf(omissions),List.of(),List.copyOf(dependencies.keySet()));
        return new Facts(proof,List.copyOf(dependencies.values()),Collections.unmodifiableMap(targets),bytes);
    }
    private static void append(List<Candidate> output,Set<String> ids,List<Candidate> input,Set<Group> groups){for(var candidate:input){if(candidate==null||!groups.contains(candidate.group()))throw invalid();if(candidate.evidence()!=null&&!ids.add(candidate.evidence().id()))throw invalid();output.add(candidate);}}
    private static void put(Map<String,List<String>> targets,String id,List<String> refs){if(id==null||refs==null||refs.isEmpty()||refs.stream().anyMatch(v->v==null||v.isBlank())||targets.putIfAbsent(id,refs.stream().distinct().sorted().toList())!=null)throw invalid();}
    private static void qualified(LocalDate day,Instant cutoff,boolean supplement){if(day==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();Instant planned=day.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();if(cutoff.isBefore(planned)||!supplement&&!cutoff.equals(planned))throw invalid();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original daily report input component unavailable");}
}
