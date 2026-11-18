package com.sip.aidocassist.chunking;

import com.sip.aidocassist.chunking.Pack.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * A table (from Word, PDF or Excel) as compact Markdown lines (port of SKB chunking/tables.py).
 * A table is a grid of raw cell texts; a horizontally merged cell appears once. The first
 * {@code headerRows} grid rows are the column-header rows (Docling marks the first row for Word
 * and Excel, so readers pass 1).
 *
 * Two shapes that aren't data tables are handled specially: a "line number | code" listing
 * becomes just the code, and an unfilled template is dropped.
 *
 * ponytail: SKB's key-value rendering (switched off there) is not ported.
 */
public final class Tables {

    private Tables() {}

    static String clean(String text) {
        return String.join(" ", text.strip().split("\\s+")).strip();
    }

    record Rows(List<List<String>> header, List<List<String>> body) {}

    /** (header rows, body rows) of cleaned cell texts; empty rows dropped. */
    static Rows rows(List<List<String>> grid, int headerRows) {
        List<List<String>> texts = new ArrayList<>();
        List<Boolean> isHeader = new ArrayList<>();
        for (int r = 0; r < grid.size(); r++) {
            List<String> row = grid.get(r).stream().map(Tables::clean).toList();
            if (row.stream().anyMatch(t -> !t.isEmpty())) {
                texts.add(row);
                isHeader.add(r < headerRows);
            }
        }
        if (texts.isEmpty()) return new Rows(List.of(), List.of());
        int headerN = isHeader.indexOf(false);
        if (headerN == -1) headerN = texts.size();
        if (headerN == 0) headerN = 1;
        // Spreadsheets often start with a one-cell title row above the real column names. If that's
        // all the header we found, extend it down to the first row that's mostly filled in (within 5 rows).
        if (filled(texts.get(headerN - 1)) <= 1) {
            int width = texts.stream().mapToInt(List::size).max().orElse(0);
            for (int i = headerN; i < Math.min(headerN + 5, texts.size()); i++) {
                if (filled(texts.get(i)) * 2 >= width) {
                    headerN = i + 1;
                    break;
                }
            }
        }
        return new Rows(texts.subList(0, headerN), texts.subList(headerN, texts.size()));
    }

    private static int filled(List<String> row) {
        return (int) row.stream().filter(t -> !t.isEmpty()).count();
    }

    /**
     * The code of a "line number | code" table, or null if it isn't one. Every row must be exactly
     * two cells with a number in the first, counting up from 1 (a repeat is allowed: Word renders a
     * wrapped line as "73, 73"). Numbers dropped: they cost space and made quotes unlike the code.
     * ponytail: a real 2-column table numbered 1..n would lose its numbers (content kept).
     */
    static List<String> codeLines(List<List<String>> grid) {
        if (grid.size() < 3 || grid.stream().anyMatch(r -> r.size() != 2)) return null;
        List<String> nums = grid.stream().map(r -> r.get(0).strip()).toList();
        if (!nums.stream().allMatch(n -> !n.isEmpty() && n.chars().allMatch(Character::isDigit))) return null;
        if (Long.parseLong(nums.get(0)) != 1) return null;
        for (int i = 1; i < nums.size(); i++) {
            long step = Long.parseLong(nums.get(i)) - Long.parseLong(nums.get(i - 1));
            if (step != 0 && step != 1) return null;
        }
        return grid.stream().map(r -> r.get(1).stripTrailing()).filter(t -> !t.isBlank()).toList();
    }

    private static String md(List<String> texts) {
        return "| " + String.join(" | ", texts.stream().map(t -> t.replace("|", "\\|")).toList()) + " |";
    }

    /** The table as a block (header lines + row lines), or null if nothing is left. */
    public static Block block(List<List<String>> grid, int headerRows) {
        List<String> code = codeLines(grid);
        if (code != null) return code.isEmpty() ? null : Block.text(code);
        Rows rows = rows(grid, headerRows);
        // Only column names, and the grid had blank rows: a template nobody filled in ("Mnemonic |
        // Document Details" + 2 empty rows). A single-row table without blank rows ("Version | 2.2") stays.
        boolean blank = grid.stream().anyMatch(r -> r.stream().allMatch(c -> clean(c).isEmpty()));
        if (!rows.header().isEmpty() && rows.body().isEmpty() && blank
                && filled(rows.header().get(rows.header().size() - 1)) >= 2) return null;
        if (rows.header().isEmpty()) return null;
        List<String> header = new ArrayList<>(rows.header().stream().map(Tables::md).toList());
        if (rows.body().isEmpty()) return Block.text(header); // header-only table (e.g. a 1x1 label cell): plain lines
        header.add("|" + " --- |".repeat(rows.header().get(rows.header().size() - 1).size()));
        return new Block(header, rows.body().stream().map(Tables::md).toList());
    }
}
