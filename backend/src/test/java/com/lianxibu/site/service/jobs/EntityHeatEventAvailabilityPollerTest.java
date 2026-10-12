package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

class EntityHeatEventAvailabilityPollerTest {
    EntityHeatEventAvailabilityTest h;JdbcEntityHeatEventLocators locators;EntityHeatEventAvailabilityPoller poller;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){h=new EntityHeatEventAvailabilityTest(){@Override GenerationJdbcStoreTest fixture(){return EntityHeatEventAvailabilityPollerTest.this.fixture();}};h.setup();locators=new JdbcEntityHeatEventLocators(h.h.h.f.source);poller=cycle(3);}
    EntityHeatEventAvailabilityPoller cycle(int limit){return new EntityHeatEventAvailabilityPoller(locators,h.service,new DataSourceTransactionManager(h.h.h.f.source),limit);}
    String id(String key){return CanonicalJson.hash(CanonicalJson.Domain.ACTION,JsonMapper.shared().createObjectNode().put("scope","entity-decision-v1").put("actor_id","fictional-owner").put("action_key",key));}
    void append(String key){h.h.h.write(()->{h.h.h.log.append(key,h.h.h.hash,new Decision("fictional-item","fictional-new","ready",true,1L),"existing","fictional-item",null,"fictional-new","Fictional repeated count observation");return true;});}
    @Test void FullNativePagesUseLookaheadAndEveryActualOriginalGetsOneFirst(){for(int i=0;i<130;i++)append("fictional-poll-"+i);var all=cycle(64);assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(64,64,0),all.poll());assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(64,64,0),all.poll());assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(3,3,0),all.poll());assertEquals(131,h.count());assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(64,64,0),all.poll());assertEquals(131,h.count());assertEquals(0,h.h.h.f.count("record_kind='logical_task' OR record_kind='ai_attempt'"));}
    @Test void LaterCommittedUuidBehindTheCursorIsObservedOnTheNextCycle(){for(int i=0;i<3;i++)append("fictional-initial-"+i);var page=h.h.rr(()->locators.page("",3));assertTrue(page.hasMore());poller.poll();String key=null;for(int i=0;i<10000;i++){String candidate="fictional-late-"+i;if(id(candidate).compareTo(page.ids().getLast())<0){key=candidate;break;}}assertNotNull(key);append(key);String event=id(key);assertNull(h.service.find(event));poller.poll();assertNull(h.service.find(event));poller.poll();assertNotNull(h.service.find(event));assertEquals(5,h.count());}
    @Test void OneBadNativeEventDoesNotFabricateVisibilityOrPreventOtherEvents(){append("fictional-other");h.h.h.f.jdbc.update("UPDATE jobs SET revision=1 WHERE id=?",h.h.id);assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(2,1,1),poller.poll());assertEquals(1,h.count());assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(2,1,1),poller.poll());assertEquals(1,h.count());}
    @Test void LegacyAndNeedsReviewAreScannedWithoutBeingCountedAsComplete(){h.h.h.f.jdbc.update("DELETE FROM jobs");h.h.h.write(()->{new EntityDecisionJdbcLog(new JdbcEntityDecisions(h.h.h.f.source),"fictional-owner",h.h.h.f.clock).append("fictional-key",h.h.h.hash,new Decision("fictional-item","fictional-new","ready",true,1L),"new","fictional-item",null,"fictional-new","Fictional legacy");h.h.h.log.append("fictional-review",h.h.h.hash,new Decision("fictional-item",null,"needs_review",false,null),"needs_review","fictional-item",null,null,"Fictional unresolved");return true;});assertEquals(new EntityHeatEventAvailabilityPoller.Outcome(2,0,0),poller.poll());assertEquals(0,h.count());}
    @Test void InvalidLocatorIdentityAndExplicitTransactionBoundariesFailClosed(){assertThrows(RuntimeException.class,()->locators.page("",3));assertThrows(RuntimeException.class,()->h.h.h.write(()->locators.page("",3)));assertThrows(RuntimeException.class,()->h.h.rr(poller::poll));assertThrows(RuntimeException.class,()->h.h.rr(()->locators.page("fictional-bad",3)));assertThrows(RuntimeException.class,()->h.h.rr(()->locators.page("",65)));h.h.h.f.jdbc.update("UPDATE jobs SET id='fictional-invalid' WHERE id=?",h.h.id);assertThrows(RuntimeException.class,poller::poll);assertEquals(0,h.count());}
    @Test void ColdNativeCycleHasMandatoryPortsAndNoIo(){var source=mock(javax.sql.DataSource.class);var tx=mock(PlatformTransactionManager.class);var observations=mock(EntityHeatEventAvailabilityService.class);new EntityHeatEventAvailabilityPoller(new JdbcEntityHeatEventLocators(source),observations,tx,64);verifyNoInteractions(source,tx,observations);assertThrows(RuntimeException.class,()->new EntityHeatEventAvailabilityPoller(locators,h.service,tx,0));assertThrows(NullPointerException.class,()->new EntityHeatEventAvailabilityPoller(null,h.service,tx,3));}
}
