package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcDispatchInputs;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** SQL DATE is the original civil business day, independently of instant time zones. */
class GenerationBusinessDateTest extends GenerationJdbcStoreTest {
    boolean nativeMySql(){return false;}
    void connectionUtc(){if(!nativeMySql())jdbc.execute("SET TIME ZONE 'UTC'");}
    @Test void originalDaySurvivesNativeFreezeAcrossFourJvmZones(){
        var originalZone=TimeZone.getDefault();
        try{
            for(String zone:List.of("Asia/Shanghai","Asia/Tokyo","America/Los_Angeles","UTC")){
                TimeZone.setDefault(TimeZone.getTimeZone(zone));fixture();connectionUtc();
                var command=command(false);var frozen=flow(store).prepare(command,action());
                assertEquals(command.businessDate().toString(),CanonicalJson.parse(frozen.snapshotJson()).get("business_date").asText());
                assertEquals(command.businessDate().toString(),jdbc.queryForObject("SELECT business_date FROM jobs WHERE id=?",(rs,n)->rs.getString(1),OWNER),zone);
                assertEquals(command.businessDate().toString(),jdbc.queryForObject("SELECT business_date FROM jobs WHERE record_kind='generation_intent'",(rs,n)->rs.getString(1)),zone);
                assertEquals(command.businessDate(),new JdbcDispatchInputs(source).read(OWNER).businessDate(),zone);
                assertEquals(0,jdbc.queryForObject("SELECT call_count FROM jobs WHERE id=?",Integer.class,OWNER));
            }
        }finally{TimeZone.setDefault(originalZone);}
    }
    @Test void nativeDateReadingDoesNotReinterpretAnExistingDay(){
        var originalZone=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));fixture();connectionUtc();flow(store).prepare(command(false),action());
            for(String zone:List.of("Asia/Shanghai","Asia/Tokyo","America/Los_Angeles","UTC")){
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(LocalDate.of(2026,1,1),new JdbcDispatchInputs(source).read(OWNER).businessDate(),zone);
                assertEquals("2026-01-01",jdbc.queryForObject("SELECT business_date FROM jobs WHERE id=?",(rs,n)->rs.getString(1),OWNER));
            }
            jdbc.update("UPDATE jobs SET business_date=NULL WHERE id=?",OWNER);
            assertNull(new JdbcDispatchInputs(source).read(OWNER).businessDate());
        }finally{TimeZone.setDefault(originalZone);}
    }
}
