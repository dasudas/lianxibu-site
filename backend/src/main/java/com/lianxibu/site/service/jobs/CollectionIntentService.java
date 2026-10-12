package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A pending intention and its permanent source-run identity; never prepares or sends an AI call. */
public final class CollectionIntentService {
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    private final Clock clock;
    public CollectionIntentService(DataSource source, Clock clock) {
        jdbc = new JdbcTemplate(source); records = new JdbcJobRecords(source); this.clock = Objects.requireNonNull(clock);
    }
    public String ensure(Facts facts) {
        JdbcFrozenInputs.requireTx(); Objects.requireNonNull(facts);
        var address=CollectionIntentIdentity.create(facts.sourceId(),facts.runKind(),facts.businessDate(),facts.generation(),facts.category());
        String stage=address.taskType(),logical=address.logicalKey(),intent=address.id();
        var payload = JsonMapper.shared().createObjectNode();
        payload.put("schema_version","collection-handoff-v1"); payload.put("intent_id",intent);
        payload.set("facts",JsonMapper.shared().valueToTree(facts));
        String encoded = JdbcFrozenInputs.canonical(payload);
        String action = "collection-handoff:" + facts.runId();
        String disposition = JdbcFrozenInputs.stableId("collection-handoff",action);
        var previous = jdbc.query("SELECT id,target_key_json,target_key_hash,action_payload,action_payload_hash FROM jobs"
                + " WHERE record_kind='disposition' AND target_type='collection_handoff' AND id=?",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getBytes(5)},disposition);
        var target = JsonMapper.shared().createObjectNode(); target.put("kind","generation_intent");target.put("id",intent);
        var receipt = new JdbcJobRecords.Disposition(disposition,action,"collection_handoff",
                JdbcFrozenInputs.canonical(target),"collection-worker","Terminal collection handed to analysis",encoded);
        var requested = new JdbcJobRecords.Intent(intent,logical,facts.generation(),stage,facts.businessDate());
        if (!previous.isEmpty()) {
            var old = previous.getFirst();
            if (!encoded.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse((String)old[3])))
                    || !JdbcFrozenInputs.canonical(target).equals(JdbcFrozenInputs.canonical(CanonicalJson.parse((String)old[1])))
                    || !Arrays.equals((byte[])old[4], sha(encoded))
                    || !Arrays.equals((byte[])old[2], sha(JdbcFrozenInputs.canonical(target)))) throw conflict();
            var row=records.byId(intent);
            if(row==null || !"generation_intent".equals(row.kind()) || !logical.equals(row.logicalKey())
                    || !Long.valueOf(facts.generation()).equals(row.generation())) throw conflict();
            if (!intent.equals(records.ensureIntent(requested,clock.instant()))) throw conflict();
            records.appendDisposition(receipt,clock.instant());
            return intent;
        }
        // A different run must not silently claim an already registered logical generation.
        if(records.byId(intent)!=null) throw conflict();
        String actual=records.ensureIntent(requested,clock.instant());
        if(!intent.equals(actual)) throw conflict();
        records.appendDisposition(receipt,clock.instant());
        return intent;
    }
    private static byte[] sha(String text) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalStateException conflict() { return new IllegalStateException("collection handoff proof conflict"); }
}
