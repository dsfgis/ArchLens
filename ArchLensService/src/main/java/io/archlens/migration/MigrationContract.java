package io.archlens.migration;

import java.util.*;
import static io.archlens.contract.ContractException.require;

/** Shared bounds for v1 migration artifacts; no value is silently defaulted during JSON parsing. */
final class MigrationContract {
    private MigrationContract() {}

    static String id(String value, String code) {
        require(value != null && value.matches("[A-Za-z][A-Za-z0-9._:-]{0,99}"), code, "Invalid migration identifier");
        return value;
    }
    static String uuid(String value, String code) {
        require(value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                code, "Invalid run UUID");
        return value;
    }
    static String hash(String value, String code) {
        require(value != null && value.matches("[0-9a-f]{64}"), code, "Expected a lowercase SHA-256 hash");
        return value;
    }
    static String text(String value, int max, String code) {
        require(value != null && !value.isBlank() && value.length() <= max, code, "Missing or oversized migration text");
        return value;
    }
    static <T> List<T> list(List<T> values, int max, String code) {
        require(values != null && values.size() <= max && values.stream().noneMatch(Objects::isNull),
                code, "Missing, oversized or null-containing migration list");
        return List.copyOf(values);
    }
    static List<String> ids(List<String> values, int max, String code) {
        List<String> result = list(values, max, code).stream().map(v -> id(v, code)).toList();
        unique(result, code);
        return result;
    }
    static List<String> texts(List<String> values, int maxItems, int maxLength, String code) {
        return list(values, maxItems, code).stream().map(v -> text(v, maxLength, code)).toList();
    }
    static <T> void unique(List<T> values, String code) {
        require(new HashSet<>(values).size() == values.size(), code, "Duplicate migration identifiers");
    }
}
