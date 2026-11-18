package com.sip.aidocassist.sources;

import com.sip.aidocassist.ingestion.Filters;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * One item a source lists (port of SKB sources/base.py). Parsing is deferred: the sync reads
 * {@code file} only when {@code modifiedAt} is newer than what's stored.
 *
 * @param parentId   stable identity: the file's absolute path, forward slashes
 * @param modifiedAt when it last changed at the source (file modified time)
 * @param skip       why it is filtered out before parsing, or null
 * @param metadata   item-level facts copied onto every record (path, size, ...)
 */
public record SourceObject(String parentId, Instant modifiedAt, String title, String sourceUrl, Path file,
                           Map<String, Object> metadata, Filters.Skip skip) {}
