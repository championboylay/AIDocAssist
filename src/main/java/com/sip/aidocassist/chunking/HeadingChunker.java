package com.sip.aidocassist.chunking;

import com.sip.aidocassist.chunking.Pack.Block;
import com.sip.aidocassist.chunking.Pack.Section;
import com.sip.aidocassist.parsing.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One section per lowest heading; the path of headings above it becomes the title. Used for Word,
 * PDF, Markdown and text. Port of SKB chunking/strategies/heading.py.
 *
 * ponytail: no "[Text in figure]" blocks; SKB gets those from Docling's OCR/diagram text, which
 * the Java readers don't extract.
 */
public final class HeadingChunker {

    private static final Set<String> TOC_TITLES = Set.of("table of contents", "contents");
    // "3.", "3.1", "3.2.1 " at the start of a heading. 1-2 digit first part only, so "400 - Bad Request"
    // isn't read as section 400.
    private static final Pattern SECTION_NUMBER = Pattern.compile("^(\\d{1,2}(?:\\.\\d+)*)\\.?\\s");

    private HeadingChunker() {}

    public static List<Section> sections(List<Item> items) {
        List<Section> out = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        // Path = root + heads. root is the document title; heads are indexed by heading level. Kept
        // apart so a level-1 heading never overwrites the title (Markdown "# X" is the title, "## X" level 1).
        List<String> root = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        boolean skipping = false;
        int lastNumbered = 0; // level of the last numbered heading seen, for PDF level inference

        for (Item item : items) {
            switch (item.kind()) {
                case TITLE -> {
                    close(out, root, heads, blocks, skipping);
                    root = new ArrayList<>(List.of(item.text().strip()));
                    heads = new ArrayList<>();
                    skipping = false;
                }
                case HEADING -> {
                    close(out, root, heads, blocks, skipping);
                    String text = item.text().strip();
                    int level = item.level();
                    // PDFs have no heading styles, so every heading arrives as level 1. Then the level comes
                    // from the section number ("3.1" -> 2), and an unnumbered heading ("Step 1 - ...") nests
                    // one level under the last numbered one. Word levels (> 1) are kept as they are.
                    Matcher num = SECTION_NUMBER.matcher(text);
                    boolean numbered = num.find();
                    if (numbered) lastNumbered = num.group(1).split("\\.").length;
                    if (level == 1) level = numbered ? lastNumbered : (lastNumbered > 0 ? lastNumbered + 1 : 1);
                    heads = new ArrayList<>(heads.subList(0, Math.min(Math.max(level - 1, 0), heads.size())));
                    heads.add(text);
                    skipping = TOC_TITLES.contains(text.toLowerCase(Locale.ROOT));
                }
                case TABLE -> {
                    Block b = Tables.block(item.grid(), 1);
                    if (b != null) blocks.add(b);
                }
                case TEXT -> {
                    String text = item.text().strip();
                    // A bare number paragraph (a code listing's line-number column repeated as loose
                    // paragraphs "1", "2", ...) carries nothing searchable.
                    if (text.isEmpty() || text.chars().allMatch(Character::isDigit)) continue;
                    blocks.add(Block.text(Pack.splitLong(text)));
                }
            }
        }
        close(out, root, heads, blocks, skipping);
        return out;
    }

    private static void close(List<Section> out, List<String> root, List<String> heads, List<Block> blocks, boolean skipping) {
        if (!blocks.isEmpty() && !skipping) {
            List<String> path = new ArrayList<>(root);
            path.addAll(heads);
            out.add(new Section(path, List.copyOf(blocks)));
        }
        blocks.clear();
    }
}
