package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class FrozenContentVersionServiceTest {
 ResultJdbcStoreTest fixture;FrozenContentVersionService versions;ContentVersionKey version;
 @BeforeEach void setup(){fixture=new ResultJdbcStoreTest();fixture.fixture();install();}
 void install(){fixture.apply();versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(fixture.source));version=ContentVersionKey.fromJson(new JdbcResultState(fixture.source).receipt("fictional-apply","fictional-owner",ResultJdbcStoreTest.ROOT,fixture.identity.attemptId()).get("content_version"));}
 @Test void replacementOfCurrentRowDoesNotEraseOriginalFullBodyAndFrozenReferences(){var old=versions.find(version).orElseThrow();fixture.jdbc.update("UPDATE contents SET revision=8,body_revision=7,body_json=?,body_hash_v1=?,input_job_id='fictional-new-input'",ResultJdbcStoreTest.BODY.replace("Fictional text","Fictional newer body"),new byte[32]);var retained=versions.find(version).orElseThrow();assertEquals(old.version(),retained.version());assertEquals(old.inputHash(),retained.inputHash());assertEquals(old.bodyJson(),retained.bodyJson());assertEquals(old.context().json(),retained.context().json());assertTrue(retained.bodyJson().contains("Fictional text"));assertFalse(retained.bodyJson().contains("newer body"));assertEquals("fictional-citation",retained.context().referenceTargets().getFirst().path("id").asText());assertEquals(1,fixture.sends);}
 @Test void partialIdentityCannotSelectOtherKindRevisionHashOrInputOwner(){for(var candidate:new ContentVersionKey[]{new ContentVersionKey("agent",version.contentId(),version.revision(),version.bodyHash(),version.inputJobId()),new ContentVersionKey(version.contentKind(),"fictional-other",version.revision(),version.bodyHash(),version.inputJobId()),new ContentVersionKey(version.contentKind(),version.contentId(),9,version.bodyHash(),version.inputJobId()),new ContentVersionKey(version.contentKind(),version.contentId(),version.revision(),"f".repeat(64),version.inputJobId()),new ContentVersionKey(version.contentKind(),version.contentId(),version.revision(),version.bodyHash(),"fictional-other-root")})assertTrue(versions.find(candidate).isEmpty());}
 @Test void alteredReceiptOrMissingFrozenPartCannotBecomeHistoricalProof(){fixture.jdbc.update("UPDATE jobs SET action_payload=? WHERE target_type='result_application'","{}");assertThrows(IllegalStateException.class,()->versions.find(version));}
 @Test void missingInputOrRetainedBodyFailsClosedEvenWhenCurrentBodyStillMatches(){fixture.jdbc.update("DELETE FROM jobs WHERE purpose='input' AND part_no=0");assertThrows(IllegalStateException.class,()->versions.find(version));assertEquals(1,fixture.count("contents"));}
}
