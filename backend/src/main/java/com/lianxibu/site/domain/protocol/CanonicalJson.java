package com.lianxibu.site.domain.protocol;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Project CanonicalJson-v1. No implicit string normalization or array sorting. */
public final class CanonicalJson {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final BigInteger MAX = BigInteger.valueOf(MAX_SAFE_INTEGER);
    private static final JsonFactory FACTORY = JsonFactory.builder().build();
    private static final JsonMapper MAPPER = JsonMapper.builder(FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() { }

    public enum Domain {
        OBSERVATION("observation-v1"), MANIFEST_PART("manifest-part-v1"),
        MANIFEST("manifest-v1"), CONTENT("content-v1"), INPUT_PART("input-part-v1"),
        INPUT("input-v1"), REVIEW("review-v1"), ACTION("action-v1"),
        IDENTITY("identity-v1"), OCCURRENCE("occurrence-v1"),
        BUSINESS_INPUT("business-input-v1"), CONTROL_BUSINESS("control-business-v1"),
        CONTROL_ATTEMPT("control-attempt-v1"), SOURCE_ITEM("source-item-v1"),
        LEGACY_EXTERNAL("legacy-external-v1"), CONTENT_KEY("content-key-v1"),
        ARTICLE_PROJECTION("article-projection-v1");

        private final String tag;
        Domain(String tag) { this.tag = tag; }
        public String tag() { return tag; }
    }

    public static JsonNode parse(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        try {
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
            return parse(raw);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("invalid UTF-8 JSON", error);
        }
    }

    public static JsonNode parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isEmpty() || raw.charAt(0) == '\ufeff') {
            throw new IllegalArgumentException("empty JSON or BOM");
        }
        validateScalars(raw);
        try (JsonParser parser = FACTORY.createParser(raw)) {
            var stack = new ArrayDeque<ReadFrame>();
            JsonNode root = null;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.PROPERTY_NAME) {
                    if (stack.isEmpty() || !stack.peek().node.isObject() || stack.peek().field != null) throw invalidStructure();
                    String name = parser.getText();
                    validateScalars(name);
                    if (stack.peek().node.has(name)) throw new IllegalArgumentException("duplicate JSON object property");
                    stack.peek().field = name;
                    continue;
                }
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    if (stack.isEmpty()) throw invalidStructure();
                    var frame = stack.pop();
                    if (frame.field != null || frame.node.isObject() != (token == JsonToken.END_OBJECT)) throw invalidStructure();
                    continue;
                }
                var nodes = MAPPER.getNodeFactory();
                JsonNode value;
                if (token == JsonToken.START_OBJECT) value = nodes.objectNode();
                else if (token == JsonToken.START_ARRAY) value = nodes.arrayNode();
                else if (token == JsonToken.VALUE_STRING) {
                    String text = parser.getText();
                    validateScalars(text);
                    value = nodes.stringNode(text);
                } else if (token == JsonToken.VALUE_NUMBER_INT) {
                    long number = integer(parser.getText());
                    value = number <= Integer.MAX_VALUE ? nodes.numberNode((int) number) : nodes.numberNode(number);
                } else if (token == JsonToken.VALUE_TRUE) value = nodes.booleanNode(true);
                else if (token == JsonToken.VALUE_FALSE) value = nodes.booleanNode(false);
                else if (token == JsonToken.VALUE_NULL) value = nodes.nullNode();
                else if (token == JsonToken.VALUE_NUMBER_FLOAT) throw new IllegalArgumentException("JSON floating number is forbidden");
                else throw invalidStructure();
                if (stack.isEmpty()) {
                    if (root != null) throw invalidStructure();
                    root = value;
                } else {
                    var frame = stack.peek();
                    if (frame.node.isObject()) {
                        if (frame.field == null) throw invalidStructure();
                        ((tools.jackson.databind.node.ObjectNode) frame.node).set(frame.field, value);
                        frame.field = null;
                    } else ((tools.jackson.databind.node.ArrayNode) frame.node).add(value);
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) stack.push(new ReadFrame(value));
            }
            if (root == null || !stack.isEmpty()) throw invalidStructure();
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid strict JSON", error);
        }
    }

    private static final class ReadFrame {
        private final JsonNode node;
        private String field;
        private ReadFrame(JsonNode node) { this.node = node; }
    }

    private static long integer(String lexical) {
        int length = lexical.length();
        if (length == 0 || length > 16 || length > 1 && lexical.charAt(0) == '0') throw invalidInteger();
        for (int index = 0; index < length; index++) {
            char digit = lexical.charAt(index);
            if (digit < '0' || digit > '9') throw invalidInteger();
        }
        if (length == 16 && lexical.compareTo("9007199254740991") > 0) throw invalidInteger();
        return Long.parseLong(lexical);
    }

    private static IllegalArgumentException invalidInteger() { return new IllegalArgumentException("JSON integer outside canonical range"); }
    private static IllegalArgumentException invalidStructure() { return new IllegalArgumentException("JSON requires exactly one complete root"); }

    public static byte[] bytes(JsonNode value) {
        Objects.requireNonNull(value, "value");
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String hash(Domain domain, JsonNode value) {
        Objects.requireNonNull(domain, "domain");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(domain.tag().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '\n');
            sha.update(bytes(value));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void write(JsonNode value, StringBuilder output) {
        if (value.isObject()) {
            output.append('{');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(value.properties());
            fields.sort(Comparator.comparing(Map.Entry::getKey, CanonicalJson::compareScalars));
            for (int index = 0; index < fields.size(); index++) {
                if (index > 0) output.append(',');
                writeString(fields.get(index).getKey(), output);
                output.append(':');
                write(fields.get(index).getValue(), output);
            }
            output.append('}');
        } else if (value.isArray()) {
            output.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) output.append(',');
                write(value.get(index), output);
            }
            output.append(']');
        } else if (value.isTextual()) {
            writeString(value.textValue(), output);
        } else if (value.isIntegralNumber()) {
            BigInteger number = value.bigIntegerValue();
            if (number.signum() < 0 || number.compareTo(MAX) > 0) {
                throw new IllegalArgumentException("integer outside canonical range");
            }
            output.append(number);
        } else if (value.isBoolean()) {
            output.append(value.booleanValue() ? "true" : "false");
        } else if (value.isNull()) {
            output.append("null");
        } else {
            throw new IllegalArgumentException("unsupported canonical JSON value");
        }
    }

    private static void writeString(String value, StringBuilder output) {
        validateScalars(value);
        if (value.length() <= Integer.MAX_VALUE - output.length() - 2) {
            output.ensureCapacity(output.length() + value.length() + 2);
        }
        output.append('"');
        int start = 0;
        for (int offset = 0; offset < value.length(); offset++) {
            char unit = value.charAt(offset);
            if (unit >= 0x20 && unit != '"' && unit != '\\') continue;
            output.append(value, start, offset);
            switch (unit) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\t' -> output.append("\\t");
                case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f");
                case '\r' -> output.append("\\r");
                default -> output.append("\\u00").append(HEX[unit >>> 4]).append(HEX[unit & 0xf]);
            }
            start = offset + 1;
        }
        output.append(value, start, value.length());
        output.append('"');
    }

    static int compareScalars(String left, String right) {
        validateScalars(left);
        validateScalars(right);
        int a = 0;
        int b = 0;
        while (a < left.length() && b < right.length()) {
            int x = left.codePointAt(a);
            int y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x);
            b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    private static void validateScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("isolated high surrogate");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("isolated low surrogate");
            }
        }
    }
}
