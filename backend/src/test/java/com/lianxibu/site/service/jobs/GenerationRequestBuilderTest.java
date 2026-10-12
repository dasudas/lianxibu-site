package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GenerationRequestBuilderTest {
    static ObjectNode snapshot(Stage stage) {
        String raw = FrozenApplicationContextTest.context(stage);
        if (stage == Stage.WEEKLY_REPORT) raw = raw.replace("2025-12-31T16:00:00.000000Z", "2025-12-28T16:00:00.000000Z").replace("2026-01-01T16:00:00.000000Z", "2026-01-04T16:00:00.000000Z");
        if (stage == Stage.MONTHLY_REPORT) raw = raw.replace("2026-01-01T16:00:00.000000Z", "2026-01-31T16:00:00.000000Z");
        var settings = new FrozenGenerationSettings(3, List.of("fictional-focus"), List.of("fictional-block"));
        var node = JsonMapper.shared().createObjectNode();
        node.put("schema_version", "1"); node.put("stage", stage.name().toLowerCase(Locale.ROOT)); node.put("generation", 1);
        node.put("settings_hash", settings.semanticHash()); node.set(FrozenGenerationSettings.FIELD, settings.toJson());
        node.set(FrozenApplicationContext.FIELD, CanonicalJson.parse(raw));
        node.putArray("members").addObject().put("id", "fictional-item").putObject("body").put("title", "Fictional evidence");
        return node;
    }
    static String build(ObjectNode input, Stage stage) { return new GenerationRequestBuilder().build(stage, input.toString(), CanonicalJson.hash(CanonicalJson.Domain.INPUT,input)); }

    @Test void fourteenStagesFreezeExactInputAndOriginalContractClosure() throws Exception {
        var schemas = CanonicalJson.parse(Files.readAllBytes(Path.of("../contracts/schemas.json")));
        try (var stream = GenerationRequestBuilder.class.getResourceAsStream("/contracts/schemas.json")) {
            assertNotNull(stream); assertArrayEquals(Files.readAllBytes(Path.of("../contracts/schemas.json")),stream.readAllBytes());
        }
        for (var stage : Stage.values()) {
            var input = snapshot(stage); String raw = build(input, stage); var request = CanonicalJson.parse(raw);
            assertEquals("deepseek-flash",request.path("model").asText());
            assertEquals("json_object",request.path("response_format").path("type").asText());
            assertEquals(CanonicalJson.parse(input.toString()), CanonicalJson.parse(request.path("messages").get(1).path("content").asText()));
            var schema = request.get("json_schema");
            String branch = stage.dedup()?"DedupResult":stage==Stage.NEWS_VALUE?"ValueResult":stage.analysis()?"AnalysisResult":stage.report()?"ReportResult":"AdHocResult";
            assertEquals("#/$defs/"+branch,schema.get("$ref").asText());
            for (var field : schema.get("$defs").properties()) assertEquals(schemas.get("$defs").get(field.getKey()),field.getValue());
            checkRefs(schema,schema.get("$defs"));
            assertEquals("site-generation-v1",request.path("prompt_version").asText());
            assertTrue(request.path("messages").get(0).path("content").asText().contains("json"));
            assertEquals(raw, build(input,stage));
            assertTrue(raw.getBytes(StandardCharsets.UTF_8).length<=1048576);
        }
    }
    static void checkRefs(JsonNode value, JsonNode definitions) {
        if(value.isObject())for(var field:value.properties()) {
            if(field.getKey().equals("$ref")) {String ref=field.getValue().asText();assertTrue(ref.startsWith("#/$defs/"));assertTrue(definitions.has(ref.substring(8)));}
            else checkRefs(field.getValue(),definitions);
        }
        if(value.isArray())for(var child:value)checkRefs(child,definitions);
    }
    @Test void frozenRequestTraversesActualTransportAndServerEnvelopeWithoutPromptRebuild() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost",0),0);
        var received=new java.util.concurrent.atomic.AtomicReference<String>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        String payload="{\"kind\":\"ad_hoc\",\"title\":\"Fictional answer\",\"sections\":[],\"citation_ids\":[]}";
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var response=JsonMapper.shared().createObjectNode();response.put("id","fictional-response");response.put("model","deepseek-flash");
            var choice=response.putArray("choices").addObject();choice.put("finish_reason","stop");choice.putObject("message").put("content",payload);
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var input=snapshot(Stage.AD_HOC_AI);String frozen=build(input,Stage.AD_HOC_AI);var request=CanonicalJson.parse(frozen);
            var client=com.lianxibu.site.repository.jobs.DeepSeekLoopbackFixture.client(java.net.http.HttpClient.newHttpClient(),java.net.URI.create("http://localhost:"+server.getAddress().getPort()+"/chat/completions"));
            input.put("settings_hash","b".repeat(64));
            var outcome=client.sendOnce(frozen);assertEquals(com.lianxibu.site.repository.jobs.DeepSeekChatClient.Status.VALID,outcome.status());assertEquals(1,calls.get());
            var wire=CanonicalJson.parse(received.get());assertEquals(request.get("messages"),wire.get("messages"));assertEquals(4096,wire.get("max_tokens").asInt());
            assertFalse(wire.has("json_schema"));assertFalse(wire.has("input_hash"));assertFalse(wire.has("api_key"));
            var context=FrozenApplicationContext.fromJson(snapshot(Stage.AD_HOC_AI).get(FrozenApplicationContext.FIELD));
            var applicability=context.input("fictional-root","fictional-attempt",request.get("input_hash").asText(),0);
            assertEquals("ad_hoc",ResultApplicationService.validate(applicability,ResultApplicationService.providerEnvelope(applicability,outcome.contentJson())).kind());
        } finally {server.stop(0);}
    }
    @Test void malformedUnboundOrOversizeInputsCannotBecomeRequests() {
        var builder=new GenerationRequestBuilder();var good=snapshot(Stage.AD_HOC_AI);
        assertThrows(GenerationFlowService.InputRejected.class,()->builder.build(Stage.AD_HOC_AI,good.toString(),"a".repeat(64)));
        for(String field:List.of(FrozenGenerationSettings.FIELD,FrozenApplicationContext.FIELD,"settings_hash","generation")){
            var bad=good.deepCopy();bad.remove(field);assertThrows(GenerationFlowService.InputRejected.class,()->build(bad,Stage.AD_HOC_AI));
        }
        var stale=good.deepCopy();stale.put("settings_hash","a".repeat(64));assertThrows(GenerationFlowService.InputRejected.class,()->build(stale,Stage.AD_HOC_AI));
        assertThrows(GenerationFlowService.InputRejected.class,()->build(good,Stage.AGENT_ANALYSIS));
        var incomplete=good.deepCopy();((ObjectNode)incomplete.get(FrozenApplicationContext.FIELD)).put("references_complete",false);
        assertThrows(GenerationFlowService.InputRejected.class,()->build(incomplete,Stage.AD_HOC_AI));
        var big=good.deepCopy();big.put("fictional_long", "x".repeat(1048576));assertThrows(GenerationFlowService.InputRejected.class,()->build(big,Stage.AD_HOC_AI));
        var expansion=good.deepCopy();expansion.put("fictional_long", "\"".repeat(300000));assertThrows(GenerationFlowService.InputRejected.class,()->build(expansion,Stage.AD_HOC_AI));
    }
    @Test void plannerMeasuresTheActualEscapedRequestAndDoesNotTreatBadInputsAsSizeFailures() {
        var builder=new GenerationRequestBuilder();var input=snapshot(Stage.AD_HOC_AI);
        String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);
        assertEquals(build(input,Stage.AD_HOC_AI),builder.buildWithinBudget(Stage.AD_HOC_AI,input.toString(),hash).orElseThrow());
        input.put("fictional_long","\"".repeat(300000));hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);
        assertTrue(input.toString().getBytes(StandardCharsets.UTF_8).length<1048576);
        assertTrue(builder.buildWithinBudget(Stage.AD_HOC_AI,input.toString(),hash).isEmpty());
        var big=snapshot(Stage.AD_HOC_AI);big.put("fictional_long","x".repeat(1100000));
        assertTrue(builder.buildWithinBudget(Stage.AD_HOC_AI,big.toString(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,big)).isEmpty());
        assertThrows(GenerationFlowService.InputRejected.class,()->builder.buildWithinBudget(Stage.AD_HOC_AI,big.toString(),"a".repeat(64)));
        big.put("fictional_long","x".repeat(2100000));
        assertThrows(GenerationFlowService.InputRejected.class,()->builder.buildWithinBudget(Stage.AD_HOC_AI,big.toString(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,big)));
        assertThrows(GenerationFlowService.InputRejected.class,()->builder.buildWithinBudget(Stage.NEWS_DEDUP,input.toString(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,input)));
    }
}
