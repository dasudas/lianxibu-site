package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ManualSourceAdmissionCommandTest {
    @TempDir Path directory;
    @Test void evidenceBytesAndOwnerAreRequiredBeforeConnectingDatabase() throws Exception {
        byte[] evidence="Fictional reviewed source evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence));
        var json=JsonMapper.shared().createObjectNode();
        json.put("schema_version","manual-source-admission-v1");json.put("action_key","fixture");json.put("source_id","fixture-source");
        json.put("reviewer_id","fixture-owner");json.put("expected_revision",1);json.put("config_version",1);
        json.put("config_sha256",hash);json.put("legacy_config_sha256",hash);json.put("parser_version","fixture-v1");
        json.put("reviewed_at","2026-01-01T00:00:00Z");json.put("expires_at","2026-02-01T00:00:00Z");json.put("valid_samples",3);
        for(String field:java.util.List.of("usage_basis","field_mapping","access_policy","probe_evidence")){
            var node=json.putObject(field);node.put("sha256",hash);node.put("ref","fictional-evidence");
        }
        var checks=json.putObject("checks");
        for(String field:java.util.List.of("tls_network","root_format","required_fields","stable_ids","time_fields","usage_scope","adapter_roundtrip"))checks.put(field,true);
        Path manifest=directory.resolve("manifest.json");Files.writeString(manifest,json.toString());
        Files.createDirectory(directory.resolve("evidence"));Path proof=directory.resolve("evidence").resolve(hash);
        assertThrows(Exception.class,()->ManualSourceAdmissionCommand.read(manifest,"fixture-owner"));
        Files.write(proof,evidence);
        assertEquals("fixture-source",ManualSourceAdmissionCommand.read(manifest,"fixture-owner").text("source_id"));
        assertThrows(Exception.class,()->ManualSourceAdmissionCommand.read(manifest,"another-owner"));
        Files.writeString(proof,"changed");
        assertThrows(Exception.class,()->ManualSourceAdmissionCommand.read(manifest,"fixture-owner"));
        assertThrows(Exception.class,()->ManualSourceAdmissionCommand.read(directory.resolve("evidence/../manifest.json"),"fixture-owner"));
        assertThrows(Exception.class,()->ManualSourceAdmissionCommand.read(Path.of("manifest.json"),"fixture-owner"));
    }
}
