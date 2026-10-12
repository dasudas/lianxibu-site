package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.CatalogAnalysisContentBaselineService;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual original day/INPUT authority for dispatch; current validity is checked only under the caller's writer. */
public final class CatalogDayAnalysisDispatchPermitService {
    public static final class Captured {
        private final Object issuer;private final CatalogAnalysisPreparedInputService.Prepared prepared;
        private final CatalogDayAnalysisCommitPermitService.Captured day;private final JdbcCatalogVersionJobFacts.Snapshot previous;
        private Captured(Object issuer,CatalogAnalysisPreparedInputService.Prepared prepared,CatalogDayAnalysisCommitPermitService.Captured day,JdbcCatalogVersionJobFacts.Snapshot previous){this.issuer=issuer;this.prepared=prepared;this.day=day;this.previous=previous;}
        public String rootId(){return prepared.input().ownerId();}public String inputHash(){return prepared.input().inputHash();}public String requestJson(){return prepared.input().requestJson();}
        public boolean matches(ResultApplicationService.Input input){if(input==null||!rootId().equals(input.rootId())||!inputHash().equals(input.inputHash()))return false;var context=CatalogAnalysisInputProfile.complete(CanonicalJson.parse(prepared.input().snapshotJson())).command().applicationContext();return input.equals(context.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision()));}
        @Override public String toString(){return "OriginalCatalogAnalysisDispatchPermit[redacted]";}
    }
    private final Object issuer=new Object();private final String category;
    private final CatalogAnalysisPreparedInputService prepared;private final CatalogDayAnalysisCommitPermitService originals;
    private final JdbcDispatchInputs roots;private final JdbcFrozenInputs inputs;private final JdbcCatalogAnalysisDispatchProofLocks locks;
    private final CatalogEntityAnalysisHeaderService headers;private final EntityInputEvidenceService members;
    private final CatalogAnalysisContentBaselineService contents;private final CatalogPreviousAnalysisService previous;
    private final JdbcCatalogVersionJobFacts previousJobs;private final TransactionTemplate reads;
    public CatalogDayAnalysisDispatchPermitService(DataSource source,CatalogAnalysisPreparedInputService prepared,
            CatalogDayAnalysisCommitPermitService originals,CatalogEntityAnalysisHeaderService headers,EntityInputEvidenceService members,
            CatalogAnalysisContentBaselineService contents,CatalogPreviousAnalysisService previous,JdbcCatalogVersionJobFacts previousJobs,
            PlatformTransactionManager manager,String category){
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        Objects.requireNonNull(source);this.prepared=Objects.requireNonNull(prepared);this.originals=Objects.requireNonNull(originals);
        this.headers=Objects.requireNonNull(headers);this.members=Objects.requireNonNull(members);this.contents=Objects.requireNonNull(contents);
        this.previous=Objects.requireNonNull(previous);this.previousJobs=Objects.requireNonNull(previousJobs);
        roots=new JdbcDispatchInputs(source);inputs=new JdbcFrozenInputs(source);locks=new JdbcCatalogAnalysisDispatchProofLocks(source);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(String root){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();WireValues.id(root,false);
        var input=Objects.requireNonNull(reads.execute(s->readRoot(root,false)));
        var day=originals.captureOriginal(input.reservation().day());if(day==null)throw invalid();
        return Objects.requireNonNull(reads.execute(s->{
            var fresh=readRoot(root,false);requireSame(input,fresh);bind(fresh,day.evidence());
            JdbcCatalogVersionJobFacts.Snapshot old=null;var baseline=profile(fresh).get("native_content_baseline");
            if(!baseline.isNull()){
                String target=CatalogAnalysisInputProfile.complete(CanonicalJson.parse(fresh.input().snapshotJson())).command().applicationContext().contentId();
                var proven=previous.readInSnapshot(category,fresh.reservation().entityId(),target).orElseThrow(CatalogDayAnalysisDispatchPermitService::invalid);
                if(!text(baseline,"input_root").equals(proven.rootId())||!text(baseline,"body_hash").equals(proven.contentBodyHash())||number(baseline,"body_revision")!=proven.contentBodyRevision())throw invalid();
                old=previousJobs.capture(proven.rootId(),proven.claimTargetHash());
            }
            return new Captured(issuer,fresh,day,old);
        }));
    }
    /** Keeps all original proofs and current entity/member/content rows locked until authorization commits. */
    public boolean verify(Captured captured){return verifyCurrent(captured,false);}
    /** Result application permits an explicitly confirmed ordinary review revision advance; original body/root stay fixed. */
    public boolean verifyForApplication(Captured captured,boolean manual){return verifyCurrent(captured,manual);}
    private boolean verifyCurrent(Captured captured,boolean manual){
        JdbcFrozenInputs.requireTx();if(captured==null||captured.issuer!=issuer||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
        var receipt=captured.prepared.reservation();locks.lock(captured.rootId(),receipt.intentId());
        var fresh=readRoot(captured.rootId(),true);requireSame(captured.prepared,fresh);
        var day=originals.verify(captured.day);bind(fresh,day);
        if(captured.previous!=null&&!captured.previous.equals(previousJobs.lockAndRead(captured.previous.root(),captured.previous.claimTargetHash())))throw invalid();
        var header=headers.lockInWriter(Set.of(receipt.entityId())).get(receipt.entityId());
        if(header==null||header.generation()!=receipt.generation()||!"queued".equals(header.state())||header.revision()<receipt.entityRevisionAfter()||!header.identityHash().equals(text(CanonicalJson.parse(receipt.json()),"entity_identity_hash")))return false;
        var group=day.entities().stream().filter(e->receipt.entityId().equals(e.entityId())).findFirst().orElseThrow(CatalogDayAnalysisDispatchPermitService::invalid);
        var expected=new TreeMap<String,CatalogDayEntityEvidenceService.Evidence>();for(var member:group.members())if(expected.put(member.member().itemId(),member)!=null)throw invalid();
        var current=members.read(receipt.entityId(),category,expected.keySet(),day.original().cutoff());
        if(current.entityRevision()!=header.revision()||current.members().size()!=expected.size())return false;
        for(var member:current.members()){var original=expected.remove(member.id());if(original==null||member.revision()!=original.member().revision()||!member.category().equals(category)||!member.identityKey().equals(original.identityKey())||!member.title().equals(original.title())||!Objects.equals(member.summary(),original.summary())||!member.payloadJson().equals(original.bodyJson()))return false;}
        if(!expected.isEmpty())return false;
        var context=CatalogAnalysisInputProfile.complete(CanonicalJson.parse(fresh.input().snapshotJson())).command().applicationContext();
        var baseline=contents.lockInWriter(receipt.entityId(),context.contentId());var original=profile(fresh).get("native_content_baseline");
        if(original.isNull())return baseline==null;
        return baseline!=null&&baseline.id().equals(text(original,"content_id"))&&(manual?baseline.revision()>=number(original,"revision"):baseline.revision()==number(original,"revision"))&&baseline.bodyRevision()==number(original,"body_revision")&&baseline.bodyHash().equals(text(original,"body_hash"))&&baseline.inputRoot().equals(text(original,"input_root"));
    }
    private CatalogAnalysisPreparedInputService.Prepared readRoot(String id,boolean writer){
        var root=roots.read(id);if(root==null||!id.equals(root.id())||!Objects.equals(category+"_analysis",root.type())||root.inputHash()==null||root.inputHash().length!=32||root.cutoff()==null||root.businessDate()==null||root.stagePart()!=0)throw invalid();
        String hash=HexFormat.of().formatHex(root.inputHash());var snapshot=CanonicalJson.parse(inputs.read(id,hash));var base=CatalogAnalysisInputProfile.complete(snapshot);var context=base.command().applicationContext();
        var value=writer?prepared.lockInWriter(root.businessDate(),context.input(id,"catalog-dispatch",hash,root.revision()).targetEntityId()):prepared.read(root.businessDate(),context.input(id,"catalog-dispatch",hash,root.revision()).targetEntityId());
        if(value==null||!id.equals(value.input().ownerId())||!hash.equals(value.input().inputHash())||!root.cutoff().equals(value.input().cutoff())||!root.logicalKey().equals(value.reservation().logicalKey())||root.generation()!=value.reservation().generation()||!value.input().snapshotJson().equals(JdbcFrozenInputs.canonical(snapshot)))throw invalid();return value;
    }
    private void bind(CatalogAnalysisPreparedInputService.Prepared input,CatalogDayEntityEvidenceService.Captured day){
        var receipt=input.reservation();var snapshot=CanonicalJson.parse(input.input().snapshotJson());var profile=snapshot.get(CatalogAnalysisInputProfile.FIELD);var original=day.original();var closure=original.closed().closure();
        if(!category.equals(original.category())||!receipt.day().equals(original.day())||!receipt.closureHash().equals(original.closureHash())||!receipt.groupingHash().equals(day.grouping().hash())||!input.input().cutoff().equals(original.cutoff())||!JdbcFrozenInputs.canonical(profile.get("cohort")).equals(closure.cohort().json())||!JdbcFrozenInputs.canonical(profile.get("closure")).equals(closure.json())||!text(profile.get("directory_ref"),"directory_owner_id").equals(original.closed().original().plan().directory().ownerId())||!text(profile.get("directory_ref"),"bundle_hash").equals(original.closed().original().plan().bundleHash()))throw invalid();
        var proofs=JsonMapper.shared().createArrayNode();for(var source:original.sources().stream().sorted(Comparator.comparing(s->s.receipt().sourceId())).toList()){
            var result=CanonicalJson.parse(source.result().json());var entry=closure.entries().stream().filter(e->e.sourceId().equals(source.receipt().sourceId())).findFirst().orElseThrow();
            var proof=proofs.addObject().put("source_id",source.receipt().sourceId()).put("run_id",source.receipt().runId()).put("final_result_hash",source.result().hash()).put("available_at",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(entry.progress().availableAt())).put("coverage_state",text(result,"coverage_state"));proof.set("stage_plan_ref",result.get("plan_ref").deepCopy());var fragments=proof.putArray("fragments");
            for(var fragment:result.get("stage_result").get("fragments"))fragments.addObject().put("root_id",text(fragment,"root_id")).put("stage_part",number(fragment,"stage_part")).put("input_hash",text(fragment,"input_hash")).put("result_hash",text(fragment,"result_hash"));
        }
        if(!Arrays.equals(CanonicalJson.bytes(proofs),CanonicalJson.bytes(profile.get("source_proofs"))))throw invalid();
        var group=day.entities().stream().filter(e->receipt.entityId().equals(e.entityId())).findFirst().orElseThrow(CatalogDayAnalysisDispatchPermitService::invalid);
        var body=new TreeMap<String,JsonNode>();for(var member:snapshot.get("members"))if(body.put(text(member,"id"),member)!=null)throw invalid();
        var anchors=new ArrayList<CatalogDayAnalysisReservationLedger.Member>();for(var evidence:group.members().stream().sorted(Comparator.comparing(m->m.member().itemId())).toList()){
            var member=evidence.member();anchors.add(new CatalogDayAnalysisReservationLedger.Member(member.itemId(),member.runId(),member.sourceId(),member.rootId(),evidence.inputHash(),evidence.bodyHash(),member.fragmentResultHash(),member.finalResultHash(),member.revision()));
            var frozen=body.remove(member.itemId());if(frozen==null||!text(frozen,"body_hash").equals(evidence.bodyHash())||!JdbcFrozenInputs.canonical(frozen.get("body")).equals(evidence.bodyJson())||!JdbcFrozenInputs.canonical(frozen.get("references")).equals(evidence.referencesJson())||!WireValues.utcMicroseconds(frozen.get("completed_at")).equals(evidence.completedAt()))throw invalid();
        }
        if(!body.isEmpty()||!anchors.equals(receipt.members()))throw invalid();
    }
    private static void requireSame(CatalogAnalysisPreparedInputService.Prepared a,CatalogAnalysisPreparedInputService.Prepared b){if(a==null||b==null||!a.input().equals(b.input())||!a.reservation().json().equals(b.reservation().json()))throw invalid();}
    private static JsonNode profile(CatalogAnalysisPreparedInputService.Prepared input){return CanonicalJson.parse(input.input().snapshotJson()).get(CatalogAnalysisInputProfile.FIELD);}
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}private static long number(JsonNode n,String field){return WireValues.nonnegativeInteger(n.get(field));}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day analysis dispatch permission unavailable");}
}
