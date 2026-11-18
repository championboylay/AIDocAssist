package com.sip.aidocassist.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Port of SKB ingestion/filters.py self-check, plus the file-name rules. */
class FiltersTest {

    @Test
    void secretPatterns() {
        assertEquals("aws-access-key", Filters.findSecret("key AKIAIOSFODNN7EXAMPLE here"));
        assertEquals("private-key", Filters.findSecret("-----BEGIN RSA PRIVATE KEY-----\nMIIE"));
        assertEquals("private-key", Filters.findSecret("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assertEquals("github-token", Filters.findSecret("token ghp_" + "a".repeat(36)));
        assertEquals("slack-token", Filters.findSecret("xoxb-1234567890-abcdef"));
        assertEquals("google-api-key", Filters.findSecret("AIza" + "B".repeat(35)));
        assertEquals("aws-secret-key", Filters.findSecret("aws_secret_access_key = " + "A".repeat(40)));
        assertEquals("jwt", Filters.findSecret("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"));
        // Real spec content must pass: field names, message ids, error codes.
        assertNull(Filters.findSecret("CustomerID VARCHAR(20), RTSubject request, error code E1001"));
        assertNull(Filters.findSecret("-----BEGIN PUBLIC KEY-----"));
    }

    @Test
    void fileRules(@TempDir Path root) throws Exception {
        Path doc = Files.writeString(root.resolve("Spec.docx"), "x");
        assertNull(Filters.skipReason(root, doc));
        assertEquals("filename-pattern", Filters.skipReason(root, Files.writeString(root.resolve("~$Spec.docx"), "x")).reason());
        assertEquals("filename-pattern", Filters.skipReason(root, Files.writeString(root.resolve("draft.tmp"), "x")).reason());
        Files.createDirectories(root.resolve(".git"));
        assertEquals("filename-pattern", Filters.skipReason(root, Files.writeString(root.resolve(".git/notes.md"), "x")).reason());
        assertEquals(new Filters.Skip("unsupported-type", ".png"), Filters.skipReason(root, Files.writeString(root.resolve("shot.png"), "x")));
        assertEquals(new Filters.Skip("empty", "0 bytes"), Filters.skipReason(root, Files.writeString(root.resolve("blank.txt"), "")));
    }
}
