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

/**
 * Files an admin uploaded, stored in {@code <UPLOAD_DIR>/<entry id>/} (port of SKB
 * sources/upload/adapter.py). Read like the filesystem source; what differs: parent_id is
 * {@code <entry id>/<file>} (independent of where UPLOAD_DIR points), the link is the app's own
 * access-checked download route, and the listing is complete (the app owns the folder).
 */
public class UploadAdapter implements SourceAdapter {

    private final UploadStore store;

    public UploadAdapter(UploadStore store) {
        this.store = store;
    }

    @Override
    public List<SourceObject> fetch(Map<String, Object> config) throws IOException {
        String entry = String.valueOf(config.get("folder"));
        Path folder = store.folder(entry);
        // Missing folder = misconfigured UPLOAD_DIR or a lost disk, not "the file was deleted": fail
        // loudly instead of letting the inventory pass remove the entry's records.
        if (!Files.isDirectory(folder)) throw new IOException("Upload folder not found: " + folder + ". Check UPLOAD_DIR.");
        List<SourceObject> out = new ArrayList<>();
        for (Path file : UploadStore.files(folder)) {
            String name = file.getFileName().toString();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("path", name);
            meta.put("relative_path", name);
            meta.put("extension", Chunker.extension(file));
            meta.put("size_bytes", Files.size(file));
            out.add(new SourceObject(entry + "/" + name,
                    Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.MILLIS),
                    Chunker.title(file), "/files/" + entry + "/" + name, file, meta, Filters.skipReason(folder, file)));
        }
        return out;
    }
}
