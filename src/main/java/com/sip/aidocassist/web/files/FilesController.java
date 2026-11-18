package com.sip.aidocassist.web.files;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.sources.UploadStore;
import com.sip.aidocassist.web.auth.User;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Download an uploaded original (port of SKB skb/api/files.py): the link of an Upload citation.
 * Allowed if the viewer is an admin, or the entry is enabled and the viewer's groups overlap its
 * tags (the same rule as search). Anything else, including a file that doesn't exist, is the same
 * 404, so the response never reveals what exists. Always an attachment, never shown in the browser.
 */
@RestController
public class FilesController {

    private final JdbcClient db;
    private final ObjectMapper json;
    private final UploadStore uploads;

    public FilesController(JdbcClient db, ObjectMapper json, UploadStore uploads) {
        this.db = db;
        this.json = json;
        this.uploads = uploads;
    }

    @GetMapping("/files/{entry}/{name}")
    ResponseEntity<Resource> download(@PathVariable String entry, @PathVariable String name, @AuthenticationPrincipal User user)
            throws Exception {
        var row = db.sql("SELECT default_group_ids, enabled FROM sync_sources WHERE id = ? AND source = 'upload' AND deleted_at IS NULL")
                .param(entry).query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2) == 1}).optional().orElse(null);
        if (row == null) return ResponseEntity.notFound().build();
        List<String> tags = json.readValue((String) row[0], new TypeReference<>() {});
        boolean allowed = user.isAdmin() || ((Boolean) row[1] && user.groups().stream().anyMatch(tags::contains));
        Path file = allowed ? uploads.download(entry, name) : null; // stays inside the entry's folder, or null
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new FileSystemResource(file));
    }
}
