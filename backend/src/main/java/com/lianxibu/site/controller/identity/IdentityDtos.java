package com.lianxibu.site.controller.identity;

import java.util.List;

/** Wire DTOs use contract field names exactly; service records remain independent of JSON. */
public final class IdentityDtos {
    private IdentityDtos() { }

    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Session(boolean authenticated, String user_id, String role, String csrf_token) { }
    public record CollectorNode(String id, String name, List<String> source_ids, List<String> permissions,
                                long revision, boolean revoked, String last_heartbeat_at) { }
    public record CollectorNodePage(List<CollectorNode> items, long page, int page_size, long total) { }
    public record NodeCredential(CollectorNode node, String token) { }
}
