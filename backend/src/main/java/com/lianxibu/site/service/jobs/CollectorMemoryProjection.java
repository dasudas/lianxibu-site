package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.CollectorMemoryFacts;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Original collector lifetimes and counters; a replay cannot complete recovery. */
final class CollectorMemoryProjection {
    record Result(Memory reading, boolean partial, boolean reset, Map<String,Long> watermarks) {}
    private static final long MAX=9007199254740991L;
    private static final List<String> NUMBERS=List.of("cm_node","cm_history","cm_read_micros",
            "cm_observed_micros","cm_received_micros","cm_started_micros","cm_sequence","cm_pid","cm_oom","cm_kills");

    static Result join(CollectorMemoryFacts.Evidence e, State previous, Instant now) {
        if(e==null || e.snapshot()==null || !e.enabled() || !"OPS-06".equals(previous.key().ruleId())
                || !"collector".equals(previous.key().component()) || !e.nodeId().equals(previous.key().resourceKey())
                || e.readAt().isBefore(now) || !e.readAt().isBefore(now.plusSeconds(60))) throw invalid();
        var s=e.snapshot();
        if(Duration.between(s.observedAt(),e.readAt()).compareTo(Duration.ofSeconds(120))>0
                || Duration.between(e.receivedAt(),e.readAt()).compareTo(Duration.ofSeconds(120))>0) throw invalid();
        long read=micros(e.readAt()), observed=micros(s.observedAt()), received=micros(e.receivedAt()), born=micros(s.captureStartedAt());
        var old=previous.counters(); boolean required=old.containsKey("cm_required"), reset=false;
        boolean sameGroup=false;
        if(required) {
            validate(old);
            boolean sameCapture=s.captureEpoch().replace("-","").equals(words(old,"cm_e",4));
            sameGroup=s.cgroupEpoch().equals(words(old,"cm_g",8));
            if(e.nodeRevision()<old.get("cm_node") || e.historyRevision()<=old.get("cm_history")
                    || read<=old.get("cm_read_micros") || observed<=old.get("cm_observed_micros")
                    || received<old.get("cm_received_micros")) throw invalid();
            if(sameCapture && (born!=old.get("cm_started_micros") || s.pid()!=old.get("cm_pid")
                    || !s.processIdentity().equals(words(old,"cm_p",8)) || s.sequence()<=old.get("cm_sequence"))) throw invalid();
            if(!sameCapture && born<=old.get("cm_observed_micros")) throw invalid();
            if(sameGroup && (s.oomEvents()<old.get("cm_oom") || s.oomKills()<old.get("cm_kills"))) throw invalid();
            reset=!sameCapture || !sameGroup || e.nodeRevision()!=old.get("cm_node")
                    || observed-old.get("cm_observed_micros")>120_000_000L;
        }
        boolean newOom=sameGroup ? s.oomEvents()>old.get("cm_oom") || s.oomKills()>old.get("cm_kills")
                : s.oomEvents()>0 || s.oomKills()>0;
        var w=new HashMap<String,Long>();
        w.put("cm_required",1L); w.put("cm_node",e.nodeRevision()); w.put("cm_history",e.historyRevision());
        w.put("cm_read_micros",read); w.put("cm_observed_micros",observed); w.put("cm_received_micros",received);
        w.put("cm_started_micros",born); w.put("cm_sequence",s.sequence()); w.put("cm_pid",s.pid());
        w.put("cm_oom",s.oomEvents()); w.put("cm_kills",s.oomKills());
        put(w,"cm_e",s.captureEpoch().replace("-","")); put(w,"cm_p",s.processIdentity()); put(w,"cm_g",s.cgroupEpoch());
        boolean partial=s.maximumBytes()==null || s.maximumBytes()!=e.expectedMaximumBytes();
        return new Result(new Memory(false,BigDecimal.valueOf(s.rssBytes()).divide(BigDecimal.valueOf(1L<<20),8,java.math.RoundingMode.DOWN),newOom),partial,reset,Map.copyOf(w));
    }

    static String value(CollectorMemoryFacts.Evidence e) {
        if(e==null)return "null";
        var v=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();
        v.put("node",e.nodeId());v.put("node_revision",e.nodeRevision());v.put("enabled",e.enabled());
        v.put("expected_maximum_bytes",e.expectedMaximumBytes());v.put("history_revision",e.historyRevision());v.put("read",e.readAt().toString());
        if(e.snapshot()==null){v.putNull("snapshot");v.putNull("received");}
        else {
            var s=e.snapshot();var n=v.putObject("snapshot");n.put("capture_epoch",s.captureEpoch());n.put("process_identity",s.processIdentity());
            n.put("capture_started_at",s.captureStartedAt().toString());n.put("sequence",s.sequence());n.put("pid",s.pid());n.put("observed_at",s.observedAt().toString());
            n.put("rss_bytes",s.rssBytes());n.put("cgroup_epoch",s.cgroupEpoch());if(s.maximumBytes()==null)n.putNull("maximum_bytes");else n.put("maximum_bytes",s.maximumBytes());
            n.put("oom_events",s.oomEvents());n.put("oom_kills",s.oomKills());v.put("received",e.receivedAt().toString());
        }
        return v.toString();
    }

    private static void validate(Map<String,Long> w) {
        if(!Long.valueOf(1).equals(w.get("cm_required")))throw invalid();
        for(String key:NUMBERS){Long v=w.get(key);if(v==null||v<0||v>MAX)throw invalid();}
        for(String key:List.of("cm_node","cm_history","cm_sequence","cm_pid"))if(w.get(key)==0)throw invalid();
        if(w.get("cm_read_micros")<w.get("cm_received_micros") || w.get("cm_received_micros")<w.get("cm_observed_micros")
                || w.get("cm_observed_micros")<w.get("cm_started_micros") || words(w,"cm_e",4)==null
                || words(w,"cm_p",8)==null || words(w,"cm_g",8)==null)throw invalid();
    }
    private static void put(Map<String,Long> w,String prefix,String hex){for(int i=0;i<hex.length()/8;i++)w.put(prefix+i,Long.parseUnsignedLong(hex.substring(i*8,i*8+8),16));}
    private static String words(Map<String,Long> w,String prefix,int count){var out=new StringBuilder();for(int i=0;i<count;i++){Long v=w.get(prefix+i);if(v==null||v<0||v>4294967295L)return null;out.append(String.format(Locale.ROOT,"%08x",v));}return out.toString();}
    private static long micros(Instant at){long v=Math.addExact(Math.multiplyExact(at.getEpochSecond(),1_000_000L),at.getNano()/1000);if(v<0||v>MAX)throw invalid();return v;}
    private static IllegalStateException invalid(){return new IllegalStateException("native collector memory proof unavailable");}
}
