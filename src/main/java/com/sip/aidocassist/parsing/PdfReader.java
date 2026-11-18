package com.sip.aidocassist.parsing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import technology.tabula.ObjectExtractor;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PDF to items: text lines from PDFBox, ruled tables from Tabula. A PDF has no heading styles, so
 * a heading is a short line set larger than the body text, or a bold line that is numbered or at
 * least body size. Every heading is reported as level 1, as Docling does for PDFs; HeadingChunker
 * then takes the level from the section number ("3.1" -> 2), exactly as SKB does.
 *
 * Also: text inside a table is not repeated as paragraphs; lines repeated at the top or bottom of
 * most pages (page headers/footers, page numbers) are dropped; table-of-contents lines
 * ("Introduction ....... 3") are dropped; consecutive lines with normal spacing join into one paragraph.
 *
 * Known risk (CLAUDE.md): tables WITHOUT ruled lines are not detected; their text comes out as
 * paragraphs. ponytail: no multi-column reading order, no OCR of scanned pages.
 */
public final class PdfReader {

    private static final Pattern TOC_LINE = Pattern.compile("(\\.\\s?){4,}\\s*\\d+\\s*$");
    private static final Pattern SECTION_NUMBER = Pattern.compile("^\\d{1,2}(\\.\\d+)*\\.?\\s");
    private static final Pattern LIST_START = Pattern.compile("^([•●▪◦\\-–*]|\\(?[a-zA-Z0-9]{1,3}[.)])\\s");
    private static final double EDGE = 0.08; // top/bottom share of the page where headers/footers live

    private PdfReader() {}

    /** One text line on a page. y grows downwards from the top of the page. */
    record Line(int page, String text, float top, float bottom, float left, float right, float size, boolean bold) {
        float height() { return bottom - top; }
    }

    private record Grid(int page, float top, float bottom, float left, float right, List<List<String>> rows) {}

    public static List<Item> read(InputStream in) throws IOException {
        try (PDDocument doc = PDDocument.load(in)) {
            int pages = doc.getNumberOfPages();
            List<Line> lines = new LineCollector().collect(doc);
            List<Grid> tables = tables(doc, pages);
            lines = withoutPageFurniture(lines, doc, pages);
            lines = lines.stream().filter(l -> tables.stream().noneMatch(t -> inside(l, t))).toList();
            return items(lines, tables, bodySize(lines));
        }
    }

    // ---------- tables ----------

    /**
     * Ruled tables. Tabula's lattice mode only says WHERE a table is: on real specs it also reads each
     * shaded cell background as a table of its own (nested duplicates, letters cut off), so areas
     * inside another area are dropped, and the cells are read with Tabula's stream mode (columns from
     * text alignment) inside each remaining area. Measured on ACRA_NGM_API_Specification_v1.pdf.
     */
    private static List<Grid> tables(PDDocument doc, int pages) {
        List<Grid> out = new ArrayList<>();
        ObjectExtractor extractor = new ObjectExtractor(doc); // not closed: that would close doc
        for (int p = 1; p <= pages; p++) {
            try {
                technology.tabula.Page page = extractor.extract(p);
                // A ruled area over half the page is a frame (slide background, page border), not a table;
                // dropped first, so the real tables inside it don't count as nested.
                double pageArea = page.getWidth() * page.getHeight();
                List<Table> found = new SpreadsheetExtractionAlgorithm().extract(page).stream()
                        .filter(t -> t.getArea() <= 0.5 * pageArea).toList();
                for (Table t : found) {
                    if (found.stream().anyMatch(o -> o != t && contains(o, t))) continue;
                    List<List<String>> rows = new ArrayList<>();
                    var area = page.getArea(t.getTop(), t.getLeft(), t.getBottom(), t.getRight());
                    for (Table s : new BasicExtractionAlgorithm().extract(area))
                        for (List<RectangularTextContainer> row : s.getRows()) {
                            List<String> cells = new ArrayList<>();
                            for (RectangularTextContainer c : row) cells.add(c.getText().replace('\r', ' ').strip());
                            rows.add(cells);
                        }
                    rows = tidy(rows);
                    if (!rows.isEmpty()) out.add(new Grid(p, t.getTop(), t.getBottom(), t.getLeft(), t.getRight(), rows));
                }
            } catch (RuntimeException e) {
                // ponytail: a page Tabula can't analyse keeps its text as paragraphs rather than failing the file
            }
        }
        return out;
    }

    private static boolean contains(Table outer, Table inner) {
        return outer.getTop() <= inner.getTop() + 2 && outer.getLeft() <= inner.getLeft() + 2
                && outer.getBottom() >= inner.getBottom() - 2 && outer.getRight() >= inner.getRight() - 2
                && outer.getArea() > inner.getArea();
    }

    /** Drop empty columns and empty rows; a row with an empty first cell is wrapped text of the row above. */
    static List<List<String>> tidy(List<List<String>> rows) {
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<Integer> keep = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            final int col = c;
            if (rows.stream().anyMatch(r -> col < r.size() && !r.get(col).isEmpty())) keep.add(c);
        }
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : rows) {
            List<String> cells = new ArrayList<>(keep.stream().map(c -> c < r.size() ? r.get(c) : "").toList());
            if (cells.stream().allMatch(String::isEmpty)) continue;
            if (!out.isEmpty() && cells.get(0).isEmpty() && cells.size() > 1) {
                List<String> prev = out.get(out.size() - 1);
                for (int c = 0; c < cells.size(); c++)
                    if (!cells.get(c).isEmpty()) prev.set(c, (prev.get(c) + " " + cells.get(c)).strip());
            } else {
                out.add(cells);
            }
        }
        return out;
    }

    private static boolean inside(Line l, Grid t) {
        float midY = (l.top() + l.bottom()) / 2, midX = (l.left() + l.right()) / 2;
        return l.page() == t.page() && midY >= t.top() - 1 && midY <= t.bottom() + 1 && midX >= t.left() - 1 && midX <= t.right() + 1;
    }

    // ---------- page furniture ----------

    /** Drop lines repeated near the top or bottom of at least half the pages (3 or more), and bare page numbers there. */
    static List<Line> withoutPageFurniture(List<Line> lines, PDDocument doc, int pages) {
        Map<String, Set<Integer>> seen = new HashMap<>();
        for (Line l : lines) {
            String k = edgeKey(l, doc);
            if (k != null) seen.computeIfAbsent(k, x -> new HashSet<>()).add(l.page());
        }
        int needed = Math.max(3, (pages + 1) / 2);
        return lines.stream().filter(l -> {
            String k = edgeKey(l, doc);
            if (k == null) return true;
            boolean pageNumber = l.text().strip().matches("(?i)(page\\s*)?\\d+(\\s*(of|/)\\s*\\d+)?");
            return !pageNumber && seen.get(k).size() < needed;
        }).toList();
    }

    private static String edgeKey(Line l, PDDocument doc) {
        float h = doc.getPage(l.page() - 1).getMediaBox().getHeight();
        String zone = l.top() < h * EDGE ? "top:" : l.bottom() > h * (1 - EDGE) ? "bottom:" : null;
        return zone == null ? null : zone + l.text().replaceAll("\\d+", "#").strip().toLowerCase(Locale.ROOT);
    }

    // ---------- headings and paragraphs ----------

    /** The most common font size, weighted by characters: the body text. */
    static float bodySize(List<Line> lines) {
        Map<Float, Integer> chars = new HashMap<>();
        for (Line l : lines) chars.merge(Math.round(l.size() * 2) / 2f, l.text().length(), Integer::sum);
        return chars.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(10f);
    }

    /**
     * Larger than the body, or bold at body size, and shaped like a heading: starts with a capital
     * or a digit (not a sentence continuing from the line above), doesn't end like a sentence or a
     * lead-in (". , ; :"), and if unnumbered is short (callout boxes set in bold are long sentences).
     */
    static boolean isHeading(Line l, float body) {
        String t = l.text().strip();
        if (t.length() < 2 || t.length() > 150 || t.chars().noneMatch(Character::isLetter)) return false;
        if (!Character.isUpperCase(t.codePointAt(0)) && !Character.isDigit(t.codePointAt(0))) return false;
        if (t.endsWith(".") || t.endsWith(",") || t.endsWith(";") || t.endsWith(":") || TOC_LINE.matcher(t).find()) return false;
        boolean numbered = SECTION_NUMBER.matcher(t).find();
        if (!numbered && t.length() > 80) return false;
        if (l.size() >= body + 1.0f) return true;
        return l.bold() && (numbered || l.size() >= body - 0.25f);
    }

    /**
     * The document's usual distance from one line's top to the next, as a multiple of font size
     * (about 1.2 single-spaced): the median over consecutive same-size lines. PDFBox's glyph height
     * is not the line height, so gaps are measured top to top.
     */
    static float linePitch(List<Line> lines) {
        List<Float> ratios = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Line a = lines.get(i - 1), b = lines.get(i);
            float d = b.top() - a.top();
            if (a.page() == b.page() && Math.abs(a.size() - b.size()) < 0.5f && d > 0 && d < b.size() * 2.5f) ratios.add(d / b.size());
        }
        if (ratios.isEmpty()) return 1.2f;
        ratios.sort(null);
        return ratios.get(ratios.size() / 2);
    }

    private static List<Item> items(List<Line> lines, List<Grid> tables, float body) {
        record Placed(int page, float top, Line line, Grid table) {}
        List<Placed> all = new ArrayList<>();
        for (Line l : lines) all.add(new Placed(l.page(), l.top(), l, null));
        for (Grid t : tables) all.add(new Placed(t.page(), t.top(), null, t));
        all.sort(Comparator.comparingInt(Placed::page).thenComparingDouble(Placed::top));

        float pitch = linePitch(lines);
        List<Item> items = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        Line prev = null;
        boolean prevHeading = false;
        for (Placed p : all) {
            if (p.table() != null) {
                flush(items, para);
                List<List<String>> rows = p.table().rows();
                // A one-column box is a code or message sample, not a table: its lines, as they are.
                if (rows.stream().allMatch(r -> r.size() == 1)) items.add(Item.text(String.join("\n", rows.stream().map(r -> r.get(0)).toList())));
                else items.add(Item.table(rows));
                prev = null;
                continue;
            }
            Line l = p.line();
            String text = l.text().strip();
            if (TOC_LINE.matcher(text).find()) continue;
            // Same paragraph: next line on the page, same size, at the document's usual line pitch (a paragraph gap is larger).
            boolean close = prev != null && prev.page() == l.page() && Math.abs(prev.size() - l.size()) < 0.5f
                    && l.top() - prev.top() <= l.size() * pitch * 1.15f + 0.5f;
            if (isHeading(l, body)) {
                flush(items, para);
                Item last = items.isEmpty() ? null : items.get(items.size() - 1);
                // A heading wrapped onto a second line, same style, no new number: one heading.
                if (prevHeading && close && last != null && last.kind() == Item.Kind.HEADING
                        && Math.abs(prev.size() - l.size()) < 0.5f && !SECTION_NUMBER.matcher(text).find()) {
                    items.set(items.size() - 1, Item.heading(last.text() + " " + text, 1));
                } else {
                    items.add(Item.heading(text, 1));
                }
                prevHeading = true;
            } else {
                if (!close || prevHeading || LIST_START.matcher(text).find()) flush(items, para);
                if (para.length() > 0) para.append(' ');
                para.append(text);
                prevHeading = false;
            }
            prev = l;
        }
        flush(items, para);
        return items;
    }

    private static void flush(List<Item> items, StringBuilder para) {
        if (!para.isEmpty()) items.add(Item.text(para.toString()));
        para.setLength(0);
    }

    // ---------- PDFBox line collection ----------

    /** Collects each text line with its position, font size and boldness. */
    private static final class LineCollector extends PDFTextStripper {
        private final List<Line> lines = new ArrayList<>();
        private final List<TextPosition> current = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        LineCollector() throws IOException {
            setSortByPosition(true);
        }

        List<Line> collect(PDDocument doc) throws IOException {
            getText(doc); // drives writeString / writeLineSeparator below
            endLine();
            return lines;
        }

        @Override
        protected void writeString(String s, List<TextPosition> positions) {
            if (!text.isEmpty()) text.append(' ');
            text.append(s);
            current.addAll(positions);
        }

        @Override
        protected void writeLineSeparator() { endLine(); }

        @Override
        protected void writePageEnd() { endLine(); }

        private void endLine() {
            String t = text.toString().strip();
            if (!t.isEmpty() && !current.isEmpty()) {
                float top = Float.MAX_VALUE, bottom = 0, left = Float.MAX_VALUE, right = 0;
                Map<Float, Integer> sizes = new HashMap<>();
                int boldChars = 0;
                for (TextPosition p : current) {
                    top = Math.min(top, p.getYDirAdj() - p.getHeightDir());
                    bottom = Math.max(bottom, p.getYDirAdj());
                    left = Math.min(left, p.getXDirAdj());
                    right = Math.max(right, p.getXDirAdj() + p.getWidthDirAdj());
                    sizes.merge(Math.round(p.getFontSizeInPt() * 2) / 2f, 1, Integer::sum);
                    if (bold(p.getFont())) boldChars++;
                }
                float size = sizes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                lines.add(new Line(getCurrentPageNo(), t, top, bottom, left, right, size, boldChars >= current.size() * 0.9));
            }
            text.setLength(0);
            current.clear();
        }

        private static boolean bold(PDFont font) {
            if (font == null) return false;
            String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
            if (name.contains("bold") || name.contains("black") || name.contains("heavy") || name.contains("semibold")) return true;
            PDFontDescriptor d = font.getFontDescriptor();
            return d != null && (d.isForceBold() || d.getFontWeight() >= 700);
        }
    }
}
