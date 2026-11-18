package com.sip.aidocassist.generation;

import com.sip.aidocassist.llm.Chat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follow-up rewrite (CLAUDE.md "Generation and citations"; port of SKB generation/answer.py
 * refers_back and prompt.py reform_question). Before retrieval, a question that points back into
 * the thread is rewritten by the 4b model so it stands on its own.
 *
 * Only questions that point back ("what does it contain?") or short fragments naming nothing
 * ("and the response?") are rewritten. A self-contained question is searched exactly as typed: in
 * SKB the rewriter once turned "What is ConnectedParty/PercentageOwnership?" into "...in the
 * Permissible Disclosure API response?" (the earlier topic) and the search missed.
 */
@Component
public class Reformer {

    /** One earlier turn of the thread: the user's question and the assistant's answer. */
    public record Exchange(String question, String answer) {}

    /** CLAUDE.md: up to 5 turns, so "it" can reach further back; the prompt prefers the most recent topic. */
    static final int HISTORY_TURNS = 5;

    private static final Pattern REFERENCE_WORDS = Pattern.compile(
            "\\b(it|its|this|that|these|those|they|them|their|he|she|him|her|his|same|above|previous|"
                    + "earlier|former|latter|aforementioned|one|ones|there|then|else|another|other|also)\\b", Pattern.CASE_INSENSITIVE);
    private static final int SHORT_FRAGMENT_WORDS = 4;
    /** Something specific the question names itself: ConnectedParty, MANT-401, v1.3, CDD, soap:messageId. */
    private static final Pattern IDENTIFIER = Pattern.compile("\\b\\w*[a-z][A-Z]\\w*|\\b[A-Z]{2,}\\b|\\w*\\d\\w*|\\w+[_/:]\\w*");
    private static final Pattern WORDS = Pattern.compile("\\w+");
    private static final Pattern MARKER = Pattern.compile("\\s*\\[\\s*E\\d+\\s*\\]");

    static final Map<String, Object> REFORM_SCHEMA = Map.of("type", "object",
            "properties", Map.of("question", Map.of("type", "string")), "required", List.of("question"));
    static final String REFORM_SYSTEM = """
            Rewrite the user's latest question so it can be understood on its own, without the conversation. \
            Replace ONLY pronouns and references (e.g. 'it', 'that ticket', 'the July one') with the specific names, \
            fields, documents or identifiers they refer to in the conversation. Copy every name and identifier \
            exactly as written, including slashes and spaces (e.g. 'ConnectedParty/ PercentageOwnership'). \
            Never add a document, system or topic that the question does not refer to. If a reference could mean \
            more than one earlier topic, choose the most recent one, unless the question says otherwise \
            (e.g. 'the first one'). Keep the user's intent; \
            do not answer it. If the question already names its own subject, return it unchanged.""";

    private final Chat ollama;

    public Reformer(Chat ollama) {
        this.ollama = ollama;
    }

    /** Does this question point back into the conversation? */
    static boolean refersBack(String question) {
        if (REFERENCE_WORDS.matcher(question).find()) return true;
        Matcher m = WORDS.matcher(question);
        int n = 0;
        while (m.find()) n++;
        return n <= SHORT_FRAGMENT_WORDS && !IDENTIFIER.matcher(question).find();
    }

    /** Case, spacing and punctuation don't count as a rewrite. */
    static String gist(String text) {
        List<String> w = new ArrayList<>();
        Matcher m = Pattern.compile("[a-z0-9]+").matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) w.add(m.group());
        return String.join(" ", w);
    }

    /**
     * The question rewritten from the thread's last turns, or null when it isn't rewritten (a first
     * question, a self-contained one, or a "rewrite" that only changed case or punctuation).
     */
    public String rewrite(String question, List<Exchange> history) {
        if (history == null || history.isEmpty() || !refersBack(question)) return null;
        List<Exchange> last = history.subList(Math.max(0, history.size() - HISTORY_TURNS), history.size());
        StringBuilder convo = new StringBuilder();
        for (Exchange x : last) {
            // Citation markers mean nothing outside their own turn, and they leaked into rewrites in SKB.
            String a = MARKER.matcher(x.answer()).replaceAll("");
            convo.append("User: ").append(x.question()).append("\nAssistant: ").append(a.length() > 800 ? a.substring(0, 800) : a).append('\n');
        }
        String out = ollama.chat(REFORM_SYSTEM, "Conversation so far:\n" + convo + "\nLatest question: " + question, REFORM_SCHEMA)
                .path("question").asText("").strip();
        return out.isEmpty() || gist(out).equals(gist(question)) ? null : out;
    }
}
