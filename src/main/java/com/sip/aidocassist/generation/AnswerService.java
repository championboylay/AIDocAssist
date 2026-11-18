package com.sip.aidocassist.generation;

import com.sip.aidocassist.generation.Evidence.Draft;
import com.sip.aidocassist.generation.Evidence.Quote;
import com.sip.aidocassist.generation.Reformer.Exchange;
import com.sip.aidocassist.llm.Chat;
import com.sip.aidocassist.retrieval.Fusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * One chat turn (CLAUDE.md "Generation and citations"; port of SKB generation/answer.py):
 * rewrite a follow-up -> search with the user's groups -> the 4b model answers with quotes ->
 * deterministic citation check -> one retry -> a cited answer or an honest abstention.
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    // Unanswered turns (SKB's wording, owner's decision there 2026-10-01).
    public static final String NO_EVIDENCE = "I couldn't find enough evidence in the current documents to answer this question confidently.";
    public static final String NOT_ANSWERED = NO_EVIDENCE;
    public static final String NOT_VERIFIED = "I found a possible answer, but its citations didn't check out against the sources, so I'm not showing it.";
    /** "Suggested" on an answered turn: uncited evidence scoring at least this share of the weakest cited one. */
    // ponytail: 0.5 is SKB's first guess; tune against real questions in Phase 7.
    static final double SUGGEST_RATIO = 0.5;
    static final int SUGGEST_UNANSWERED = 3;

    /** A citation as stored on the assistant message: label, and source/source_id from the database. */
    public record Citation(String label, String source, String sourceId, String excerpt) {}

    /** Evidence retrieved but not cited, with its fused rank (1-based). */
    public record Suggestion(String source, String sourceId, int rank, double score) {}

    /**
     * The result of a turn. reformedQuery = the rewrite actually searched (null if none);
     * outcome = answered | no-evidence | not-answerable | not-verified (for the log and tests).
     */
    public record Turn(String answer, boolean abstained, List<Citation> citations, List<Suggestion> suggested,
                       String reformedQuery, int retrieved, int attempts, String outcome, List<String> problems, Map<String, Long> millis) {}

    private final Fusion fusion;
    private final Reformer reformer;
    private final Chat ollama;
    private final JdbcClient db;

    public AnswerService(Fusion fusion, Reformer reformer, Chat ollama, JdbcClient db) {
        this.fusion = fusion;
        this.reformer = reformer;
        this.ollama = ollama;
        this.db = db;
    }

    /** history = the thread's earlier turns, oldest first (empty for a first question). */
    public Turn answer(String question, List<String> groups, List<Exchange> history) {
        Map<String, Long> ms = new LinkedHashMap<>();
        long[] clock = {System.currentTimeMillis()};
        java.util.function.Consumer<String> mark = name -> { long now = System.currentTimeMillis(); ms.put(name, now - clock[0]); clock[0] = now; };

        String reformed = reformer.rewrite(question, history);
        if (reformed != null) mark.accept("reform");
        String query = reformed != null ? reformed : question;

        Fusion.Result found = fusion.search(query, groups); // the group filter is inside every retriever
        mark.accept("search");
        List<Fusion.Hit> hits = found.hits();
        if (hits.isEmpty()) return done(question, new Turn(NO_EVIDENCE, true, List.of(), List.of(), reformed, 0, 0, "no-evidence", List.of(), ms));
        Map<String, Evidence> evidence = evidence(hits);

        List<String> dropped = new ArrayList<>();
        Draft out = prepare(Draft.from(ollama.chat(PromptBuilder.ANSWER_SYSTEM, PromptBuilder.answerPrompt(query, evidence, null, null),
                PromptBuilder.ANSWER_SCHEMA)), evidence, dropped);
        mark.accept("generate");
        int attempts = 1;
        List<String> problems = out.answerable() ? CitationValidator.validate(out.answer(), out.citations(), evidence) : List.of();
        if (!problems.isEmpty()) { // exactly one retry, told what failed
            out = prepare(Draft.from(ollama.chat(PromptBuilder.ANSWER_SYSTEM, PromptBuilder.answerPrompt(query, evidence, problems, out),
                    PromptBuilder.ANSWER_SCHEMA)), evidence, dropped);
            mark.accept("retry");
            attempts = 2;
            problems = out.answerable() ? CitationValidator.validate(out.answer(), out.citations(), evidence) : List.of();
        }

        if (!out.answerable())
            return done(question, new Turn(NOT_ANSWERED, true, List.of(), suggest(hits, Set.of()), reformed, hits.size(), attempts,
                    "not-answerable", List.of(), ms));
        if (!problems.isEmpty())
            return done(question, new Turn(NOT_VERIFIED, true, List.of(), suggest(hits, Set.of()), reformed, hits.size(), attempts,
                    "not-verified", problems, ms));
        Renumbered r = renumber(out.answer(), out.citations(), evidence);
        Set<String> cited = new LinkedHashSet<>();
        for (Citation c : r.citations()) cited.add(c.sourceId());
        return done(question, new Turn(r.answer(), false, r.citations(), suggest(hits, cited), reformed, hits.size(), attempts,
                "answered", List.of(), ms));
    }

    /** One log line per turn, to trace "it couldn't answer me": question, rewrite, outcome, timings. Never answers or quotes. */
    private Turn done(String question, Turn t) {
        log.info("turn outcome={} retrieved={} cited={} attempts={} ms={} question=\"{}\"{}{}", t.outcome(), t.retrieved(),
                t.citations().size(), t.attempts(), t.millis(), question.replace('"', '\''),
                t.reformedQuery() == null ? "" : " rewrite=\"" + t.reformedQuery().replace('"', '\'') + "\"",
                t.problems().isEmpty() ? "" : " problems=" + t.problems());
        return t;
    }

    /** E1..En in retrieval order, with raw_content looked up (citations fall back to it). */
    private Map<String, Evidence> evidence(List<Fusion.Hit> hits) {
        Map<String, String> raw = new HashMap<>();
        List<String> ids = hits.stream().map(Fusion.Hit::id).toList();
        db.sql("SELECT id, raw_content FROM knowledge_records WHERE id IN (" + String.join(",", ids.stream().map(i -> "?").toList()) + ")")
                .params(ids).query(rs -> { raw.put(rs.getString(1), rs.getString(2)); });
        Map<String, Evidence> out = new LinkedHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            Fusion.Hit h = hits.get(i);
            out.put("E" + (i + 1), new Evidence(h.id(), h.source(), h.sourceId(), h.title(), h.content(), raw.get(h.id()), h.score()));
        }
        return out;
    }

    /**
     * Drop citations whose quote fails on its own, keep details only if fully verified, THEN attach
     * markers if the answer has none, so appended markers only ever point at verified quotes.
     * validate() runs after this, unchanged.
     */
    static Draft prepare(Draft out, Map<String, Evidence> evidence, List<String> dropped) {
        List<Quote> kept = CitationValidator.dropBad(out.citations(), evidence);
        for (Quote c : out.citations()) if (!kept.contains(c)) dropped.add(CitationValidator.normalizeLabel(c.label()));
        Draft joined = joinDetails(out, kept);
        return attachMarkers(new Draft(joined.answerable(), joined.answer(), "", kept));
    }

    /**
     * The model returns the direct answer and further findings ("details") separately; a 4b model
     * follows a separate field far better than "write two paragraphs". Details are shown as a second
     * paragraph only if EVERY marker in them has a verified quote; otherwise just that paragraph is
     * dropped. It is extra, so it must never cost the answer a failed check. No markers: dropped too.
     */
    static Draft joinDetails(Draft out, List<Quote> kept) {
        String details = out.details() == null ? "" : out.details().strip();
        List<String> labels = CitationValidator.markers(details);
        Set<String> verified = new java.util.HashSet<>();
        for (Quote c : kept) verified.add(CitationValidator.normalizeLabel(c.label()));
        String answer = out.answer();
        if (!details.isEmpty() && out.answerable() && !labels.isEmpty() && verified.containsAll(labels))
            answer = answer.stripTrailing() + "\n\n" + details;
        return new Draft(out.answerable(), answer, "", out.citations());
    }

    /**
     * The 4b model reliably omits markers from one-sentence answers (seen in SKB on table facts) while
     * its quotes are exact. If the answer has NO markers but carries citations, append theirs.
     * Harmless: validate() still checks every quote; with no citations it still fails.
     */
    static Draft attachMarkers(Draft out) {
        if (out.answerable() && !out.citations().isEmpty() && CitationValidator.markers(out.answer()).isEmpty()) {
            Set<String> labels = new LinkedHashSet<>();
            for (Quote c : out.citations()) labels.add(CitationValidator.normalizeLabel(c.label()));
            String suffix = String.join(" ", labels.stream().map(l -> "[" + l + "]").toList());
            return new Draft(true, out.answer().stripTrailing() + " " + suffix, out.details(), out.citations());
        }
        return out;
    }

    record Renumbered(String answer, List<Citation> citations) {}

    /**
     * Keep only the cited evidence and number it E1, E2... in order of first use, so the chips read
     * E1..En with no gaps. source/source_id come from the evidence (the database), never the model.
     */
    static Renumbered renumber(String answer, List<Quote> citations, Map<String, Evidence> evidence) {
        List<String> order = CitationValidator.markers(answer);
        Map<String, String> renamed = new HashMap<>();
        for (int i = 0; i < order.size(); i++) renamed.put(order.get(i), "E" + (i + 1));
        Matcher m = CitationValidator.MARKER.matcher(answer);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, "[" + renamed.get(m.group(1)) + "]");
        m.appendTail(sb);
        Map<String, String> quotes = new HashMap<>();
        for (Quote c : citations) quotes.putIfAbsent(CitationValidator.normalizeLabel(c.label()), c.quote().strip());
        List<Citation> out = new ArrayList<>();
        for (String old : order) {
            Evidence e = evidence.get(old);
            out.add(new Citation(renamed.get(old), e.source(), e.sourceId(), quotes.getOrDefault(old, "")));
        }
        return new Renumbered(sb.toString(), out);
    }

    /**
     * Evidence the model was given but the answer doesn't cite. Answered: those scoring at least
     * SUGGEST_RATIO x the weakest cited one, so near-misses don't show. Not answered: the top 3.
     */
    static List<Suggestion> suggest(List<Fusion.Hit> hits, Set<String> cited) {
        List<Suggestion> out = new ArrayList<>();
        double floor = cited.isEmpty() ? 0
                : SUGGEST_RATIO * hits.stream().filter(h -> cited.contains(h.sourceId())).mapToDouble(Fusion.Hit::score).min().orElse(0);
        for (int i = 0; i < hits.size(); i++) {
            Fusion.Hit h = hits.get(i);
            if (cited.contains(h.sourceId())) continue;
            if (!cited.isEmpty() && h.score() < floor) continue;
            out.add(new Suggestion(h.source(), h.sourceId(), i + 1, h.score()));
            if (cited.isEmpty() && out.size() == SUGGEST_UNANSWERED) break;
        }
        return out;
    }
}
