package com.sip.aidocassist.sources;

import com.sip.aidocassist.chunking.Chunker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Where uploaded originals live: {@code <UPLOAD_DIR>/<entry id>/<file>}, one file per entry
 * (CLAUDE.md "Upload"; port of SKB sources/upload/adapter.py and admin_sync._receive). The app is
 * the only keeper of these files: back UPLOAD_DIR up together with the SQLite file.
 */
@Component
public class UploadStore {

    public static final long MAX_BYTES = 20L * 1024 * 1024;
    /** In-progress uploads are written under this prefix and never listed. */
    static final String TEMP_PREFIX = ".upload-";

    private final Path root;

    public UploadStore(@Value("${upload.dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public Path folder(String entryId) {
        return root.resolve(entryId);
    }

    /** The file name only, so an upload can't write outside its folder; null if nothing usable is left. Both / and \ count. */
    public static String safeName(String name) {
        if (name == null) return null;
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        while (base.startsWith(".")) base = base.substring(1);
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        return base.isBlank() ? null : base;
    }

    /** Why this (already safe) name can't be uploaded, or null. Size is checked separately. */
    public static String refusal(String name) {
        if (name == null) return "no usable file name";
        String ext = Chunker.extension(Path.of(name));
        if (!Chunker.SUPPORTED.contains(ext))
            return "type not supported (" + (ext.isEmpty() ? "no extension" : ext) + "); allowed: " + String.join(", ", Chunker.SUPPORTED);
        return null;
    }

    /** The entry's stored file, or null (none yet, or lost from disk). */
    public Path storedFile(String entryId) throws IOException {
        List<Path> files = files(folder(entryId));
        return files.isEmpty() ? null : files.get(0);
    }

    /** Regular files in an entry's folder, in-progress uploads left out. */
    static List<Path> files(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(TEMP_PREFIX)).sorted().toList();
        }
    }

    /**
     * Store an upload as the entry's one file. Written to a temp file in the same folder first and
     * then moved into place, so an oversized or interrupted upload never replaces a good file. A name
     * differing from the stored file only in capitals keeps the stored name (same file: its records
     * keep their ids). A new version under another name replaces the old file.
     *
     * @return the stored file name
     * @throws IllegalArgumentException with the reason to show, if refused
     */
    public String store(String entryId, String originalName, long size, InputStream content) throws IOException {
        String name = safeName(originalName);
        String reason = refusal(name);
        if (reason != null) throw new IllegalArgumentException(originalName + " (" + reason + ")");
        if (size > MAX_BYTES) throw new IllegalArgumentException(name + " (over " + MAX_BYTES / (1024 * 1024) + " MB)");
        Path folder = folder(entryId);
        Files.createDirectories(folder);
        for (Path existing : files(folder))
            if (existing.getFileName().toString().equalsIgnoreCase(name)) name = existing.getFileName().toString();
        Path tmp = folder.resolve(TEMP_PREFIX + UUID.randomUUID());
        try {
            long written = Files.copy(content, tmp);
            if (written > MAX_BYTES) throw new IllegalArgumentException(name + " (over " + MAX_BYTES / (1024 * 1024) + " MB)");
            Files.move(tmp, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        for (Path other : files(folder))
            if (!other.getFileName().toString().equals(name)) Files.delete(other); // one file per entry
        return name;
    }

    /** Remove an entry's folder and everything in it (Delete). */
    public void deleteFolder(String entryId) throws IOException {
        Path folder = folder(entryId);
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** The stored file for a download: inside the entry's folder, existing, not a temp file; else null. */
    public Path download(String entryId, String name) {
        Path base = folder(entryId).normalize();
        Path file = base.resolve(name).normalize();
        if (!file.getParent().equals(base) || !Files.isRegularFile(file)
                || file.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(TEMP_PREFIX)) return null;
        return file;
    }
}
