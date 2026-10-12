package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position;
import com.lianxibu.site.repository.sources.JdbcSourceRunAnalysis;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.node.ArrayNode;

/** Projects a proven final source result within the caller's final receipt transaction. */
public final class SourceRunAnalysisService {
    @FunctionalInterface public interface PriorReader {
        /** Verify native final receipts among the original refs in this writer; never infer current generation. */
        Position latest(String runId,List<String> originalRefs);
    }
    public record Outcome(String disposition,long revision){ }
    private final JdbcSourceRunAnalysis store;private final PriorReader prior;private final String category;
    public SourceRunAnalysisService(JdbcSourceRunAnalysis store,PriorReader prior){this(store,prior,"news_tech");}
    /** Explicit proof-consumer category. The native locked row must match this exact category. */
    public SourceRunAnalysisService(JdbcSourceRunAnalysis store,PriorReader prior,String category){
        this.store=Objects.requireNonNull(store);this.prior=Objects.requireNonNull(prior);this.category=switch(Objects.requireNonNull(category)){case "news_tech","agent","skill","workflow","model"->category;default->throw invalid();};
    }
    public Outcome apply(SourceRunAnalysisCommand command){
        try {
            JdbcSourceRunAnalysis.requireWriter();Objects.requireNonNull(command);var run=store.locked(command.runId());
            if(!run.id().equals(command.runId())||!run.sourceId().equals(command.sourceId())||!run.sourceIdentity().equals(command.sourceId())||!category.equals(run.category())||!run.businessDate().equals(command.businessDate())||run.generation()!=command.collectionGeneration()||!run.manifestHash().equals(command.manifestHash())||run.accepted()!=command.accepted()||run.excluded()!=command.excluded()||run.expected()!=command.accepted()+command.excluded()||run.pending()!=0||!"production".equals(run.activationStage())||!Set.of("daily","historical_backfill","production_qualification","correction").contains(run.kind())||!Set.of("complete","partial","no_usable_input","empty_success").contains(run.deliveryState())||!Set.of("waiting_input","queued","running","succeeded","partial","failed").contains(run.analysisState())||run.revision()<0||run.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw invalid();
            var refs=CanonicalJson.parse(run.refs());if(!refs.isArray())throw invalid();var ids=new LinkedHashSet<String>();for(var id:refs)if(!ids.add(WireValues.id(id,false)))throw invalid();
            var previous=prior.latest(run.id(),List.copyOf(ids));String state=command.coverageState().equals("partial")?"partial":"succeeded";
            if(previous==null){if(run.dedupSummary()!=null||run.analysisSummary()!=null||Set.of("succeeded","partial").contains(run.analysisState()))throw invalid();}
            else {
                if(!ids.contains(previous.receiptId()))throw invalid();int order=command.position().inputCutoff().compareTo(previous.inputCutoff());
                if(order==0){if(!command.position().logicalKey().equals(previous.logicalKey()))throw invalid();order=Long.compare(command.position().generation(),previous.generation());}
                if(order<0)return new Outcome("superseded",run.revision());
                if(order==0){if(!command.position().equals(previous)||!command.dedupSummary().equals(run.dedupSummary())||!command.analysisSummary().equals(run.analysisSummary())||!state.equals(run.analysisState()))throw invalid();return new Outcome("unchanged",run.revision());}
            }
            if(ids.contains(command.position().receiptId()))throw invalid();((ArrayNode)refs).add(command.position().receiptId());byte[] encoded=CanonicalJson.bytes(refs);if(encoded.length>65536)throw invalid();
            store.project(run,command.dedupSummary(),command.analysisSummary(),state,new String(encoded,StandardCharsets.UTF_8));return new Outcome("applied",run.revision()+1);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("proven source run analysis projection rejected");}
}
