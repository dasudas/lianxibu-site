package com.lianxibu.site.service.sources;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.sources.JdbcSourceRunAnalysis;
import java.util.*;
import org.junit.jupiter.api.*;

/** Real locked run/projection writes; native final-receipt authority remains an explicit prior port. */
class CatalogSourceRunAnalysisServiceTest extends SourceRunAnalysisServiceTest {
 @Override @BeforeEach void setup(){super.setup();jdbc.update("UPDATE sources SET category='agent'");service=configured(new JdbcSourceRunAnalysis(source),"agent");}
 SourceRunAnalysisService configured(JdbcSourceRunAnalysis storage,String category){return new SourceRunAnalysisService(storage,(run,refs)->refs.stream().map(proofs::get).filter(Objects::nonNull).max(Comparator.comparing(com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position::inputCutoff).thenComparingLong(com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position::generation)).orElse(null),category);}
 @Test void originalConstructorStaysNewsOnlyAndFourCatalogConsumersRequireExactCategory(){
  service=new SourceRunAnalysisService(new JdbcSourceRunAnalysis(source),(run,refs)->null);assertThrows(RuntimeException.class,()->apply(command()));assertEquals(2,revision());assertNull(text("analysis_summary"));
  int n=0;for(String category:List.of("agent","skill","workflow","model")){jdbc.update("UPDATE sources SET category=?",category);service=configured(new JdbcSourceRunAnalysis(source),category);var original=command("fictional-catalog-key-"+category,1,CUTOFF.plusSeconds(n),"fictional-catalog-final-"+category);assertEquals("applied",apply(original).disposition());assertEquals("unchanged",apply(original).disposition());assertEquals(3+n,revision());assertEquals(original.analysisSummary(),text("analysis_summary"));assertEquals("{\"fictional_collector\":true}",canonical(text("result_json")));assertEquals("{}",canonical(text("coverage_json")));n++;}
 }
 @Test void anotherValidCategoryIntroducedInsideActualWriterBeforeLockRollsEverythingBack(){
  var storage=org.mockito.Mockito.spy(new JdbcSourceRunAnalysis(source));org.mockito.Mockito.doAnswer(call->{jdbc.update("UPDATE sources SET category='skill'");return call.callRealMethod();}).when(storage).locked("fictional-run");service=configured(storage,"agent");var command=command();assertThrows(RuntimeException.class,()->apply(command));assertEquals("agent",jdbc.queryForObject("SELECT category FROM sources",String.class));assertEquals(2,revision());assertNull(text("analysis_summary"));assertFalse(text("job_refs_json").contains(command.position().receiptId()));
 }
 @Test void rawCategoryAliasesAndDifferentConfiguredValidCategoryCannotProject(){
  var command=command();for(String category:List.of("AGENT","skill","workflow","model","news_tech")){jdbc.update("UPDATE sources SET category=?",category);assertThrows(RuntimeException.class,()->apply(command));assertEquals(2,revision());assertNull(text("analysis_summary"));}jdbc.update("UPDATE sources SET category='agent'");assertEquals("applied",apply(command).disposition());
 }
 @Test void configuredConstructorIsClosedAndDoesNotReadStorageOrPriorProof(){
  var storage=org.mockito.Mockito.mock(JdbcSourceRunAnalysis.class);var prior=org.mockito.Mockito.mock(SourceRunAnalysisService.PriorReader.class);for(String category:List.of("news_tech","agent","skill","workflow","model"))assertNotNull(new SourceRunAnalysisService(storage,prior,category));for(String category:List.of("","AGENT","report","agent_dedup"))assertThrows(RuntimeException.class,()->new SourceRunAnalysisService(storage,prior,category));org.mockito.Mockito.verifyNoInteractions(storage,prior);
 }
}
