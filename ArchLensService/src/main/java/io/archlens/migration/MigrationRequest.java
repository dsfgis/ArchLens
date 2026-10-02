package io.archlens.migration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** Immutable v1 migration input. References are registry IDs; credential values and source contents never belong here. */
public record MigrationRequest(String schemaVersion, AnalysisMode analysisMode, String objective,
                               List<SourceRef> sourceRefs, TargetEnvironment targetEnvironment,
                               List<Condition> constraints, List<Condition> invariants,
                               AcceptanceScope acceptanceScope, PolicyRefs policyRefs, Budget budget,
                               List<String> unresolvedFields) {
    public static final String VERSION = "archlens.migration-request.v1";
    public enum AnalysisMode { CODE_ONLY, DATABASE_ONLY, JOINT }
    public enum SourceKind { LOCAL_CODE, GIT_CODE, BUSINESS_DATABASE, OFFLINE_SCHEMA }
    public enum Level { A0, A1, A2 }
    public enum ValidationLevel { V1, V2, V3 }
    public enum DataPolicy { SUMMARY_ONLY, SCOPED_SEMANTICS, SCOPED_SNIPPETS }
    public enum ExecutionPolicy { READ_ONLY, ISOLATED_VALIDATION }

    public MigrationRequest {
        require(VERSION.equals(schemaVersion), "MIG_SCHEMA_UNSUPPORTED", "Unsupported migration request version");
        require(analysisMode != null, "MIG_MODE_REQUIRED", "Analysis mode is required");
        objective = text(objective, 5, 4000, "MIG_OBJECTIVE_INVALID");
        sourceRefs = copy(sourceRefs, 1, 64, "MIG_SOURCE_REQUIRED");
        constraints = copy(constraints, 0, 64, "MIG_CONSTRAINT_INVALID");
        invariants = copy(invariants, 0, 64, "MIG_INVARIANT_INVALID");
        unresolvedFields = strings(unresolvedFields, 0, 64, "MIG_UNRESOLVED_INVALID");
        require(targetEnvironment != null && acceptanceScope != null && policyRefs != null && budget != null,
                "MIG_REQUEST_INCOMPLETE", "Target, acceptance scope, policies and budget are required");
        unique(sourceRefs.stream().map(SourceRef::sourceRef).toList(), "MIG_SOURCE_DUPLICATE");
        unique(constraints.stream().map(Condition::id).toList(), "MIG_CONSTRAINT_DUPLICATE");
        unique(invariants.stream().map(Condition::id).toList(), "MIG_INVARIANT_DUPLICATE");
        Set<String> sourceIds = new HashSet<>();
        sourceRefs.forEach(s -> sourceIds.add(s.sourceRef()));
        boolean code = sourceRefs.stream().anyMatch(s -> s.kind() == SourceKind.LOCAL_CODE || s.kind() == SourceKind.GIT_CODE);
        boolean database = sourceRefs.stream().anyMatch(s -> s.kind() == SourceKind.BUSINESS_DATABASE || s.kind() == SourceKind.OFFLINE_SCHEMA);
        require(analysisMode != AnalysisMode.CODE_ONLY || code, "MIG_CODE_SOURCE_REQUIRED", "Code analysis requires a code source");
        require(analysisMode != AnalysisMode.DATABASE_ONLY || database, "MIG_DATABASE_SOURCE_REQUIRED", "Database analysis requires a database or offline schema");
        require(analysisMode != AnalysisMode.JOINT || code && database, "MIG_JOINT_SOURCES_REQUIRED", "Joint analysis requires both source types");
        require(sourceIds.containsAll(acceptanceScope.requiredSourceRefs()), "MIG_SCOPE_SOURCE_UNKNOWN", "Acceptance scope references an absent source");
        Set<String> requiredIds = new HashSet<>(acceptanceScope.requiredSourceRefs());
        require(analysisMode != AnalysisMode.CODE_ONLY || sourceRefs.stream().anyMatch(s -> requiredIds.contains(s.sourceRef())
                        && (s.kind() == SourceKind.LOCAL_CODE || s.kind() == SourceKind.GIT_CODE)),
                "MIG_SCOPE_SOURCE_REQUIRED", "Code source must be part of the acceptance scope");
        require(analysisMode != AnalysisMode.DATABASE_ONLY || sourceRefs.stream().anyMatch(s -> requiredIds.contains(s.sourceRef())
                        && (s.kind() == SourceKind.BUSINESS_DATABASE || s.kind() == SourceKind.OFFLINE_SCHEMA)),
                "MIG_SCOPE_SOURCE_REQUIRED", "Database source must be part of the acceptance scope");
        require(analysisMode != AnalysisMode.JOINT || sourceRefs.stream().anyMatch(s -> requiredIds.contains(s.sourceRef())
                        && (s.kind() == SourceKind.LOCAL_CODE || s.kind() == SourceKind.GIT_CODE))
                        && sourceRefs.stream().anyMatch(s -> requiredIds.contains(s.sourceRef())
                        && (s.kind() == SourceKind.BUSINESS_DATABASE || s.kind() == SourceKind.OFFLINE_SCHEMA)),
                "MIG_SCOPE_SOURCE_REQUIRED", "Both source types must be in the joint acceptance scope");
        require(constraints.stream().map(Condition::id).collect(java.util.stream.Collectors.toSet()).containsAll(acceptanceScope.requiredConstraintIds()),
                "MIG_SCOPE_CONSTRAINT_UNKNOWN", "Acceptance scope references an absent constraint");
        require(invariants.stream().map(Condition::id).collect(java.util.stream.Collectors.toSet()).containsAll(acceptanceScope.requiredInvariantIds()),
                "MIG_SCOPE_INVARIANT_UNKNOWN", "Acceptance scope references an absent invariant");
        require(acceptanceScope.level() == Level.A0 || acceptanceScope.requiredValidationLevels().contains(ValidationLevel.V1),
                "MIG_DESIGN_REQUIRES_V1", "Design scopes require structural evidence validation");
        require(acceptanceScope.level() != Level.A2 || acceptanceScope.requiredValidationLevels().contains(ValidationLevel.V3),
                "MIG_A2_REQUIRES_V3", "A2 requires execution validation");
        require(!acceptanceScope.requiredValidationLevels().contains(ValidationLevel.V3)
                        || policyRefs.executionPolicy() == ExecutionPolicy.ISOLATED_VALIDATION,
                "MIG_VALIDATION_POLICY_CONFLICT", "V3 requires isolated validation authorization");
        require(sourceRefs.stream().filter(s -> s.kind() == SourceKind.BUSINESS_DATABASE).allMatch(s -> s.credentialRef() != null),
                "MIG_CREDENTIAL_REF_REQUIRED", "Online business databases require a credential reference");
    }

    public record SourceRef(String sourceRef, SourceKind kind, String locatorRef, @JsonProperty(required = true) String credentialRef,
                            @JsonProperty(required = true) String declaredProduct,
                            @JsonProperty(required = true) String declaredVersion, Boolean offlineUnverified) {
        public SourceRef {
            sourceRef = id(sourceRef, "MIG_SOURCE_INVALID");
            require(kind != null, "MIG_SOURCE_INVALID", "Source kind is required");
            locatorRef = id(locatorRef, "MIG_LOCATOR_INVALID");
            if (credentialRef != null) credentialRef = id(credentialRef, "MIG_CREDENTIAL_REF_INVALID");
            declaredProduct = optional(declaredProduct, 100, "MIG_SOURCE_INVALID");
            declaredVersion = optional(declaredVersion, 100, "MIG_SOURCE_INVALID");
            require(offlineUnverified != null && (kind == SourceKind.OFFLINE_SCHEMA) == offlineUnverified,
                    "MIG_OFFLINE_MARKER_INVALID", "Offline schemas must be marked unverified; live sources must not");
            require(kind != SourceKind.OFFLINE_SCHEMA || credentialRef == null,
                    "MIG_OFFLINE_CREDENTIAL_INVALID", "Offline schema cannot carry a credential reference");
        }
    }
    public record Technology(String product, @JsonProperty(required = true) String version,
                             @JsonProperty(required = true) String compatibilityMode) {
        public Technology {
            product = text(product, 1, 100, "MIG_TARGET_INVALID");
            version = optional(version, 100, "MIG_TARGET_INVALID");
            compatibilityMode = optional(compatibilityMode, 100, "MIG_TARGET_INVALID");
        }
    }
    public record TargetEnvironment(@JsonProperty(required = true) Technology runtime,
                                    @JsonProperty(required = true) Technology database,
                                    @JsonProperty(required = true) String operatingSystem,
                                    @JsonProperty(required = true) String architecture) {
        public TargetEnvironment {
            operatingSystem = optional(operatingSystem, 100, "MIG_TARGET_INVALID");
            architecture = optional(architecture, 100, "MIG_TARGET_INVALID");
        }
    }
    public record Condition(String id, String description) {
        public Condition {
            id = MigrationRequest.id(id, "MIG_CONDITION_INVALID");
            description = text(description, 1, 1000, "MIG_CONDITION_INVALID");
        }
    }
    public record AcceptedUnknown(String id, String impact, String confirmedBy) {
        public AcceptedUnknown {
            id = MigrationRequest.id(id, "MIG_UNKNOWN_INVALID");
            impact = text(impact, 1, 1000, "MIG_UNKNOWN_INVALID");
            confirmedBy = MigrationRequest.id(confirmedBy, "MIG_UNKNOWN_CONFIRMATION_REQUIRED");
        }
    }
    public record AcceptanceScope(Level level, List<String> requiredSourceRefs, List<String> requiredSubjects,
                                  List<String> requiredPaths, List<String> requiredConstraintIds,
                                  List<String> requiredInvariantIds, List<ValidationLevel> requiredValidationLevels,
                                  List<AcceptedUnknown> acceptedUnknowns) {
        public AcceptanceScope {
            require(level != null, "MIG_SCOPE_INVALID", "Acceptance level is required");
            requiredSourceRefs = ids(requiredSourceRefs, 1, 64, "MIG_SCOPE_INVALID");
            requiredSubjects = strings(requiredSubjects, 0, 256, "MIG_SCOPE_INVALID");
            requiredPaths = strings(requiredPaths, 0, 64, "MIG_SCOPE_INVALID");
            requiredConstraintIds = ids(requiredConstraintIds, 0, 64, "MIG_SCOPE_INVALID");
            requiredInvariantIds = ids(requiredInvariantIds, 0, 64, "MIG_SCOPE_INVALID");
            requiredValidationLevels = copy(requiredValidationLevels, 0, 3, "MIG_SCOPE_INVALID");
            acceptedUnknowns = copy(acceptedUnknowns, 0, 64, "MIG_SCOPE_INVALID");
            unique(requiredValidationLevels, "MIG_SCOPE_DUPLICATE");
            unique(acceptedUnknowns.stream().map(AcceptedUnknown::id).toList(), "MIG_SCOPE_DUPLICATE");
        }
    }
    public record PolicyRefs(String authorizationRef, @JsonProperty(required = true) String modelConfigRef, DataPolicy dataPolicy,
                             ExecutionPolicy executionPolicy, List<String> allowedTools) {
        public PolicyRefs {
            authorizationRef = id(authorizationRef, "MIG_AUTHORIZATION_REQUIRED");
            if (modelConfigRef != null) modelConfigRef = id(modelConfigRef, "MIG_MODEL_REF_INVALID");
            require(dataPolicy != null && executionPolicy != null, "MIG_POLICY_INVALID", "Data and execution policies are required");
            allowedTools = ids(allowedTools, 0, 64, "MIG_POLICY_INVALID");
        }
    }
    public record Budget(int maxToolCalls, int maxModelCalls, long maxElapsedMillis) {
        public Budget {
            require(maxToolCalls >= 1 && maxToolCalls <= 1000 && maxModelCalls >= 0 && maxModelCalls <= 200
                            && maxElapsedMillis >= 1000 && maxElapsedMillis <= 86_400_000,
                    "MIG_BUDGET_INVALID", "Migration budget exceeds supported limits");
        }
    }

    private static String id(String value, String code) {
        require(value != null && value.matches("[A-Za-z][A-Za-z0-9._:-]{0,99}"), code, "Invalid registry or object ID");
        return value;
    }
    private static String text(String value, int min, int max, String code) {
        require(value != null && !value.isBlank() && value.length() >= min && value.length() <= max, code, "Text outside permitted bounds");
        return value;
    }
    private static String optional(String value, int max, String code) {
        return value == null ? null : text(value, 1, max, code);
    }
    private static <T> List<T> copy(List<T> values, int min, int max, String code) {
        require(values != null && values.size() >= min && values.size() <= max && values.stream().noneMatch(Objects::isNull),
                code, "List missing, too large or contains null");
        return List.copyOf(values);
    }
    private static List<String> strings(List<String> values, int min, int max, String code) {
        return copy(values, min, max, code).stream().map(v -> text(v, 1, 500, code)).toList();
    }
    private static List<String> ids(List<String> values, int min, int max, String code) {
        List<String> result = copy(values, min, max, code).stream().map(v -> id(v, code)).toList();
        unique(result, "MIG_SCOPE_DUPLICATE");
        return result;
    }
    private static <T> void unique(List<T> values, String code) {
        require(new HashSet<>(values).size() == values.size(), code, "Duplicate identifiers are not allowed");
    }
}
