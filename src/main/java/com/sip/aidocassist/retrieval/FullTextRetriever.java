package com.sip.aidocassist.retrieval;

import com.sip.aidocassist.retrieval.Visible.Ranked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FTS5 full-text search (port of SKB retrieval/fulltext.py onto SQLite). The question's words,
 * stopwords removed, are OR-ed: a natural-language question almost never has all its words in one
 * chunk (SKB ORs plainto_tsquery's terms the same way). The porter tokenizer stems both sides.
 * Ranked with bm25, title weighted 10 against content 1 (SKB's A/B weights).
 */
@Component
public class FullTextRetriever {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private final JdbcClient db;

    public FullTextRetriever(JdbcClient db) {
        this.db = db;
    }

    /**
     * The FTS5 MATCH expression, or null if nothing searchable is left. Every term is a quoted
     * string, so FTS5 syntax in the question (" * - : ^ NEAR AND OR NOT) is plain text, never an operator.
     */
    static String matchExpression(String question) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(question.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (!RareTokenRetriever.STOPWORDS.contains(w)) terms.add("\"" + w + "\""); // a word has no quotes in it
        }
        return terms.isEmpty() ? null : String.join(" OR ", terms);
    }

    public List<Ranked> search(String question, List<String> groups, int k) {
        String match = matchExpression(question);
        if (match == null || groups.isEmpty()) return List.of();
        // bm25() is smaller for a better match; the score kept is negated so "higher is better" like the others.
        return db.sql("SELECT k.id, -bm25(knowledge_fts, 10.0, 1.0) AS score FROM knowledge_fts "
                        + "JOIN knowledge_records k ON k.rowid = knowledge_fts.rowid "
                        + "WHERE knowledge_fts MATCH ? AND " + Visible.where(groups) + " ORDER BY score DESC, k.id LIMIT ?")
                .params(Visible.args(List.of(match), groups, List.of(k)))
                .query((rs, i) -> new Ranked(rs.getString(1), rs.getDouble(2))).list();
    }
}
