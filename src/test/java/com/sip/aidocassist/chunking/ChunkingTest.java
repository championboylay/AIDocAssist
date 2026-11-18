package com.sip.aidocassist.chunking;

import com.sip.aidocassist.chunking.Pack.Block;
import com.sip.aidocassist.parsing.MarkdownReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.sip.aidocassist.chunking.Pack.MAX_CHARS;
import static org.junit.jupiter.api.Assertions.*;

/** SKB's chunking self-checks (base.py, tables.py, strategies/heading.py), ported. */
class ChunkingTest {

    // ---------- pack (SKB chunking/base.py) ----------

    @Test
    void longTableSplitsBetweenRowsRepeatingTheHeader() {
        List<String> header = List.of("| Field | Type |", "| --- | --- |");
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) rows.add(String.format("| field_%03d | %s |", i, "x".repeat(80)));
        List<String> pieces = Pack.pack(List.of(Block.text(List.of("intro paragraph")), new Block(header, rows)));
        assertTrue(pieces.size() > 1);
        assertTrue(pieces.stream().allMatch(p -> p.length() <= MAX_CHARS));
        for (String p : pieces) {
            List<String> lines = Arrays.asList(p.split("\n"));
            assertEquals("| --- | --- |", lines.get(lines.indexOf("| Field | Type |") + 1), "every table piece repeats the header");
        }
        List<String> body = pieces.stream().flatMap(p -> Arrays.stream(p.split("\n"))).filter(l -> l.startsWith("| field_")).toList();
        assertEquals(rows, body, "no row lost or duplicated");
    }

    @Test
    void overlongLinesAreCutSoNoChunkExceedsTheLimit() {
        List<String> pieces = Pack.pack(List.of(Block.text(Pack.splitLong("y".repeat(MAX_CHARS * 2 + 5)))));
        assertFalse(pieces.isEmpty());
        assertTrue(pieces.stream().allMatch(p -> p.length() <= MAX_CHARS));
        // A whole XML sample in one table cell (seen in CIMBSG Upgrade Real-time Specification_v2.2.docx).
        List<String> cell = Pack.pack(List.of(Block.text(List.of("<x>" + "z".repeat(6000) + "</x>"))));
        assertEquals(2, cell.size());
        assertTrue(cell.stream().allMatch(p -> p.length() <= MAX_CHARS));
    }

    // ---------- tables (SKB chunking/tables.py) ----------

    private static List<List<String>> grid(String[]... rows) {
        return Arrays.stream(rows).map(Arrays::asList).toList();
    }

    @Test
    void sheetStyleTableTitleRowEmptyCellAndPipe() {
        var t = grid(new String[]{"3.8 Request Fields", "", ""}, new String[]{"Field Name", "Type", "Remark"},
                new String[]{"SourceSystem", "String(100)", ""}, new String[]{"Code", "A|B", "  either   one "});
        assertEquals(new Block(List.of("| 3.8 Request Fields |  |  |", "| Field Name | Type | Remark |", "| --- | --- | --- |"),
                        List.of("| SourceSystem | String(100) |  |", "| Code | A\\|B | either one |")),
                Tables.block(t, 1));
    }

    @Test
    void codeListingTemplateAndLabelTables() {
        // A "line number | code" listing -> just the code (a repeated number allowed).
        var listing = grid(new String[]{"1", "<Envelope>"}, new String[]{"2", "  <Body/>"}, new String[]{"3", "</Envelope>"}, new String[]{"3", ""});
        assertEquals(Block.text(List.of("<Envelope>", "  <Body/>", "</Envelope>")), Tables.block(listing, 1));
        assertNull(Tables.codeLines(grid(new String[]{"2", "a"}, new String[]{"3", "b"}, new String[]{"4", "c"})), "must start at 1");
        assertNull(Tables.codeLines(grid(new String[]{"1", "a"}, new String[]{"5", "b"}, new String[]{"6", "c"})), "must count up by 1");
        assertNull(Tables.codeLines(grid(new String[]{"No", "Item"}, new String[]{"1", "a"}, new String[]{"2", "b"})), "a header row means a data table");
        // An unfilled template (names + blank rows) is dropped; a filled one-row table is kept; a 1x1 label is a plain line.
        assertNull(Tables.block(grid(new String[]{"Mnemonic", "Document Details"}, new String[]{"", ""}, new String[]{"", ""}), 1));
        assertEquals(Block.text(List.of("| Version | 2.2 |")), Tables.block(grid(new String[]{"Version", "2.2"}), 1));
        assertEquals(Block.text(List.of("| SIBS |")), Tables.block(grid(new String[]{"SIBS"}), 1));
    }

    // ---------- headings (SKB chunking/strategies/heading.py) ----------

    private static List<List<String>> paths(String md) {
        return HeadingChunker.sections(MarkdownReader.read(md)).stream().map(Pack.Section::path).toList();
    }

    @Test
    void markdownTitleStaysTheRoot() {
        assertEquals(List.of(List.of("Runbook"), List.of("Runbook", "Restart"), List.of("Runbook", "Restart", "Verify"), List.of("Runbook", "Rollback")),
                paths("# Runbook\n\nIntro.\n\n## Restart\n\nStop then start.\n\n### Verify\n\nCheck health.\n\n## Rollback\n\nRevert.\n"));
    }

    @Test
    void flatHeadingLevelsComeFromSectionNumbers() {
        // All level 1, as PDFs arrive: levels from numbers; unnumbered nest under the last numbered; "400 - ..." is not section 400.
        assertEquals(List.of(List.of("3. Auth"), List.of("3. Auth", "3.1 Flow"), List.of("3. Auth", "3.1 Flow", "Step 1 - Call"),
                        List.of("3. Auth", "3.1 Flow", "400 - Bad Request"), List.of("4. Disclosure")),
                paths("## 3. Auth\n\na\n\n## 3.1 Flow\n\nb\n\n## Step 1 - Call\n\nc\n\n## 400 - Bad Request\n\nd\n\n## 4. Disclosure\n\ne\n"));
    }

    @Test
    void tableOfContentsAndBareNumbersAreDropped() {
        assertEquals(List.of(List.of("1 Intro")), paths("## Table of Contents\n\n1 Intro\n\n## 1 Intro\n\nbody\n"));
        var secs = HeadingChunker.sections(MarkdownReader.read("## A\n\n52\n\n53\n\nRetry 3 times.\n"));
        assertEquals(List.of(new Pack.Section(List.of("A"), List.of(Block.text(List.of("Retry 3 times.")))) ), secs);
    }

    @Test
    void markdownTablesListsAndChunkTitles() {
        var chunks = Pack.chunks("Runbook", HeadingChunker.sections(MarkdownReader.read(
                "## Ports\n\n| Service | Port |\n|---|---|\n| api | 8080 |\n\n- first\n- second\n")));
        assertEquals(1, chunks.size());
        assertEquals("Runbook > Ports", chunks.get(0).title());
        assertEquals("| Service | Port |\n| --- | --- |\n| api | 8080 |\n\nfirst\n\nsecond", chunks.get(0).text());
    }
}
