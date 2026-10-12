package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Freezes exact input, dependency coverage and a complete request before a logical task queues. */
public final class GenerationFlowService {
    public enum Stage {
        NEWS_DEDUP(1), NEWS_VALUE(2), AGENT_DEDUP(3), SKILL_DEDUP(4),
        WORKFLOW_DEDUP(5), MODEL_DEDUP(6), AGENT_ANALYSIS(7), SKILL_ANALYSIS(8),
        WORKFLOW_ANALYSIS(9), MODEL_ANALYSIS(10), DAILY_REPORT(11),
        WEEKLY_REPORT(12), MONTHLY_REPORT(13), AD_HOC_AI(14);
        private final int priority;
        Stage(int priority) { this.priority = priority; }
        public int priority() { return priority; }
        public boolean report() { return priority >= 11 && priority <= 13; }
        public boolean analysis() { return priority >= 7 && priority <= 10; }
        public boolean dedup() { return priority == 1 || priority >= 3 && priority <= 6; }
    }
    public record Evidence(String id, String kind, LocalDate businessDate, Instant completedAt,
                           String bodyHash, String bodyJson, boolean effectiveValid,
                           boolean blocked, String projectionDecision, List<String> references) { }
    public record Dependency(String id, Stage stage, String state, Instant completedAt) { }
    public record Prepare(String logicalKey, long generation, Stage stage, LocalDate businessDate,
                          String sourceId, List<Dependency> dependencies, List<Evidence> evidence,
                          List<String> expectedDependencies, boolean explicitSupplement,
                          String settingsHash, FrozenApplicationContext applicationContext,
                          List<CollectionHandoffProofService.Proof> collectionProofs,long stagePart) {
        public Prepare {
            if(collectionProofs!=null)collectionProofs=List.copyOf(collectionProofs);
        }
        public Prepare(String logicalKey,long generation,Stage stage,LocalDate businessDate,String sourceId,
                       List<Dependency> dependencies,List<Evidence> evidence,List<String> expectedDependencies,
                       boolean explicitSupplement,String settingsHash,FrozenApplicationContext applicationContext,
                       List<CollectionHandoffProofService.Proof> collectionProofs) {
            this(logicalKey,generation,stage,businessDate,sourceId,dependencies,evidence,expectedDependencies,
                 explicitSupplement,settingsHash,applicationContext,collectionProofs,0);
        }
        public Prepare(String logicalKey,long generation,Stage stage,LocalDate businessDate,String sourceId,
                       List<Dependency> dependencies,List<Evidence> evidence,List<String> expectedDependencies,
                       boolean explicitSupplement,String settingsHash,FrozenApplicationContext applicationContext) {
            this(logicalKey,generation,stage,businessDate,sourceId,dependencies,evidence,expectedDependencies,
                 explicitSupplement,settingsHash,applicationContext,List.of());
        }
        public Prepare(String logicalKey,long generation,Stage stage,LocalDate businessDate,String sourceId,
                       List<Dependency> dependencies,List<Evidence> evidence,List<String> expectedDependencies,
                       boolean explicitSupplement,String settingsHash) {
            this(logicalKey,generation,stage,businessDate,sourceId,dependencies,evidence,expectedDependencies,
                 explicitSupplement,settingsHash,null);
        }
    }
    public record Frozen(String ownerId, String logicalKey, long generation, Stage stage,
                         Instant cutoff, String coverageState, List<String> missing,
                         String snapshotJson, String inputHash, String requestJson,
                         int priority, Instant eligibleAt, String state) { }
    public interface RequestBuilder {
        /** Returns full provider/model/messages/parameters/schema/prompt version, without a secret. */
        String build(Stage stage, String snapshotJson, String inputHash);
    }
    public interface InputEnricher {
        /** Database-only lookup in this writer transaction. Return only the required closed extension. */
        JsonNode fields(String rootId,Prepare command,Instant cutoff,JsonNode snapshot);
    }
    public interface SettingsSnapshotSource {
        /** Already mapped preferences, read once inside the shared writer transaction. */
        FrozenGenerationSettings freeze();
    }
    @FunctionalInterface public interface CollectionProofSource {
        /** Read the real collector handoff inside the original shared writer. */
        CollectionHandoffProofService.Proof read(String runId);
    }
    public interface FrozenStore {
        Frozen committed(String actionKey);
        /** Persist intent, immutable input, prepared attempt and queued root atomically. */
        void save(Frozen value, String actionKey);
    }
    public static final class InputRejected extends RuntimeException {
        public InputRejected() { super("generation input or frozen request rejected"); }
    }
    public static final class CapacityGuarded extends RuntimeException {
        public CapacityGuarded() { super("capacity_guard"); }
    }
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FINISHED = Set.of("succeeded", "complete", "partial", "empty_success");
    private final SharedWriteExecutor writes;
    private final FrozenStore store;
    private final RequestBuilder requests;
    private final Clock clock;
    private final java.util.function.BooleanSupplier preparationAllowed;
    private final ReportInputSnapshotService reportInputs;
    private final InputEnricher enricher;
    private final SettingsSnapshotSource settings;
    private final CollectionProofSource collections;

    public GenerationFlowService(SharedWriteExecutor writes, FrozenStore store,
                                 RequestBuilder requests, Clock clock) {
        this(writes,store,requests,clock,() -> false);
    }
    public GenerationFlowService(SharedWriteExecutor writes, FrozenStore store,
                                 RequestBuilder requests, Clock clock,
                                 java.util.function.BooleanSupplier preparationAllowed) {
        this(writes,store,requests,clock,(ReportInputSnapshotService)null,preparationAllowed,null);
    }

    /** New report runtime composition must install this provider. The older constructors
     * retain isolated legacy behavior and must not be used to enable report writers. */
    public GenerationFlowService(SharedWriteExecutor writes, FrozenStore store,
                                 RequestBuilder requests, Clock clock,
                                 java.util.function.BooleanSupplier preparationAllowed,
                                 ReportInputSnapshotService reportInputs) {
        this(writes,store,requests,clock,java.util.Objects.requireNonNull(reportInputs,
                "complete report input provider required"),preparationAllowed,null);
    }
    public GenerationFlowService(SharedWriteExecutor writes,FrozenStore store,RequestBuilder requests,Clock clock,
                                 java.util.function.BooleanSupplier preparationAllowed,ReportInputSnapshotService reportInputs,InputEnricher enricher) {
        this(writes,store,requests,clock,reportInputs,preparationAllowed,java.util.Objects.requireNonNull(enricher));
    }
    private GenerationFlowService(SharedWriteExecutor writes, FrozenStore store,
                                  RequestBuilder requests, Clock clock,
                                  ReportInputSnapshotService reportInputs,
                                  java.util.function.BooleanSupplier preparationAllowed,InputEnricher enricher) {
        this(writes,store,requests,clock,reportInputs,preparationAllowed,enricher,null);
    }
    /** Production input preparation captures real settings before hashing or request building. */
    public GenerationFlowService(SharedWriteExecutor writes,FrozenStore store,RequestBuilder requests,Clock clock,
                                 java.util.function.BooleanSupplier preparationAllowed,ReportInputSnapshotService reportInputs,
                                 InputEnricher enricher,SettingsSnapshotSource settings) {
        this(writes,store,requests,clock,reportInputs,preparationAllowed,
                java.util.Objects.requireNonNull(enricher),java.util.Objects.requireNonNull(settings));
    }
    private GenerationFlowService(SharedWriteExecutor writes,FrozenStore store,RequestBuilder requests,Clock clock,
                                  ReportInputSnapshotService reportInputs,java.util.function.BooleanSupplier preparationAllowed,
                                  InputEnricher enricher,SettingsSnapshotSource settings) {
        this(writes,store,requests,clock,reportInputs,preparationAllowed,enricher,settings,null);
    }
    /** Production dedup must install source-owned collection proof reads. */
    public GenerationFlowService(SharedWriteExecutor writes,FrozenStore store,RequestBuilder requests,Clock clock,
                                 java.util.function.BooleanSupplier preparationAllowed,ReportInputSnapshotService reportInputs,
                                 InputEnricher enricher,SettingsSnapshotSource settings,CollectionProofSource collections) {
        this(writes,store,requests,clock,reportInputs,preparationAllowed,java.util.Objects.requireNonNull(enricher),
                java.util.Objects.requireNonNull(settings),java.util.Objects.requireNonNull(collections));
    }
    private GenerationFlowService(SharedWriteExecutor writes,FrozenStore store,RequestBuilder requests,Clock clock,
                                  ReportInputSnapshotService reportInputs,java.util.function.BooleanSupplier preparationAllowed,
                                  InputEnricher enricher,SettingsSnapshotSource settings,CollectionProofSource collections) {
        if (writes == null || store == null || requests == null || clock == null || preparationAllowed == null)
            throw new IllegalArgumentException("generation dependencies required");
        this.writes = writes;
        this.store = store;
        this.requests = requests;
        this.clock = clock;
        this.preparationAllowed = preparationAllowed;
        this.reportInputs = reportInputs;
        this.enricher = enricher;
        this.settings = settings;
        this.collections = collections;
    }

    public Frozen prepare(Prepare command, ActionContext action) {
        validate(command);
        if(enricher!=null&&command.stage().report()&&reportInputs==null)throw new InputRejected();
        if(enricher!=null&&command.stage().dedup()&&collections==null)throw new InputRejected();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant cutoff = cutoff(command, now);
        return writes.execute(action, store::committed, context -> {
            if (!preparationAllowed.getAsBoolean()) throw new CapacityGuarded();
            CollectionInputProofs.verify(command,collections,enricher!=null&&command.stage().dedup());
            if (!ready(command, now)) {
                Frozen waiting = new Frozen(context.preallocatedResourceId(), command.logicalKey(),
                        command.generation(), command.stage(), cutoff, "pending", List.of(),
                        null, null, null, command.stage().priority(), nextEligible(now), "waiting_input");
                store.save(waiting, context.actionKey());
                return waiting;
            }
            if (command.stage().report() && reportInputs != null) {
                // Source lookup, selection, full request and persistence share this exact
                // transaction. No command-evidence fallback can bypass missing proof.
                var input = withApplicationContext(reportInputs.freeze(command, cutoff), command);
                if(enricher!=null) {
                    String snapshot=enrich(input.snapshotJson(),command,context.preallocatedResourceId(),cutoff);
                    input=new ReportInputSnapshotService.FrozenInput(snapshot,CanonicalJson.hash(Domain.INPUT,CanonicalJson.parse(snapshot)),input.coverageState(),input.missing(),input.hasContent(),input.state());
                }
                String request = null;
                if (input.hasContent()) {
                    request = requests.build(command.stage(), input.snapshotJson(), input.inputHash());
                    requireCompleteRequest(request, input.inputHash());
                    request = new String(CanonicalJson.bytes(CanonicalJson.parse(request)),StandardCharsets.UTF_8);
                }
                Frozen frozen = new Frozen(context.preallocatedResourceId(), command.logicalKey(),
                        command.generation(), command.stage(), cutoff, input.coverageState(),
                        input.missing(), input.snapshotJson(), input.inputHash(), request,
                        command.stage().priority(), nextEligible(now), input.state());
                store.save(frozen, context.actionKey());
                return frozen;
            }
            List<Evidence> selected = selected(command, cutoff);
            List<String> missing = missing(command, cutoff, selected);
            String coverage = missing.isEmpty() ? "complete" : "partial";
            boolean hasContent = selected.stream().anyMatch(item -> !"no_updates".equals(item.kind()));
            if (!hasContent && selected.isEmpty() && command.collectionProofs().isEmpty()) {
                String state = command.stage().report() ? "no_input" : "no_updates";
                Frozen empty = new Frozen(context.preallocatedResourceId(), command.logicalKey(),
                        command.generation(), command.stage(), cutoff, coverage, missing,
                        null, null, null, command.stage().priority(), nextEligible(now), state);
                store.save(empty, context.actionKey());
                return empty;
            }
            String snapshot = enrich(snapshot(command, cutoff, selected, missing, coverage),command,context.preallocatedResourceId(),cutoff);
            String inputHash = CanonicalJson.hash(Domain.INPUT, CanonicalJson.parse(snapshot));
            if (!hasContent) {
                String emptyState=selected.isEmpty()&&!command.collectionProofs().isEmpty()&&missing.isEmpty()?"no_updates":"coverage_only";
                Frozen coverageOnly = new Frozen(context.preallocatedResourceId(),
                        command.logicalKey(), command.generation(), command.stage(), cutoff,
                        coverage, missing, snapshot, inputHash, null,
                        command.stage().priority(), nextEligible(now), emptyState);
                store.save(coverageOnly, context.actionKey());
                return coverageOnly;
            }
            String request = requests.build(command.stage(), snapshot, inputHash);
            requireCompleteRequest(request, inputHash);
            request = new String(CanonicalJson.bytes(CanonicalJson.parse(request)),StandardCharsets.UTF_8);
            Frozen frozen = new Frozen(context.preallocatedResourceId(), command.logicalKey(),
                    command.generation(), command.stage(), cutoff, coverage, missing,
                    snapshot, inputHash, request, command.stage().priority(), nextEligible(now),
                    "queued");
            store.save(frozen, context.actionKey());
            return frozen;
        });
    }

    private String enrich(String snapshot,Prepare command,String owner,Instant cutoff) {
        if(enricher==null)return snapshot;
        try {
            var root=(ObjectNode)CanonicalJson.parse(snapshot);
            // A proved zero-accepted collection has no result applicability or member bindings.
            // Freeze its terminal evidence and settings without inventing a dummy member.
            boolean emptyCollection=command.stage().dedup()&&command.applicationContext()==null
                &&root.path("members").isArray()&&root.path("members").isEmpty()&&!command.collectionProofs().isEmpty()
                &&command.collectionProofs().stream().allMatch(p->p.facts().accepted()==0);
            if(!emptyCollection) {
                var context=FrozenApplicationContext.fromJson(root.get(FrozenApplicationContext.FIELD));
                if(!context.referencesComplete())throw new InputRejected();
                String field=command.stage().dedup()||command.stage()==Stage.NEWS_VALUE?FrozenMemberBindings.FIELD:
                    command.stage().analysis()||command.stage().report()?FrozenPublicMetadata.FIELD:null;
                Set<String> expected=field==null?Set.of():Set.of(field);
                // Provider sees a separate tree; it cannot mutate the original selected input.
                var fields=enricher.fields(owner,command,cutoff,CanonicalJson.parse(snapshot));
                com.lianxibu.site.domain.protocol.WireValues.object(fields,expected,expected);
                if(field!=null) {
                    if(root.has(field))throw new InputRejected();
                    root.set(field,fields.get(field));
                    if(field.equals(FrozenPublicMetadata.FIELD))FrozenPublicMetadata.read(root.get(field),context);
                    else FrozenMemberBindings.read(root,context.input(owner,"input-preparation",CanonicalJson.hash(Domain.INPUT,root),0));
                }
            }
            if(settings!=null) {
                FrozenGenerationSettings captured=settings.freeze();
                if(captured==null||root.has(FrozenGenerationSettings.FIELD)
                        ||!command.settingsHash().equals(captured.semanticHash()))throw new InputRejected();
                root.set(FrozenGenerationSettings.FIELD,captured.toJson());
            }
            return new String(CanonicalJson.bytes(root),StandardCharsets.UTF_8);
        }catch(RuntimeException invalid){throw new InputRejected();}
    }

    static boolean ready(Prepare value, Instant now) {
        Stage stage = value.stage();
        if (stage.report() && !value.explicitSupplement()
                && now.isBefore(plannedCutoff(value.businessDate()))) return false;
        if (stage == Stage.NEWS_VALUE || stage.analysis()) return barrierClosed(value, now);
        if (stage.dedup()&&!value.collectionProofs().isEmpty())return true;
        if (stage.dedup()) return value.dependencies().stream()
                .anyMatch(dep -> FINISHED.contains(dep.state()) || terminalFailure(dep.state()));
        return true;
    }
    private static boolean barrierClosed(Prepare value, Instant now) {
        Instant barrier = value.businessDate().plusDays(1).atTime(4, 30)
                .atZone(SHANGHAI).toInstant();
        if (!now.isBefore(barrier)) return true;
        List<Dependency> related = value.dependencies().stream()
                .filter(dep -> dependencyStage(value.stage(), dep.stage())).toList();
        Set<String> finished = related.stream().filter(dep -> FINISHED.contains(dep.state()))
                .map(Dependency::id).collect(Collectors.toSet());
        return !related.isEmpty() && related.stream().allMatch(dep -> FINISHED.contains(dep.state()))
                && finished.containsAll(value.expectedDependencies());
    }
    private static boolean dependencyStage(Stage stage, Stage upstream) {
        return switch (stage) {
            case NEWS_VALUE -> upstream == Stage.NEWS_DEDUP;
            case AGENT_ANALYSIS -> upstream == Stage.AGENT_DEDUP;
            case SKILL_ANALYSIS -> upstream == Stage.SKILL_DEDUP;
            case WORKFLOW_ANALYSIS -> upstream == Stage.WORKFLOW_DEDUP;
            case MODEL_ANALYSIS -> upstream == Stage.MODEL_DEDUP;
            default -> false;
        };
    }
    private static Instant cutoff(Prepare value, Instant now) {
        if (value.stage().report()) return value.explicitSupplement()
                ? now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                : plannedCutoff(value.businessDate());
        if ((value.stage() == Stage.NEWS_VALUE || value.stage().analysis())
                && !value.explicitSupplement()) {
            Instant barrier = value.businessDate().plusDays(1).atTime(4, 30)
                    .atZone(SHANGHAI).toInstant();
            return now.isBefore(barrier) ? now : barrier;
        }
        return now;
    }
    private static boolean terminalFailure(String state) {
        return Set.of("failed", "retry_exhausted", "cancelled", "blocked",
                "no_usable_input").contains(state);
    }
    private static Instant plannedCutoff(LocalDate day) {
        return day.plusDays(1).atTime(5, 0).atZone(SHANGHAI).toInstant();
    }
    private static List<String> missing(Prepare value, Instant cutoff, List<Evidence> selected) {
        Set<String> present = new HashSet<>();
        for (Dependency dep : value.dependencies()) {
            if (FINISHED.contains(dep.state()) && dep.completedAt() != null
                    && !dep.completedAt().isAfter(cutoff)) present.add(dep.id());
        }
        List<String> missing = new ArrayList<>();
        for(var proof:value.collectionProofs())if(Set.of("partial","no_usable_input").contains(proof.facts().deliveryState()))
            missing.add("collection:"+proof.facts().runId());
        for (String id : value.expectedDependencies()) if (!present.contains(id)) missing.add(id);
        Set<String> included = new HashSet<>();
        for (Evidence item : selected) included.add(item.id());
        for (Evidence item : value.evidence())
            if (item.businessDate() != null && belongs(value.stage(), value.businessDate(), item.businessDate())
                    && !included.contains(item.id())) missing.add(item.id());
        if (value.stage() == Stage.WEEKLY_REPORT || value.stage() == Stage.MONTHLY_REPORT) {
            LocalDate day = value.stage() == Stage.WEEKLY_REPORT
                    ? value.businessDate().minusDays(6) : value.businessDate().withDayOfMonth(1);
            Set<LocalDate> covered = new HashSet<>();
            for (Evidence item : selected) covered.add(item.businessDate());
            while (!day.isAfter(value.businessDate())) {
                if (!covered.contains(day)) missing.add("date:" + day);
                day = day.plusDays(1);
            }
        }
        missing.sort(String::compareTo);
        return missing.stream().distinct().toList();
    }
    private static List<Evidence> selected(Prepare value, Instant cutoff) {
        Map<String, Evidence> candidates = value.evidence().stream()
                .filter(item -> item.effectiveValid() && !item.blocked()
                        && (item.projectionDecision() == null
                            || Set.of("accepted", "promoted").contains(item.projectionDecision()))
                        && item.completedAt() != null && !item.completedAt().isAfter(cutoff)
                        && item.businessDate() != null
                        && belongs(value.stage(), value.businessDate(), item.businessDate()))
                .collect(Collectors.toMap(Evidence::id, item -> item));
        Set<String> available = new HashSet<>(candidates.keySet());
        boolean changed;
        do {
            changed = available.removeIf(id -> !available.containsAll(candidates.get(id).references()));
        } while (changed);
        return available.stream().map(candidates::get)
                .sorted(Comparator.comparing(Evidence::id)).toList();
    }
    private static boolean belongs(Stage stage, LocalDate business, LocalDate item) {
        return switch (stage) {
            case WEEKLY_REPORT -> !item.isBefore(business.minusDays(6)) && !item.isAfter(business);
            case MONTHLY_REPORT -> !item.isBefore(business.withDayOfMonth(1))
                    && !item.isAfter(business);
            default -> item.equals(business);
        };
    }
    private static String snapshot(Prepare command, Instant cutoff, List<Evidence> selected,
                                   List<String> missing, String coverage) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schema_version", "1");
        value.put("logical_key", command.logicalKey());
        value.put("generation", command.generation());
        if(command.stagePart()!=0)value.put("stage_part",command.stagePart());
        value.put("stage", command.stage().name().toLowerCase());
        value.put("business_date", command.businessDate().toString());
        value.put("cutoff", cutoff.toString());
        value.put("settings_hash", command.settingsHash());
        value.put("coverage_state", coverage);
        ArrayNode omissions = value.putArray("missing");
        for (String id : missing) omissions.add(id);
        ArrayNode members = value.putArray("members");
        for (Evidence item : selected) {
            ObjectNode member = members.addObject();
            member.put("id", item.id());
            member.put("kind", item.kind());
            member.put("business_date", item.businessDate().toString());
            member.put("completed_at", item.completedAt().toString());
            member.put("body_hash", item.bodyHash());
            member.set("body", CanonicalJson.parse(item.bodyJson()));
            ArrayNode refs = member.putArray("references");
            for (String ref : item.references()) refs.add(ref);
        }
        value.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(command.dependencies(),command.expectedDependencies()));
        if(!command.collectionProofs().isEmpty())value.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(command.collectionProofs()));
        if(command.applicationContext()!=null)value.set(FrozenApplicationContext.FIELD,command.applicationContext().toJson());
        return new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8);
    }
    private static ReportInputSnapshotService.FrozenInput withApplicationContext(ReportInputSnapshotService.FrozenInput input,Prepare command) {
        var value=(ObjectNode)CanonicalJson.parse(input.snapshotJson());
        value.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(command.dependencies(),command.expectedDependencies()));
        if(command.applicationContext()!=null)value.set(FrozenApplicationContext.FIELD,command.applicationContext().toJson());
        return new ReportInputSnapshotService.FrozenInput(new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8),
            CanonicalJson.hash(Domain.INPUT,value),input.coverageState(),input.missing(),input.hasContent(),input.state());
    }
    static Instant nextEligible(Instant now) {
        LocalDateTime local = LocalDateTime.ofInstant(now, SHANGHAI);
        if (!local.toLocalTime().isBefore(LocalTime.of(1, 0))
                && local.toLocalTime().isBefore(LocalTime.of(7, 0))) return now;
        LocalDate day = local.toLocalDate();
        if (!local.toLocalTime().isBefore(LocalTime.of(7, 0))) day = day.plusDays(1);
        return day.atTime(1, 0).atZone(SHANGHAI).toInstant();
    }
    private static void requireCompleteRequest(String json, String inputHash) {
        try {
            JsonNode request = CanonicalJson.parse(json);
            if (!request.isObject() || !nonblank(request, "provider")
                    || !nonblank(request, "model")
                    || !request.has("messages") || !request.get("messages").isArray()
                    || request.get("messages").isEmpty() || !request.has("response_format")
                    || !request.get("response_format").isObject()
                    || !request.has("parameters") || !request.get("parameters").isObject()
                    || !nonblank(request, "schema_version")
                    || !nonblank(request, "prompt_version")
                    || !inputHash.equals(request.path("input_hash").asText())
                    || secretField(request)) throw new InputRejected();
        } catch (RuntimeException invalid) {
            throw new InputRejected();
        }
    }
    private static boolean nonblank(JsonNode object, String field) {
        return object.has(field) && object.get(field).isTextual()
                && !object.get(field).asText().isBlank();
    }
    private static boolean secretField(JsonNode value) {
        if (value.isObject()) for (var field : value.properties()) {
            if (Set.of("api_key", "authorization", "secret", "password")
                    .contains(field.getKey().toLowerCase()) || secretField(field.getValue())) return true;
        }
        if (value.isArray()) for (JsonNode child : value) if (secretField(child)) return true;
        return false;
    }
    private static void validate(Prepare value) {
        if (value == null || value.stage() == null || value.businessDate() == null
                || value.logicalKey() == null || value.logicalKey().isBlank()
                || value.generation() < 0 || value.generation() > CanonicalJson.MAX_SAFE_INTEGER
                || value.stagePart()<0||value.stagePart()>=10000
                || value.stagePart()!=0&&!(value.stage().dedup()||value.stage()==Stage.NEWS_VALUE)
                || value.stage() == Stage.WEEKLY_REPORT
                    && value.businessDate().getDayOfWeek() != java.time.DayOfWeek.SUNDAY
                || value.stage() == Stage.MONTHLY_REPORT
                    && value.businessDate().getDayOfMonth() != value.businessDate().lengthOfMonth()
                || value.dependencies() == null || value.evidence() == null
                || value.expectedDependencies() == null || value.settingsHash() == null
                || !value.settingsHash().matches("[0-9a-f]{64}")
                || value.expectedDependencies().stream().anyMatch(id -> id == null || id.isBlank())
                || value.expectedDependencies().stream().distinct().count()
                    != value.expectedDependencies().size()
                || value.dependencies().stream().anyMatch(dep -> dep == null || dep.stage() == null
                    || dep.id() == null || dep.id().isBlank() || dep.state() == null)
                || value.evidence().stream().anyMatch(item -> item == null || item.id() == null
                    || item.id().isBlank() || item.kind() == null || item.kind().isBlank()
                    || item.references() == null
                    || item.references().stream().anyMatch(ref -> ref == null || ref.isBlank())
                    || item.bodyHash() == null || !item.bodyHash().matches("[0-9a-f]{64}"))
                || value.evidence().stream().map(Evidence::id).distinct().count()
                    != value.evidence().size()) throw new InputRejected();
        var context=value.applicationContext();
        CollectionInputProofs.validate(value);
        if(context!=null&&(context.stage()!=value.stage()||context.generation()!=value.generation()))throw new InputRejected();
        if(context!=null&&value.stage().report()) {
            LocalDate end=Instant.parse(context.periodEnd()).atZone(SHANGHAI).toLocalDate();
            if(!end.equals(value.businessDate().plusDays(1)))throw new InputRejected();
        }
    }
}
