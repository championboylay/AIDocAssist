package com.sip.aidocassist.sources;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Turns a sync_sources.config into the items currently at the source (CLAUDE.md "Code structure").
 * Knows nothing about chunking, tags, embeddings or the database. Filesystem now, Upload in Phase 3.
 */
public interface SourceAdapter {
    List<SourceObject> fetch(Map<String, Object> config) throws IOException;
}
