package com.lianxibu.site.controller.collector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.controller.collector.CollectorGateway.Operation;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CollectorControllerTest {
    private NodeIdentityService identity;
    private CollectorGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(NodeIdentityService.class);
        gateway = mock(CollectorGateway.class);
        ObjectProvider<NodeIdentityService> nodes = mock(ObjectProvider.class);
        ObjectProvider<CollectorGateway> runtime = mock(ObjectProvider.class);
        when(nodes.getIfAvailable()).thenReturn(identity);
        when(runtime.getIfAvailable()).thenReturn(gateway);
        when(identity.authorizeBearer(anyString(), anyString(), nullable(String.class)))
                .thenReturn(new NodeIdentityService.Principal("node", List.of("fixture"), List.of(), 1));
        when(gateway.execute(any(), anyString(), any(), any())).thenAnswer(call ->
                new CollectorGateway.Result(200, response(call.getArgument(2)), null));
        mvc = MockMvcBuilders.standaloneSetup(new CollectorController(nodes, runtime)).build();
    }

    @Test
    void allThirteenCollectorRoutesMapToExactGatewayOperations() throws Exception {
        String prefix = "/api/collector/v1";
        route(get(prefix + "/config"), Operation.CONFIG);
        route(write(post(prefix + "/run-claims"), "{\"source_ids\":[\"fixture\"],\"required_features\":[],\"max_runs\":1}", true), Operation.RUN_CLAIMS);
        route(write(put(prefix + "/runs/fixture/claim"), control("{\"run_id\":\"fixture\",\"action\":\"ack\"}", false), true), Operation.CLAIM_ACTION);
        route(get(prefix + "/runs?limit=10&source_id=fixture"), Operation.RUNS);
        route(get(prefix + "/runs/fixture"), Operation.RUN);
        route(get(prefix + "/runs/fixture/manifest"), Operation.MANIFEST_READ);
        route(write(put(prefix + "/runs/fixture/manifest"), validWire("ManifestControl"), true), Operation.MANIFEST_WRITE);
        route(write(put(prefix + "/runs/fixture/result"), control("{\"run_id\":\"fixture\",\"manifest_hash\":\"" + "a".repeat(64)
                + "\",\"part_count\":0,\"expected_count\":0,\"fetch_outcome\":\"success\",\"coverage\":{}}", true), true), Operation.RUN_RESULT);
        route(write(post(prefix + "/runs/fixture/members/fixture/actions"), control("{\"run_id\":\"fixture\",\"member_id\":\"fixture\",\"expected_member_revision\":0,\"action\":\"seal\",\"reason\":\"fixture\"}", true), true), Operation.MEMBER_SEAL);
        route(write(post(prefix + "/observations"), "{\"observations\":[" + validWire("ObservationEnvelope") + "]}", false), Operation.OBSERVATIONS);
        route(write(post(prefix + "/heartbeat"), "{\"observed_at\":\"2026-09-30T08:00:00.000000Z\",\"pending_observations\":0,\"pending_controls\":0,\"quarantined\":0,\"outbox_bytes\":1,\"free_bytes\":100,\"oldest_pending_at\":null}", false), Operation.HEARTBEAT);
        route(write(post(prefix + "/probe-claims"), "{\"source_ids\":[\"fixture\"],\"required_features\":[]}", true), Operation.PROBE_CLAIMS);
        route(write(put(prefix + "/probes/fixture/result"), control("{\"probe_id\":\"fixture\",\"result\":{}}", false), true), Operation.PROBE_RESULT);
    }

    @Test
    void identityPayloadAndServiceFailuresCloseTheBoundary() throws Exception {
        String path = "/api/collector/v1/run-claims";
        mvc.perform(write(post(path), "{\"source_ids\":[],\"required_features\":[],\"max_runs\":1}", true))
                .andExpect(status().isUnauthorized());
        verify(gateway, never()).execute(any(), anyString(), any(), any());

        mvc.perform(auth(write(post(path), "{\"source_ids\":[\"other\"],\"required_features\":[],\"max_runs\":1}", true)))
                .andExpect(status().isForbidden());
        mvc.perform(auth(write(post(path), "{\"source_ids\":[],\"required_features\":[],\"max_runs\":1,\"extra\":1}", true)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(write(post(path), "{\"source_ids\":[],\"source_ids\":[],\"required_features\":[],\"max_runs\":1}", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(write(post(path), "{\"source_ids\":[],\"required_features\":[],\"max_runs\":1}", false)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(write(put("/api/collector/v1/runs/fixture/claim"), control("{\"run_id\":\"different\",\"action\":\"ack\"}", false), true)))
                .andExpect(status().isUnprocessableEntity());
        verify(gateway, never()).execute(any(), anyString(), any(), any());

        when(identity.authorizeBearer(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN, null));
        mvc.perform(auth(get("/api/collector/v1/config"))).andExpect(status().isForbidden());
        reset(identity);

        when(identity.authorizeBearer(anyString(), anyString(), nullable(String.class)))
                .thenReturn(new NodeIdentityService.Principal("node", List.of("fixture"), List.of(), 1));
        doThrow(new CollectorGateway.Refused(409, "stale_claim")).when(gateway).execute(any(), anyString(), any(), any());
        mvc.perform(auth(write(put("/api/collector/v1/runs/fixture/claim"), control("{\"run_id\":\"fixture\",\"action\":\"ack\"}", false), true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("stale_claim"));

        reset(gateway);
        ObjectNode unsafe = (ObjectNode) response(Operation.CLAIM_ACTION);
        unsafe.putNull("receipt_ref");
        when(gateway.execute(any(),anyString(),any(),any())).thenReturn(new CollectorGateway.Result(200,unsafe,null));
        mvc.perform(auth(write(put("/api/collector/v1/runs/fixture/claim"), control("{\"run_id\":\"fixture\",\"action\":\"ack\"}", false), true)))
                .andExpect(status().isServiceUnavailable());

        reset(gateway);
        ObjectNode incomplete = (ObjectNode) response(Operation.OBSERVATIONS);
        incomplete.putArray("acks");
        when(gateway.execute(any(),anyString(),any(),any())).thenReturn(new CollectorGateway.Result(200,incomplete,null));
        mvc.perform(auth(write(post("/api/collector/v1/observations"),
                "{\"observations\":[" + validWire("ObservationEnvelope") + "]}",false)))
                .andExpect(status().isServiceUnavailable());

        reset(gateway);
        when(gateway.execute(any(),anyString(),any(),any())).thenThrow(new RuntimeException("private service detail"));
        mvc.perform(auth(get("/api/collector/v1/config"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.message").value("temporarily_unavailable"));
    }

    @Test
    void emptyProbeAndCachedConfigUseBodylessContractStatuses() throws Exception {
        reset(gateway);
        when(gateway.execute(any(),anyString(),any(),any())).thenReturn(new CollectorGateway.Result(304,null,"\"fixture-etag\""));
        mvc.perform(auth(get("/api/collector/v1/config").header("If-None-Match","\"fixture-etag\"")))
                .andExpect(status().isNotModified());
        reset(gateway);
        when(gateway.execute(any(),anyString(),any(),any())).thenReturn(new CollectorGateway.Result(204,null,null));
        mvc.perform(auth(write(post("/api/collector/v1/probe-claims"),
                "{\"source_ids\":[],\"required_features\":[]}",true)))
                .andExpect(status().isNoContent());
    }

    private void route(MockHttpServletRequestBuilder request, Operation operation) throws Exception {
        reset(gateway);
        when(gateway.execute(any(), anyString(), any(), any())).thenAnswer(call ->
                new CollectorGateway.Result(200, response(call.getArgument(2)), null));
        mvc.perform(auth(request)).andExpect(status().isOk()).andExpect(jsonPath("$.meta.request_id").exists());
        verify(gateway).execute(any(), anyString(), org.mockito.ArgumentMatchers.eq(operation), any());
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer fictional-token");
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body, boolean keyed) {
        request.contentType(MediaType.APPLICATION_JSON).content(body);
        if (keyed) request.header("Idempotency-Key", "fixture-key");
        return request;
    }

    private static String control(String business, boolean revision) {
        return "{\"business\":" + business + ",\"attempt\":{\"claim_token\":\"fixture\",\"expected_run_revision\":"
                + (revision ? "0" : "null") + "}}";
    }

    private static String validWire(String schema) throws Exception {
        JsonNode cases = CanonicalJson.parse(Files.readAllBytes(Path.of("../contracts/wire-examples.json"))).get("cases");
        for (JsonNode item : cases) {
            if (schema.equals(item.get("schema").asText()) && item.get("valid").asBoolean()) {
                return item.get("value").toString();
            }
        }
        throw new IllegalStateException("missing contract fixture " + schema);
    }

    private static JsonNode response(Operation operation) {
        Set<String> fields = switch (operation) {
            case CONFIG -> Set.of("protocol_revision", "sources", "next_cursor", "valid_until");
            case RUN_CLAIMS -> Set.of("runs");
            case RUNS -> Set.of("items", "next_cursor");
            case RUN -> Set.of("run_id", "source_id", "run_kind", "business_date", "generation", "window_start",
                    "window_end", "fetch_mode", "collection_reason", "config_version", "run_revision",
                    "delivery_state", "fetch_state", "analysis_state", "coverage", "expected_count", "accepted_count",
                    "excluded_count", "pending_count", "barrier_closed_at", "late_results_available",
                    "result_business_hash", "manifest_hash");
            case MANIFEST_READ -> Set.of("run_id", "parts", "next_cursor");
            case OBSERVATIONS -> Set.of("acks");
            case HEARTBEAT -> Set.of("received_at");
            case PROBE_CLAIMS -> Set.of("probe_id", "source_id", "config", "claim_token", "lease_until", "current_lease_valid");
            default -> Set.of("business_hash", "run_id", "probe_id", "run_revision", "delivery_state",
                    "current_lease_valid", "lease_until", "receipt_ref");
        };
        ObjectNode data = JsonMapper.shared().createObjectNode();
        fields.forEach(data::putNull);
        switch(operation) {
            case CONFIG -> {
                data.put("protocol_revision","0.11");
                data.putArray("sources");
                data.put("valid_until","2026-10-01T00:00:00.000000Z");
            }
            case RUN_CLAIMS -> data.putArray("runs");
            case RUNS -> data.putArray("items");
            case RUN -> {
                data.put("run_id","fixture");data.put("source_id","fixture");
                ObjectNode coverage=data.putObject("coverage");
                coverage.put("state","complete");coverage.putArray("missing_inputs");
                coverage.putArray("missing_dates");coverage.putArray("no_update_dates");
            }
            case MANIFEST_READ -> {data.put("run_id","fixture");data.putArray("parts");}
            case OBSERVATIONS -> {
                ObjectNode ack = data.putArray("acks").addObject();
                ack.put("observation_id","fixture");ack.put("member_id","fixture");ack.put("status","accepted");
                ack.putNull("code");ack.put("receipt_ref","receipt");ack.put("accepted_at","2026-09-30T08:00:00.000000Z");
                ack.putNull("projection_decision");ack.putNull("current_article_revision");ack.putNull("comparison_reason");
            }
            case HEARTBEAT -> data.put("received_at","2026-09-30T08:00:00.000000Z");
            case PROBE_CLAIMS -> {
                data.put("probe_id","fixture");data.put("source_id","fixture");
                ObjectNode frozen=data.putObject("config");
                frozen.put("source_id","fixture");frozen.put("config_version",1);
                frozen.put("activation_stage","trial");
                ObjectNode config=frozen.putObject("config");
                for(String field: Set.of("adapter","entry_urls","identity_rule","window_rule","parser_version",
                        "max_items","max_response_bytes","allowed_hosts","valid_until","auth_secret_ref")) config.putNull(field);
                data.put("claim_token","fixture");
                data.put("lease_until","2026-09-30T09:00:00.000000Z");data.put("current_lease_valid",true);
            }
            default -> {
                data.put("business_hash","a".repeat(64));
                if(operation==Operation.PROBE_RESULT) data.put("probe_id","fixture");
                else data.put("run_id","fixture");
                data.put("run_revision",0);data.put("delivery_state","receiving");
                data.put("current_lease_valid",false);data.put("receipt_ref","fixture-receipt");
            }
        }
        return data;
    }
}
