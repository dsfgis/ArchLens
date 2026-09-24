package io.archlens.investigation;

import java.nio.file.Path;
import java.util.*;
import static io.archlens.contract.ContractException.require;
import static io.archlens.contract.Model.text;

/** Credentials and executable operations are deliberately absent from this contract. */
public record InvestigationRequest(String schemaVersion, Scenario scenario, Profile sourceProfile,
        Profile targetProfile, String objective, List<String> constraints, List<String> invariants,
        List<String> files, String columnRequest, Budget budget) {
    public static final String VERSION = "archlens.investigation-request.v1";
    public enum Scenario { CURRENT_STATE, COLUMN_CHANGE, DATABASE_MIGRATION, LANGUAGE_MIGRATION, REFACTORING, DEPENDENCY_UPGRADE }
    public record Profile(String product, String version) {
        public Profile { text(product,"product"); require(product.length()<=200,"INPUT_LIMIT","Product too long");
            require(version==null || (!version.isBlank() && version.length()<=100),"INVALID_VERSION","Use null for unknown version"); }
    }
    public record Budget(int maxFiles, long maxBytes, long timeoutMillis) {
        public Budget {
            require(maxFiles>=1 && maxFiles<=1000 && maxBytes>=1 && maxBytes<=50_000_000
                    && timeoutMillis>=1 && timeoutMillis<=120_000,"INVALID_BUDGET","Budget outside supported limits");
        }
        public static Budget defaults() { return new Budget(100,10_000_000,30_000); }
    }
    public InvestigationRequest {
        require(VERSION.equals(schemaVersion),"UNSUPPORTED_SCHEMA","Unsupported investigation contract");
        Objects.requireNonNull(scenario); Objects.requireNonNull(sourceProfile); text(objective,"objective");
        require(objective.length()<=10_000,"INPUT_LIMIT","Objective too long");
        constraints=checkedText(constraints); invariants=checkedText(invariants); files=List.copyOf(files);
        require(files.size()<=1000,"INPUT_LIMIT","At most 1000 explicit files");
        Set<String> unique=new HashSet<>();
        for(String file:files) { relative(file); require(unique.add(Path.of(file).normalize().toString()),"DUPLICATE_SOURCE","Duplicate source"); }
        if(columnRequest!=null) relative(columnRequest);
        require((scenario==Scenario.COLUMN_CHANGE)==(columnRequest!=null),"INVALID_COLUMN_ADAPTER","Only COLUMN_CHANGE requires columnRequest");
        Objects.requireNonNull(budget);
    }
    private static List<String> checkedText(List<String> values) {
        var copy=List.copyOf(values); require(copy.size()<=100,"INPUT_LIMIT","Too many constraints");
        copy.forEach(v->{text(v,"constraint");require(v.length()<=2000,"INPUT_LIMIT","Constraint too long");});return copy;
    }
    static void relative(String value) {
        text(value,"source path"); Path path=Path.of(value);
        require(!path.isAbsolute() && !path.normalize().startsWith("..") && !value.contains(":"),"PATH_OUTSIDE_ROOT","Source must be a relative path inside the request directory");
    }
}
