package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public reads over current database qualification. No response or eligibility cache. */
public final class PublishedContentFacade implements ContentService {
    public record Version(String kind,String id,String title,long revision,long bodyRevision,
                          Instant publishedAt,String inputOwner,String bodyHash,String bodyJson) { }
    public interface Projection {
        /** Resolve frozen public metadata in the caller's read transaction. Missing proof must fail.
         * Never return source payloads, prompts or current settings as historical metadata. */
        ContentView project(Version version);
    }
    private static final List<String> INTERPRETATIONS=List.of("agent","model","skill","workflow");
    private static final Set<String> TYPES=Set.of("daily","weekly","monthly");
    private final ContentReadService content;
    private final Projection projection;
    private final TransactionTemplate reads;

    public PublishedContentFacade(ContentReadService content,Projection projection,PlatformTransactionManager tx) {
        this.content=Objects.requireNonNull(content);this.projection=Objects.requireNonNull(projection);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public ContentView report(String id) {
        WireValues.id(id,false);
        return read(()->view(content.published("report",id).orElseThrow(PublishedContentFacade::missing)));
    }
    @Override public ContentView interpretation(String id) {
        WireValues.id(id,false);
        return read(()->{
            JdbcContentReads.Row found=null;
            for(String kind:INTERPRETATIONS) {
                var candidate=content.published(kind,id);
                if(candidate.isPresent()) {
                    if(found!=null)throw new IdentityService.Rejected(IdentityService.Refusal.IDENTITY_CONFLICT,null);
                    found=candidate.get();
                }
            }
            if(found==null)throw missing();return view(found);
        });
    }
    @Override public ContentPage reports(String type,long page,int size) {
        if(type!=null&&!TYPES.contains(type))throw new IllegalArgumentException("invalid report type");
        return page("report",type,page,size);
    }
    @Override public ContentPage interpretations(String kind,long page,int size) {
        if(kind!=null&&!INTERPRETATIONS.contains(kind))throw new IllegalArgumentException("invalid interpretation kind");
        return page(kind,null,page,size);
    }
    private ContentPage page(String kind,String type,long page,int size) {
        if(page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid content page");
        return read(()->{
            var batch=content.publishedMatchingPage(kind,
                    row -> (kind!=null||INTERPRETATIONS.contains(row.kind()))
                            && (type==null||type.equals(reportType(row))),page,size);
            if(batch.total()>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();
            // Projection failures are errors, never silently removed rows or misleading totals.
            return new ContentPage(batch.items().stream().map(this::view).toList(),page,size,batch.total());
        });
    }
    private static String reportType(JdbcContentReads.Row row) {
        var type=CanonicalJson.parse(row.bodyJson()).get("type");
        if(type==null||!type.isString()||!TYPES.contains(type.asString()))throw unavailable();
        return type.asString();
    }
    private ContentView view(JdbcContentReads.Row row) {
        String hash=HexFormat.of().formatHex(row.bodyHashV1());
        if(!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(row.bodyJson()))))throw unavailable();
        var version=new Version(row.kind(),row.id(),row.title(),row.revision(),row.bodyRevision(),
                row.publishedAt(),row.inputJobId(),hash,row.bodyJson());
        var value=projection.project(version);
        if(value==null||!row.kind().equals(value.kind())||!row.id().equals(value.id())
                ||!row.title().equals(value.title())||row.revision()!=value.revision()
                ||row.bodyRevision()!=value.bodyRevision()||!row.publishedAt().equals(value.publishedAt())
                ||!row.inputJobId().equals(value.inputSnapshotId())||!"published".equals(value.publicationState())
                ||value.reviewRequired()||value.blockingReasons()==null||!value.blockingReasons().isEmpty()
                ||value.coverage()==null||value.citations()==null||value.body()==null
                ||("report".equals(row.kind())!=(value.body() instanceof ReportBody))
                ||!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body(value.body()))))throw unavailable();
        if(value.body() instanceof InterpretationBody interpretation&&!row.kind().equals(interpretation.category()))throw unavailable();
        return value;
    }
    /** Exact public body serialization, independent of controller DTOs. */
    public static JsonNode body(ContentBody body) {
        var node=JsonMapper.shared().createObjectNode();List<Section> sections;List<String> citations;
        if(body instanceof ReportBody report) {
            node.put("title",report.title());node.put("type",report.type());
            node.put("period_start",time(report.periodStart()));node.put("period_end",time(report.periodEnd()));
            node.put("timezone",report.timezone());
            if(report.learningTask()==null)node.putNull("learning_task");else {
                var task=report.learningTask();var child=node.putObject("learning_task");
                child.put("package_id",task.packageId());child.put("object_kind",task.objectKind());
                child.put("object_id",task.objectId());child.put("title",task.title());child.put("reason",task.reason());
            }
            sections=report.sections();citations=report.citationIds();
        } else if(body instanceof InterpretationBody interpretation) {
            node.put("title",interpretation.title());node.put("category",interpretation.category());
            node.put("entity_id",interpretation.entityId());sections=interpretation.sections();citations=interpretation.citationIds();
        } else throw unavailable();
        var list=node.putArray("sections");for(var section:sections) {
            var child=list.addObject();child.put("heading",section.heading());child.put("text",section.text());ids(child,"citation_ids",section.citationIds());
        }
        ids(node,"citation_ids",citations);return node;
    }
    private static String time(Instant instant) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(instant);
    }
    private static void ids(ObjectNode node,String field,List<String> values) {var array=node.putArray(field);values.forEach(array::add);}
    private <T>T read(Supplier<T> operation) {
        try{return reads.execute(status->operation.get());}
        catch(IdentityService.Rejected refusal){throw refusal;}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static IdentityService.Rejected missing(){return new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null);}
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
    @Override public ContentView adminContent(Actor a,String k,String id){throw unavailable();}
    @Override public ContentView write(Actor a,String k,String id,ContentWrite c,String key,String csrf,String origin){throw unavailable();}
    @Override public ActionResult action(Actor a,String k,String id,ContentAction c,String key,String csrf,String origin){throw unavailable();}
    @Override public ActionResult evidenceAction(Actor a,String k,String id,EvidenceAction c,String key,String csrf,String origin){throw unavailable();}
    @Override public VersionPage referencedVersions(Actor a,String k,String id,long page,int size){throw unavailable();}
    @Override public ActionResult versionAction(Actor a,String k,String id,VersionAction c,String key,String csrf,String origin){throw unavailable();}
    @Override public Job generationIntent(Actor a,GenerationIntentWrite c,String key,String csrf,String origin){throw unavailable();}
}
