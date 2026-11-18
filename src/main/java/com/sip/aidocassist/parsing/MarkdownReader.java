package com.sip.aidocassist.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown and plain text to items. As in SKB, plain text is read as Markdown (no headings means
 * one section of paragraphs). Follows Docling's reading: "# X" is the document title, "## X" a
 * level-1 heading, "### X" level 2; each list item is its own paragraph; pipe tables are tables.
 *
 * ponytail: inline formatting (**bold**, links) is kept as typed; nothing searchable is lost.
 */
public final class MarkdownReader {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$");

    private MarkdownReader() {}

    public static List<Item> read(String text) {
        List<Item> items = new ArrayList<>();
        List<String> para = new ArrayList<>();
        List<List<String>> table = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) { // fenced code: kept as one block, lines intact
                flush(items, para, table);
                String fence = trimmed.substring(0, 3);
                List<String> code = new ArrayList<>();
                while (++i < lines.length && !lines[i].strip().startsWith(fence)) code.add(lines[i]);
                if (!String.join("", code).isBlank()) items.add(Item.text(String.join("\n", code)));
                continue;
            }
            if (trimmed.startsWith("|")) {
                if (!para.isEmpty()) flush(items, para, List.of());
                if (!TABLE_SEPARATOR.matcher(trimmed).matches()) table.add(cells(trimmed));
                continue;
            }
            if (!table.isEmpty()) flush(items, para, table);
            Matcher h = HEADING.matcher(trimmed);
            Matcher li = LIST_ITEM.matcher(line);
            if (trimmed.isEmpty()) {
                flush(items, para, table);
            } else if (h.matches()) {
                flush(items, para, table);
                int hashes = h.group(1).length();
                items.add(hashes == 1 ? Item.title(h.group(2)) : Item.heading(h.group(2), hashes - 1));
            } else if (li.matches()) {
                flush(items, para, table);
                para.add(li.group(1).strip());
            } else {
                para.add(trimmed);
            }
        }
        flush(items, para, table);
        return items;
    }

    private static void flush(List<Item> items, List<String> para, List<List<String>> table) {
        if (!para.isEmpty()) items.add(Item.text(String.join(" ", para)));
        para.clear();
        if (!table.isEmpty()) {
            items.add(Item.table(List.copyOf(table)));
            table.clear();
        }
    }

    /** "| a | b\|c |" -> ["a", "b|c"]: split on unescaped pipes, edge pipes dropped. */
    static List<String> cells(String row) {
        String s = row.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') { cur.append('|'); i++; }
            else if (c == '|') { out.add(cur.toString().strip()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().strip());
        return out;
    }
}
