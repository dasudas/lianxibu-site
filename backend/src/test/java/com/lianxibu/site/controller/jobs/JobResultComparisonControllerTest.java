package com.lianxibu.site.controller.jobs;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobResultComparisonReadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobResultComparisonControllerTest {
    private final Actor owner=new Actor("fictional-owner","owner");
    private IdentityService auth;private JobResultComparisonReadService service;
    private ObjectProvider<JobResultComparisonReadService> provider;private MockMvc mvc;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){auth=mock(IdentityService.class);service=mock(JobResultComparisonReadService.class);var identity=(ObjectProvider<IdentityService>)mock(ObjectProvider.class);provider=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);when(provider.getIfAvailable()).thenReturn(service);when(auth.authenticate("fictional-session")).thenReturn(owner);mvc=MockMvcBuilders.standaloneSetup(new JobResultComparisonController(identity,provider)).build();}
    @Test void unavailableIsClosedAndDoesNotInventBody()throws Exception{when(service.read(owner,"fictional-job")).thenReturn(new JobResultComparisonReadService.Unavailable("fictional-job",4,"no_retained_result"));mvc.perform(get(path()).cookie(cookie())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.state").value("unavailable")).andExpect(jsonPath("$.data.reason").value("no_retained_result")).andExpect(jsonPath("$.data.candidate_body").doesNotExist());}
    @Test void exactComparisonIncludesSeparateVersionsAndFrozenProof()throws Exception{
        var snapshot=fixture();when(provider.getIfAvailable()).thenReturn(new JobResultComparisonReadService(id->snapshot));
        mvc.perform(get(path()).cookie(cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_content_revision").value(5))
                .andExpect(jsonPath("$.data.current_body_revision").value(3))
                .andExpect(jsonPath("$.data.input_cutoff_at").value("2026-01-03T01:00:00.000000Z"))
                .andExpect(jsonPath("$.data.result_hash").value(snapshot.retained().resultHash()))
                .andExpect(jsonPath("$.data.current_body.title").value("Fictional current"))
                .andExpect(jsonPath("$.data.candidate_body.title").value("Fictional candidate"))
                .andExpect(jsonPath("$.data.can_apply").value(true))
                .andExpect(jsonPath("$.data.raw_payload").doesNotExist());
    }
    @Test void completeProjectionOverByteLimitFailsWithoutTruncation()throws Exception{
        var v=fixture();var missing=java.util.stream.IntStream.range(0,1000).mapToObj(i->new ContentService.MissingInput("source","fictional-source-"+i,null,"\uD83C\uDF31".repeat(256))).toList();
        var large=new JobResultComparisonReadService.Snapshot(v.jobId(),v.recordKind(),v.jobRevision(),v.contentId(),v.contentKind(),v.contentRevision(),v.bodyRevision(),v.currentBody(),v.inputSnapshotId(),v.cutoff(),new ContentService.Coverage("partial",missing,List.of(),List.of()),v.input(),v.retained(),v.current());
        when(provider.getIfAvailable()).thenReturn(new JobResultComparisonReadService(id->large));
        mvc.perform(get(path()).cookie(cookie())).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After","1"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void ownershipPrecedesClosedQueryAndAllStorage()throws Exception{mvc.perform(get(path()).param("unknown","fictional")).andExpect(status().isUnauthorized());when(auth.authenticate("fictional-session")).thenReturn(new Actor("fictional-user","user"));mvc.perform(get(path()).cookie(cookie())).andExpect(status().isForbidden());when(auth.authenticate("fictional-session")).thenReturn(owner);mvc.perform(get(path()).cookie(cookie()).param("page","1")).andExpect(status().isBadRequest());mvc.perform(get(path()).cookie(cookie()).param("id","fictional-a","fictional-b")).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void absentAssemblyMissingAndStorageFailureCannotCreateCandidate()throws Exception{when(provider.getIfAvailable()).thenReturn(null);mvc.perform(get(path()).cookie(cookie())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"));when(provider.getIfAvailable()).thenReturn(service);when(service.read(owner,"fictional-job")).thenThrow(new JobResultComparisonReadService.Missing());mvc.perform(get(path()).cookie(cookie())).andExpect(status().isNotFound());doThrow(new IllegalStateException("Fictional hidden storage payload")).when(service).read(owner,"fictional-job");mvc.perform(get(path()).cookie(cookie())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.message").value("temporarily_unavailable"));doReturn(new JobResultComparisonReadService.Unavailable("fictional-other",4,"no_retained_result")).when(service).read(owner,"fictional-job");mvc.perform(get(path()).cookie(cookie())).andExpect(status().isServiceUnavailable());}
    private static JobResultComparisonReadService.Snapshot fixture()throws Exception{
        var json=tools.jackson.databind.json.JsonMapper.builder().build();String hash="a".repeat(64);
        var body=CanonicalJson.parse("{\"title\":\"Fictional current\",\"sections\":[],\"citation_ids\":[],\"type\":\"daily\",\"period_start\":\"2026-01-01T16:00:00.000000Z\",\"period_end\":\"2026-01-02T16:00:00.000000Z\",\"timezone\":\"Asia/Shanghai\",\"learning_task\":null}");
        var candidate=body.deepCopy();((tools.jackson.databind.node.ObjectNode)candidate).put("title","Fictional candidate");
        var payload=json.createObjectNode();payload.put("kind","report");payload.set("body",candidate);
        var core=json.createObjectNode();core.put("schema_version","1");core.put("input_hash",hash);core.set("payload",payload);
        var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(CanonicalJson.bytes(core));
        String resultHash=java.util.HexFormat.of().formatHex(digest.digest());
        var result=new com.lianxibu.site.service.jobs.ResultApplicationService.Validated("report",new String(CanonicalJson.bytes(payload),java.nio.charset.StandardCharsets.UTF_8),resultHash,hash);
        var input=new com.lianxibu.site.service.jobs.ResultApplicationService.Input("fictional-job","fictional-attempt",com.lianxibu.site.service.jobs.GenerationFlowService.Stage.DAILY_REPORT,2,hash,1,1L,java.util.Set.of(),java.util.Map.of(),java.util.Set.of(),java.util.Set.of(),java.util.Set.of(),null,"2026-01-01T16:00:00.000000Z","2026-01-02T16:00:00.000000Z");
        var current=new com.lianxibu.site.service.jobs.ResultApplicationService.Current(2,hash,4,5L,true,false,null,resultHash,"fictional-attempt",true,"CONTENT_REVISION_CONFLICT");
        return new JobResultComparisonReadService.Snapshot("fictional-job","logical_task",4,"fictional-content","report",5L,3L,body,"fictional-input",Instant.parse("2026-01-03T01:00:00Z"),new ContentService.Coverage("complete",List.of(),List.of(),List.of()),input,result,current);
    }
    private static String path(){return "/admin/jobs/fictional-job/result-comparison";}
    private static jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
}
