package com.lianxibu.site.service.identity;

import com.lianxibu.site.domain.identity.NodeTokens;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.identity.JdbcNodeStore;
import com.lianxibu.site.repository.identity.JdbcNodeStore.Row;
import com.lianxibu.site.service.identity.BrowserIdentityAdapter.NodeOperations;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.identity.IdentityService.CollectorNode;
import com.lianxibu.site.service.identity.IdentityService.CollectorNodePage;
import com.lianxibu.site.service.identity.IdentityService.NodeActionCommand;
import com.lianxibu.site.service.identity.IdentityService.NodeActionOutcome;
import com.lianxibu.site.service.identity.IdentityService.NodeCreateCommand;
import com.lianxibu.site.service.identity.IdentityService.NodeCredential;
import com.lianxibu.site.service.identity.IdentityService.Refusal;
import com.lianxibu.site.service.identity.IdentityService.Rejected;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Owner node management and current Bearer authorization, with atomic 30-day replay. */
public final class NodeIdentityService implements NodeOperations {
    public record Principal(String nodeId, List<String> sourceIds, List<String> permissions,
                            long revision) { }

    private static final Set<String> PERMISSIONS = Set.of("config:read", "run:claim", "run:read",
            "run:write", "observation:write", "heartbeat:write", "probe:claim", "probe:write");
    private final JdbcNodeStore store;
    private final NodeTokens tokens;
    private final Clock clock;

    public NodeIdentityService(JdbcNodeStore store, NodeTokens tokens, Clock clock) {
        if (store == null || tokens == null || clock == null) {
            throw new IllegalArgumentException("node identity dependencies required");
        }
        this.store = store;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Called for every Go request, after extracting Bearer; revoked nodes cannot replay old work. */
    public Principal authorizeBearer(String token, String permission, String sourceId) {
        if (!NodeTokens.valid(token) || !PERMISSIONS.contains(permission)) throw reject(Refusal.FORBIDDEN);
        Row row = store.byTokenHash(NodeTokens.digest(token));
        if (row == null || !row.enabled()) throw reject(Refusal.FORBIDDEN);
        List<String> permissions = decode(row.permissionsJson());
        List<String> sources = decode(row.sourceIdsJson());
        if (!permissions.contains(permission) || (sourceId != null && !sources.contains(sourceId))) {
            throw reject(Refusal.FORBIDDEN);
        }
        return new Principal(row.id(), sources, permissions, row.revision());
    }

    @Override
    public CollectorNodePage listNodes(Actor actor, long page, int pageSize) {
        owner(actor);
        if (page < 1 || pageSize < 1 || pageSize > 50 || page > Long.MAX_VALUE / pageSize) {
            throw reject(Refusal.IDENTITY_CONFLICT);
        }
        return new CollectorNodePage(store.page((page - 1) * pageSize, pageSize).stream()
                .map(NodeIdentityService::node).toList(), page, pageSize, store.count());
    }

    @Override
    public NodeCredential createNode(Actor actor, NodeCreateCommand command, String key,
                                     String csrfToken, String origin) {
        owner(actor);
        writeContext(key, csrfToken, origin);
        if (command == null || command.name() == null || command.name().isBlank()
                || command.name().codePointCount(0, command.name().length()) > 100) {
            throw reject(Refusal.IDENTITY_CONFLICT);
        }
        List<String> sources = normalized(command.sourceIds(), 1000, false);
        List<String> permissions = normalized(command.permissions(), 8, true);
        String scope = "collector_nodes:create";
        byte[] hash = actionHash(actor.userId(), scope, "create", null,
                createCommand(command.name(), sources, permissions));
        return store.transaction(() -> {
            lockActor(actor);
            NodeCredential replay = replayCredential(actor, scope, key, hash);
            if (replay != null) return replay;
            checkSources(sources);
            String id = UUID.randomUUID().toString();
            String token = tokens.issue(actor.userId(), scope, key, id);
            store.create(id, command.name(), NodeTokens.digest(token), json(sources), json(permissions));
            CollectorNode node = new CollectorNode(id, command.name(), sources, permissions, 1, false, null);
            receipt(actor, scope, key, hash, node, "create");
            return new NodeCredential(node, token);
        });
    }

    @Override
    public NodeActionOutcome actOnNode(Actor actor, String nodeId, NodeActionCommand command,
                                       String key, String csrfToken, String origin) {
        owner(actor);
        writeContext(key, csrfToken, origin);
        if (nodeId == null || command == null
                || command.expectedRevision() < 1 || command.reason() == null
                || command.reason().isBlank() || command.reason().length() > 2048
                || !Set.of("rotate_token", "revoke", "set_permissions").contains(command.action())) {
            throw reject(Refusal.IDENTITY_CONFLICT);
        }
        try { WireValues.id(nodeId, false); }
        catch (IllegalArgumentException invalid) { throw reject(Refusal.IDENTITY_CONFLICT); }
        boolean set = "set_permissions".equals(command.action());
        List<String> sources = set ? normalized(command.sourceIds(), 1000, false) : null;
        List<String> permissions = set ? normalized(command.permissions(), 8, true) : null;
        if (!set && (command.sourceIds() != null || command.permissions() != null)) {
            throw reject(Refusal.IDENTITY_CONFLICT);
        }
        String scope = "collector_nodes:actions:" + nodeId;
        byte[] hash = actionHash(actor.userId(), scope, command.action(), nodeId,
                actionCommand(command, sources, permissions));
        return store.transaction(() -> {
            lockActor(actor);
            NodeCredential replay = replayCredential(actor, scope, key, hash);
            if (replay != null) return new NodeActionOutcome(replay.node(), replay.token());
            Row before = store.lockNode(nodeId);
            if (before == null) throw reject(Refusal.NOT_FOUND);
            if (!before.enabled() || before.revision() != command.expectedRevision()) {
                throw reject(Refusal.REVISION_CONFLICT);
            }
            String token = null;
            int changed;
            if (set) {
                checkSources(sources);
                changed = store.setPermissions(nodeId, before.revision(), json(sources), json(permissions));
            } else if ("revoke".equals(command.action())) {
                changed = store.revoke(nodeId, before.revision());
            } else {
                token = tokens.issue(actor.userId(), scope, key, nodeId);
                changed = store.rotate(nodeId, before.revision(), NodeTokens.digest(token));
            }
            if (changed != 1) throw reject(Refusal.REVISION_CONFLICT);
            CollectorNode after = new CollectorNode(before.id(), before.name(),
                    set ? sources : decode(before.sourceIdsJson()),
                    set ? permissions : decode(before.permissionsJson()),
                    before.revision() + 1, "revoke".equals(command.action()), before.lastSeenAt());
            receipt(actor, scope, key, hash, after, command.action());
            return new NodeActionOutcome(after, token);
        });
    }

    private void checkSources(List<String> sources) {
        for (String source : sources) if (!store.sourceExists(source)) throw reject(Refusal.NOT_FOUND);
    }

    private NodeCredential replayCredential(Actor actor, String scope, String key, byte[] hash) {
        JdbcNodeStore.Receipt receipt = store.receipt(actor.userId(), scope, NodeTokens.digest(key));
        if (receipt == null) return null;
        if (!MessageDigest.isEqual(receipt.requestHash(), hash)) throw reject(Refusal.IDENTITY_CONFLICT);
        if (receipt.expiresAt() == null || !receipt.expiresAt().isAfter(clock.instant())
                || receipt.responseJson() == null) {
            String resourceRef = null;
            if (receipt.resultRefJson() != null) {
                JsonNode ref = CanonicalJson.parse(receipt.resultRefJson());
                JsonNode id = ref.get("node_id");
                if (id != null && id.isTextual()) resourceRef = id.textValue();
            }
            throw new Rejected(Refusal.IDENTITY_CONFLICT, null, "idempotency_result_expired",
                    receipt.id(), resourceRef);
        }
        JsonNode value = CanonicalJson.parse(receipt.responseJson());
        CollectorNode node = parseNode(value.get("node"));
        String action = value.get("action").textValue();
        String token = Set.of("create", "rotate_token").contains(action)
                ? tokens.issue(actor.userId(), scope, key, node.id()) : null;
        return new NodeCredential(node, token);
    }

    private void receipt(Actor actor, String scope, String key, byte[] hash, CollectorNode node,
                         String action) {
        ObjectNode result = JsonMapper.shared().createObjectNode();
        result.put("action", action);
        result.set("node", nodeJson(node));
        ObjectNode ref = JsonMapper.shared().createObjectNode();
        ref.put("node_id", node.id());
        ref.put("action", action);
        ref.put("revision", node.revision());
        store.saveReceipt(actor.userId(), scope, key, NodeTokens.digest(key), hash,
                new String(CanonicalJson.bytes(result), StandardCharsets.UTF_8),
                new String(CanonicalJson.bytes(ref), StandardCharsets.UTF_8), clock.instant());
    }

    private static CollectorNode node(Row row) {
        return new CollectorNode(row.id(), row.name(), decode(row.sourceIdsJson()),
                decode(row.permissionsJson()), row.revision(), !row.enabled(), row.lastSeenAt());
    }

    private static ObjectNode nodeJson(CollectorNode node) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("id", node.id());
        value.put("name", node.name());
        value.set("source_ids", array(node.sourceIds()));
        value.set("permissions", array(node.permissions()));
        value.put("revision", node.revision());
        value.put("revoked", node.revoked());
        if (node.lastHeartbeatAt() == null) value.putNull("last_heartbeat_at");
        else value.put("last_heartbeat_at", node.lastHeartbeatAt().toString());
        return value;
    }

    private static CollectorNode parseNode(JsonNode node) {
        return new CollectorNode(node.get("id").textValue(), node.get("name").textValue(),
                decode(node.get("source_ids").toString()), decode(node.get("permissions").toString()),
                node.get("revision").longValue(), node.get("revoked").booleanValue(),
                node.get("last_heartbeat_at").isNull() ? null
                        : java.time.Instant.parse(node.get("last_heartbeat_at").textValue()));
    }

    private static List<String> decode(String raw) {
        if (raw == null) return List.of();
        JsonNode array = CanonicalJson.parse(raw);
        if (!array.isArray()) throw new IllegalStateException("node scope JSON must be array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) throw new IllegalStateException("node scope value must be string");
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }

    private static List<String> normalized(List<String> values, int max, boolean permission) {
        if (values == null || values.size() > max) throw reject(Refusal.IDENTITY_CONFLICT);
        TreeSet<String> sorted = new TreeSet<>(NodeIdentityService::compareScalars);
        for (String value : values) {
            if (value == null || value.isBlank()
                    || value.codePointCount(0, value.length()) > (permission ? 32 : 80)
                    || value.codePoints().anyMatch(cp -> cp < 32 || cp == 127)
                    || (permission && !PERMISSIONS.contains(value)) || !sorted.add(value)) {
                throw reject(Refusal.IDENTITY_CONFLICT);
            }
            if (!permission) {
                try { WireValues.id(value, true); }
                catch (IllegalArgumentException invalid) { throw reject(Refusal.IDENTITY_CONFLICT); }
            }
        }
        return List.copyOf(sorted);
    }

    private static int compareScalars(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            int c = left.codePointAt(a), d = right.codePointAt(b);
            if (c != d) return Integer.compare(c, d);
            a += Character.charCount(c);
            b += Character.charCount(d);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    private static String json(List<String> values) {
        return new String(CanonicalJson.bytes(array(values)), StandardCharsets.UTF_8);
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode result = JsonMapper.shared().createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static ObjectNode createCommand(String name, List<String> sources, List<String> permissions) {
        ObjectNode result = JsonMapper.shared().createObjectNode();
        result.put("name", name);
        result.set("source_ids", array(sources));
        result.set("permissions", array(permissions));
        return result;
    }

    private static ObjectNode actionCommand(NodeActionCommand command, List<String> sources,
                                             List<String> permissions) {
        ObjectNode result = JsonMapper.shared().createObjectNode();
        result.put("expected_revision", command.expectedRevision());
        result.put("action", command.action());
        result.put("reason", command.reason());
        if (sources != null) result.set("source_ids", array(sources));
        if (permissions != null) result.set("permissions", array(permissions));
        return result;
    }

    private static byte[] actionHash(String actorId, String scope, String action, String target,
                                     ObjectNode command) {
        ObjectNode input = JsonMapper.shared().createObjectNode();
        input.put("actor_id", actorId);
        input.put("scope", scope);
        input.put("action", action);
        if (target == null) input.putNull("target"); else input.put("target", target);
        input.set("command", command);
        return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.ACTION, input));
    }

    private void lockActor(Actor actor) {
        if (!store.lockActor(actor.userId())) throw reject(Refusal.UNAUTHENTICATED);
    }

    private static void owner(Actor actor) {
        if (actor == null || actor.userId() == null || !"owner".equals(actor.role())) {
            throw reject(Refusal.FORBIDDEN);
        }
    }

    private static void writeContext(String key, String csrf, String origin) {
        if (key == null || !key.matches("[!-~]{1,128}") || csrf == null || csrf.isBlank()
                || origin == null || origin.isBlank()) throw reject(Refusal.FORBIDDEN);
        // BrowserIdentityAdapter's controller invokes authorizeWrite for the session-bound check.
    }

    private static Rejected reject(Refusal refusal) { return new Rejected(refusal, null); }
}
