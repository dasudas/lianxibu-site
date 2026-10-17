package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.JsonNode;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Archives the complete frozen plan. Trusted qualification and scheduling are separate. */
public final class FrozenSourceStagePlanService {
    private final JdbcFrozenSourcePlans storage;private final FrozenSourceUniverseService universes;private final Clock clock;private final String actor;
    public FrozenSourceStagePlanService(JdbcFrozenSourcePlans storage,FrozenSourceUniverseService universes,Clock clock,String actor) {
        this.storage=Objects.requireNonNull(storage);this.universes=Objects.requireNonNull(universes);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);
    }
    public FrozenSourceStagePlan.Reference freeze(SourcePartitionPlanner.Plan plan) {
        return freezeInternal(plan,null);
    }
    /** Only this complete pure validation can create the opaque write capability. */
    public static ValidatedPlan prevalidate(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe) {
        return new ValidatedPlan(Objects.requireNonNull(plan),Objects.requireNonNull(universe),FrozenSourceStagePlan.encode(plan,universe));
    }
    public static final class ValidatedPlan {
        private final SourcePartitionPlanner.Plan plan;
        private final MemberUniverse.Universe universe;
        private final FrozenSourceStagePlan.Encoded encoded;
        private ValidatedPlan(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,FrozenSourceStagePlan.Encoded encoded){this.plan=plan;this.universe=universe;this.encoded=encoded;}
        public SourcePartitionPlanner.Plan plan(){return plan;}
        public FrozenSourceStagePlan.Reference reference(){return encoded.reference();}
        @Override public String toString(){return "ValidatedSourcePlan[redacted]";}
    }
    /** Rechecks actual archived membership and every stored byte in the original short writer. */
    public FrozenSourceStagePlan.Reference freezeValidated(ValidatedPlan validated) {
        if(validated==null)throw unavailable();return freezeInternal(validated.plan,validated);
    }
    private FrozenSourceStagePlan.Reference freezeInternal(SourcePartitionPlanner.Plan plan,ValidatedPlan validated) {
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(plan);String ownerId=plan.reference().ownerId();var owner=storage.lockOwner(ownerId);validateOwner(owner);
            var original=universes.read(plan.reference());matches(owner,plan);
            if(validated==null)validated=prevalidate(plan,original);else if(!original.equals(validated.universe))throw unavailable();
            var encoded=validated.encoded;
            var receipt=JsonMapper.shared().createObjectNode();receipt.put("schema_version","source-stage-plan-receipt-v1");receipt.put("owner_job_id",ownerId);
            receipt.put("logical_key",plan.logicalKey());receipt.put("generation",plan.generation());receipt.put("stage",plan.stage().name().toLowerCase(Locale.ROOT));receipt.put("business_date",owner.businessDate().toString());
            receipt.set("universe_ref",CanonicalJson.parse(plan.reference().json()));receipt.set("plan_ref",CanonicalJson.parse(encoded.reference().json()));
            String receiptJson=JdbcFrozenInputs.canonical(receipt);
            var previous=storage.receipt(ownerId);
            if(previous!=null) {
                if(!encoded.reference().equals(reference(previous)))throw unavailable();verifyWritten(encoded,receiptJson);return encoded.reference();
            }
            if(!storage.parts(ownerId).isEmpty()||storage.hasRoots(ownerId)||!"waiting_input".equals(owner.state()))throw unavailable();
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);if(plan.scope().cutoff().isAfter(now))throw unavailable();
            for(int n=0;n<encoded.payloads().size();n++)storage.put(ownerId,n,encoded.payloads().get(n),now);
            storage.append(ownerId,actor,receiptJson,now);
            verifyWritten(encoded,receiptJson);return encoded.reference();
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }
    public SourcePartitionPlanner.Plan read(String ownerId){return readOriginal(ownerId).plan();}
    /** Original plan and universe proved together in this caller's writer; never cached. */
    OriginalPlan readOriginal(String ownerId){return readOriginal(ownerId,true);}
    /** Complete non-locking proof in any active transaction; only readonly RR may reuse it. */
    OriginalPlan readImmutableOriginal(String ownerId){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        WireValues.id(ownerId,false);var frame=snapshotFrame();
        if(frame!=null&&frame.values.containsKey(ownerId))return frame.values.get(ownerId);
        var original=readOriginal(ownerId,false);if(frame!=null)frame.remember(ownerId,original);return original;
    }
    /** Same RR snapshot only. Synchronizations are suspended separately by REQUIRES_NEW. */
    private ReadSnapshot snapshotFrame(){
        if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!TransactionSynchronizationManager.isSynchronizationActive()
                ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))return null;
        for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
            if(synchronization instanceof ReadSnapshot frame&&frame.issuer==this)return frame;
        var frame=new ReadSnapshot(this);TransactionSynchronizationManager.registerSynchronization(frame);return frame;
    }
    private static final class ReadSnapshot implements TransactionSynchronization{
        private static final long MAX_BYTES=1024*1024;
        private final FrozenSourceStagePlanService issuer;
        private final Map<String,OriginalPlan> values=new LinkedHashMap<>();
        private long bytes;
        private ReadSnapshot(FrozenSourceStagePlanService issuer){this.issuer=issuer;}
        private void remember(String id,OriginalPlan original){
            long size=65536L+original.universe.members().size()*768L+original.universe.parts().size()*512L+original.plan.decisions().size()*256L+original.blocks.size()*1024L;
            size+=textBytes(original.universe.runId())+textBytes(original.universe.sourceId())+textBytes(original.universe.manifestHash())+textBytes(original.plan.logicalKey())+textBytes(original.plan.scope().stageContextJson());
            for(var member:original.universe.members())size+=textBytes(member.itemId())+textBytes(member.memberId())+textBytes(member.sourceItemId())+textBytes(member.observationId())+textBytes(member.payloadHash())+textBytes(member.replacesObservationId());
            var receipt=original.receipt;size+=textBytes(receipt.id())+textBytes(receipt.actionKey())+textBytes(receipt.targetType())+textBytes(receipt.targetKeyJson())+textBytes(receipt.operatorId())+textBytes(receipt.reason())+textBytes(receipt.payloadJson());
            for(var chunk:original.plan.chunks())size+=512L+chunk.itemIds().size()*256L+textBytes(chunk.rootId())+textBytes(chunk.snapshotJson())+textBytes(chunk.requestJson());
            if(size>MAX_BYTES||values.size()>=8||bytes+size>MAX_BYTES)return;
            values.put(id,original);bytes+=size;
        }
        private static long textBytes(String value){return value==null?0:64L+2L*value.length();}
        @Override public void afterCompletion(int status){values.clear();bytes=0;}
    }
    static final class OriginalPlan {
        private final FrozenSourceStagePlanService issuer;
        private final SourcePartitionPlanner.Plan plan;
        private final MemberUniverse.Universe universe;
        private final List<NativeBlock> blocks;
        private final JdbcJobRecords.Disposition receipt;
        private OriginalPlan(FrozenSourceStagePlanService issuer,SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,List<JdbcFrozenSourceUniverses.Part> parts,JdbcJobRecords.Disposition receipt){
            this.issuer=issuer;this.plan=plan;this.universe=universe;this.blocks=parts.stream().map(NativeBlock::capture).toList();this.receipt=receipt;
        }
        SourcePartitionPlanner.Plan plan(){return plan;}
        MemberUniverse.Universe universe(){return universe;}
        @Override public String toString(){return "OriginalSourcePlan[redacted]";}
    }

    /** The encoder already validated the whole original plan. Close the write against those
     * exact canonical bytes without reconstructing every original provider request again. */
    private void verifyWritten(FrozenSourceStagePlan.Encoded encoded,String expectedReceipt) {
        String owner=encoded.reference().ownerId(),stamp=JdbcFrozenSourcePlans.stamp(owner);
        var parts=storage.parts(owner);if(parts.size()!=encoded.payloads().size())throw unavailable();
        for(int n=0;n<parts.size();n++) {
            var part=parts.get(n);var body=CanonicalJson.parse(part.payload());
            if(!owner.equals(part.owner())||!"input".equals(part.purpose())||!stamp.equals(part.stamp())||part.number()!=n
                    ||!JdbcFrozenSourceUniverses.partId(owner,stamp,n).equals(part.id())
                    ||!encoded.payloads().get(n).equals(JdbcFrozenInputs.canonical(body))
                    ||!Arrays.equals(part.hash(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body))))throw unavailable();
        }
        var receipt=storage.receipt(owner);
        if(receipt==null||!"source_stage_plan".equals(receipt.targetType())||!actor.equals(receipt.operatorId())
                ||!"Frozen complete source stage plan".equals(receipt.reason())
                ||!expectedReceipt.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(receipt.payloadJson()))))throw unavailable();
        var target=CanonicalJson.parse(receipt.targetKeyJson());closed(target,"kind","id");
        if(!"generation_intent".equals(text(target,"kind"))||!owner.equals(text(target,"id")))throw unavailable();
    }
    /** Private fingerprints of actual stored bytes after complete native decoding, never wire hashes. */
    private record NativeBlock(String id,String owner,String purpose,String stamp,long number,String storedHash,String byteHash){
        private static NativeBlock capture(JdbcFrozenSourceUniverses.Part part){
            if(part==null||part.payload()==null||part.hash()==null||part.hash().length!=32)throw unavailable();
            try{return new NativeBlock(part.id(),part.owner(),part.purpose(),part.stamp(),part.number(),HexFormat.of().formatHex(part.hash()),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(part.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
            catch(java.security.NoSuchAlgorithmException impossible){throw unavailable();}
        }
    }
    /** Close this service's complete actual capture against fresh original native rows and locks. */
    OriginalPlan verifyCapturedOriginal(OriginalPlan captured){return verifyCapturedOriginal(captured,true);}
    /** Each lookup reads real native rows again; it neither rebuilds requests nor grants current qualification. */
    OriginalPlan verifyCapturedImmutableOriginal(OriginalPlan captured){return verifyCapturedOriginal(captured,false);}
    private OriginalPlan verifyCapturedOriginal(OriginalPlan captured,boolean writer){
        try{
            if(captured==null||captured.issuer!=this)throw unavailable();
            String ownerId=captured.plan.reference().ownerId();
            var owner=writer?storage.lockOwner(ownerId):storage.immutableOwner(ownerId);validateOwner(owner);matches(owner,captured.plan);
            var receipt=writer?storage.receipt(ownerId):storage.immutableReceipt(ownerId);
            if(!captured.receipt.equals(receipt))throw unavailable();
            var actualUniverse=writer?universes.read(captured.plan.reference()):universes.readImmutable(captured.plan.reference());
            if(!captured.universe.equals(actualUniverse))throw unavailable();
            var parts=writer?storage.parts(ownerId):storage.immutableParts(ownerId);
            if(parts.size()!=captured.blocks.size())throw unavailable();
            for(int n=0;n<parts.size();n++)if(!captured.blocks.get(n).equals(NativeBlock.capture(parts.get(n))))throw unavailable();
            return captured;
        }catch(RuntimeException rejected){throw unavailable();}
    }

    /** Original committed archive only; never reads current collection or qualification. */
    public SourcePartitionPlanner.Plan readImmutable(String ownerId){return readImmutableOriginal(ownerId).plan();}
    /** Null is an unprepared original plan, never a successful empty plan. */
    public FrozenSourceStagePlan.Reference findImmutableReference(String ownerId,String logicalKey,long generation,GenerationFlowService.Stage stage,LocalDate day) {
        JdbcSourceDedupResults.requireReadOnly();WireValues.id(ownerId,false);
        if(logicalKey==null||logicalKey.isBlank()||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER
                ||stage==null||!(stage.dedup()||stage==GenerationFlowService.Stage.NEWS_VALUE)||day==null)throw unavailable();
        var owner=storage.findImmutableOwner(ownerId,logicalKey,generation);var receipt=storage.immutableReceipt(ownerId);
        if(owner==null||receipt==null){
            if(receipt!=null||!storage.immutableParts(ownerId).isEmpty()||storage.immutableHasRoots(ownerId))throw unavailable();
            if(owner==null)return null;
        }
        validateOwner(owner);
        if(!ownerId.equals(owner.id())||!logicalKey.equals(owner.key())||generation!=owner.generation()
                ||!stage.name().toLowerCase(Locale.ROOT).equals(owner.type())||!day.equals(owner.businessDate()))throw unavailable();
        if(receipt==null){if(!"waiting_input".equals(owner.state()))throw unavailable();return null;}
        var reference=reference(receipt);readImmutable(reference);return reference;
    }
    private OriginalPlan readOriginal(String ownerId,boolean writer) {
        try {
            WireValues.id(ownerId,false);var owner=writer?storage.lockOwner(ownerId):storage.immutableOwner(ownerId);validateOwner(owner);
            var receipt=writer?storage.receipt(ownerId):storage.immutableReceipt(ownerId);if(receipt==null||!"source_stage_plan".equals(receipt.targetType()))throw unavailable();WireValues.id(receipt.operatorId(),false);
            var target=CanonicalJson.parse(receipt.targetKeyJson());closed(target,"kind","id");
            if(!"generation_intent".equals(text(target,"kind"))||!ownerId.equals(text(target,"id")))throw unavailable();
            var payload=payload(receipt);var reference=reference(receipt);var universe=FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(payload.get("universe_ref")));
            if(!ownerId.equals(text(payload,"owner_job_id"))||!ownerId.equals(reference.ownerId())||!ownerId.equals(universe.ownerId())
                    ||!owner.key().equals(text(payload,"logical_key"))||owner.generation()!=WireValues.nonnegativeInteger(payload.get("generation"))
                    ||!owner.type().equals(text(payload,"stage"))||!owner.businessDate().toString().equals(text(payload,"business_date")))throw unavailable();
            var original=writer?universes.read(universe):universes.readImmutable(universe);var parts=writer?storage.parts(ownerId):storage.immutableParts(ownerId);if(parts.size()<2||parts.size()>FrozenSourceStagePlan.MAX_CHUNKS+1)throw unavailable();
            String stamp=JdbcFrozenSourcePlans.stamp(ownerId);var values=new ArrayList<String>();
            for(int n=0;n<parts.size();n++) {
                var part=parts.get(n);var body=CanonicalJson.parse(part.payload());
                if(!ownerId.equals(part.owner())||!"input".equals(part.purpose())||!stamp.equals(part.stamp())||part.number()!=n
                        ||!JdbcFrozenSourceUniverses.partId(ownerId,stamp,n).equals(part.id())
                        ||!Arrays.equals(part.hash(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body))))throw unavailable();
                values.add(JdbcFrozenInputs.canonical(body));
            }
            var plan=FrozenSourceStagePlan.decode(reference,values,original);matches(owner,plan);
            if(!universe.equals(plan.reference()))throw unavailable();return new OriginalPlan(this,plan,original,parts,receipt);
        }catch(RuntimeException rejected){throw unavailable();}
    }
    public SourcePartitionPlanner.Plan read(FrozenSourceStagePlan.Reference reference) {
        try {
            Objects.requireNonNull(reference);var plan=read(reference.ownerId());
            if(!reference.equals(reference(storage.receipt(reference.ownerId()))))throw unavailable();return plan;
        }catch(RuntimeException rejected){throw unavailable();}
    }
    public SourcePartitionPlanner.Plan readImmutable(FrozenSourceStagePlan.Reference reference) {
        try {
            Objects.requireNonNull(reference);var original=readImmutableOriginal(reference.ownerId());
            if(!reference.equals(reference(original.receipt)))throw unavailable();return original.plan;
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private static JsonNode payload(JdbcJobRecords.Disposition receipt) {
        var payload=CanonicalJson.parse(receipt.payloadJson());closed(payload,"schema_version","owner_job_id","logical_key","generation","stage","business_date","universe_ref","plan_ref");
        if(!"source-stage-plan-receipt-v1".equals(text(payload,"schema_version")))throw unavailable();return payload;
    }
    private static FrozenSourceStagePlan.Reference reference(JdbcJobRecords.Disposition receipt){return FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(payload(receipt).get("plan_ref")));}
    private static void validateOwner(JdbcFrozenSourcePlans.Owner owner) {
        if(owner==null||!"generation_intent".equals(owner.kind())||owner.key()==null||owner.key().isBlank()||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER
                ||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))||owner.businessDate()==null||owner.type()==null)throw unavailable();
        var stage=GenerationFlowService.Stage.valueOf(owner.type().toUpperCase(Locale.ROOT));if(!owner.type().equals(stage.name().toLowerCase(Locale.ROOT))||!(stage.dedup()||stage==GenerationFlowService.Stage.NEWS_VALUE))throw unavailable();
    }
    private static void matches(JdbcFrozenSourcePlans.Owner owner,SourcePartitionPlanner.Plan plan) {
        if(!owner.id().equals(plan.reference().ownerId())||!owner.key().equals(plan.logicalKey())||owner.generation()!=plan.generation()
                ||!owner.type().equals(plan.stage().name().toLowerCase(Locale.ROOT))||plan.scope()==null||!owner.businessDate().equals(plan.scope().businessDate()))throw unavailable();
    }
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}
    private static void closed(JsonNode n,String...fields){var expected=Set.of(fields);WireValues.object(n,expected,expected);}
    private static IllegalStateException unavailable(){return new IllegalStateException("frozen source stage plan proof unavailable");}
}
