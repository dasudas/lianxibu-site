package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.JdbcValueWrites;
import com.lianxibu.site.service.articles.ValueApplicationService;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import java.util.List;
import org.junit.jupiter.api.*;

class ValueApplicationServiceTest {
 DedupResultHandlerTest fixture;ValueApplicationService service;
 @BeforeEach void setup() throws Exception {fixture=new DedupResultHandlerTest();fixture.setup();fixture.apply();var jdbc=fixture.f.jdbc;jdbc.execute("ALTER TABLE source_run_items ADD value_json CLOB");jdbc.execute("ALTER TABLE source_run_items ADD high_value INT");service=new ValueApplicationService(new JdbcValueWrites(fixture.f.source));}
 List<Value> values(){return List.of(new Value("fictional-item",3,"news_tech",true,true,"Fictional selected"),new Value("fictional-item-two",3,"news_tech",false,false,"Fictional omitted"));}
 void apply(boolean fail){fixture.f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-value","fictional-value-root",null),key->null,a->{service.applyInTransaction("fictional-value-root","a".repeat(64),"b".repeat(64),values());if(fail)throw new IllegalStateException("fictional downstream failure");return true;});}
 @Test void storesPerMemberDecisionWithoutChangingEntityCounts(){apply(false);assertEquals(4,fixture.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id='fictional-item'",Integer.class));assertEquals(1,fixture.f.jdbc.queryForObject("SELECT high_value FROM source_run_items WHERE id='fictional-item'",Integer.class));assertEquals(0,fixture.f.jdbc.queryForObject("SELECT high_value FROM source_run_items WHERE id='fictional-item-two'",Integer.class));assertEquals(2,fixture.f.jdbc.queryForObject("SELECT occurrence_count FROM analyzed_entities",Integer.class));assertTrue(fixture.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id='fictional-item'",String.class).contains("member-value-v1"));}
 @Test void staleSecondMemberAndInvalidEvidenceLeaveNoValues(){fixture.f.jdbc.update("UPDATE source_run_items SET revision=4 WHERE id='fictional-item-two'");assertThrows(RuntimeException.class,()->apply(false));assertEmpty();fixture.f.jdbc.update("UPDATE source_run_items SET revision=3,effective_valid=0 WHERE id='fictional-item-two'");assertThrows(RuntimeException.class,()->apply(false));assertEmpty();}
 @Test void noTransactionOrLaterFailureCannotLeavePartialValues(){assertThrows(IllegalStateException.class,()->service.applyInTransaction("fictional-root","a".repeat(64),"b".repeat(64),values()));assertThrows(IllegalStateException.class,()->apply(true));assertEmpty();}
 void assertEmpty(){assertEquals(0,fixture.f.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE high_value IS NOT NULL OR value_json IS NOT NULL",Integer.class));assertEquals(2,fixture.f.jdbc.queryForObject("SELECT occurrence_count FROM analyzed_entities",Integer.class));}
}
