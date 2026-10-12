package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EvidenceValidityService;
import com.lianxibu.site.service.jobs.VersionValidityService.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact immutable body proof + permanent closed disposition chain, never a guessed zero. */
public final class VersionValidityLedgerService implements ReviewPublicationJdbcStore.ValidityProof {
    private record Parsed(JdbcVersionValidity.Event row,JsonNode target,long revision,Action action,String reverse) { }
    private static final Set<String> FIELDS=Set.of("schema_version","action","validity_revision","invalidation_id");
    private final JdbcVersionValidity store;
    private final FrozenContentVersionService versions;
    private final EvidenceValidityService evidence;
    private final com.lianxibu.site.service.content.ContentReviewWriteService contents;
    private final Clock clock;
    private final String actor;
    public VersionValidityLedgerService(JdbcVersionValidity store,FrozenContentVersionService versions,EvidenceValidityService evidence,com.lianxibu.site.service.content.ContentReviewWriteService contents,Clock clock,String actor){this.store=Objects.requireNonNull(store);this.versions=Objects.requireNonNull(versions);this.evidence=Objects.requireNonNull(evidence);this.contents=Objects.requireNonNull(contents);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);}
    @Override public boolean exactVersionExists(JsonNode target){canonical(target);return isContent(target)?versions.find(ContentVersionKey.fromJson(target)).isPresent():evidence.read(target).isPresent();}
    @Override public void lockStableIdentity(JsonNode target){canonical(target);JdbcFrozenInputs.requireTx();if(isContent(target)){var key=ContentVersionKey.fromJson(target);if(contents.reviewSnapshot(key.contentKind(),key.contentId())==null)throw unavailable();}else evidence.lock(target);}
    @Override public Validity validity(JsonNode target){
        String full=canonical(target);if(!exactVersionExists(target))throw new Rejected("unknown_version");
        var events=new TreeMap<Long,Parsed>();var ids=new HashSet<String>();Instant after=null;String afterId="";
        while(true){var batch=store.scan(type(target),hash(full),after,afterId,500);if(batch.isEmpty())break;
            for(var row:batch){var parsed=parse(row);if(!full.equals(canonical(parsed.target()))||!ids.add(row.id())||events.putIfAbsent(parsed.revision(),parsed)!=null)throw unavailable();}
            var last=batch.getLast();after=last.createdAt();afterId=last.id();if(batch.size()<500)break;
        }
        var active=new HashSet<String>();long revision=0;
        for(var event:events.values()){if(event.revision()!=++revision)throw unavailable();if(event.action()==Action.INVALIDATE)active.add(event.row().id());else if(!active.remove(event.reverse()))throw unavailable();}
        var result=new Validity(revision,active.isEmpty(),Set.copyOf(active));
        if(!isContent(target)){var physical=evidence.read(target).orElseThrow(VersionValidityLedgerService::unavailable);if(physical.revision()!=revision||physical.valid()!=result.effectiveValid())throw unavailable();}
        return result;
    }
    public Result committed(String key,String expectedHash){
        var row=store.action(key);if(row==null)return null;var event=parse(row);if(!actor.equals(row.actor()))throw new Rejected("command_identity_conflict");
        var command=new Command(event.target(),event.revision()-1,event.action(),row.reason(),event.reverse());
        if(!expectedHash.equals(VersionValidityService.commandHash(command,canonical(event.target()))))throw new Rejected("command_identity_conflict");
        // The immutable chain at and beyond this event must remain complete; newer actions do not change this receipt.
        validity(event.target());
        var active=new HashSet<String>();long revision=0;Instant after=null;String afterId="";var chain=new TreeMap<Long,Parsed>();
        while(true){var batch=store.scan(type(event.target()),hash(canonical(event.target())),after,afterId,500);for(var item:batch){var parsed=parse(item);if(parsed.revision()<=event.revision()&&chain.putIfAbsent(parsed.revision(),parsed)!=null)throw unavailable();}if(batch.size()<500)break;var last=batch.getLast();after=last.createdAt();afterId=last.id();}
        for(var item:chain.values()){if(item.revision()!=++revision)throw unavailable();if(item.action()==Action.INVALIDATE)active.add(item.row().id());else if(!active.remove(item.reverse()))throw unavailable();}
        if(revision!=event.revision())throw unavailable();return new Result(row.id(),canonical(event.target()),revision,active.isEmpty(),true);
    }
    public String append(Command command,String commandHash,long next,String actionKey){
        JdbcFrozenInputs.requireTx();String full=canonical(command.target());if(!Objects.equals(commandHash,VersionValidityService.commandHash(command,full)))throw unavailable();
        var current=validity(command.target());if(current.revision()!=command.expectedValidityRevision()||next!=current.revision()+1||next>CanonicalJson.MAX_SAFE_INTEGER)throw new Rejected("validity_revision_conflict");
        if(command.action()==Action.REVERSE_INVALIDATION&&!current.activeDispositionIds().contains(command.reversesDispositionId()))throw new Rejected("invalidation_not_active");
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","content-validity-v1");payload.put("action",command.action()==Action.INVALIDATE?"invalidate_conclusion":"reverse_invalidation");payload.put("validity_revision",next);payload.put("invalidation_id",command.reversesDispositionId());
        String id=JdbcFrozenInputs.stableId("validity",actionKey);store.append(new JdbcJobRecords.Disposition(id,actionKey,type(command.target()),full,actor,command.reason(),JdbcFrozenInputs.canonical(payload)),next,command.reversesDispositionId(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        if(!isContent(command.target()))evidence.update(command.target(),current.revision(),next,command.action()==Action.REVERSE_INVALIDATION&&current.activeDispositionIds().size()==1);
        return id;
    }
    private static Parsed parse(JdbcVersionValidity.Event row){
        try{WireValues.id(row.id(),false);WireValues.id(row.actor(),false);if(row.reason()==null||row.reason().isBlank()||row.reason().codePointCount(0,row.reason().length())>2048||row.createdAt()==null)throw unavailable();
            var target=CanonicalJson.parse(row.targetJson());String full=canonical(target);if(!type(target).equals(row.targetType())||!Arrays.equals(hash(full),row.targetHash()))throw unavailable();
            var payload=CanonicalJson.parse(row.payloadJson());WireValues.object(payload,FIELDS,FIELDS);if(!Arrays.equals(hash(JdbcFrozenInputs.canonical(payload)),row.payloadHash())||!payload.path("schema_version").asText().equals("content-validity-v1"))throw unavailable();
            long revision=WireValues.nonnegativeInteger(payload.get("validity_revision"));if(revision<1||row.revision()==null||row.revision()!=revision)throw unavailable();
            Action action=switch(WireValues.text(payload.get("action"))){case "invalidate_conclusion"->Action.INVALIDATE;case "reverse_invalidation"->Action.REVERSE_INVALIDATION;default->throw unavailable();};
            String reverse=payload.get("invalidation_id").isNull()?null:WireValues.id(payload.get("invalidation_id"),false);if(action==Action.INVALIDATE?reverse!=null:reverse==null)throw unavailable();if(!Objects.equals(reverse,row.reversesId()))throw unavailable();
            return new Parsed(row,target,revision,action,reverse);
        }catch(IllegalArgumentException error){throw unavailable();}
    }
    static String canonical(JsonNode target){return VersionValidityService.canonicalTarget(target);}
    private static boolean isContent(JsonNode target){return type(target).equals("content_version");}
    private static String type(JsonNode target){return WireValues.text(target.get("kind"));}
    private static byte[] hash(String text){return HexFormat.of().parseHex(JdbcResultState.sha(text));}
    private static IllegalStateException unavailable(){return new IllegalStateException("exact version ledger proof unavailable");}
}
