package com.sip.aidocassist.chunking;

import com.sip.aidocassist.chunking.Pack.Block;
import com.sip.aidocassist.chunking.Pack.Section;
import com.sip.aidocassist.parsing.XlsxReader.Comment;
import com.sip.aidocassist.parsing.XlsxReader.Region;
import com.sip.aidocassist.parsing.XlsxReader.SheetData;
import com.sip.aidocassist.parsing.XlsxReader.Workbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel: one section per visible sheet (its blocks of cells as Markdown tables), plus one
 * "Cell comments" section per sheet. Port of SKB chunking/strategies/sheet.py.
 *
 * A sheet holding two or more REAL tables (at least MIN_TABLE_ROWS rows or MIN_TABLE_CHARS, and
 * not a remark column: at most 2 columns wide, inside a taller table's rows) gives each its own
 * section, titled by its one-cell title row ("3.8 CDD Risk Rating ...") or "Table n", so a chunk
 * never mixes two tables. Small blocks stay together in the sheet's own section, in order.
 */
public final class SheetChunker {

    // ponytail: SKB's thresholds, measured on 5 real workbooks (2026-10-02); revisit with more.
    static final int MIN_TABLE_ROWS = 5;
    static final int MIN_TABLE_CHARS = 1000;

    private SheetChunker() {}

    private record Item(Block block, Region region) {}

    public static List<Section> sections(Workbook wb) {
        List<Section> out = new ArrayList<>();
        for (SheetData sheet : wb.sheets()) {
            List<String> path = List.of(sheet.name().strip());
            List<Item> items = new ArrayList<>();
            for (Region r : sheet.regions()) {
                Block b = Tables.block(r.grid(), 1);
                if (b != null) items.add(new Item(b, r));
            }
            List<Region> onSheet = items.stream().map(Item::region).toList();
            List<Item> real = items.stream().filter(i -> isBig(i.block()) && !isRemarkColumn(i.region(), onSheet)).toList();
            if (real.size() < 2) { // one real table or none: the sheet is one section
                if (!items.isEmpty()) out.add(new Section(path, items.stream().map(Item::block).toList()));
                continue;
            }
            Map<String, Integer> used = new HashMap<>();
            for (int n = 0; n < real.size(); n++) {
                String t = title(real.get(n).region());
                String name = t.isEmpty() ? "Table " + (n + 1) : t;
                int k = used.merge(name, 1, Integer::sum);
                out.add(new Section(List.of(path.get(0), k == 1 ? name : name + " (" + k + ")"), List.of(real.get(n).block())));
            }
            List<Block> rest = items.stream().filter(i -> !real.contains(i)).map(Item::block).toList();
            if (!rest.isEmpty()) out.add(new Section(path, rest));
        }
        for (SheetData sheet : wb.sheets()) {
            if (sheet.comments().isEmpty()) continue;
            List<String> lines = new ArrayList<>();
            for (Comment c : sheet.comments()) {
                String where = c.rowLabel().isEmpty() ? "cell " + c.ref() : "cell " + c.ref() + ", row: " + c.rowLabel();
                lines.add("[" + where + "] " + c.text());
            }
            out.add(new Section(List.of(sheet.name().strip(), "Cell comments"), List.of(Block.text(lines))));
        }
        return out;
    }

    /** The table's one-cell title row above its column names ("3.8 CDD Risk Rating ..."), else "". */
    static String title(Region r) {
        var header = Tables.rows(r.grid(), 1).header();
        if (header.size() <= 1) return "";
        List<String> cells = header.get(0).stream().filter(t -> !t.isEmpty()).toList();
        return cells.size() == 1 ? cells.get(0).substring(0, Math.min(80, cells.get(0).length())) : "";
    }

    static boolean isBig(Block b) {
        return b.lines().size() >= MIN_TABLE_ROWS || b.lines().stream().mapToInt(l -> l.length() + 1).sum() >= MIN_TABLE_CHARS;
    }

    /** At most 2 columns wide and inside the rows of a taller block on the same sheet ("As above" notes). */
    static boolean isRemarkColumn(Region r, List<Region> others) {
        return r.cols() <= 2 && others.stream().anyMatch(o -> o != r && o.top() <= r.top() && r.bottom() <= o.bottom()
                && o.bottom() - o.top() > r.bottom() - r.top());
    }
}
