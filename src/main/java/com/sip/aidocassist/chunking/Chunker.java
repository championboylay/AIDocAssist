package com.sip.aidocassist.chunking;

import com.sip.aidocassist.chunking.Pack.Chunk;
import com.sip.aidocassist.chunking.Pack.Section;
import com.sip.aidocassist.parsing.DocxReader;
import com.sip.aidocassist.parsing.MarkdownReader;
import com.sip.aidocassist.parsing.PdfReader;
import com.sip.aidocassist.parsing.XlsxReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * File -> chunks, the reader and chunker picked by extension (SKB chunking/detector.py). The ONE
 * place to look when a file type is added. No database, no model: chunking is deterministic.
 */
public final class Chunker {

    /** @param hiddenSheets Excel sheets left out (the sync logs each as hidden-sheet) */
    public record Result(List<Chunk> chunks, List<String> hiddenSheets) {}

    public static final List<String> SUPPORTED = List.of(".docx", ".pdf", ".xlsx", ".md", ".txt");

    private Chunker() {}

    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** The file name without its extension: the first part of every chunk title. */
    public static String title(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    public static Result chunk(Path file) throws IOException {
        String ext = extension(file);
        List<Section> sections;
        List<String> hidden = List.of();
        try (InputStream in = Files.newInputStream(file)) {
            switch (ext) {
                case ".docx" -> sections = HeadingChunker.sections(DocxReader.read(in));
                case ".pdf" -> sections = HeadingChunker.sections(PdfReader.read(in));
                // Not valid UTF-8 -> replacement characters, as SKB (errors="replace").
                case ".md", ".txt" -> sections = HeadingChunker.sections(MarkdownReader.read(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                case ".xlsx" -> {
                    var wb = XlsxReader.read(in);
                    hidden = wb.hiddenSheets();
                    sections = SheetChunker.sections(wb);
                }
                default -> throw new IllegalArgumentException("unsupported type: " + ext);
            }
        }
        return new Result(Pack.chunks(title(file), sections), hidden);
    }
}
