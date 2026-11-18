package com.sip.aidocassist.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * The shapes every chunker shares and the size limit (port of SKB chunking/base.py). A chunker
 * decides WHAT a section is (a heading's content, a sheet, a table); {@link #pack} cuts sections
 * into size-limited chunks the same way for all of them.
 */
public final class Pack {

    // ponytail: character budget, not tokens (~1000 tokens). Tune after Phase 7 evaluation shows
    // chunks are too coarse or too fragmented.
    public static final int MAX_CHARS = 4000;

    /** Header lines (repeated when the block continues into a new chunk) and body lines. */
    public record Block(List<String> header, List<String> lines) {
        public static Block text(List<String> lines) { return new Block(List.of(), lines); }
    }

    /** A heading path (becomes the chunk title) and its blocks. */
    public record Section(List<String> path, List<Block> blocks) {}

    /**
     * @param text     content: what's embedded, searched and shown to the model
     * @param path     heading path below the document title
     * @param part     which piece of its section (a long section packs into several)
     */
    public record Chunk(String title, String text, List<String> path, int part) {}

    private Pack() {}

    /** Lines longer than MAX_CHARS cut into MAX_CHARS pieces; the only mid-text split. */
    public static List<String> splitLong(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            while (line.length() > MAX_CHARS) {
                out.add(line.substring(0, MAX_CHARS));
                line = line.substring(MAX_CHARS);
            }
            out.add(line);
        }
        return out;
    }

    /**
     * Fill chunks up to MAX_CHARS; when a block continues into a new chunk, its header lines are
     * repeated there (a field-definition row means nothing without its column names). Rows are
     * never cut in the middle.
     */
    public static List<String> pack(List<Block> blocks) {
        List<String> chunks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int size = 0; // sum of (len + 1) over cur
        for (Block b : blocks) {
            if (!cur.isEmpty()) { cur.add(""); size += 1; }
            boolean started = false;
            // Any line over the limit (e.g. a whole XML sample in one table cell) is cut, so no chunk exceeds it.
            for (String line : b.lines().stream().flatMap(l -> splitLong(l).stream()).toList()) {
                int hsize = started ? 0 : b.header().stream().mapToInt(h -> h.length() + 1).sum();
                if (!cur.isEmpty() && size + hsize + line.length() + 1 > MAX_CHARS) {
                    chunks.add(String.join("\n", cur).strip());
                    cur = new ArrayList<>();
                    size = 0;
                    started = false;
                }
                if (!started) {
                    for (String h : b.header()) { cur.add(h); size += h.length() + 1; }
                    started = true;
                }
                cur.add(line);
                size += line.length() + 1;
            }
        }
        if (!cur.isEmpty()) chunks.add(String.join("\n", cur).strip());
        chunks.removeIf(String::isEmpty);
        return chunks;
    }

    /** Sections to chunks: title = document title + heading path, " > " between. */
    public static List<Chunk> chunks(String docTitle, List<Section> sections) {
        List<Chunk> out = new ArrayList<>();
        for (Section s : sections) {
            List<String> pieces = pack(s.blocks());
            List<String> titleParts = new ArrayList<>(List.of(docTitle));
            titleParts.addAll(s.path());
            for (int part = 0; part < pieces.size(); part++)
                out.add(new Chunk(String.join(" > ", titleParts), pieces.get(part), s.path(), part));
        }
        return out;
    }
}
