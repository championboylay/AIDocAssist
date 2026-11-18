package com.sip.aidocassist.parsing;

import com.sip.aidocassist.chunking.HeadingChunker;
import com.sip.aidocassist.chunking.Pack;
import com.sip.aidocassist.chunking.SheetChunker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The readers on small documents built in the test (SKB builds its self-check files the same way). */
class ReadersTest {

    // ---------- Excel (port of SKB chunking/strategies/sheet.py self-check) ----------

    private static ByteArrayInputStream save(XSSFWorkbook wb) throws Exception {
        var out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static void row(XSSFSheet s, int r, String... cells) {
        Row row = s.createRow(r);
        for (int c = 0; c < cells.length; c++) if (cells[c] != null) row.createCell(c).setCellValue(cells[c]);
    }

    private static void comment(XSSFSheet s, int r, int c, String text) {
        var drawing = s.createDrawingPatriarch();
        ClientAnchor anchor = s.getWorkbook().getCreationHelper().createClientAnchor();
        anchor.setRow1(r); anchor.setCol1(c); anchor.setRow2(r + 3); anchor.setCol2(c + 3);
        var cm = drawing.createCellComment(anchor);
        cm.setString(s.getWorkbook().getCreationHelper().createRichTextString(text));
        var cell = s.getRow(r).getCell(c) != null ? s.getRow(r).getCell(c) : s.getRow(r).createCell(c);
        cell.setCellComment(cm);
    }

    @Test
    void sheetTitleRowCommentsAndHiddenSheets() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet ws = wb.createSheet("Fields");
        row(ws, 0, "3.8 Request Message Fields");
        row(ws, 1, "Field Name", "Type", "Mandatory");
        row(ws, 2, "SourceSystem", "String(100)", "YES");
        row(ws, 3, "PicTrust", "String(1)", "NO");
        row(ws, 4, "RunTimestamp", "Timestamp", "YES");
        comment(ws, 3, 2, "Can we pass an empty value here?");
        XSSFSheet old = wb.createSheet("Old");
        row(old, 0, "Obsolete", "zzhiddenmarker");
        comment(old, 0, 1, "old note");
        wb.setSheetHidden(1, true);
        var book = XlsxReader.read(save(wb));
        var secs = SheetChunker.sections(book);
        assertEquals(List.of(List.of("Fields"), List.of("Fields", "Cell comments")), secs.stream().map(Pack.Section::path).toList());
        String text = String.join("\n", Pack.chunks("w", secs).stream().map(Pack.Chunk::text).toList());
        assertTrue(text.contains("| Field Name | Type | Mandatory |") && text.contains("| SourceSystem | String(100) | YES |"), text);
        assertTrue(text.contains("[cell C4, row: PicTrust] Can we pass an empty value here?"), "comment labelled with its row's field: " + text);
        assertFalse(text.contains("zzhiddenmarker") || text.contains("old note"), "hidden sheet and its comment skipped");
        assertEquals(List.of("Old"), book.hiddenSheets());
    }

    @Test
    void twoRealTablesOnOneSheetEachGetASection() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet ws = wb.createSheet("Spec");
        row(ws, 0, "3.8 Request Fields");
        row(ws, 1, "Field Name", "Type");
        for (int i = 0; i < 6; i++) row(ws, 2 + i, "Req" + i, "String(10)");
        row(ws, 9, "Code", "Meaning");
        for (int i = 0; i < 5; i++) row(ws, 10 + i, "E" + i, "error " + i);
        for (int r = 2; r < 8; r++) { // a remark column beside the first table's rows: not a table of its own
            ws.getRow(r).createCell(4).setCellValue("As above");
            ws.getRow(r).createCell(5).setCellValue("ok");
        }
        var secs = SheetChunker.sections(XlsxReader.read(save(wb)));
        assertEquals(List.of(List.of("Spec", "3.8 Request Fields"), List.of("Spec", "Table 2"), List.of("Spec")),
                secs.stream().map(Pack.Section::path).toList());
        assertTrue(secs.get(0).blocks().toString().contains("Req5") && secs.get(1).blocks().toString().contains("E4")
                && secs.get(2).blocks().toString().contains("As above"));
    }

    // ---------- Word ----------

    @Test
    void wordHeadingsFromStylesWithAutomaticNumbersTablesAndToc() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        // A multi-level decimal list "%1." / "%1.%2", like a spec template's heading numbering.
        CTAbstractNum abs = CTAbstractNum.Factory.newInstance();
        abs.setAbstractNumId(BigInteger.ZERO);
        String[] patterns = {"%1.", "%1.%2"};
        for (int i = 0; i < patterns.length; i++) {
            CTLvl lvl = abs.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(i));
            lvl.addNewStart().setVal(BigInteger.ONE);
            lvl.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            lvl.addNewLvlText().setVal(patterns[i]);
        }
        XWPFNumbering numbering = doc.createNumbering();
        BigInteger numId = numbering.addNum(numbering.addAbstractNum(new XWPFAbstractNum(abs)));

        doc.createParagraph().createRun().setText("Spec");
        doc.getParagraphs().get(0).setStyle("Title");
        var toc = doc.createParagraph(); toc.setStyle("TOC1"); toc.createRun().setText("Introduction 3");
        heading(doc, "Introduction", "Heading1", numId, 0);
        doc.createParagraph().createRun().setText("Why this exists.");
        heading(doc, "Auth", "Heading1", numId, 0);
        heading(doc, "Flow", "Heading2", numId, 1);
        XWPFTable t = doc.createTable(2, 2);
        t.getRow(0).getCell(0).setText("Field"); t.getRow(0).getCell(1).setText("Type");
        t.getRow(1).getCell(0).setText("token"); t.getRow(1).getCell(1).setText("String");
        heading(doc, "Errors", "Heading2", numId, 1);
        doc.createParagraph().createRun().setText("E1001 means both given.");

        var out = new ByteArrayOutputStream();
        doc.write(out);
        var chunks = Pack.chunks("Spec_v2", HeadingChunker.sections(DocxReader.read(new ByteArrayInputStream(out.toByteArray()))));
        assertEquals(List.of("Spec_v2 > Spec > 1 Introduction", "Spec_v2 > Spec > 2 Auth > 2.1 Flow", "Spec_v2 > Spec > 2 Auth > 2.2 Errors"),
                chunks.stream().map(Pack.Chunk::title).toList());
        assertEquals("| Field | Type |\n| --- | --- |\n| token | String |", chunks.get(1).text());
        assertFalse(chunks.stream().anyMatch(c -> c.text().contains("Introduction 3")), "TOC entries skipped");
    }

    private static void heading(XWPFDocument doc, String text, String style, BigInteger numId, int ilvl) {
        var p = doc.createParagraph();
        p.setStyle(style);
        p.setNumID(numId);
        p.setNumILvl(BigInteger.valueOf(ilvl));
        p.createRun().setText(text);
    }

    // ---------- PDF ----------

    @Test
    void pdfHeadingsParagraphsRuledTableAndRepeatedFooter() throws Exception {
        var out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int page = 1; page <= 3; page++) {
                PDPage p = new PDPage();
                doc.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                    float y = 700;
                    if (page == 1) {
                        y = text(cs, PDType1Font.HELVETICA_BOLD, 14, y, "3. Auth");
                        y = text(cs, PDType1Font.HELVETICA, 10, y - 6, "The client asks the gateway for a token and");
                        y = text(cs, PDType1Font.HELVETICA, 10, y, "sends it with every later call.");
                        y = text(cs, PDType1Font.HELVETICA_BOLD, 14, y - 10, "3.1 Flow");
                        y = text(cs, PDType1Font.HELVETICA, 10, y - 6, "The gateway forwards the request unchanged.");
                        table(cs, 72, y - 20, new String[][]{{"Code", "Meaning"}, {"E1001", "Both given"}, {"E1019", "Missing id"}});
                    } else {
                        y = text(cs, PDType1Font.HELVETICA_BOLD, 14, y, page == 2 ? "400 - Bad Request" : "4. Disclosure");
                        text(cs, PDType1Font.HELVETICA, 10, y - 6, "Body text on page " + page + " explains the error.");
                    }
                    text(cs, PDType1Font.HELVETICA, 8, 30, "Internal / Confidential   Page " + page); // footer on every page
                }
            }
            doc.save(out);
        }
        var secs = HeadingChunker.sections(PdfReader.read(new ByteArrayInputStream(out.toByteArray())));
        assertEquals(List.of(List.of("3. Auth"), List.of("3. Auth", "3.1 Flow"), List.of("3. Auth", "3.1 Flow", "400 - Bad Request"),
                        List.of("4. Disclosure")),
                secs.stream().map(Pack.Section::path).toList());
        String all = String.join("\n", Pack.chunks("d", secs).stream().map(Pack.Chunk::text).toList());
        assertTrue(all.contains("The client asks the gateway for a token and sends it with every later call."), "wrapped lines join: " + all);
        assertTrue(all.contains("| Code | Meaning |\n| --- | --- |\n| E1001 | Both given |\n| E1019 | Missing id |"), "ruled table: " + all);
        assertEquals(1, all.split("E1001", -1).length - 1, "table text not repeated as paragraphs");
        assertFalse(all.contains("Confidential"), "repeated footer dropped");
    }

    private static float text(PDPageContentStream cs, PDType1Font font, float size, float y, String s) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(72, y);
        cs.showText(s);
        cs.endText();
        return y - size * 1.2f;
    }

    private static void table(PDPageContentStream cs, float x, float top, String[][] cells) throws Exception {
        float w = 150, h = 20;
        for (int r = 0; r <= cells.length; r++) { cs.moveTo(x, top - r * h); cs.lineTo(x + w * cells[0].length, top - r * h); }
        for (int c = 0; c <= cells[0].length; c++) { cs.moveTo(x + c * w, top); cs.lineTo(x + c * w, top - cells.length * h); }
        cs.stroke();
        for (int r = 0; r < cells.length; r++)
            for (int c = 0; c < cells[r].length; c++) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 10);
                cs.newLineAtOffset(x + c * w + 4, top - r * h - 14);
                cs.showText(cells[r][c]);
                cs.endText();
            }
    }
}
