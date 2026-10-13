package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ObservationEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ArticleUnicodePolicyTest {
    private static ObjectNode dualRoleCatalog() {
        ObjectNode wire = (ObjectNode) CanonicalJson.parse("""
            {"protocol_revision":"0.11","observation_id":"fictional-observation","member_id":"fictional-member",
             "run_id":"fictional-run","business_date":"2026-01-01","source_id":"fictional-skill","source_revision":1,
             "source_item_id":"fictional-skill-document","kind":"catalog","retrieved_at":"2026-01-01T00:00:00.000000Z",
             "replaces_observation_id":null,"payload_hash":"placeholder","payload":{
              "schema_version":"1","parser_version":"fictional-skill-v1","config_version":1,"category":"skill",
              "identity":{"namespace":"github_skill","key":"fictional-org/fictional-repo::skills/fictional"},
              "title":"Fictional skill","source_url":"https://example.invalid/skills/fictional/SKILL.md","version":null,
              "published_at":null,"updated_at":null,"summary":null,"document_text":"Fictional skill and permission notice.",
              "truncated":false,"evidence":[]}}
            """);
        var evidence = ((ObjectNode) wire.get("payload")).putArray("evidence");
        for (String role : java.util.List.of("skill_document", "metadata")) {
            evidence.addObject().put("url", "https://example.invalid/skills/fictional/SKILL.md")
                    .put("revision", "c".repeat(40)).put("path", "skills/fictional/SKILL.md")
                    .put("file_hash", "a".repeat(64)).put("evidence_type", role);
        }
        return wire;
    }

    @Test void oneFixedDocumentCanCarryDistinctDocumentAndPermissionRoles() {
        validate(dualRoleCatalog());
    }

    @Test void distinctMetadataRoleMayKeepItsUnknownHash() {
        var wire = dualRoleCatalog();
        ((ObjectNode) wire.get("payload").get("evidence").get(1)).putNull("file_hash");
        validate(wire);
    }

    @Test void duplicateRoleAndConflictingFixedDocumentHashesStayRejected() {
        var duplicate = dualRoleCatalog();
        ((ObjectNode) duplicate.get("payload").get("evidence").get(1)).put("evidence_type", "skill_document");
        assertThrows(IllegalArgumentException.class, () -> validate(duplicate));
        var conflicting = dualRoleCatalog();
        ((ObjectNode) conflicting.get("payload").get("evidence").get(1)).put("file_hash", "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> validate(conflicting));
    }

    @Test void supplementaryCharactersUseSchemaCodePointLimits() throws Exception {
        String symbol = new String(Character.toChars(0x1F600));
        var cases = CanonicalJson.parse(Files.readAllBytes(Path.of("../contracts/hn-time-wire-examples.json"))).get("cases");
        ObjectNode wire = null;
        for (var sample : cases) if (sample.get("valid").booleanValue()) {
            wire = ((ObjectNode) sample.get("value")).deepCopy();
            break;
        }
        assertNotNull(wire);
        ObjectNode payload = (ObjectNode) wire.get("payload");
        payload.put("title", symbol.repeat(512));
        payload.putNull("source_summary");
        payload.put("body_text", symbol.repeat(50000));
        validate(wire);
        payload.put("body_text", symbol.repeat(50001));
        ObjectNode bodyInvalid = wire.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> validate(bodyInvalid));
        payload.putNull("body_text");
        payload.put("source_summary", symbol.repeat(16384));
        validate(wire);
        payload.put("source_summary", symbol.repeat(16385));
        ObjectNode summaryInvalid = wire.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> validate(summaryInvalid));
        payload.putNull("source_summary");
        payload.put("title", symbol.repeat(513));
        ObjectNode titleInvalid = wire.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> validate(titleInvalid));
    }

    private static void validate(ObjectNode wire) {
        wire.remove("payload_hash");
        wire.put("payload_hash", CanonicalJson.hash(CanonicalJson.Domain.OBSERVATION, wire));
        ObservationPayloadPolicy.validate(ObservationEnvelope.fromJson(wire));
    }
}
