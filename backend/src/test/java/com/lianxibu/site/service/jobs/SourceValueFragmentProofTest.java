package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceValueFragmentProofTest {
    ValueFlagFactoryTest f;SourcePartitionPlanner.Plan plan;MemberUniverse.Universe universe;String root,envelope,handoff;
    @BeforeEach void setup() throws Exception {
        f=new ValueFlagFactoryTest();f.setup();f.apply();plan=f.base.plan;root=f.identity.rootId();
        universe=f.f.readonly(()->f.f.p1.universes.readImmutable(plan.reference()));handoff=f.f.readonly(()->new JdbcSourceDedupResults(f.f.f.source).handoff(root));
        envelope=ResultApplicationService.providerEnvelope(f.factory.forAttempt(f.identity).input(),JdbcFrozenInputs.canonical(f.base.payload));
    }
    SourceValueFragmentProof read(String raw){return SourceValueFragmentProof.read(plan,universe,root,envelope,raw);}
    @Test void originalV2ClassValueAndExactPostFactsAreProvenWithoutATransaction() {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var proof=read(handoff);assertEquals(root,proof.rootId());assertEquals(0,proof.stagePart());assertEquals(1,proof.generation());assertEquals(f.base.base.entity,proof.members().getFirst().entityId());assertEquals(f.base.base.revision+2,proof.members().getFirst().revision());assertTrue(CanonicalJson.parse(proof.result().payloadJson()).get("items").get(0).get("valuable").booleanValue());assertFalse(CanonicalJson.parse(proof.result().payloadJson()).get("items").get(0).get("high_value").booleanValue());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(handoff)),proof.handoffHash());assertThrows(UnsupportedOperationException.class,()->proof.members().clear());
    }
    @Test void rehashedLocalFactsCannotChangeTheOriginalEntityRunCategoryRevisionOrDate() {
        for(String field:new String[]{"entityId","runId","sourceId","sourceItemId","category","businessDate","activationStage"}){var n=(ObjectNode)CanonicalJson.parse(handoff);((ObjectNode)n.get("members").get(0)).put(field,"fictional-replacement");assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(n)));}
        var n=(ObjectNode)CanonicalJson.parse(handoff);((ObjectNode)n.get("members").get(0)).put("revision",f.base.base.revision+1);assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(n)));
    }
    @Test void missingDuplicateOrUnknownFactsAreRejected() {
        var n=(ObjectNode)CanonicalJson.parse(handoff);n.putArray("members");assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(n)));
        var duplicate=(ObjectNode)CanonicalJson.parse(handoff);((tools.jackson.databind.node.ArrayNode)duplicate.get("members")).add(duplicate.get("members").get(0).deepCopy());assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(duplicate)));
        var extra=(ObjectNode)CanonicalJson.parse(handoff);((ObjectNode)extra.get("members").get(0)).put("fictional_extra",true);assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(extra)));
    }
    @Test void originalResultPayloadAndFragmentIdentityMustMatch() {
        assertThrows(RuntimeException.class,()->SourceValueFragmentProof.read(plan,universe,"fictional-other-root",envelope,handoff));
        var n=(ObjectNode)CanonicalJson.parse(handoff);((ObjectNode)n.get("payload").get("items").get(0)).put("valuable",false);assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(n)));
        var stage=(ObjectNode)CanonicalJson.parse(handoff);stage.put("stage","news_dedup");assertThrows(RuntimeException.class,()->read(JdbcFrozenInputs.canonical(stage)));
    }
    @Test void legacyV1NeverGetsAnInventedOrdinaryValueAndOverLimitProofIsRejected() throws Exception {
        var old=new ValueResultFactoryTest();old.setup();old.apply();var oldUniverse=old.f.readonly(()->old.f.p1.universes.readImmutable(old.base.plan.reference()));String oldHandoff=old.f.readonly(()->new JdbcSourceDedupResults(old.f.f.source).handoff(old.identity.rootId()));String oldEnvelope=ResultApplicationService.providerEnvelope(old.factory.forAttempt(old.identity).input(),JdbcFrozenInputs.canonical(old.base.payload));assertThrows(RuntimeException.class,()->SourceValueFragmentProof.read(old.base.plan,oldUniverse,old.identity.rootId(),oldEnvelope,oldHandoff));assertThrows(RuntimeException.class,()->read(" ".repeat(262145)));
    }
}
