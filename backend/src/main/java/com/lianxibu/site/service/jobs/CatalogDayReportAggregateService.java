package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** All four original catalog components, rechecked in one RR snapshot. Other daily report groups are separate. */
public final class CatalogDayReportAggregateService {
    public static final int MAX_ORIGINAL_BYTES=32*1024*1024;
    public static final List<String> CATEGORIES=List.of("agent","skill","workflow","model");
    public record Reference(String evidenceId,String dependencyId,String targetJson){
        public Reference{Objects.requireNonNull(evidenceId);Objects.requireNonNull(dependencyId);Objects.requireNonNull(targetJson);}
    }
    public static final class Captured {
        private final Object issuer;private final LocalDate day;private final Instant cutoff;private final CatalogDayReportComponentService.DirectoryRef directory;
        private final Map<String,CatalogDayReportComponentService.Captured> components;private final List<Candidate> candidates;private final List<GenerationFlowService.Dependency> dependencies;private final List<Reference> references;private final List<MissingInput> missing;
        private Captured(Object issuer,LocalDate day,Instant cutoff,CatalogDayReportComponentService.DirectoryRef directory,Map<String,CatalogDayReportComponentService.Captured> components,List<Candidate> candidates,List<GenerationFlowService.Dependency> dependencies,List<Reference> references,List<MissingInput> missing){this.issuer=issuer;this.day=day;this.cutoff=cutoff;this.directory=directory;this.components=Map.copyOf(components);this.candidates=List.copyOf(candidates);this.dependencies=List.copyOf(dependencies);this.references=List.copyOf(references);this.missing=List.copyOf(missing);}
        public long originalByteSize(){long bytes=0;for(var component:components.values())bytes=Math.addExact(bytes,component.originalByteSize());return bytes;}
        public LocalDate day(){return day;}public Instant cutoff(){return cutoff;}public CatalogDayReportComponentService.DirectoryRef directory(){return directory;}
        public Map<String,CatalogDayReportComponentService.Captured> components(){return components;}public List<Candidate> candidates(){return candidates;}
        public List<GenerationFlowService.Dependency> dependencies(){return dependencies;}public List<Reference> references(){return references;}public List<MissingInput> missing(){return missing;}
        @Override public String toString(){return "OriginalCatalogReportAggregate[redacted]";}
    }
    private final Object issuer=new Object();private final Map<String,CatalogDayReportComponentService> services;private final TransactionTemplate reads;private final int budget;
    public CatalogDayReportAggregateService(Map<String,CatalogDayReportComponentService> services,PlatformTransactionManager manager){this(services,manager,MAX_ORIGINAL_BYTES);}
    public CatalogDayReportAggregateService(Map<String,CatalogDayReportComponentService> services,PlatformTransactionManager manager,int budget){
        if(budget<1024||budget>MAX_ORIGINAL_BYTES)throw invalid();this.budget=budget;
        Objects.requireNonNull(services);if(!services.keySet().equals(Set.copyOf(CATEGORIES)))throw invalid();
        for(var entry:services.entrySet())if(entry.getValue()==null||!entry.getKey().equals(entry.getValue().category()))throw invalid();this.services=Map.copyOf(services);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(LocalDate day,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive()||day==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        var components=new LinkedHashMap<String,CatalogDayReportComponentService.Captured>();
        for(String category:CATEGORIES){var original=services.get(category).capture(day,cutoff);if(original==null)return null;components.put(category,original);}
        return reads.execute(s->combine(day,cutoff,components));
    }
    /** Original components and current eligibility must still match in one borrowed RR snapshot. */
    public Captured verifyObserved(Captured captured){
        readonly();if(captured==null||captured.issuer!=issuer)throw invalid();var fresh=combine(captured.day,captured.cutoff,captured.components);
        if(!fresh.directory.equals(captured.directory)||!fresh.candidates.equals(captured.candidates)||!fresh.dependencies.equals(captured.dependencies)||!fresh.references.equals(captured.references)||!fresh.missing.equals(captured.missing))throw invalid();return captured;
    }
    private Captured combine(LocalDate day,Instant cutoff,Map<String,CatalogDayReportComponentService.Captured> components){
        readonly();var candidates=new ArrayList<Candidate>();var dependencies=new ArrayList<GenerationFlowService.Dependency>();var references=new ArrayList<Reference>();var missing=new ArrayList<MissingInput>();var candidateIds=new HashSet<String>();var dependencyIds=new HashSet<String>();var referenceIds=new HashSet<String>();CatalogDayReportComponentService.DirectoryRef directory=null;long bytes=0;
        for(String category:CATEGORIES){
            var component=services.get(category).verifyObserved(components.get(category));bytes=Math.addExact(bytes,component.originalByteSize());if(bytes>budget)throw new GenerationFlowService.CapacityGuarded();if(!category.equals(component.category())||!day.equals(component.day())||!cutoff.equals(component.cutoff()))throw invalid();
            if(directory==null)directory=component.directory();else if(!directory.equals(component.directory()))throw invalid();
            if(directory.sealedAt().isAfter(cutoff))throw invalid();
            for(var candidate:component.candidates()){if(candidate.evidence()!=null&&!candidateIds.add(candidate.evidence().id()))throw invalid();candidates.add(candidate);}
            for(var dependency:component.dependencies()){if(!dependencyIds.add(dependency.id()))throw invalid();dependencies.add(dependency);}
            for(String target:component.referenceJson()){var version=ContentVersionKey.fromJson(CanonicalJson.parse(target));String id=JdbcFrozenInputs.stableId("report-catalog-version",target);if(!referenceIds.add(id)||!candidateIds.contains(id)||!dependencyIds.contains(version.inputJobId()))throw invalid();references.add(new Reference(id,version.inputJobId(),target));}
            missing.addAll(component.missing());
        }
        if(!candidateIds.equals(referenceIds)||dependencies.size()!=references.size())throw invalid();
        return new Captured(issuer,day,cutoff,Objects.requireNonNull(directory),components,candidates,dependencies,references,missing);
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog report aggregate unavailable");}
}
