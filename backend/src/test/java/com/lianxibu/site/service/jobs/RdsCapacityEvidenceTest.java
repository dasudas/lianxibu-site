package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.RdsTelemetry.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RdsCapacityEvidenceTest {
    static final Resource RESOURCE = new Resource("1212121212121212", "cn-hangzhou", "rm-fictional");
    static final Instant NOW = Instant.parse("2026-10-01T00:10:00Z");
    static String attributes() { return "{\"Items\":{\"DBInstanceAttribute\":[{\"DBInstanceId\":\"rm-fictional\",\"RegionId\":\"cn-hangzhou\",\"Engine\":\"MySQL\",\"DBInstanceStatus\":\"Running\",\"DBInstanceStorage\":50}]}}"; }
    static String metadata() { return "{\"Success\":true,\"Code\":\"200\",\"TotalCount\":1,\"Resources\":{\"Resource\":[{\"Namespace\":\"acs_rds_dashboard\",\"MetricName\":\"DiskUsage\",\"Unit\":\"%\",\"Dimensions\":\"userId,instanceId\",\"Statistics\":\"Average,Minimum,Maximum\",\"Periods\":\"5,60,300\"}]}}"; }
    static String metrics(Instant at) {
        String point = "[{\"userId\":\"1212121212121212\",\"instanceId\":\"rm-fictional\",\"timestamp\":"+at.toEpochMilli()+",\"Minimum\":8,\"Average\":9,\"Maximum\":10}]";
        return "{\"Success\":true,\"Code\":\"200\",\"Period\":\"60\",\"Datapoints\":"+quote(point)+"}";
    }
    static String quote(String value) { return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""; }
    static Evidence evidence(String a, String m, String d) { return new Evidence(a,m,d,NOW); }
    static Evidence valid() { return evidence(attributes(),metadata(),metrics(NOW.minusSeconds(120))); }
    static Sample validate(Evidence value) { return new RdsCapacityEvidence().validate(RESOURCE,value,NOW); }
    @Test void boundPercentUsesMaximumAndPreservesMeasurementTime() {
        var sample=validate(valid()); assertTrue(sample.available());
        assertEquals(new BigDecimal("90"),sample.freePercent()); assertEquals(NOW.minusSeconds(120),sample.measuredAt());
        assertFalse(valid().toString().contains("rm-fictional")); assertFalse(RESOURCE.toString().contains("121212"));
    }
    @Test void realWireDecimalsAreSupportedWithoutRelaxingCanonicalBusinessJson() {
        String value=metrics(NOW).replace("\\\"Minimum\\\":8","\\\"Minimum\\\":8.539").replace("\\\"Average\\\":9","\\\"Average\\\":8.539").replace("\\\"Maximum\\\":10","\\\"Maximum\\\":8.539");
        var sample=validate(evidence(attributes(),metadata(),value));assertTrue(sample.available());assertEquals(new BigDecimal("91.461"),sample.freePercent());
        assertFalse(validate(evidence(attributes(),metadata(),value.replace("8.539","1e-10000000"))).available());
    }
    @Test void duplicateFieldsAndTrailingJsonRemainForbidden() {
        assertFalse(validate(evidence(attributes(),metadata().replace("\"Success\":true","\"Success\":true,\"Success\":true"),metrics(NOW))).available());
        assertFalse(validate(evidence(attributes(),metadata(),metrics(NOW)+"{}" )).available());
    }
    @Test void attributesMustIdentifyOneRunningMysqlInstanceWithPositiveCapacity() {
        for(String changed:new String[]{attributes().replace("rm-fictional","rm-other"),attributes().replace("cn-hangzhou","cn-other"),attributes().replace("MySQL","PostgreSQL"),attributes().replace("Running","Creating"),attributes().replace(":50",":0"),"{\"Items\":{\"DBInstanceAttribute\":[]}}"})
            assertFalse(validate(evidence(changed,metadata(),metrics(NOW))).available());
    }
    @Test void metadataMustProveUnitDimensionsStatisticsPeriodAndUniqueMetric() {
        for(String changed:new String[]{metadata().replace("%","GB"),metadata().replace("userId,instanceId","instanceId"),metadata().replace("Maximum","Sum"),metadata().replace("5,60,300","5,300"),metadata().replace("DiskUsage","OtherMetric"),metadata().replace("\"TotalCount\":1","\"TotalCount\":2"),metadata().replace("true","false")})
            assertFalse(validate(evidence(attributes(),changed,metrics(NOW))).available());
    }
    @Test void missingInvalidForeignAndOutOfRangeDataNeverBecomesZeroUsage() {
        String original=metrics(NOW.minusSeconds(60));
        for(String changed:new String[]{"{}",original.replace("1212121212121212","3434343434343434"),original.replace("rm-fictional","rm-other"),original.replace("\\\"Maximum\\\":10","\\\"Maximum\\\":101"),original.replace("\\\"Minimum\\\":8","\\\"Minimum\\\":11"),original.replace("true","false"),original.replace("\"Period\":\"60\"","\"Period\":\"300\""),original.replace("\"Code\":\"200\"","\"Code\":\"403\"")})
            assertFalse(validate(evidence(attributes(),metadata(),changed)).available(),changed);
        assertFalse(validate(null).available());
    }
    @Test void futureStaleAndUnalignedMeasurementOrObservationIsUnknown() {
        for(Instant at:new Instant[]{NOW.plusSeconds(60),NOW.minusSeconds(360),NOW.minusSeconds(1)})
            assertFalse(validate(evidence(attributes(),metadata(),metrics(at))).available());
        assertFalse(validate(new Evidence(attributes(),metadata(),metrics(NOW),NOW.plusSeconds(1))).available());
        assertFalse(validate(new Evidence(attributes(),metadata(),metrics(NOW),NOW.minusSeconds(21))).available());
    }
    @Test void duplicatesOverfullResponsesAndMalformedJsonAreRejected() {
        String point=metrics(NOW); String inner=point.substring(point.indexOf("[{"),point.lastIndexOf("}]")+2);
        String duplicate=point.replace(inner,inner.substring(0,inner.length()-1)+","+inner.substring(1));
        assertFalse(validate(evidence(attributes(),metadata(),duplicate)).available());
        assertFalse(validate(evidence(" ".repeat(262145),metadata(),point)).available());
        assertFalse(validate(evidence("not-json",metadata(),point)).available());
    }
}
