package com.sip.aidocassist.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RFC 4180 CSV (quoted fields, "" escapes, newlines in quotes) read into header-keyed rows. */
public final class Csv {

    private Csv() {}

    public static List<Map<String, String>> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1); // Excel's BOM, like Python's utf-8-sig
        return toMaps(parse(text));
    }

    static List<Map<String, String>> toMaps(List<List<String>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> header = rows.get(0).stream().map(String::trim).toList();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() == 1 && row.get(0).isBlank()) continue;
            if (row.size() != header.size())
                throw new IllegalArgumentException("expected " + header.size() + " fields, got " + row.size() + ": " + row);
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) m.put(header.get(i), row.get(i));
            out.add(m);
        }
        return out;
    }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch != '"') field.append(ch);
                else if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(field.toString()); field.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else field.append(ch);
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }
}
