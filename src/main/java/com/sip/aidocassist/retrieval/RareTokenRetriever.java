package com.sip.aidocassist.retrieval;

import com.sip.aidocassist.retrieval.Visible.Ranked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact substring match on the question's rarest words (port of SKB retrieval/raretoken.py).
 * Catches identifiers the other two miss: field names (PicTrust, CONNECTED_PARTY), codes, versions,
 * which full-text stemming mangles and embeddings blur.
 */
@Component
public class RareTokenRetriever {

    // Filtered BEFORE IDF: casual words ("thanks") occur in few documents and would otherwise score as rare by accident.
    static final Set<String> STOPWORDS = Set.copyOf(Arrays.asList("""
            a about above after again against all also am an and any are as at be because been before being
            below between both but by can could did do does doing down during each few for from further had has
            have having he her here hers him his how i if in into is it its itself just me more most my no nor
            not now of off on once only or other our ours out over own same she should so some such than that
            the their theirs them then there these they this those through to too under until up very was we
            were what when where which while who whom why will with would you your yours
            please thanks thank hi hello hey regards ok okay yes yeah tell know need want get got give find
            show explain describe mean means use used using let say said look looks like""".split("\\s+")));
    static final int MIN_LEN = 3;
    static final int MAX_TOKENS = 3;          // search on the rarest few only
    static final double MAX_DF_RATIO = 0.25;  // a word in >25% of visible chunks isn't "rare", however specific it looks

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./:-]*[A-Za-z0-9]");
    private static final String HAYSTACK = "(coalesce(k.title, '') || ' ' || k.content)";

    /** A rare token and its IDF among the visible records. */
    public record Picked(String token, double idf) {}

    private final JdbcClient db;

    public RareTokenRetriever(JdbcClient db) {
        this.db = db;
    }

    static List<String> tokens(String question) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(question.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group();
            boolean shortNumber = t.chars().allMatch(Character::isDigit) && t.length() < 4; // "row 35"; 4+ digits are codes/ticket ids
            if (t.length() >= MIN_LEN && !STOPWORDS.contains(t) && !shortNumber) out.add(t);
        }
        return new ArrayList<>(out);
    }

    /** A LIKE pattern matching t anywhere, with % _ and \ escaped (used with ESCAPE '\'). SQLite's LIKE ignores ASCII case. */
    static String like(String t) {
        return "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** The rarest question tokens that occur in at least one visible record, rarest first. */
    public List<Picked> pick(String question, List<String> groups) {
        List<String> toks = tokens(question);
        if (toks.isEmpty() || groups.isEmpty()) return List.of();
        // ponytail: one LIKE scan per token; fine for thousands of chunks. An FTS5 trigram index is the upgrade.
        int n = db.sql("SELECT count(*) FROM knowledge_records k WHERE " + Visible.where(groups))
                .params(groups).query(Integer.class).single();
        List<Picked> scored = new ArrayList<>();
        for (String t : toks) {
            int df = db.sql("SELECT count(*) FROM knowledge_records k WHERE " + HAYSTACK + " LIKE ? ESCAPE '\\' AND " + Visible.where(groups))
                    .params(Visible.args(List.of(like(t)), groups, List.of())).query(Integer.class).single();
            if (df > 0 && df <= Math.max(1, MAX_DF_RATIO * n)) scored.add(new Picked(t, Math.log((double) n / df)));
        }
        scored.sort(Comparator.comparingDouble(Picked::idf).reversed());
        return scored.subList(0, Math.min(MAX_TOKENS, scored.size()));
    }

    /** Records by summed IDF of the rare tokens they contain, best first. */
    public List<Ranked> search(String question, List<String> groups, List<Picked> picked, int k) {
        if (picked.isEmpty() || groups.isEmpty()) return List.of();
        String score = String.join(" + ", picked.stream().map(p -> "CASE WHEN " + HAYSTACK + " LIKE ? ESCAPE '\\' THEN ? ELSE 0 END").toList());
        String any = String.join(" OR ", picked.stream().map(p -> HAYSTACK + " LIKE ? ESCAPE '\\'").toList());
        List<Object> before = new ArrayList<>();
        for (Picked p : picked) { before.add(like(p.token())); before.add(p.idf()); }
        List<Object> after = new ArrayList<>();
        for (Picked p : picked) after.add(like(p.token()));
        after.add(k);
        // Ties are common (one rare token -> every match scores the same): shorter chunk first (the token is
        // a bigger share of it), then id, so the same question always ranks the same.
        return db.sql("SELECT k.id, " + score + " AS score FROM knowledge_records k WHERE " + Visible.where(groups)
                        + " AND (" + any + ") ORDER BY score DESC, length(k.content), k.id LIMIT ?")
                .params(Visible.args(before, groups, after))
                .query((rs, i) -> new Ranked(rs.getString(1), rs.getDouble(2))).list();
    }
}
