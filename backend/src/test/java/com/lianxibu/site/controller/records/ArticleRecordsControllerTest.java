package com.lianxibu.site.controller.records;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.records.ArticleRecordReadService;
import com.lianxibu.site.service.records.RecordsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArticleRecordsControllerTest {
    private final Actor actor=new Actor("fictional-user","user");
    private IdentityService identity;
    private ArticleRecordReadService reads;
    private MockMvc mvc;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){identity=mock(IdentityService.class);reads=mock(ArticleRecordReadService.class);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);ObjectProvider<ArticleRecordReadService> provider=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);when(provider.getIfAvailable()).thenReturn(reads);when(identity.authenticate("fictional-session")).thenReturn(actor);mvc=MockMvcBuilders.standaloneSetup(new ArticleRecordsController(auth,provider)).build();}
    @Test void completeAggregateMapsLiteralFilterAndClosedWire()throws Exception{
        var record=new RecordsService.Record(null,"article","fictional-article",1,"planned","unassessed","Fictional note",null,null,null,null,Instant.parse("2026-01-01T00:00:00Z"),true,true,false);
        when(reads.list(actor,"100%_","planned",true,1,20)).thenReturn(new ArticleRecordReadService.Page(List.of(new ArticleRecordReadService.Item(new ArticleRecordReadService.Article("fictional-article","Fictional title",null),record)),1,20,1,new ArticleRecordReadService.Stats(2,1,1,0,1)));
        mvc.perform(get("/private/article-records").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).param("q","100%_").param("status","planned").param("favorite","true")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.stats.total").value(2)).andExpect(jsonPath("$.data.items[0].record.object_kind").value("article")).andExpect(jsonPath("$.data.items[0].record.updated_at").value("2026-01-01T00:00:00.000000Z")).andExpect(jsonPath("$.data.items[0].record.user_id").doesNotExist()).andExpect(jsonPath("$.data.items[0].article.body_text").doesNotExist());
    }
    @Test void authenticationAndRoleFailBeforeRead()throws Exception{
        mvc.perform(get("/private/article-records")).andExpect(status().isUnauthorized());
        when(identity.authenticate("fictional-session")).thenReturn(new Actor("fictional-user","collector"));
        mvc.perform(get("/private/article-records").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session"))).andExpect(status().isForbidden());verifyNoInteractions(reads);
    }
    @Test void invalidQueryAndProviderFailureAreRecoverableAndDoNotLeak()throws Exception{
        for(String query:List.of("?q=a&q=b","?user_id=fictional-other"))mvc.perform(get("/private/article-records"+query).cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session"))).andExpect(status().isBadRequest());
        for(String query:List.of("?favorite=1","?status=unknown","?page=0","?q="))mvc.perform(get("/private/article-records"+query).cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session"))).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(reads);
        when(reads.list(actor,null,null,null,1,20)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Fictional internal details"));
        mvc.perform(get("/private/article-records").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session"))).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.message").value("temporarily_unavailable"));
    }
}
