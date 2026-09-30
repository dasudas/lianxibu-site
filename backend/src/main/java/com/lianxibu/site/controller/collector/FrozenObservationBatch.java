package com.lianxibu.site.controller.collector;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Recover each exact Go outbox object from the validated batch, including its original JSON bytes. */
final class FrozenObservationBatch {
    private FrozenObservationBatch() { }

    static List<byte[]> extract(byte[] raw, JsonNode validated) {
        if (raw == null || validated == null || !validated.path("observations").isArray())
            throw new IllegalArgumentException("observation batch bytes required");
        int startArray = arrayStart(raw);
        if (startArray < 0) throw new IllegalArgumentException("observation array missing");
        List<byte[]> items = new ArrayList<>();
        int position = startArray + 1;
        while (true) {
            int start = position;
            position = whitespace(raw, position);
            if (position >= raw.length) throw new IllegalArgumentException("truncated observation batch");
            if (raw[position] == ']') break;
            if (raw[position] != '{') throw new IllegalArgumentException("observation object required");
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            while (position < raw.length) {
                byte current = raw[position++];
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (current == '\\') escaped = true;
                    else if (current == '"') quoted = false;
                } else if (current == '"') quoted = true;
                else if (current == '{') depth++;
                else if (current == '}' && --depth == 0) break;
            }
            if (depth != 0 || quoted) throw new IllegalArgumentException("truncated observation object");
            position = whitespace(raw, position);
            byte[] item = Arrays.copyOfRange(raw, start, position);
            if (items.size() >= validated.get("observations").size()
                    || !CanonicalJson.parse(item).equals(validated.get("observations").get(items.size())))
                throw new IllegalArgumentException("observation bytes mismatch");
            items.add(item);
            if (position >= raw.length) throw new IllegalArgumentException("truncated observation batch");
            if (raw[position] == ',') position++;
            else if (raw[position] != ']') throw new IllegalArgumentException("invalid observation separator");
        }
        if (items.size() != validated.get("observations").size())
            throw new IllegalArgumentException("incomplete observation batch");
        return List.copyOf(items);
    }

    private static int arrayStart(byte[] raw) {
        boolean quoted = false;
        boolean escaped = false;
        for (int position = 0; position < raw.length; position++) {
            byte current = raw[position];
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '[') return position;
        }
        return -1;
    }

    private static int whitespace(byte[] raw, int position) {
        while (position < raw.length && (raw[position] == ' ' || raw[position] == '\r'
                || raw[position] == '\n' || raw[position] == '\t')) position++;
        return position;
    }
}
