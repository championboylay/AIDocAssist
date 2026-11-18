package com.sip.aidocassist.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * The access condition every retriever puts in its own WHERE clause (CLAUDE.md "Group filter, every
 * retriever"; SKB's VISIBLE): the record's allowed_group_ids overlap the user's groups, and its sync
 * entry is enabled and not deleted. Records outside it never enter a candidate list, never reach
 * fusion, never reach the model. Expects knowledge_records aliased as {@code k}.
 */
public final class Visible {

    private Visible() {}

    /** The SQL condition with one placeholder per group; use with {@link #args}. groups must not be empty. */
    public static String where(List<String> groups) {
        return "EXISTS (SELECT 1 FROM json_each(k.allowed_group_ids) WHERE value IN (" + String.join(",", groups.stream().map(g -> "?").toList()) + ")) "
                + "AND k.sync_source_id IN (SELECT id FROM sync_sources WHERE enabled = 1 AND deleted_at IS NULL)";
    }

    /** before + groups + after, in placeholder order. */
    public static List<Object> args(List<?> before, List<String> groups, List<?> after) {
        List<Object> all = new ArrayList<>(before);
        all.addAll(groups);
        all.addAll(after);
        return all;
    }

    /** One retriever's result: record id and its own score, best first. */
    record Ranked(String id, double score) {}
}
