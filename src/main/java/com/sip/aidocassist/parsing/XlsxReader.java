package com.sip.aidocassist.parsing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel (.xlsx) with Apache POI, cells read directly. Each visible sheet becomes its blocks of
 * cells ("regions": cells connected through shared edges, i.e. separated by empty rows/columns,
 * which is what Docling calls a table) and its cell comments. Hidden sheets are listed so the
 * sync can log them, never read.
 *
 * Formulas show their last calculated value (what a reader sees), never the formula.
 */
public final class XlsxReader {

    /** A block of cells; top/left inclusive, bottom/right exclusive (0-based sheet rows/columns). */
    public record Region(int top, int bottom, int left, int right, List<List<String>> grid) {
        public int cols() { return right - left; }
    }

    /** A cell comment, labelled with its cell and the first filled cell of its row (usually the field name). */
    public record Comment(String ref, String rowLabel, String text) {}

    public record SheetData(String name, List<Region> regions, List<Comment> comments) {}

    public record Workbook(List<SheetData> sheets, List<String> hiddenSheets) {}

    private static final DataFormatter FORMAT = new DataFormatter();

    private XlsxReader() {}

    public static Workbook read(InputStream in) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            List<SheetData> sheets = new ArrayList<>();
            List<String> hidden = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                XSSFSheet sheet = wb.getSheetAt(i);
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) { hidden.add(sheet.getSheetName()); continue; }
                Map<Long, String> cells = cells(sheet);
                sheets.add(new SheetData(sheet.getSheetName(), regions(cells), comments(sheet, cells)));
            }
            return new Workbook(sheets, hidden);
        }
    }

    private static long key(int row, int col) { return ((long) row << 20) | col; }

    /** Non-empty cells by position, values as displayed. */
    private static Map<Long, String> cells(Sheet sheet) {
        Map<Long, String> out = new HashMap<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                String v = value(cell).strip();
                if (!v.isEmpty()) out.put(key(cell.getRowIndex(), cell.getColumnIndex()), v);
            }
        }
        return out;
    }

    static String value(Cell cell) {
        if (cell.getCellType() != CellType.FORMULA) return FORMAT.formatCellValue(cell);
        return switch (cell.getCachedFormulaResultType()) { // the stored result; formulas aren't recalculated
            case NUMERIC -> FORMAT.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue()).toUpperCase();
            default -> "";
        };
    }

    /** Connected blocks of non-empty cells, in row-major order of their first cell. */
    static List<Region> regions(Map<Long, String> cells) {
        List<Long> order = new ArrayList<>(cells.keySet());
        order.sort(Comparator.naturalOrder()); // key = row << 20 | col, so this is row-major
        Map<Long, Boolean> seen = new HashMap<>();
        List<Region> out = new ArrayList<>();
        for (long start : order) {
            if (seen.containsKey(start)) continue;
            int top = Integer.MAX_VALUE, bottom = 0, left = Integer.MAX_VALUE, right = 0;
            ArrayDeque<Long> todo = new ArrayDeque<>(List.of(start));
            seen.put(start, true);
            while (!todo.isEmpty()) {
                long k = todo.poll();
                int r = (int) (k >> 20), c = (int) (k & 0xFFFFF);
                top = Math.min(top, r); bottom = Math.max(bottom, r + 1);
                left = Math.min(left, c); right = Math.max(right, c + 1);
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    if (r + d[0] < 0 || c + d[1] < 0) continue;
                    long n = key(r + d[0], c + d[1]);
                    if (cells.containsKey(n) && seen.putIfAbsent(n, true) == null) todo.add(n);
                }
            }
            List<List<String>> grid = new ArrayList<>();
            for (int r = top; r < bottom; r++) {
                List<String> row = new ArrayList<>();
                for (int c = left; c < right; c++) row.add(cells.getOrDefault(key(r, c), ""));
                grid.add(row);
            }
            out.add(new Region(top, bottom, left, right, grid));
        }
        return out;
    }

    private static List<Comment> comments(XSSFSheet sheet, Map<Long, String> cells) {
        List<Map.Entry<CellAddress, XSSFComment>> list = new ArrayList<>(sheet.getCellComments().entrySet());
        list.sort(Comparator.comparing((Map.Entry<CellAddress, XSSFComment> e) -> e.getKey().getRow())
                .thenComparing(e -> e.getKey().getColumn()));
        List<Comment> out = new ArrayList<>();
        for (var e : list) {
            String text = e.getValue().getString() == null ? "" : e.getValue().getString().getString().strip();
            if (text.isEmpty()) continue;
            int row = e.getKey().getRow();
            String label = cells.entrySet().stream()
                    .filter(c -> (int) (c.getKey() >> 20) == row)
                    .min(Map.Entry.comparingByKey())
                    .map(c -> String.join(" ", c.getValue().split("\\s+")))
                    .map(s -> s.length() > 60 ? s.substring(0, 60) : s).orElse("");
            out.add(new Comment(e.getKey().formatAsString(), label, text));
        }
        return out;
    }
}
