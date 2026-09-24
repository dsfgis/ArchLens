package io.archlens.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Canonical objects sort by Unicode code point; arrays preserve order. */
public final class Json {
    private Json() {}
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    static { MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(100).maxStringLength(5_000_000).build()); }

    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        append(MAPPER.valueToTree(value), out);
        return out.toString();
    }
    private static void append(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
            names.sort(Json::compareCodePoints); out.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) out.append(','); first = false;
                out.append(quote(name)).append(':'); append(node.get(name), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) { if (i > 0) out.append(','); append(node.get(i), out); }
            out.append(']');
        } else if (node.isNumber()) {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else out.append(node.toString());
    }
    public static int compareCodePoints(String a, String b) {
        PrimitiveIterator.OfInt x = a.codePoints().iterator(), y = b.codePoints().iterator();
        while (x.hasNext() && y.hasNext()) { int c = Integer.compare(x.nextInt(), y.nextInt()); if (c != 0) return c; }
        return Boolean.compare(x.hasNext(), y.hasNext());
    }
    private static String quote(String s) {
        try { return MAPPER.writeValueAsString(s); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
    public static String hash(Object value) { return sha256(canonical(value).getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hashParts(Object... parts) { return hash(Arrays.asList(parts)); }
    public static Map<String, Object> freeze(Map<String, Object> map) {
        Objects.requireNonNull(map, "attributes");
        Map<String, Object> result = new TreeMap<>(Json::compareCodePoints);
        map.forEach((k, v) -> result.put(k, freezeValue(v)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put((String) k, freezeValue(v))); return freeze(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(Json::freezeValue).toList();
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw new ContractException("INVALID_ATTRIBUTES", "Only JSON values are allowed");
    }
}
