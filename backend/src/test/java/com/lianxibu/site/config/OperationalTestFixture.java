package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.OperationalAdmissionService.*;
import com.lianxibu.site.service.settings.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
/** Existing collector tests explicitly supply fictional healthy metrics; production uses JDBC snapshots. */
@TestConfiguration(proxyBeanMethods=false)
public class OperationalTestFixture {
    @Bean @Primary OperationalInputGate fictionalOperationalInputGate(){
        Clock clock=Clock.systemUTC();
        List<Key> capacity=List.of(new Key("OPS-04","database","fictional-database"),new Key("OPS-10","monitor","fictional-monitor"));
        List<Key> dispatch=List.of(new Key("OPS-03","worker","fictional-slot"),new Key("OPS-06","java","fictional-java"),new Key("OPS-08","provider","fictional-provider"));
        var sources=new HashMap<String,List<Key>>();for(String id:List.of("fixture:feed","fixture:go","global-hn"))sources.put(id,List.of(new Key("OPS-07","source",id)));
        Scopes scopes=new Scopes(capacity,dispatch,sources);
        return new OperationalInputGate(new OperationalAdmissionService(()->{
            var keys=new ArrayList<>(capacity);keys.addAll(dispatch);sources.values().forEach(keys::addAll);
            var states=new ArrayList<State>();Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            for(Key key:keys){Reading reading=switch(key.ruleId()){
                case "OPS-04"->new Capacity(new BigDecimal("50"));case "OPS-10"->new Telemetry(0,0);
                case "OPS-03"->new SlotRecovery(0,0,true);case "OPS-06"->new Memory(true,new BigDecimal("500"),false);
                case "OPS-08"->new Provider(false,0,0,false);case "OPS-07"->new ContractFailures(0,0,true);default->throw new IllegalStateException();};
                var rule=MonitoringFixtures.complete().stream().filter(r->r.ruleId().equals(key.ruleId())).findFirst().orElseThrow();
                states.add(new OperationalRuleEngine().sample(State.empty(key),OperationalRulePolicy.compile(rule),1,now,true,reading).state());
            }
            return new Snapshot(new SettingsJdbcStore.OperationalConfiguration(1,MonitoringFixtures.complete(),true),states);
        },scopes,clock));
    }
}
