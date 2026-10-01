package com.lianxibu.site.controller.content;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.content.AdminContentReadService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class AdminContentControllerTest {
 private final Actor owner=new Actor("fictional-owner","owner");private IdentityService identity;private AdminContentReadService reads;private MockMvc mvc;
 @BeforeEach @SuppressWarnings("unchecked") void setup(){identity=mock(IdentityService.class);reads=mock(AdminContentReadService.class);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);ObjectProvider<AdminContentReadService> provider=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);when(provider.getIfAvailable()).thenReturn(reads);when(identity.authenticate("fictional-session")).thenReturn(owner);mvc=MockMvcBuilders.standaloneSetup(new AdminContentController(auth,provider)).build();}
 @Test void ownerMetadataIsClosedAndNullableVersionsStayUnknown()throws Exception{when(reads.list(owner,"report","draft","daily",2,1)).thenReturn(new AdminContentReadService.Page(List.of(new AdminContentReadService.Item("fictional-one","report","Fictional title",3,null,"draft","daily")),2,1,2));mvc.perform(get("/admin/content").cookie(cookie()).param("kind","report").param("state","draft").param("type","daily").param("page","2").param("page_size","1")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].revision").value(3)).andExpect(jsonPath("$.data.items[0].body_revision").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.data.items[0].report_type").value("daily")).andExpect(jsonPath("$.data.items[0].body_json").doesNotExist()).andExpect(jsonPath("$.data.items[0].review_hash_v1").doesNotExist());}
 @Test void unauthorizedAndOrdinaryUserNeverRead()throws Exception{mvc.perform(get("/admin/content")).andExpect(status().isUnauthorized());when(identity.authenticate("fictional-session")).thenReturn(new Actor("fictional-user","user"));mvc.perform(get("/admin/content").cookie(cookie())).andExpect(status().isForbidden());verifyNoInteractions(reads);}
 @Test void closedQueriesAndDataFailuresAreRecoverableWithoutPrivateDetails()throws Exception{for(String query:List.of("?kind=report&kind=report","?user_id=fictional-other"))mvc.perform(get("/admin/content"+query).cookie(cookie())).andExpect(status().isBadRequest());for(String query:List.of("?kind=model&type=daily","?type=hourly","?state=arbitrary","?page=0"))mvc.perform(get("/admin/content"+query).cookie(cookie())).andExpect(status().isUnprocessableEntity());verifyNoInteractions(reads);when(reads.list(owner,null,null,null,1,20)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Fictional storage detail"));mvc.perform(get("/admin/content").cookie(cookie())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.message").value("temporarily_unavailable"));}
 private static jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
}
