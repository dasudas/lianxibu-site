package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.settings.OperationalRulePolicy.Compiled;
import com.lianxibu.site.domain.jobs.JavaVmOomTelemetry;
import com.lianxibu.site.domain.jobs.SourceContractFacts;
import com.lianxibu.site.domain.jobs.CollectorNodeFacts;
import com.lianxibu.site.domain.jobs.ApiRuntimeTelemetry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic transitions over durable samples; contains no external notification or cleanup. */
public final class OperationalRuleEngine {
    public enum Level { WARNING, CRITICAL }
    public enum Status { NORMAL, ACTIVE, RESOLVED, TELEMETRY_UNKNOWN, CONFIGURATION_UNKNOWN }
    public enum Guard { NEW_RUN, PREPARE_ANALYSIS, DISPATCH_AI, UPLOAD_OBSERVATION, NEW_FETCH, RETRY_MEMBER }
    public record Key(String ruleId, String component, String resourceKey) {
        public Key {
            if (ruleId == null || !ruleId.matches("OPS-(0[1-9]|10)") || component == null
                    || !component.matches("[a-z][a-z0-9_]{0,63}") || resourceKey == null
                    || resourceKey.isBlank() || resourceKey.codePointCount(0, resourceKey.length()) > 128)
                throw new IllegalArgumentException("invalid operational identity");
        }
    }
    public sealed interface Reading permits BacklogHistory, Backlog, Heartbeat, SlotRecovery, Capacity, Disk, Memory, JavaMemory, JavaMemoryEvents, JavaMemoryVmEvents,
            ContractFailures, SourceContracts, CollectorNode, CollectorMemory, Provider, ApiHealth, ApiWindow, Telemetry { }
    /** Eligible age must be reconstructed from durable ready/unpaused spans. */
    public record Backlog(long eligibleSeconds, long wallSeconds, boolean explicitlyDisposed) implements Reading { }
    public record BacklogHistory(com.lianxibu.site.domain.jobs.BacklogHistoryTelemetry.Evidence facts) implements Reading {
        @Override public String toString(){return "BacklogHistory["+BacklogHistoryProjection.value(facts)+"]";}
    }
    public record Heartbeat(long gapSeconds, boolean expected, boolean validHeartbeat, boolean reconciled) implements Reading { }
    public record SlotRecovery(long overdueSeconds, long consecutiveFailures, boolean scanSuccessful) implements Reading { }
    public record Capacity(BigDecimal freePercent, Instant measuredAt) implements Reading {
        public Capacity(BigDecimal freePercent) { this(freePercent,null); }
    }
    public record Disk(BigDecimal queueMib, BigDecimal freeMib) implements Reading { }
    /** Separate Java and collector resources; unavailable fields are not filled with zero. */
    public record Memory(boolean javaNode, BigDecimal measuredMib, boolean outOfMemory) implements Reading { }
    /** Complete qualified Java facts; the artifact hard limit is controlled by host assembly. */
    public record JavaMemory(BigDecimal availableMib, BigDecimal residentMib, BigDecimal hardLimitMib,
                             boolean outOfMemory) implements Reading { }
    /** Kernel local cumulative events joined to separately qualified host, RSS, budget and heap facts. */
    public record JavaMemoryEvents(JavaMemory memory, String resourceEpoch, long oomEvents, long oomKills,
                                   Instant receivedAt) implements Reading { }
    /** Native counters retain their original process lifetime independently of the kernel resource. */
    public record JavaMemoryVmEvents(JavaMemoryEvents kernel, JavaVmOomTelemetry.Evidence vm) implements Reading {
        // This private Reading projection participates in immutable command hashing, unlike diagnostic Evidence.toString().
        @Override public String toString() {
            return "JavaMemoryVmEvents[kernel=" + kernel + ",vm=" + (vm == null ? "null" :
                    vm.pid() + "/" + vm.startedAt() + "/" + vm.receivedAt() + "/" + vm.javaHeapEvents()
                            + "/" + vm.metaspaceEvents() + "/" + vm.classMetaspaceEvents()) + "]";
        }
    }
    public record ContractFailures(long newEvents, long eventsInWindow, boolean revalidated) implements Reading { }
    /** Native source evidence participates in immutable command hashing. */
    public record SourceContracts(SourceContractFacts.Evidence facts) implements Reading {
        @Override public String toString(){var e=facts;return e==null?"SourceContracts[null]":"SourceContracts["+e.sourceId()+"/"+e.configVersion()+"/"+e.resourceEpoch()+"/"+e.totalFailures()+"/"+e.eventsInWindow()+"/"+e.nodeCount()+"/"+e.observedAt()+"/"+e.snapshotReceivedAt()+"/"+e.readAt()+"/"+e.reviewedAt()+"]";}
    }

    /** Private immutable hash projection; diagnostic facts stay redacted. */
    public record CollectorNode(CollectorNodeFacts.Evidence facts) implements Reading {
        @Override public String toString(){var e=facts;return e==null?"CollectorNode[null]":"CollectorNode["+e.nodeId()+"/"+e.nodeRevision()+"/"+e.enabled()+"/"+e.expectedSince()+"/"+heartbeatValue(e.heartbeat())+"/"+e.pendingServerClaims()+"/"+e.readAt()+"]";}
    }

    public record CollectorMemory(com.lianxibu.site.domain.jobs.CollectorMemoryFacts.Evidence facts) implements Reading {
        @Override public String toString(){return "CollectorMemory["+CollectorMemoryProjection.value(facts)+"]";}
    }

    public record Provider(boolean explicitFailure, long unknownCount, long oldestUnknownSeconds,
                           boolean repairedAndValidated) implements Reading { }
    /** windowEndedAt identifies a complete, nonoverlapping rate window. */
    public record ApiHealth(long requests, BigDecimal errorPercent, BigDecimal lockP95Millis,
                            Instant windowEndedAt) implements Reading { }
    /** Private original window projection participates in immutable command hashing. */
    public record ApiWindow(ApiRuntimeTelemetry.Evidence facts) implements Reading {
        @Override public String toString(){var e=facts;return e==null?"ApiWindow[null]":"ApiWindow["+apiValue(e)+"/"+e.readAt()+"]";}
    }
    private static String apiValue(ApiRuntimeTelemetry.Evidence e){return e.processEpoch()+"/"+e.startedAt()+"/"+e.windowStart()+"/"+e.windowEnd()+"/"+e.requests()+"/"+e.serverErrors()+"/"+e.lockSamples()+"/"+e.lockWaitP95Nanos();}
    public record Telemetry(long metricAgeSeconds, long capacityAgeSeconds) implements Reading { }
    public record State(Key key, long revision, long configurationRevision, Status status, Level level,
                        Instant firstSeenAt, Instant lastSeenAt, Instant lastSampleAt,
                        String observedValue, String threshold, Map<String, Instant> timers,
                        Map<String, Long> counters, Set<Guard> guards, boolean acknowledged,
                        boolean recoveryReady, Instant nextReminderAt, Instant silencedUntil) {
        public State { timers = Map.copyOf(timers); counters = Map.copyOf(counters); guards = Set.copyOf(guards); }
        public static State empty(Key key) {
            return new State(key, 0, -1, Status.NORMAL, null, null, null, null, null, null,
                    Map.of(), Map.of(), Set.of(), false, false, null, null);
        }
    }
    public record Notice(String action, Level level, Instant at, String observedValue, String threshold) { }
    public record Transition(State state, List<Notice> notices) {
        public Transition { notices = List.copyOf(notices); }
    }
    private record Decision(Level severity, boolean healthy, boolean manualRecovery,
                            Set<Guard> requestedGuards, String observed, String threshold,
                            boolean verifiedProviderRepair) { }
    private static final Set<Guard> CAPACITY_GUARDS = Set.of(Guard.NEW_RUN, Guard.PREPARE_ANALYSIS,
            Guard.DISPATCH_AI, Guard.UPLOAD_OBSERVATION);

    public Transition sample(State previous, Compiled config, long configurationRevision,
                             Instant now, boolean available, Reading reading) {
        if (previous == null || now == null || configurationRevision < 0
                || now.getNano() != 0 || now.getEpochSecond() % 60 != 0
                || previous.lastSampleAt() != null && !now.isAfter(previous.lastSampleAt()))
            throw new IllegalArgumentException("invalid operational sample sequence");
        Map<String, Instant> timers = new HashMap<>(previous.timers());
        Map<String, Long> counters = new HashMap<>(previous.counters());
        boolean continuous = previous.lastSampleAt() != null
                && now.equals(previous.lastSampleAt().plusSeconds(60))
                && configurationRevision == previous.configurationRevision();
        if (!continuous) {
            timers.clear(); counters.clear();
            counters.putAll(durableWatermarks(previous));
            if ("OPS-08".equals(previous.key().ruleId()) && previous.guards().contains(Guard.DISPATCH_AI))
                counters.put("provider_failed", 1L);
        }
        if ("OPS-08".equals(previous.key().ruleId()) && previous.guards().contains(Guard.DISPATCH_AI))
            counters.put("provider_failed", 1L);
        if (config == null) return unknown(previous, configurationRevision, now, Status.CONFIGURATION_UNKNOWN);
        if (!previous.key().ruleId().equals(config.ruleId())) throw new IllegalArgumentException("rule identity mismatch");
        if (!available || reading == null) return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
        boolean partialBacklog=false, partialCollectorMemory=false;
        if("OPS-01".equals(previous.key().ruleId())){
            boolean required=previous.counters().containsKey("backlog_required");
            if(required&&!(reading instanceof BacklogHistory))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
            if(reading instanceof BacklogHistory nativeReading){
                var facts=nativeReading.facts();if(facts==null||facts.readAt().isBefore(now)||!facts.readAt().isBefore(now.plusSeconds(60)))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                try{var proof=BacklogHistoryProjection.join(facts,previous);if(proof.newEpoch())timers.clear();counters.putAll(proof.watermarks());partialBacklog=proof.partial();reading=proof.reading();}
                catch(RuntimeException unavailable){return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);}
            }
        }
        if (Set.of("OPS-02","OPS-05").contains(previous.key().ruleId())) {
            boolean required=previous.counters().containsKey("node_required");
            if(required&&!(reading instanceof CollectorNode))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
            if(reading instanceof CollectorNode nativeReading){
                var e=nativeReading.facts();var old=previous.counters();
                if(e==null||!previous.key().resourceKey().equals(e.nodeId())||e.readAt().isBefore(now)||!e.readAt().isBefore(now.plusSeconds(60)))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                long read=micros(e.readAt());var h=e.heartbeat();long history=h==null?0:h.historyRevision(),observed=h==null?0:micros(h.observedAt()),received=h==null?0:micros(h.receivedAt());String fingerprint=heartbeatHash(h);
                if(read<0||observed<0||received<0||required&&(!validNodeWatermarks(previous)||read<=old.get("node_read_micros")||e.nodeRevision()<old.get("node_revision")||history<old.get("node_history")||observed<old.get("node_observed_micros")||received<old.get("node_received_micros")||history==old.get("node_history")&&!fingerprint.equals(nodeFingerprint(previous))))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                boolean fresh=h!=null&&h.nodeRevision()==e.nodeRevision()&&java.time.Duration.between(h.observedAt(),e.readAt()).compareTo(java.time.Duration.ofSeconds(120))<=0&&java.time.Duration.between(h.receivedAt(),e.readAt()).compareTo(java.time.Duration.ofSeconds(120))<=0;
                boolean different=h!=null&&(!required||history>old.get("node_history"));
                if("OPS-05".equals(previous.key().ruleId())&&(!e.enabled()||!fresh))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                if(required&&e.nodeRevision()!=old.get("node_revision")){timers.clear();counters.remove("heartbeats");counters.remove("gap");}
                counters.put("node_required",1L);counters.put("node_revision",e.nodeRevision());counters.put("node_history",history);counters.put("node_observed_micros",observed);counters.put("node_received_micros",received);counters.put("node_read_micros",read);
                for(int word=0;word<8;word++)counters.put("node_f"+word,Long.parseUnsignedLong(fingerprint.substring(word*8,word*8+8),16));
                if("OPS-02".equals(previous.key().ruleId())){
                    Instant last=h==null?e.expectedSince():h.observedAt();long gap=java.time.Duration.between(last,e.readAt()).getSeconds();boolean reconciled=fresh&&h.pendingObservations()==0&&h.pendingControls()==0&&h.quarantined()==0&&e.pendingServerClaims()==0;
                    reading=new Heartbeat(gap,e.enabled(),e.enabled()&&fresh&&different,reconciled);
                }else reading=new Disk(mib(h.outboxBytes()),mib(h.freeBytes()));
            }
        }
        if("OPS-09".equals(previous.key().ruleId())){
            boolean required=previous.counters().containsKey("api_required");
            if(required&&!(reading instanceof ApiWindow))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
            if(reading instanceof ApiWindow nativeReading){
                var e=nativeReading.facts();if(e==null||config.seconds("rate_window_seconds")!=300||e.readAt().isBefore(now)||!e.readAt().isBefore(now.plusSeconds(60))||e.windowEnd().isAfter(now)||java.time.Duration.between(e.windowEnd(),e.readAt()).compareTo(java.time.Duration.ofSeconds(300))>=0)return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                long born=micros(e.startedAt()),start=micros(e.windowStart()),end=micros(e.windowEnd()),read=micros(e.readAt());var before=previous.counters();String epoch=e.processEpoch().replace("-",""),hash=apiHash(e);
                if(born<0||start<0||end<0||read<0||required&&!validApiWatermarks(previous)||required&&(read<=before.get("api_read_micros")||end<before.get("api_end_micros")))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                boolean same=required&&epoch.equals(apiWords(previous,"api_e",4));
                if(same&&(born!=before.get("api_born_micros")||end==before.get("api_end_micros")&&!hash.equals(apiWords(previous,"api_f",8))))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                if(required&&!same&&born<=before.get("api_read_micros"))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                if(required&&!same){timers.clear();counters.remove("rate_windows");}
                else if(same&&!timers.containsKey("rate_window_end"))timers.put("rate_window_end",Instant.ofEpochSecond(before.get("api_end_micros")/1000000));
                counters.put("api_required",1L);counters.put("api_born_micros",born);counters.put("api_start_micros",start);counters.put("api_end_micros",end);counters.put("api_read_micros",read);for(int word=0;word<4;word++)counters.put("api_e"+word,Long.parseUnsignedLong(epoch.substring(word*8,word*8+8),16));for(int word=0;word<8;word++)counters.put("api_f"+word,Long.parseUnsignedLong(hash.substring(word*8,word*8+8),16));
                BigDecimal percent=e.requests()==0?BigDecimal.ZERO:BigDecimal.valueOf(e.serverErrors()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(e.requests()),8,java.math.RoundingMode.UP);BigDecimal lock=BigDecimal.valueOf(e.lockWaitP95Nanos()).divide(BigDecimal.valueOf(1000000),8,java.math.RoundingMode.UP);reading=new ApiHealth(e.requests(),percent,lock,e.windowEnd());
            }
        }
        if ("OPS-07".equals(previous.key().ruleId())) {
            boolean required=previous.counters().containsKey("source_required");
            if(required&&!(reading instanceof SourceContracts))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
            if(reading instanceof SourceContracts nativeReading){
                var e=nativeReading.facts();
                if(e==null||!previous.key().resourceKey().equals(e.sourceId())||e.readAt().isBefore(now)||!e.readAt().isBefore(now.plusSeconds(60))||java.time.Duration.between(e.observedAt(),e.readAt()).compareTo(java.time.Duration.ofSeconds(120))>0||java.time.Duration.between(e.snapshotReceivedAt(),e.readAt()).compareTo(java.time.Duration.ofSeconds(120))>0)
                    return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                long read=micros(e.readAt()),reviewed=micros(e.reviewedAt());var old=previous.counters();
                if(read<0||reviewed<0||required&&!validSourceWatermarks(previous)||required&&(read<=old.get("source_read_micros")||e.configVersion()<old.get("source_config")))
                    return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                boolean same=required&&e.configVersion()==old.get("source_config")&&e.resourceEpoch().equals(sourceEpoch(previous));
                if(same&&e.totalFailures()<old.get("source_total"))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                long delta=same?e.totalFailures()-old.get("source_total"):e.totalFailures();
                if(delta>0&&e.eventsInWindow()==0)return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                long failedConfig=old.getOrDefault("source_failed_config",0L),failedAt=old.getOrDefault("source_failed_micros",0L);
                if(!required&&!previous.guards().isEmpty()){failedConfig=e.configVersion();failedAt=read;}
                if(delta>0||e.eventsInWindow()>=config.critical().longValueExact()){failedConfig=Math.max(failedConfig,e.configVersion());failedAt=Math.max(failedAt,read);}
                boolean revalidated=failedConfig>0&&e.configVersion()>failedConfig&&e.totalFailures()==0&&reviewed>failedAt;
                if(required&&!same)timers.clear();
                counters.put("source_required",1L);counters.put("source_config",e.configVersion());counters.put("source_total",e.totalFailures());counters.put("source_read_micros",read);counters.put("source_failed_config",failedConfig);counters.put("source_failed_micros",failedAt);
                for(int word=0;word<8;word++)counters.put("source_e"+word,Long.parseUnsignedLong(e.resourceEpoch().substring(word*8,word*8+8),16));
                reading=new ContractFailures(Math.min(delta,e.eventsInWindow()),e.eventsInWindow(),revalidated);
            }
        }
        if ("OPS-06".equals(previous.key().ruleId())) {
            boolean required=previous.counters().containsKey("cm_required");
            if(required && !(reading instanceof CollectorMemory))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
            if(reading instanceof CollectorMemory nativeReading){
                if(previous.counters().keySet().stream().anyMatch(name->name.startsWith("java_")))return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);
                try{var proof=CollectorMemoryProjection.join(nativeReading.facts(),previous,now);if(proof.reset())timers.clear();counters.putAll(proof.watermarks());partialCollectorMemory=proof.partial();reading=proof.reading();}
                catch(RuntimeException unavailable){return unknown(previous,configurationRevision,now,Status.TELEMETRY_UNKNOWN);}
            }
            boolean vmRequired = previous.counters().keySet().stream().anyMatch(name -> name.startsWith("java_vm_"));
            if (vmRequired && !(reading instanceof JavaMemoryVmEvents))
                return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
            if (reading instanceof JavaMemoryVmEvents combined) {
                var vm = combined.vm(); var kernel = combined.kernel();
                if (vm == null || kernel == null || kernel.memory() == null
                        || vm.receivedAt().isBefore(now) || !vm.receivedAt().isBefore(now.plusSeconds(60)))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                long born = micros(vm.startedAt()), received = micros(vm.receivedAt());
                if (born < 0 || received < 0 || vm.pid() > 9007199254740991L
                        || vmRequired && !validVmWatermarks(previous))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                if (vmRequired && received <= previous.counters().get("java_vm_received_micros"))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                boolean same = vmRequired && vm.pid() == previous.counters().get("java_vm_pid")
                        && born == previous.counters().get("java_vm_started_micros");
                if (same && (vm.javaHeapEvents() < previous.counters().get("java_vm_heap")
                        || vm.metaspaceEvents() < previous.counters().get("java_vm_meta")
                        || vm.classMetaspaceEvents() < previous.counters().get("java_vm_class_meta")))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                boolean newOom = same ? vm.javaHeapEvents() > previous.counters().get("java_vm_heap")
                        || vm.metaspaceEvents() > previous.counters().get("java_vm_meta")
                        || vm.classMetaspaceEvents() > previous.counters().get("java_vm_class_meta")
                        : vm.javaHeapEvents() > 0 || vm.metaspaceEvents() > 0 || vm.classMetaspaceEvents() > 0;
                if (vmRequired && !same) timers.clear();
                counters.put("java_vm_required", 1L); counters.put("java_vm_pid", vm.pid());
                counters.put("java_vm_started_micros", born); counters.put("java_vm_received_micros", received);
                counters.put("java_vm_heap", vm.javaHeapEvents()); counters.put("java_vm_meta", vm.metaspaceEvents());
                counters.put("java_vm_class_meta", vm.classMetaspaceEvents());
                var memory = kernel.memory();
                reading = new JavaMemoryEvents(new JavaMemory(memory.availableMib(), memory.residentMib(), memory.hardLimitMib(),
                        memory.outOfMemory() || newOom), kernel.resourceEpoch(), kernel.oomEvents(), kernel.oomKills(), kernel.receivedAt());
            }
            boolean kernelRequired = hasKernelWatermark(previous);
            if (kernelRequired && !(reading instanceof JavaMemoryEvents))
                return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
            if (reading instanceof JavaMemoryEvents events) {
                nonnegative(events.oomEvents(), events.oomKills());
                Instant received = events.receivedAt();
                if (events.memory() == null || events.resourceEpoch() == null || !events.resourceEpoch().matches("[0-9a-f]{64}")
                        || received == null || received.getNano() % 1000 != 0 || received.isBefore(now) || !received.isBefore(now.plusSeconds(60)))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                long receivedMicros = Math.addExact(Math.multiplyExact(received.getEpochSecond(), 1_000_000L), received.getNano() / 1000L);
                nonnegative(receivedMicros);
                String priorEpoch = kernelRequired ? cgroupEpoch(previous) : null;
                if (kernelRequired && (priorEpoch == null
                        || !Long.valueOf(1).equals(previous.counters().get("java_kernel_required"))
                        || !Long.valueOf(1).equals(previous.counters().get("java_rss_required"))
                        || !previous.counters().containsKey("java_kernel_oom") || !previous.counters().containsKey("java_kernel_kills")
                        || !previous.counters().containsKey("java_memory_received_micros")
                        || receivedMicros <= previous.counters().get("java_memory_received_micros")))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                boolean same = kernelRequired && events.resourceEpoch().equals(priorEpoch);
                if (same && (events.oomEvents() < previous.counters().get("java_kernel_oom")
                        || events.oomKills() < previous.counters().get("java_kernel_kills")))
                    return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
                boolean newOom = same ? events.oomEvents() > previous.counters().get("java_kernel_oom")
                        || events.oomKills() > previous.counters().get("java_kernel_kills") : events.oomEvents() > 0 || events.oomKills() > 0;
                if (kernelRequired && !same) timers.clear();
                counters.put("java_kernel_required", 1L);
                counters.put("java_kernel_oom", events.oomEvents()); counters.put("java_kernel_kills", events.oomKills());
                counters.put("java_memory_received_micros", receivedMicros);
                for (int word = 0; word < 8; word++)
                    counters.put("java_cgroup_e" + word, Long.parseLong(events.resourceEpoch().substring(word * 8, word * 8 + 8), 16));
                JavaMemory memory = events.memory();
                reading = new JavaMemory(memory.availableMib(), memory.residentMib(), memory.hardLimitMib(), memory.outOfMemory() || newOom);
            }
            if (previous.counters().containsKey("java_rss_required") && reading instanceof Memory)
                return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
            if (reading instanceof JavaMemory) counters.put("java_rss_required", 1L);
        }
        if ("OPS-04".equals(previous.key().ruleId()) && reading instanceof Capacity capacity) {
            Long last = previous.counters().get("capacity_measured_at");
            Instant measured = capacity.measuredAt();
            if (measured == null && last != null
                    || measured != null && (measured.getNano() != 0 || measured.getEpochSecond() < 0
                    || measured.getEpochSecond() % 60 != 0 || measured.isAfter(now)
                    || measured.isBefore(now.minusSeconds(300)) || last != null && measured.getEpochSecond() <= last))
                return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
            if (measured != null) {
                if (last != null && measured.getEpochSecond() != last + 60) timers.clear();
                counters.put("capacity_measured_at", measured.getEpochSecond());
                counters.put("capacity_verified_at", now.getEpochSecond());
            }
        }
        Decision decision = evaluate(config, reading, timers, counters, now);
        if(partialBacklog||partialCollectorMemory){timers.remove("healthy");decision=new Decision(decision.severity(),false,decision.manualRecovery(),decision.requestedGuards(),decision.observed(),decision.threshold(),decision.verifiedProviderRepair());}
        Set<Guard> guards = new java.util.HashSet<>(previous.guards());
        guards.addAll(decision.requestedGuards());
        if (decision.verifiedProviderRepair()) guards.remove(Guard.DISPATCH_AI);
        Level level = previous.level();
        Status status = previous.status();
        boolean acknowledged = previous.acknowledged(), recoveryReady = false;
        Instant first = previous.firstSeenAt(), reminder = previous.nextReminderAt();
        List<Notice> notices = new ArrayList<>();
        if (decision.severity() != null) {
            boolean firstTrigger = level == null;
            boolean upgrade = level == Level.WARNING && decision.severity() == Level.CRITICAL;
            status = Status.ACTIVE;
            if (firstTrigger || upgrade) {
                level = decision.severity(); acknowledged = false;
                if (firstTrigger) first = now;
                notices.add(new Notice(firstTrigger ? "trigger" : "upgrade", level, now, decision.observed(), decision.threshold()));
                reminder = now.plusSeconds(reminderSeconds(config, level));
            }
        } else if (decision.healthy()) {
            if (decision.manualRecovery() && !guards.isEmpty()) {
                recoveryReady = true; status = Status.ACTIVE;
            } else {
                guards.clear();
                if (level != null) notices.add(new Notice("resolved", level, now, decision.observed(), decision.threshold()));
                status = level == null ? Status.NORMAL : Status.RESOLVED;
                level = null; acknowledged = false; reminder = null;
            }
        } else if (level == null) status = Status.NORMAL;
        if (level != null && !acknowledged && notices.isEmpty() && reminder != null && !now.isBefore(reminder)
                && (previous.silencedUntil() == null || !now.isBefore(previous.silencedUntil()))) {
            notices.add(new Notice("reminder", level, now, decision.observed(), decision.threshold()));
            reminder = now.plusSeconds(reminderSeconds(config, level));
        }
        // Silence only same-level notification. Upgrades and recovery remain visible.
        if (previous.silencedUntil() != null && now.isBefore(previous.silencedUntil()))
            notices.removeIf(notice -> "reminder".equals(notice.action()));
        if(partialBacklog||partialCollectorMemory)status=Status.TELEMETRY_UNKNOWN;
        return new Transition(new State(previous.key(), previous.revision() + 1, configurationRevision,
                status, level, first, now, now, decision.observed(), decision.threshold(), timers, counters,
                guards, acknowledged, recoveryReady, reminder, previous.silencedUntil()), notices);
    }
    private Transition unknown(State previous, long configRevision, Instant now, Status status) {
        // Missing configuration or telemetry cannot release a guard or complete a window.
        return new Transition(new State(previous.key(), previous.revision() + 1, configRevision, status,
                previous.level(), previous.firstSeenAt(), now, now, null, previous.threshold(), Map.of(), durableWatermarks(previous),
                previous.guards(), previous.acknowledged(), false, previous.nextReminderAt(), previous.silencedUntil()), List.of());
    }
    private static Map<String, Long> durableWatermarks(State state) {
        Map<String, Long> watermark = new HashMap<>();
        if ("OPS-04".equals(state.key().ruleId())) {
            for (String name : List.of("capacity_measured_at", "capacity_verified_at"))
                if (state.counters().containsKey(name)) watermark.put(name, state.counters().get(name));
        }
        if ("OPS-06".equals(state.key().ruleId())) {
            state.counters().forEach((name,value)->{if(name.startsWith("cm_"))watermark.put(name,value);});
            for (String name : List.of("java_rss_required", "java_kernel_required", "java_kernel_oom", "java_kernel_kills", "java_memory_received_micros"))
                if (state.counters().containsKey(name)) watermark.put(name, state.counters().get(name));
            for (int word = 0; word < 8; word++) {
                String name = "java_cgroup_e" + word;
                if (state.counters().containsKey(name)) watermark.put(name, state.counters().get(name));
            }
            state.counters().forEach((name, value) -> { if (name.startsWith("java_vm_")) watermark.put(name, value); });
        }
        if(Set.of("OPS-02","OPS-05").contains(state.key().ruleId()))state.counters().forEach((name,value)->{if(name.startsWith("node_"))watermark.put(name,value);});
        if("OPS-07".equals(state.key().ruleId()))state.counters().forEach((name,value)->{if(name.startsWith("source_"))watermark.put(name,value);});
        if("OPS-01".equals(state.key().ruleId()))state.counters().forEach((name,value)->{if(name.startsWith("backlog_"))watermark.put(name,value);});
        if("OPS-09".equals(state.key().ruleId()))state.counters().forEach((name,value)->{if(name.startsWith("api_"))watermark.put(name,value);});
        return watermark;
    }
    private static String apiHash(ApiRuntimeTelemetry.Evidence e){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(apiValue(e).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("native API hash unavailable");}}
    private static String apiWords(State state,String prefix,int length){var result=new StringBuilder();for(int word=0;word<length;word++){Long value=state.counters().get(prefix+word);if(value==null||value<0||value>4294967295L)return null;result.append(String.format(java.util.Locale.ROOT,"%08x",value));}return result.toString();}
    private static boolean validApiWatermarks(State state){var c=state.counters();if(!Long.valueOf(1).equals(c.get("api_required")))return false;for(String key:List.of("api_born_micros","api_start_micros","api_end_micros","api_read_micros")){Long value=c.get(key);if(value==null||value<0||value>9007199254740991L)return false;}return c.get("api_start_micros")>=c.get("api_born_micros")&&c.get("api_end_micros")-c.get("api_start_micros")==300000000&&c.get("api_read_micros")>=c.get("api_end_micros")&&apiWords(state,"api_e",4)!=null&&apiWords(state,"api_f",8)!=null;}
    private static java.math.BigDecimal mib(long bytes){return java.math.BigDecimal.valueOf(bytes).divide(java.math.BigDecimal.valueOf(1L<<20),8,java.math.RoundingMode.DOWN);}
    private static String heartbeatValue(CollectorNodeFacts.Heartbeat h){return h==null?"null":h.nodeRevision()+"/"+h.historyRevision()+"/"+h.observedAt()+"/"+h.receivedAt()+"/"+h.pendingObservations()+"/"+h.pendingControls()+"/"+h.quarantined()+"/"+h.outboxBytes()+"/"+h.freeBytes()+"/"+h.oldestPendingAt();}
    private static String heartbeatHash(CollectorNodeFacts.Heartbeat h){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(heartbeatValue(h).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("native heartbeat hash unavailable");}}
    private static boolean validNodeWatermarks(State state){var c=state.counters();if(!Long.valueOf(1).equals(c.get("node_required")))return false;for(String key:List.of("node_revision","node_history","node_observed_micros","node_received_micros","node_read_micros")){Long value=c.get(key);if(value==null||value<0||value>9007199254740991L)return false;}return c.get("node_revision")>0&&nodeFingerprint(state)!=null;}
    private static String nodeFingerprint(State state){var value=new StringBuilder();for(int word=0;word<8;word++){Long part=state.counters().get("node_f"+word);if(part==null||part<0||part>4294967295L)return null;value.append(String.format(java.util.Locale.ROOT,"%08x",part));}return value.toString();}
    private static boolean validSourceWatermarks(State state){var values=state.counters();if(!Long.valueOf(1).equals(values.get("source_required")))return false;for(String key:List.of("source_config","source_total","source_read_micros","source_failed_config","source_failed_micros")){Long value=values.get(key);if(value==null||value<0||value>9007199254740991L)return false;}return values.get("source_config")>0&&sourceEpoch(state)!=null;}
    private static String sourceEpoch(State state){StringBuilder value=new StringBuilder();for(int word=0;word<8;word++){Long number=state.counters().get("source_e"+word);if(number==null||number<0||number>4294967295L)return null;value.append(String.format(java.util.Locale.ROOT,"%08x",number));}return value.toString();}
    private static long micros(Instant at) {
        try {
            long value = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1000L);
            return value >= 0 && value <= 9007199254740991L ? value : -1;
        } catch (ArithmeticException invalid) { return -1; }
    }
    private static boolean validVmWatermarks(State state) {
        var values = state.counters();
        if (!Long.valueOf(1).equals(values.get("java_vm_required"))
                || !Long.valueOf(1).equals(values.get("java_rss_required"))
                || !Long.valueOf(1).equals(values.get("java_kernel_required"))) return false;
        for (String name : List.of("java_vm_pid", "java_vm_started_micros", "java_vm_received_micros", "java_vm_heap", "java_vm_meta", "java_vm_class_meta")) {
            Long value = values.get(name);
            if (value == null || value < 0 || value > 9007199254740991L
                    || (name.equals("java_vm_heap") || name.equals("java_vm_meta") || name.equals("java_vm_class_meta")) && value > Integer.MAX_VALUE) return false;
        }
        return values.get("java_vm_pid") > 0 && values.get("java_vm_started_micros") <= values.get("java_vm_received_micros");
    }
    private static boolean hasKernelWatermark(State state) {
        return state.counters().keySet().stream().anyMatch(name -> name.startsWith("java_kernel_")
                || name.startsWith("java_cgroup_") || name.equals("java_memory_received_micros"));
    }
    private static String cgroupEpoch(State state) {
        var result = new StringBuilder(64);
        for (int word = 0; word < 8; word++) {
            Long value = state.counters().get("java_cgroup_e" + word);
            if (value == null || value < 0 || value > 0xffffffffL) return null;
            result.append(java.util.HexFormat.of().toHexDigits(value.intValue()));
        }
        return result.toString();
    }
    private static long reminderSeconds(Compiled config, Level level) {
        return config.seconds(level == Level.CRITICAL ? "critical_reminder_seconds" : "warning_reminder_seconds");
    }
    private Decision evaluate(Compiled c, Reading value, Map<String, Instant> timers,
                              Map<String, Long> counts, Instant now) {
        Level severity = null; boolean healthy = false, manual = false, repaired = false;
        Set<Guard> guards = Set.of(); BigDecimal observed, threshold = c.warning();
        switch (c.ruleId()) {
            case "OPS-01" -> {
                Backlog r = required(value, Backlog.class); nonnegative(r.eligibleSeconds(), r.wallSeconds());
                observed = number(r.eligibleSeconds());
                boolean warn = held(timers, "eligible_warning", observed.compareTo(c.warning()) >= 0, c.seconds("warning_hold_seconds"), now)
                        || number(r.wallSeconds()).compareTo(c.parameter("wall_warning_seconds")) >= 0;
                if (number(r.wallSeconds()).compareTo(c.critical()) >= 0) severity = Level.CRITICAL;
                else if (warn) severity = Level.WARNING;
                healthy = r.explicitlyDisposed() || held(timers, "healthy", observed.compareTo(c.recovery()) < 0
                        && number(r.wallSeconds()).compareTo(c.parameter("wall_warning_seconds")) < 0,
                        c.seconds("recovery_hold_seconds"), now);
                if (r.explicitlyDisposed()) severity = null;
                if (severity == Level.CRITICAL) { observed = number(r.wallSeconds()); threshold = c.critical(); }
            }
            case "OPS-02" -> {
                Heartbeat r = required(value, Heartbeat.class); nonnegative(r.gapSeconds()); observed = number(r.gapSeconds());
                boolean warn = consecutive(counts, "gap", r.expected() && observed.compareTo(c.warning()) > 0)
                        >= c.parameter("warning_sample_count").longValueExact();
                if (r.expected() && observed.compareTo(c.critical()) > 0) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (warn) severity = Level.WARNING;
                healthy = consecutive(counts, "heartbeats", r.validHeartbeat() && observed.compareTo(c.warning()) <= 0)
                        >= c.recovery().longValueExact() && r.reconciled();
            }
            case "OPS-03" -> {
                SlotRecovery r = required(value, SlotRecovery.class); nonnegative(r.overdueSeconds(), r.consecutiveFailures());
                observed = number(r.overdueSeconds());
                if (observed.compareTo(c.warning()) > 0 || number(r.consecutiveFailures()).compareTo(c.critical()) >= 0) {
                    severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI);
                    if (observed.compareTo(c.warning()) <= 0) { observed = number(r.consecutiveFailures()); threshold = c.critical(); }
                }
                healthy = held(timers, "healthy", r.scanSuccessful() && r.overdueSeconds() == 0
                        && r.consecutiveFailures() == 0, c.recovery().longValueExact(), now);
            }
            case "OPS-04" -> {
                Capacity r = required(value, Capacity.class); observed = bounded(r.freePercent(), "100");
                Instant measured = r.measuredAt() == null ? now : r.measuredAt();
                boolean critical = held(timers, "critical", observed.compareTo(c.critical()) < 0, c.seconds("critical_hold_seconds"), measured);
                boolean warning = held(timers, "warning", observed.compareTo(c.warning()) < 0, c.seconds("warning_hold_seconds"), measured);
                if (observed.compareTo(c.parameter("protect_free_percent")) < 0) {
                    severity = Level.CRITICAL; guards = CAPACITY_GUARDS; threshold = c.parameter("protect_free_percent");
                } else if (critical) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (warning) severity = Level.WARNING;
                healthy = held(timers, "healthy", observed.compareTo(c.recovery()) >= 0, c.seconds("recovery_hold_seconds"), measured);
                manual = true;
            }
            case "OPS-05" -> {
                Disk r = required(value, Disk.class); observed = bounded(r.queueMib(), "9007199254740991");
                BigDecimal free = bounded(r.freeMib(), "9007199254740991");
                boolean warning = held(timers, "warning", observed.compareTo(c.warning()) >= 0, c.seconds("warning_hold_seconds"), now);
                if (observed.compareTo(c.critical()) >= 0 || free.compareTo(c.parameter("disk_free_min_mib")) < 0) {
                    severity = Level.CRITICAL; guards = Set.of(Guard.NEW_FETCH);
                    if (free.compareTo(c.parameter("disk_free_min_mib")) < 0) { observed = free; threshold = c.parameter("disk_free_min_mib"); }
                    else threshold = c.critical();
                } else if (warning) severity = Level.WARNING;
                healthy = held(timers, "healthy", r.queueMib().compareTo(c.recovery()) < 0
                        && free.compareTo(c.parameter("recovery_disk_free_mib")) >= 0, c.seconds("recovery_hold_seconds"), now);
            }
            case "OPS-06" -> {
                if (value instanceof JavaMemory r) {
                    BigDecimal available = bounded(r.availableMib(), "9007199254740991");
                    BigDecimal rss = bounded(r.residentMib(), "9007199254740991");
                    BigDecimal limit = bounded(r.hardLimitMib(), "9007199254740991");
                    if (limit.signum() == 0) throw new IllegalArgumentException("positive artifact memory limit required");
                    observed = available;
                    if (held(timers, "warning", available.compareTo(c.warning()) < 0, c.seconds("java_warning_hold_seconds"), now)) {
                        severity = Level.WARNING; guards = Set.of(Guard.DISPATCH_AI);
                    }
                    if (rss.compareTo(limit) >= 0) {
                        severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI); observed = rss; threshold = limit;
                    }
                    if (r.outOfMemory()) {
                        severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI); observed = number(1); threshold = number(1);
                    }
                    healthy = held(timers, "healthy", !r.outOfMemory() && available.compareTo(c.recovery()) >= 0
                            && rss.compareTo(limit) < 0, c.seconds("recovery_hold_seconds"), now);
                    break;
                }
                Memory r = required(value, Memory.class); observed = bounded(r.measuredMib(), "9007199254740991");
                if (r.javaNode()) {
                    if (held(timers, "warning", observed.compareTo(c.warning()) < 0, c.seconds("java_warning_hold_seconds"), now)) {
                        severity = Level.WARNING; guards = Set.of(Guard.DISPATCH_AI);
                    }
                    if (r.outOfMemory()) { severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI); observed = number(1); threshold = number(1); }
                    healthy = held(timers, "healthy", !r.outOfMemory() && observed.compareTo(c.recovery()) >= 0, c.seconds("recovery_hold_seconds"), now);
                } else {
                    threshold = c.parameter("collector_warning_rss_mib");
                    if (r.outOfMemory() || observed.compareTo(c.critical()) >= 0) {
                        severity = Level.CRITICAL; guards = Set.of(Guard.NEW_FETCH); threshold = c.critical();
                        if (r.outOfMemory()) { observed = number(1); threshold = number(1); }
                    }
                    else if (held(timers, "warning", observed.compareTo(threshold) > 0, c.seconds("collector_warning_hold_seconds"), now)) severity = Level.WARNING;
                    healthy = held(timers, "healthy", !r.outOfMemory() && observed.compareTo(c.parameter("collector_recovery_rss_mib")) <= 0, c.seconds("recovery_hold_seconds"), now);
                }
            }
            case "OPS-07" -> {
                ContractFailures r = required(value, ContractFailures.class); nonnegative(r.newEvents(), r.eventsInWindow());
                if (r.newEvents() > r.eventsInWindow()) throw new IllegalArgumentException("invalid event window");
                observed = number(r.eventsInWindow());
                if (r.newEvents() > 0) { severity = Level.WARNING; guards = Set.of(Guard.RETRY_MEMBER); }
                if (observed.compareTo(c.critical()) >= 0) { severity = Level.CRITICAL; threshold = c.critical(); guards = Set.of(Guard.RETRY_MEMBER, Guard.NEW_FETCH); }
                healthy = held(timers, "healthy", r.newEvents() == 0, c.recovery().longValueExact(), now) && r.revalidated();
            }
            case "OPS-08" -> {
                Provider r = required(value, Provider.class); nonnegative(r.unknownCount(), r.oldestUnknownSeconds());
                observed = number(r.unknownCount());
                if (r.explicitFailure()) { severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI); observed = number(1); threshold = c.critical(); }
                else if (observed.compareTo(c.warning()) >= 0 || number(r.oldestUnknownSeconds()).compareTo(c.parameter("unknown_age_seconds")) >= 0)
                    severity = Level.WARNING;
                if (!r.explicitFailure() && number(r.oldestUnknownSeconds()).compareTo(c.parameter("unknown_age_seconds")) >= 0) {
                    observed = number(r.oldestUnknownSeconds()); threshold = c.parameter("unknown_age_seconds");
                }
                boolean blockedBefore = counts.getOrDefault("provider_failed", 0L) > 0;
                if (r.explicitFailure()) counts.put("provider_failed", 1L);
                if (!r.explicitFailure() && r.repairedAndValidated()) { counts.put("provider_failed", 0L); repaired = true; }
                healthy = !r.explicitFailure() && r.unknownCount() == 0 && (!blockedBefore || r.repairedAndValidated());
            }
            case "OPS-09" -> {
                ApiHealth r = required(value, ApiHealth.class); nonnegative(r.requests());
                observed = bounded(r.errorPercent(), "100"); BigDecimal lock = bounded(r.lockP95Millis(), "9007199254740991");
                long window = c.seconds("rate_window_seconds");
                if (r.windowEndedAt() == null || r.windowEndedAt().isAfter(now)
                        || r.windowEndedAt().isBefore(now.minusSeconds(window)) || r.windowEndedAt().getNano() != 0)
                    throw new IllegalArgumentException("invalid rate window");
                boolean requests = number(r.requests()).compareTo(c.parameter("minimum_requests")) >= 0;
                Instant lastWindow = timers.get("rate_window_end");
                if (lastWindow == null || r.windowEndedAt().isAfter(lastWindow)) {
                    if (lastWindow != null && !r.windowEndedAt().equals(lastWindow.plusSeconds(window))) counts.put("rate_windows", 0L);
                    consecutive(counts, "rate_windows", requests && observed.compareTo(c.warning()) > 0);
                    timers.put("rate_window_end", r.windowEndedAt());
                } else if (!lastWindow.equals(r.windowEndedAt())) throw new IllegalArgumentException("rate window rollback");
                boolean lockWarning = held(timers, "lock_warning", lock.compareTo(c.parameter("lock_wait_p95_ms")) > 0,
                        c.seconds("lock_warning_hold_seconds"), now);
                if (requests && observed.compareTo(c.critical()) >= 0) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (counts.getOrDefault("rate_windows", 0L) >= c.parameter("warning_window_count").longValueExact()) severity = Level.WARNING;
                else if (lockWarning) { severity = Level.WARNING; observed = lock; threshold = c.parameter("lock_wait_p95_ms"); }
                healthy = held(timers, "healthy", r.requests() > 0 && r.errorPercent().compareTo(c.recovery()) < 0
                        && lock.compareTo(c.parameter("lock_wait_p95_ms")) <= 0, c.seconds("recovery_hold_seconds"), now);
            }
            case "OPS-10" -> {
                Telemetry r = required(value, Telemetry.class); nonnegative(r.metricAgeSeconds(), r.capacityAgeSeconds()); observed = number(Math.max(r.metricAgeSeconds(), r.capacityAgeSeconds()));
                if (observed.compareTo(c.critical()) > 0) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (observed.compareTo(c.warning()) > 0) severity = Level.WARNING;
                if (number(r.capacityAgeSeconds()).compareTo(c.parameter("capacity_unknown_guard_seconds")) > 0) guards = CAPACITY_GUARDS;
                healthy = held(timers, "healthy", r.metricAgeSeconds() <= c.seconds("sample_interval_seconds") && r.capacityAgeSeconds() <= c.seconds("sample_interval_seconds"),
                        c.recovery().longValueExact(), now);
            }
            default -> throw new IllegalArgumentException("unsupported operational rule");
        }
        return new Decision(severity, healthy, manual, guards, observed.toPlainString(), threshold.toPlainString(), repaired);
    }
    private static boolean held(Map<String, Instant> timers, String key, boolean condition, long seconds, Instant now) {
        if (!condition) { timers.remove(key); return false; }
        Instant since = timers.computeIfAbsent(key, ignored -> now);
        return !now.isBefore(since.plusSeconds(seconds));
    }
    private static long consecutive(Map<String, Long> counters, String key, boolean condition) {
        long value = condition ? Math.min(1_000_000L, counters.getOrDefault(key, 0L) + 1) : 0;
        counters.put(key, value); return value;
    }
    private static BigDecimal number(long value) { return BigDecimal.valueOf(value); }
    private static void nonnegative(long... values) { for (long value : values) if (value < 0 || value > 9007199254740991L) throw new IllegalArgumentException("invalid metric"); }
    private static BigDecimal bounded(BigDecimal value, String max) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal(max)) > 0 || value.scale() > 8)
            throw new IllegalArgumentException("invalid metric");
        return value;
    }
    private static <T> T required(Reading reading, Class<T> type) {
        if (!type.isInstance(reading)) throw new IllegalArgumentException("reading rule mismatch"); return type.cast(reading);
    }
}

