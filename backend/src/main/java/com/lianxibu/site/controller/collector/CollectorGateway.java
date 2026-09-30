package com.lianxibu.site.controller.collector;

import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import tools.jackson.databind.JsonNode;

/** Runtime adapter installed by B26; this HTTP boundary never persists a request itself. */
public interface CollectorGateway {
    enum Operation {
        CONFIG, RUN_CLAIMS, CLAIM_ACTION, RUNS, RUN, MANIFEST_READ,
        MANIFEST_WRITE, RUN_RESULT, MEMBER_SEAL, OBSERVATIONS, HEARTBEAT,
        PROBE_CLAIMS, PROBE_RESULT
    }

    record Request(String runId, String memberId, String probeId, String cursor,
                   Integer limit, String sourceId, String ifNoneMatch,
                   String idempotencyKey, JsonNode body) { }

    record Result(int status, JsonNode data, String etag) { }

    Result execute(Principal principal, Operation operation, Request request);

    /** Safe business failure from the runtime adapter, without secret or payload text. */
    final class Refused extends RuntimeException {
        private final int status;
        private final String code;
        public Refused(int status, String code) {
            super(code);
            if (!java.util.Set.of(400,401,403,404,409,410,413,422,429,503).contains(status) ||
                    code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("invalid public collector refusal");
            }
            this.status = status;
            this.code = code;
        }
        public int status() { return status; }
        public String code() { return code; }
    }
}
