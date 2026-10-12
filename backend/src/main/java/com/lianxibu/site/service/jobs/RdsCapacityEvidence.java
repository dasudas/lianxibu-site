package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.RdsTelemetry.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/** Fail closed unless independently bound instance, metric metadata and measurement all agree. */
public final class RdsCapacityEvidence {
    // Cloud wire JSON permits decimals. Project canonical business JSON deliberately does not.
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(64).maxStringLength(262144).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public Sample validate(Resource resource,Evidence evidence,Instant now) {
        try {
            if(resource==null||evidence==null||now==null||evidence.receivedAt()==null
                    ||evidence.receivedAt().isAfter(now)||evidence.receivedAt().isBefore(now.minusSeconds(20)))throw new IllegalArgumentException();
            JsonNode attributes=parse(evidence.attributesJson()),metadata=parse(evidence.metadataJson()),metrics=parse(evidence.metricsJson());
            JsonNode instances=attributes.path("Items").path("DBInstanceAttribute");
            if(!instances.isArray()||instances.size()!=1)throw new IllegalArgumentException();
            JsonNode instance=instances.get(0);
            exact(instance,"DBInstanceId",resource.instanceId()); exact(instance,"RegionId",resource.regionId());
            exact(instance,"Engine","MySQL"); exact(instance,"DBInstanceStatus","Running");
            JsonNode storage=instance.path("DBInstanceStorage");
            if(!storage.isIntegralNumber()||!storage.canConvertToLong()||storage.longValue()<1||storage.longValue()>1048576)throw new IllegalArgumentException();
            success(metadata); success(metrics);
            JsonNode entries=metadata.path("Resources").path("Resource");
            if(!metadata.path("TotalCount").isIntegralNumber()||metadata.path("TotalCount").longValue()!=1||!entries.isArray()||entries.size()!=1)throw new IllegalArgumentException();
            JsonNode metric=entries.get(0);
            exact(metric,"Namespace","acs_rds_dashboard"); exact(metric,"MetricName","DiskUsage"); exact(metric,"Unit","%");
            if(!csv(metric,"Dimensions").equals(Set.of("userId","instanceId"))
                    ||!csv(metric,"Statistics").equals(Set.of("Average","Minimum","Maximum"))
                    ||!csv(metric,"Periods").contains("60"))throw new IllegalArgumentException();
            exact(metrics,"Period","60");
            JsonNode points=parse(text(metrics,"Datapoints"));
            if(!points.isArray()||points.isEmpty()||points.size()>30)throw new IllegalArgumentException();
            var times=new HashSet<Long>(); Instant latest=null; BigDecimal maximum=null;
            for(JsonNode point:points) {
                exact(point,"userId",resource.accountId()); exact(point,"instanceId",resource.instanceId());
                JsonNode stamp=point.path("timestamp");
                if(!stamp.isIntegralNumber()||!stamp.canConvertToLong()||stamp.longValue()<0
                        ||stamp.longValue()%60000!=0||!times.add(stamp.longValue()))throw new IllegalArgumentException();
                Instant measured=Instant.ofEpochMilli(stamp.longValue());
                if(measured.isAfter(now)||measured.isBefore(now.minusSeconds(600)))throw new IllegalArgumentException();
                BigDecimal minimum=percent(point,"Minimum"),average=percent(point,"Average"),high=percent(point,"Maximum");
                if(minimum.compareTo(average)>0||average.compareTo(high)>0)throw new IllegalArgumentException();
                if(latest==null||measured.isAfter(latest)){latest=measured;maximum=high;}
            }
            if(latest.isBefore(now.minusSeconds(300)))throw new IllegalArgumentException();
            return new Sample(true,BigDecimal.valueOf(100).subtract(maximum),latest);
        } catch(RuntimeException invalid) { return Sample.unknown(); }
    }
    private static JsonNode parse(String value) {
        if(value==null||value.length()>262144||value.getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException();
        JsonNode result=JSON.readTree(value); if(result==null)throw new IllegalArgumentException(); return result;
    }
    private static void success(JsonNode value) {
        if(!value.path("Success").isBoolean()||!value.path("Success").booleanValue()
                ||!(value.path("Code").isTextual()&&"200".equals(value.path("Code").asText())
                ||value.path("Code").isIntegralNumber()&&value.path("Code").longValue()==200))throw new IllegalArgumentException();
    }
    private static String text(JsonNode value,String key) { if(!value.path(key).isTextual())throw new IllegalArgumentException(); return value.path(key).asText(); }
    private static void exact(JsonNode value,String key,String expected) { if(!expected.equals(text(value,key)))throw new IllegalArgumentException(); }
    private static Set<String> csv(JsonNode value,String key) {
        String raw=text(value,key);if(raw.length()>128)throw new IllegalArgumentException();
        List<String> parts=Arrays.stream(raw.split(",",-1)).map(String::trim).toList();
        Set<String> unique=new HashSet<>(parts);if(unique.size()!=parts.size()||unique.contains(""))throw new IllegalArgumentException();return unique;
    }
    private static BigDecimal percent(JsonNode value,String key) {
        JsonNode node=value.path(key);if(!node.isNumber())throw new IllegalArgumentException();
        BigDecimal number=node.decimalValue(); if(number.scale() < -3||number.scale()>8||number.precision()>32||number.signum()<0||number.compareTo(BigDecimal.valueOf(100))>0)throw new IllegalArgumentException(); return number;
    }
}
