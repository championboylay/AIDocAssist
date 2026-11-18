package com.sip.aidocassist;

import javax.sql.DataSource;

/** Lets tests in other packages build the app's real SQLite DataSource (same URL and pragmas). */
public final class AiDocAssistApplicationAccess {
    private AiDocAssistApplicationAccess() {}

    public static DataSource dataSource(String path) throws Exception {
        return new AiDocAssistApplication().dataSource(path);
    }
}
