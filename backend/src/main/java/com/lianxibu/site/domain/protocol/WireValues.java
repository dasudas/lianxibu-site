package com.lianxibu.site.domain.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Reusable v1 wire value checks shared by module DTOs. */
public final class WireValues {
    private WireValues() { }

    public static final class UnknownMember extends IllegalArgumentException {
        public UnknownMember() { super("unknown JSON member"); }
    }

    public static JsonNode object(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("JSON object required");
        if (!allowed.containsAll(required)) throw new IllegalArgumentException("invalid field definition");
        Set<String> actual = new HashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!allowed.containsAll(actual)) throw new UnknownMember();
        if (!actual.containsAll(required)) throw new IllegalArgumentException("missing JSON member");
        return node;
    }

    public static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("JSON string required");
        return node.textValue();
    }

    public static String id(JsonNode node, boolean source) {
        return id(text(node), source);
    }

    public static String id(String value, boolean source) {
        if (value == null) throw new IllegalArgumentException("ID required");
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("isolated ID surrogate");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("isolated ID surrogate");
            }
        }
        int maxCharacters = source ? 80 : 64;
        int maxBytes = source ? 320 : 256;
        int characters = value.codePointCount(0, value.length());
        if (characters < 1 || characters > maxCharacters ||
                value.getBytes(StandardCharsets.UTF_8).length > maxBytes ||
                value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ID outside wire bounds");
        }
        return value;
    }

    public static long nonnegativeInteger(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException("JSON integer required");
        }
        long value;
        try {
            value = node.bigIntegerValue().longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("integer outside wire bounds", error);
        }
        if (value < 0 || value > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("integer outside wire bounds");
        }
        return value;
    }

    public static String sha256(JsonNode node) {
        String value = text(node);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("lowercase SHA-256 required");
        return value;
    }

    public static Instant utcMicroseconds(JsonNode node) {
        String value = text(node);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")) {
            throw new IllegalArgumentException("UTC microsecond time required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("invalid UTC time", error);
        }
    }

    public static LocalDate businessDate(JsonNode node) {
        String value = text(node);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("business date required");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("invalid business date", error);
        }
    }
}
