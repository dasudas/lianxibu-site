package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Permanent closed final metadata. A finalizer separately proves the original output blocks. */
public final class CatalogSourceFinalReceiptService {
    public record Stored(Position position,String category,String runId,String sourceId,LocalDate businessDate,long collectionGeneration,String manifestHash,long accepted,long excluded,
            FrozenCatalogSourceFinal.Reference archiveRef,String dedupSummaryHash,String analysisSummaryHash,String coverageState,String disposition,long projectionRevision,Instant sealedAt){
        public Stored {
            Objects.requireNonNull(position);if(!Set.of("agent","skill","workflow","model").contains(category))throw invalid();WireValues.id(runId,false);WireValues.id(sourceId,true);Objects.requireNonNull(archiveRef);
            for(String hash:List.of(manifestHash,dedupSummaryHash,analysisSummaryHash))if(!hash.matches("[0-9a-f]{64}"))throw invalid();
            if(businessDate==null||collectionGeneration<1||collectionGeneration>CanonicalJson.MAX_SAFE_INTEGER||accepted<0||excluded<0||accepted>MemberUniverse.MAX_MEMBERS||excluded>MemberUniverse.MAX_MEMBERS||accepted+excluded>MemberUniverse.MAX_MEMBERS||!position.ownerId().equals(archiveRef.ownerId())||!position.resultHash().equals(archiveRef.resultHash())||!receiptId(position.ownerId()).equals(position.receiptId())||!Set.of("complete","partial").contains(coverageState)||!Set.of("applied","unchanged","superseded").contains(disposition)||projectionRevision<0||projectionRevision>CanonicalJson.MAX_SAFE_INTEGER||sealedAt==null||sealedAt.getNano()%1000!=0||sealedAt.isBefore(position.inputCutoff())||position.inputCutoff().isBefore(businessDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()))throw invalid();
        }
        public static Stored create(SourceRunAnalysisCommand command,FrozenCatalogSourceFinal.Reference ref,SourceRunAnalysisService.Outcome outcome,Instant sealedAt,String category){
            return new Stored(command.position(),category,command.runId(),command.sourceId(),command.businessDate(),command.collectionGeneration(),command.manifestHash(),command.accepted(),command.excluded(),ref,summaryHash(command.dedupSummary()),summaryHash(command.analysisSummary()),command.coverageState(),outcome.disposition(),outcome.revision(),sealedAt);
        }
        public String json(){
            var n=JsonMapper.shared().createObjectNode().put("schema_version","catalog-source-final-receipt-v1").put("category",category).put("run_id",runId).put("source_id",sourceId).put("business_date",businessDate.toString()).put("collection_generation",collectionGeneration).put("manifest_hash",manifestHash).put("accepted_count",accepted).put("excluded_count",excluded).put("dedup_summary_hash",dedupSummaryHash).put("analysis_summary_hash",analysisSummaryHash).put("coverage_state",coverageState).put("projection_disposition",disposition).put("projection_revision",projectionRevision).put("sealed_at",time(sealedAt));
            n.putObject("position").put("owner_job_id",position.ownerId()).put("logical_key",position.logicalKey()).put("generation",position.generation()).put("input_cutoff",time(position.inputCutoff())).put("result_hash",position.resultHash()).put("receipt_id",position.receiptId());n.set("archive_ref",CanonicalJson.parse(archiveRef.json()));String raw=JdbcFrozenInputs.canonical(n);if(raw.getBytes(StandardCharsets.UTF_8).length>32768)throw invalid();return raw;
        }
        static Stored parse(String raw){
            var n=CanonicalJson.parse(raw);var fields=Set.of("schema_version","position","category","run_id","source_id","business_date","collection_generation","manifest_hash","accepted_count","excluded_count","archive_ref","dedup_summary_hash","analysis_summary_hash","coverage_state","projection_disposition","projection_revision","sealed_at");WireValues.object(n,fields,fields);if(!"catalog-source-final-receipt-v1".equals(text(n,"schema_version")))throw invalid();
            var p=n.get("position");var names=Set.of("owner_job_id","logical_key","generation","input_cutoff","result_hash","receipt_id");WireValues.object(p,names,names);var position=new Position(text(p,"owner_job_id"),text(p,"logical_key"),number(p,"generation"),WireValues.utcMicroseconds(p.get("input_cutoff")),text(p,"result_hash"),text(p,"receipt_id"));
            return new Stored(position,text(n,"category"),text(n,"run_id"),text(n,"source_id"),WireValues.businessDate(n.get("business_date")),number(n,"collection_generation"),text(n,"manifest_hash"),number(n,"accepted_count"),number(n,"excluded_count"),FrozenCatalogSourceFinal.Reference.fromJson(JdbcFrozenInputs.canonical(n.get("archive_ref"))),text(n,"dedup_summary_hash"),text(n,"analysis_summary_hash"),text(n,"coverage_state"),text(n,"projection_disposition"),number(n,"projection_revision"),WireValues.utcMicroseconds(n.get("sealed_at")));
        }
        @Override public String toString(){return "StoredCatalogSourceFinal[redacted]";}
    }
    private final JdbcCatalogSourceFinalReceipts store;private final String category,expectedType;
    public CatalogSourceFinalReceiptService(JdbcCatalogSourceFinalReceipts store,String category){
        this.store=Objects.requireNonNull(store);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};expectedType=category+"_dedup";
    }
    public JdbcCatalogSourceFinalReceipts.Cursor begin(SourceRunAnalysisCommand command){
        try {JdbcFrozenInputs.requireTx();var cursor=store.lock(command.position().ownerId());var owner=store.owner(cursor.ownerId());validateOwner(owner,cursor.ownerId(),null);if(!owner.key().equals(command.position().logicalKey())||owner.generation()!=command.position().generation()||!owner.businessDate().equals(command.businessDate())||!Set.of("waiting_input","prepared","queued","running","no_input").contains(cursor.state()))throw invalid();return cursor;}catch(RuntimeException rejected){throw invalid();}
    }
    public void complete(JdbcCatalogSourceFinalReceipts.Cursor cursor,boolean noInput,Instant now){store.complete(cursor,noInput,now);}
    public Stored read(String owner){
        try {validateOwner(store.owner(owner),owner,null);var raw=store.read(owner);return raw==null?null:decode(raw,owner);}catch(RuntimeException rejected){throw invalid();}
    }
    public Stored append(Stored original){
        try {JdbcFrozenInputs.requireTx();Objects.requireNonNull(original);validateOwner(store.owner(original.position().ownerId()),original.position().ownerId(),original);String raw=original.json();store.append(original.position().ownerId(),raw,original.sealedAt());var actual=read(original.position().ownerId());if(actual==null||!raw.equals(actual.json()))throw invalid();return actual;}catch(RuntimeException rejected){throw invalid();}
    }
    public Position prior(String run,List<String> refs){
        try {
            JdbcFrozenInputs.requireTx();WireValues.id(run,false);Objects.requireNonNull(refs);for(String ref:refs)WireValues.id(ref,false);var remaining=new HashSet<>(refs);if(remaining.size()!=refs.size())throw invalid();Stored latest=null;
            for(var raw:store.refs(refs)){
                if(!remaining.remove(raw.id()))throw invalid();if(Set.of("generation_intent","logical_task").contains(raw.kind()))continue;
                var target=CanonicalJson.parse(raw.target());var fields=Set.of("kind","id");WireValues.object(target,fields,fields);String owner=WireValues.id(target.get("id"),false);var value=decode(raw,owner);
                if(!run.equals(value.runId())||"superseded".equals(value.disposition()))throw invalid();
                if(latest==null)latest=value;else {int order=value.position().inputCutoff().compareTo(latest.position().inputCutoff());if(order==0){if(!value.position().logicalKey().equals(latest.position().logicalKey()))throw invalid();order=Long.compare(value.position().generation(),latest.position().generation());if(order==0&&!value.position().equals(latest.position()))throw invalid();}if(order>0)latest=value;}
            }
            if(!remaining.isEmpty())throw invalid();return latest==null?null:latest.position();
        }catch(RuntimeException rejected){throw invalid();}
    }
    private Stored decode(JdbcCatalogSourceFinalReceipts.Raw raw,String owner){
        if(raw.targetBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()==null||raw.payloadBytes()<1||raw.payloadBytes()>32768)throw invalid();var target=CanonicalJson.parse(raw.target());var fields=Set.of("kind","id");WireValues.object(target,fields,fields);String payload=JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.payload()));
        if(!receiptId(owner).equals(raw.id())||!"disposition".equals(raw.kind())||!JdbcCatalogSourceFinalReceipts.TYPE.equals(raw.targetType())||!JdbcCatalogSourceFinalReceipts.ACTOR.equals(raw.actor())||!JdbcCatalogSourceFinalReceipts.REASON.equals(raw.reason())||!"generation_intent".equals(text(target,"kind"))||!owner.equals(text(target,"id"))||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target)))||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(payload))||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(action(owner))))throw invalid();
        var value=Stored.parse(payload);if(!category.equals(value.category())||!owner.equals(value.position().ownerId())||!value.sealedAt().equals(raw.actedAt())||!payload.equals(value.json()))throw invalid();validateOwner(store.owner(owner),owner,value);return value;
    }
    private void validateOwner(JdbcFrozenSourcePlans.Owner owner,String id,Stored original){
        if(!id.equals(owner.id())||!"generation_intent".equals(owner.kind())||!expectedType.equals(owner.type())||owner.key()==null||owner.key().isBlank()||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))||!id.equals(JdbcFrozenInputs.stableId("intent",owner.key()+":"+owner.generation())))throw invalid();
        if(original!=null&&(!category.equals(original.category())||!owner.key().equals(original.position().logicalKey())||owner.generation()!=original.position().generation()||!owner.businessDate().equals(original.businessDate())))throw invalid();
    }
    public static String receiptId(String owner){return JdbcCatalogSourceFinalReceipts.receiptId(owner);}
    public static String action(String owner){return JdbcCatalogSourceFinalReceipts.action(owner);}
    public static String summaryHash(String summary){return CanonicalJson.hash(CanonicalJson.Domain.CONTENT,JsonMapper.shared().valueToTree(Objects.requireNonNull(summary)));}
    private static String text(JsonNode n,String key){return WireValues.text(n.get(key));}
    private static long number(JsonNode n,String key){return WireValues.nonnegativeInteger(n.get(key));}
    private static String time(Instant value){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog source final receipt unavailable");}
}
