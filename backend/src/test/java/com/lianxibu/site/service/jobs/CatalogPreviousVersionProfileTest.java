package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

/** Actual original source/input/worker/result chain, with a fictional external sender. Comparisons remain pure. */
class CatalogPreviousVersionProfileTest {
    Fixture f;boolean mysql(){return false;}
    @BeforeEach void setup(){initialize("agent",false,false,1);}
    void initialize(String category,boolean metric,boolean unknown,int count){f=new Fixture();f.metric=metric;f.unknown=unknown;f.members=count;f.initializeCatalog(mysql(),category,true);}
    Optional<CatalogPreviousVersionProfile.Profile> profile(){var previous=new CatalogPreviousAnalysisService(f.source,f.tx).read(f.category,"fictional-entity","fictional-analysis").orElseThrow();return CatalogPreviousVersionProfile.read(CatalogPreviousAnalysisEvidence.read(previous).orElseThrow());}
    CatalogVersionEvidenceMarker.Marker marker(ObjectNode payload){return CatalogVersionEvidenceMarker.readCatalog(payload).orElseThrow();}
    @Test void FourCategoriesCompareRealPreviousVersionsAndKeepMetadataOrCommitChangesStable(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false,false,1);var profile=profile().orElseThrow();var current=f.catalogPayload();assertFalse(profile.changed(marker(current)).orElseThrow());
            current.put("summary","Fictional updated summary");assertFalse(profile.changed(marker(current)).orElseThrow());
            if(category.equals("skill")){current.put("version","c".repeat(40));((ObjectNode)current.get("evidence").get(0)).put("revision","c".repeat(40));assertFalse(profile.changed(marker(current)).orElseThrow());((ObjectNode)current.get("evidence").get(0)).put("file_hash","d".repeat(64));}
            else {current.put("version","v2");((ObjectNode)current.get("evidence").get(0)).put("revision","v2");}
            assertTrue(profile.changed(marker(current)).orElseThrow());assertEquals(1,f.sends);assertFalse(profile.toString().contains("fictional"));
        }
    }
    @Test void MissingOriginalVersionRemainsUnknownDespiteCompleteActualOriginalAnalysis(){
        initialize("agent",false,true,1);assertTrue(profile().isEmpty());assertEquals(1,f.sends);
    }
    @Test void AllHundredRefsBindProofButRepeatedSameVersionDoesNotChangeSemanticHash(){
        var first=profile().orElseThrow();initialize("agent",false,false,100);var many=profile().orElseThrow();assertEquals(100,many.markers().size());
        assertEquals(first.semanticHash(),many.semanticHash());assertNotEquals(first.proofHash(),many.proofHash());assertEquals(100,CanonicalJson.parse(many.proofJson()).get("members").size());
        assertTrue(many.proofJson().contains("fictional-item-99"));assertThrows(UnsupportedOperationException.class,()->many.markers().clear());assertEquals(1,f.sends);
    }
    @Test void SameBenchmarkDomainTracksActualRowAndKeepsUnrelatedFileDownloadsStable(){
        initialize("model",true,false,1);var profile=profile().orElseThrow();var current=f.catalogPayload();
        ((ObjectNode)current.get("evidence").get(0)).put("file_hash","d".repeat(64)).put("revision","c".repeat(40));assertFalse(profile.changed(marker(current)).orElseThrow());
        current.put("score","72");assertTrue(profile.changed(marker(current)).orElseThrow());current.put("benchmark","fictional-other-benchmark");assertTrue(profile.changed(marker(current)).isEmpty());
    }
    @Test void ForeignIdentityDifferentKindAndMalformedCurrentPayloadCannotBecomeNegativeDecision(){
        var profile=profile().orElseThrow();var foreign=f.catalogPayload();((ObjectNode)foreign.get("identity")).put("key","fictional-other");assertThrows(RuntimeException.class,()->profile.changed(marker(foreign)));
        assertThrows(RuntimeException.class,()->profile.changed(null));var malformed=f.catalogPayload();malformed.put("unexpected",true);assertThrows(RuntimeException.class,()->marker(malformed));
        initialize("model",false,false,1);var release=profile().orElseThrow();var metric=new CatalogVersionEvidenceMarkerTest().metric();((ObjectNode)metric.get("subject").get("identity")).put("key","fictional-object");assertTrue(release.changed(marker(metric)).isEmpty());
    }
    static class Fixture extends CatalogPreviousAnalysisEvidenceTest.Fixture {
        boolean metric,unknown;
        @Override ObjectNode catalogPayload(){var factories=new CatalogVersionEvidenceMarkerTest();var payload=metric?factories.metric():category.equals("skill")?factories.skill():factories.catalog(category);if(unknown)payload.putNull("version");return payload;}
    }
}
