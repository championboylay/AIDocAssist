package com.sip.aidocassist.generation;

import com.sip.aidocassist.generation.Evidence.Quote;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic citation check (CLAUDE.md "Citation validation"; port of SKB
 * generation/citation_validation.py), no model involved. A quote passes when it has at least 4 words
 * and at least 80% of its distinct words appear in the cited record's content, or failing that its
 * raw_content. Words are lower-case letters and digits compared as sets, so punctuation and word
 * order don't matter, but different words do.
 */
public final class CitationValidator {

    static final int MIN_QUOTE_WORDS = 4;      // shorter quotes ("YES", "the error") prove nothing
    static final double MIN_OVERLAP = 0.8;     // share of the quote's distinct words found in the record
    public static final Pattern MARKER = Pattern.compile("\\[\\s*(E\\d+)\\s*\\]");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Pattern LABEL = Pattern.compile("E\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private CitationValidator() {}

    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }

    static double overlap(String quote, String text) {
        Set<String> q = words(quote);
        if (q.isEmpty()) return 0;
        Set<String> t = words(text);
        return (double) q.stream().filter(t::contains).count() / q.size();
    }

    /** The model sometimes writes "[E1]" or "e1" for "E1". */
    public static String normalizeLabel(String label) {
        Matcher m = LABEL.matcher(label == null ? "" : label);
        return m.find() ? "E" + m.group(1) : (label == null ? "" : label.strip());
    }

    /** The [E#] markers in a text, in order of first use. */
    public static List<String> markers(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    private static boolean found(String quote, Evidence e) {
        return overlap(quote, e.content()) >= MIN_OVERLAP || overlap(quote, e.rawContent()) >= MIN_OVERLAP;
    }

    /** One citation on its own: a real evidence label, a long-enough quote, found in content or raw_content. */
    static boolean quoteOk(Quote c, Map<String, Evidence> evidence) {
        Evidence e = evidence.get(normalizeLabel(c.label()));
        return e != null && words(c.quote()).size() >= MIN_QUOTE_WORDS && found(c.quote(), e);
    }

    /**
     * Only the citations whose quote checks out. One bad quote among good ones is dropped, not fatal;
     * safety is unchanged, because a sentence citing a label whose only quote was dropped still fails {@link #validate}.
     */
    static List<Quote> dropBad(List<Quote> citations, Map<String, Evidence> evidence) {
        return citations.stream().filter(c -> quoteOk(c, evidence)).toList();
    }

    /** The problems found, empty when the answer may be shown. These sentences go back to the model on the retry. */
    static List<String> validate(String answer, List<Quote> citations, Map<String, Evidence> evidence) {
        List<String> problems = new ArrayList<>();
        Set<String> used = new HashSet<>(markers(answer));
        if (used.isEmpty()) problems.add("The answer has no [E#] citation markers; every factual sentence must cite its evidence.");
        Map<String, List<String>> byLabel = new LinkedHashMap<>();
        for (Quote c : citations) byLabel.computeIfAbsent(normalizeLabel(c.label()), k -> new ArrayList<>()).add(c.quote());
        Set<String> labels = new TreeSet<>(used);
        labels.addAll(byLabel.keySet());
        for (String label : labels) {
            Evidence e = evidence.get(label);
            if (e == null) {
                problems.add(label + " is not one of the evidence items provided.");
                continue;
            }
            if (used.contains(label) && !byLabel.containsKey(label)) {
                problems.add(label + " is used in the answer but has no quote.");
                continue;
            }
            for (String quote : byLabel.getOrDefault(label, List.of())) {
                if (words(quote).size() < MIN_QUOTE_WORDS)
                    problems.add("The quote for " + label + " is too short: copy at least " + MIN_QUOTE_WORDS + " words exactly.");
                else if (!found(quote, e))
                    problems.add("The quote for " + label + " does not appear in " + label + "; copy the words exactly from it.");
            }
        }
        return problems;
    }
}
