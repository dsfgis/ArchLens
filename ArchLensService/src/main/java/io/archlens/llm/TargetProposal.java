package io.archlens.llm;

import java.util.List;
import static io.archlens.contract.ContractException.require;

/** Unverified model proposal, deliberately separate from snapshot-bound ChangeSpec. */
public record TargetProposal(Kind kind, String schema, String table, String column,
                             String newName, String newType, List<String> constraints,
                             List<String> questions) {
    public enum Kind { COLUMN_RENAME, COLUMN_DROP, COLUMN_TYPE_CHANGE, UNSUPPORTED, UNCLEAR }

    public TargetProposal {
        require(kind != null, "INVALID_MODEL_OUTPUT", "Missing proposal kind");
        check(schema); check(table); check(column); check(newName); check(newType);
        require(constraints != null && questions != null && constraints.size() <= 20 && questions.size() <= 20,
                "INVALID_MODEL_OUTPUT", "Invalid proposal lists");
        constraints = List.copyOf(constraints); questions = List.copyOf(questions);
        constraints.forEach(TargetProposal::checkItem); questions.forEach(TargetProposal::checkItem);
        if (kind == Kind.COLUMN_RENAME) require(newType == null, "INVALID_MODEL_OUTPUT", "Rename cannot change type");
        if (kind == Kind.COLUMN_DROP) require(newName == null && newType == null, "INVALID_MODEL_OUTPUT", "Drop has no after state");
        if (kind == Kind.COLUMN_TYPE_CHANGE) require(newName == null, "INVALID_MODEL_OUTPUT", "Type change cannot rename");
        if (kind == Kind.UNCLEAR || kind == Kind.UNSUPPORTED || schema == null || table == null || column == null
                || kind == Kind.COLUMN_RENAME && newName == null || kind == Kind.COLUMN_TYPE_CHANGE && newType == null) {
            require(!questions.isEmpty(), "INVALID_MODEL_OUTPUT", "Incomplete targets require clarification questions");
        }
    }
    private static void check(String value) {
        require(value == null || !value.isBlank() && value.length() <= 512 && value.chars().noneMatch(Character::isISOControl),
                "INVALID_MODEL_OUTPUT", "Invalid proposal field");
    }
    private static void checkItem(String value) {
        require(value != null, "INVALID_MODEL_OUTPUT", "Null list item"); check(value);
    }
}
