package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Owner-only version locators; no historical body or input payload crosses this boundary. */
public final class ContentVersionCatalog {
    private final JdbcValidityPropagation store;private final FrozenContentVersionService versions;
    private final VersionValidityLedgerService ledger;private final ContentReadService contents;private final TransactionTemplate reads;
    public ContentVersionCatalog(JdbcValidityPropagation store,FrozenContentVersionService versions,VersionValidityLedgerService ledger,ContentReadService contents,PlatformTransactionManager tx){
        this.store=Objects.requireNonNull(store);this.versions=Objects.requireNonNull(versions);this.ledger=Objects.requireNonNull(ledger);this.contents=Objects.requireNonNull(contents);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public ContentService.VersionPage list(Actor actor,String kind,String id,long page,int size){
        if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(id,false);if(!Set.of("report","agent","model","skill","workflow").contains(kind)||page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid version query");
        try{return reads.execute(status->{
            var current=contents.reviewSnapshot(kind,id).orElseThrow(()->new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null));
            if(current.bodyRevision()==null||current.bodyHashV1()==null||current.inputJobId()==null)throw unavailable();
            var keys=new TreeMap<Long,ContentVersionKey>();
            add(keys,new ContentVersionKey(kind,id,current.bodyRevision(),HexFormat.of().formatHex(current.bodyHashV1()),current.inputJobId()));
            String cursor="";
            while(true){
                var batch=store.versionCandidates(cursor,500);if(batch.isEmpty())break;
                for(var row:batch){
                    var original=CanonicalJson.parse(row.target());var payload=CanonicalJson.parse(row.payload());
                    if(!Arrays.equals(row.targetHash(),hash(original))||!Arrays.equals(row.payloadHash(),hash(payload)))throw unavailable();
                    JsonNode node=original;
                    if("result_application".equals(row.type())){
                        if(!"result-application-receipt-v1".equals(payload.path("schema_version").asText()))throw unavailable();
                        if(!"APPLIED".equals(payload.path("status").asText()))continue;
                        node=payload.get("content_version");if(node==null||node.isNull())continue;
                        if(!Objects.equals(node.path("input_job_id").asText(),original.path("root_id").asText()))throw unavailable();
                    }
                    if(!"content_version".equals(node.path("kind").asText()))throw unavailable();
                    var candidate=ContentVersionKey.fromJson(node);
                    if(kind.equals(candidate.contentKind())&&id.equals(candidate.contentId()))add(keys,candidate);
                }
                String next=batch.getLast().id();if(next.equals(cursor))throw unavailable();cursor=next;if(batch.size()<500)break;
            }
            long offset=(page-1)*size;var items=new ArrayList<ContentService.VersionSummary>();long index=0;
            for(var version:keys.values()){
                // Even off-page candidates must have proof; otherwise total would be misleading.
                if(versions.find(version).isEmpty())throw unavailable();
                var state=ledger.validity(FrozenReferenceClosure.version(version));
                if(index++>=offset&&items.size()<size)items.add(new ContentService.VersionSummary(new ContentService.ContentVersionKey("content_version",kind,id,version.revision(),version.bodyHash(),version.inputJobId()),"body",state.revision(),state.effectiveValid()));
            }
            return new ContentService.VersionPage(List.copyOf(items),page,size,keys.size());
        });}catch(IdentityService.Rejected refusal){throw refusal;}catch(RuntimeException failure){throw unavailable();}
    }
    private static void add(TreeMap<Long,ContentVersionKey> keys,ContentVersionKey version){
        var old=keys.putIfAbsent(version.revision(),version);if(old!=null&&!old.equals(version)||keys.size()>10000||version.revision()<1||version.inputJobId()==null)throw unavailable();
    }
    private static byte[] hash(JsonNode value){return HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(value)));}
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
