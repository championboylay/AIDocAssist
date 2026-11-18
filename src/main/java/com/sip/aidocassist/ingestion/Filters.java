package com.sip.aidocassist.ingestion;

import com.sip.aidocassist.chunking.Chunker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content filters applied before anything is stored (CLAUDE.md "Content filtering"). Port of SKB
 * ingestion/filters.py (secret patterns) and the file rules of sources/filesystem/adapter.py.
 */
public final class Filters {

    // ponytail: well-known credential shapes only. Catches pasted keys, not every possible secret;
    // add patterns as real leaks show up in ingest_skips review.
    private static final Map<String, Pattern> SECRETS = new LinkedHashMap<>();
    static {
        SECRETS.put("aws-access-key", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"));
        SECRETS.put("aws-secret-key", Pattern.compile("(?i)aws_secret_access_key\\s*[:=]\\s*['\"]?[A-Za-z0-9/+=]{40}"));
        SECRETS.put("private-key", Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY( BLOCK)?-----"));
        SECRETS.put("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"));
        SECRETS.put("slack-token", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"));
        SECRETS.put("google-api-key", Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}\\b"));
        SECRETS.put("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));
    }

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules");
    private static final Set<String> SKIP_SUFFIXES = Set.of(".tmp", ".lock");
    private static final Set<String> SKIP_NAMES = Set.of("thumbs.db", ".ds_store", "desktop.ini");

    /** Why a file is left out: reason (an ingest_skips reason) and detail. */
    public record Skip(String reason, String detail) {}

    private Filters() {}

    /** Name of the first credential pattern found in text, else null. Never returns the secret itself. */
    public static String findSecret(String text) {
        for (var e : SECRETS.entrySet()) if (e.getValue().matcher(text).find()) return e.getKey();
        return null;
    }

    /** Why this file (relative to its sync folder) is not read, or null if it is. */
    public static Skip skipReason(Path root, Path file) throws IOException {
        for (Path part : root.relativize(file))
            if (SKIP_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) return new Skip("filename-pattern", "inside .git/ or node_modules/");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = Chunker.extension(file);
        if (name.startsWith("~$") || SKIP_SUFFIXES.contains(ext) || SKIP_NAMES.contains(name))
            return new Skip("filename-pattern", "lock/temp/system file");
        if (!Chunker.SUPPORTED.contains(ext)) return new Skip("unsupported-type", ext.isEmpty() ? "(no extension)" : ext);
        if (Files.size(file) == 0) return new Skip("empty", "0 bytes");
        return null;
    }
}
