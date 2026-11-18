package com.sip.aidocassist.parsing;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word (.docx) to items with Apache POI: body paragraphs and tables in reading order. Headings
 * come from paragraph styles ("Heading 1".."Heading 9", "Title"); tables are real tables, one grid
 * row per Word row, a horizontally merged cell once. Page headers/footers are not part of the body.
 *
 * ponytail: text boxes, pictures and footnotes are not read (SKB gets figure text from Docling's OCR).
 */
public final class DocxReader {

    private static final Pattern HEADING_STYLE = Pattern.compile("^heading\\s*(\\d)$");

    private DocxReader() {}

    public static List<Item> read(InputStream in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            List<Item> items = new ArrayList<>();
            HeadingNumbers numbers = new HeadingNumbers(doc);
            for (IBodyElement e : doc.getBodyElements()) {
                if (e instanceof XWPFParagraph p) paragraph(doc, p, numbers, items);
                else if (e instanceof XWPFTable t) items.add(Item.table(grid(t)));
                else if (e instanceof XWPFSDT sdt) content(sdt, items);
            }
            return items;
        }
    }

    private static void paragraph(XWPFDocument doc, XWPFParagraph p, HeadingNumbers numbers, List<Item> items) {
        String text = p.getText().strip();
        String style = styleName(doc, p);
        Matcher h = HEADING_STYLE.matcher(style);
        // Numbered before the empty check: an empty numbered heading still advances Word's counter.
        String number = h.matches() ? numbers.next(p) : "";
        if (text.isEmpty()) return;
        if (style.startsWith("toc")) return; // table-of-contents entries and the "TOC Heading" line
        if (style.equals("title")) { items.add(Item.title(text)); return; }
        if (h.matches()) items.add(Item.heading(number.isEmpty() || Character.isDigit(text.charAt(0)) ? text : number + " " + text,
                Integer.parseInt(h.group(1))));
        else items.add(Item.text(text));
    }

    /**
     * Word's automatic heading numbers ("3.2.4"), which are not part of the paragraph text: counted
     * here from the numbering definitions, per list, as Word displays them. Only headings are
     * numbered (as Docling does); list paragraphs keep their plain text.
     * ponytail: level restarts and per-list start overrides are ignored; seen only in edited templates.
     */
    static final class HeadingNumbers {
        private final XWPFDocument doc;
        private final Map<BigInteger, int[]> counters = new HashMap<>();

        HeadingNumbers(XWPFDocument doc) { this.doc = doc; }

        /** The number Word shows before this heading ("3.2.4"), or "" if it isn't numbered. */
        String next(XWPFParagraph p) {
            CTNumPr numPr = numPr(p);
            if (numPr == null || numPr.getNumId() == null || doc.getNumbering() == null) return "";
            BigInteger numId = numPr.getNumId().getVal();
            if (numId == null || numId.signum() == 0) return ""; // numId 0 = numbering switched off
            BigInteger abstractId = doc.getNumbering().getAbstractNumID(numId);
            XWPFAbstractNum abs = abstractId == null ? null : doc.getNumbering().getAbstractNum(abstractId);
            if (abs == null) return "";
            int ilvl = numPr.getIlvl() != null && numPr.getIlvl().getVal() != null ? numPr.getIlvl().getVal().intValue() : 0;
            CTLvl[] lvls = abs.getCTAbstractNum().getLvlArray();
            if (ilvl >= lvls.length) return "";
            int[] c = counters.computeIfAbsent(abstractId, k -> new int[9]);
            c[ilvl] = c[ilvl] == 0 ? start(lvls[ilvl]) : c[ilvl] + 1;
            for (int i = ilvl + 1; i < c.length; i++) c[i] = 0;
            String pattern = lvls[ilvl].getLvlText() == null ? "" : lvls[ilvl].getLvlText().getVal();
            for (int i = 0; i <= ilvl && i < lvls.length; i++) {
                int n = c[i] == 0 ? start(lvls[i]) : c[i];
                pattern = pattern.replace("%" + (i + 1), format(n, lvls[i]));
            }
            return pattern.strip().replaceAll("\\.$", ""); // "3.2." and "3.2" both shown as "3.2"
        }

        /** numPr on the paragraph, else inherited from its style (and the styles it is based on). */
        private CTNumPr numPr(XWPFParagraph p) {
            if (p.getCTP().getPPr() != null && p.getCTP().getPPr().getNumPr() != null) return p.getCTP().getPPr().getNumPr();
            String id = p.getStyleID();
            for (int depth = 0; id != null && doc.getStyles() != null && depth < 10; depth++) {
                XWPFStyle s = doc.getStyles().getStyle(id);
                if (s == null) return null;
                CTPPrGeneral ppr = s.getCTStyle().getPPr();
                if (ppr != null && ppr.getNumPr() != null) {
                    CTNumPr fromStyle = ppr.getNumPr();
                    if (fromStyle.getIlvl() == null) { // a heading style's level is implied by its outline level
                        fromStyle = (CTNumPr) fromStyle.copy();
                        int outline = ppr.getOutlineLvl() != null ? ppr.getOutlineLvl().getVal().intValue() : 0;
                        fromStyle.addNewIlvl().setVal(BigInteger.valueOf(outline));
                    }
                    return fromStyle;
                }
                id = s.getBasisStyleID();
            }
            return null;
        }

        private static int start(CTLvl lvl) {
            return lvl.getStart() != null && lvl.getStart().getVal() != null ? lvl.getStart().getVal().intValue() : 1;
        }

        private static String format(int n, CTLvl lvl) {
            String fmt = lvl.getNumFmt() == null || lvl.getNumFmt().getVal() == null ? "decimal" : lvl.getNumFmt().getVal().toString();
            return switch (fmt) {
                case "upperLetter" -> letters(n).toUpperCase(Locale.ROOT);
                case "lowerLetter" -> letters(n);
                case "upperRoman" -> roman(n);
                case "lowerRoman" -> roman(n).toLowerCase(Locale.ROOT);
                case "bullet", "none" -> "";
                default -> String.valueOf(n);
            };
        }

        private static String letters(int n) {
            StringBuilder s = new StringBuilder();
            for (; n > 0; n = (n - 1) / 26) s.insert(0, (char) ('a' + (n - 1) % 26));
            return s.toString();
        }

        private static String roman(int n) {
            int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < v.length; i++) for (; n >= v[i]; n -= v[i]) s.append(r[i]);
            return s.toString();
        }
    }

    /** Lower-case style name ("heading 2"), falling back to the style id ("Heading2") when undefined. */
    private static String styleName(XWPFDocument doc, XWPFParagraph p) {
        String id = p.getStyleID();
        if (id == null) return "";
        XWPFStyle style = doc.getStyles() == null ? null : doc.getStyles().getStyle(id);
        String name = style != null && style.getName() != null ? style.getName() : id;
        return name.toLowerCase(Locale.ROOT).replaceAll("(?<=[a-z])(?=\\d)", " ").strip();
    }

    /** A content control (often Word's generated table of contents). A TOC is skipped; anything else is text. */
    private static void content(XWPFSDT sdt, List<Item> items) {
        String text = sdt.getContent().getText().strip();
        if (text.isEmpty()) return;
        String first = text.lines().findFirst().orElse("").strip().toLowerCase(Locale.ROOT);
        if (first.equals("contents") || first.equals("table of contents")) return;
        items.add(Item.text(text));
    }

    static List<List<String>> grid(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) cells.add(cell.getText());
            rows.add(cells);
        }
        return rows;
    }
}
