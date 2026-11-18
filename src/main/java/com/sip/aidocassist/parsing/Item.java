package com.sip.aidocassist.parsing;

import java.util.List;

/**
 * One piece of a document in reading order, as the DOCX / PDF / Markdown readers produce it
 * (the Java stand-in for Docling's document items that SKB's HeadingStrategy walks).
 *
 * @param level heading level (1 = top); 1 when the format has no real levels (PDF)
 * @param grid  table cells, rows of raw cell texts; the first row is the header row
 */
public record Item(Kind kind, String text, int level, List<List<String>> grid) {

    public enum Kind { TITLE, HEADING, TEXT, TABLE }

    public static Item title(String text) { return new Item(Kind.TITLE, text, 0, null); }
    public static Item heading(String text, int level) { return new Item(Kind.HEADING, text, level, null); }
    public static Item text(String text) { return new Item(Kind.TEXT, text, 0, null); }
    public static Item table(List<List<String>> grid) { return new Item(Kind.TABLE, "", 0, grid); }
}
