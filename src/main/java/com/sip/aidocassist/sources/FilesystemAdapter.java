package com.sip.aidocassist.sources;

import com.sip.aidocassist.chunking.Chunker;
import com.sip.aidocassist.ingestion.Filters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every file under config.folder_path (port of SKB sources/filesystem/adapter.py). The listing is
 * a full inventory, so the sync deletes records of files that are no longer listed.
 */
public class FilesystemAdapter implements SourceAdapter {

    @Override
    public List<SourceObject> fetch(Map<String, Object> config) throws IOException {
        Path root = Path.of(String.valueOf(config.get("folder_path"))).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("folder_path not found: " + root);
        List<Path> files;
        // Files.walk throws on a subfolder it can't read (it doesn't skip it silently): with the
        // inventory pass, skipping would delete every record from that subfolder. The sync fails instead.
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<SourceObject> out = new ArrayList<>();
        for (Path file : files) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("path", file.toString());
            meta.put("relative_path", root.relativize(file).toString().replace('\\', '/'));
            meta.put("extension", Chunker.extension(file));
            meta.put("size_bytes", Files.size(file));
            out.add(new SourceObject(
                    // Full path, not relative: two entries can both hold e.g. README.md.
                    file.toString().replace('\\', '/'),
                    Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.MILLIS),
                    Chunker.title(file), file.toUri().toString(), file, meta, Filters.skipReason(root, file)));
        }
        return out;
    }
}
