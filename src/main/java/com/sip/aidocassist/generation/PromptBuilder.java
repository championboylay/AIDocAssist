package com.sip.aidocassist.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.chunking.Pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The answer prompt and its JSON schema (port of SKB generation/prompt.py). The 8 evidence items
 * are labelled E1..E8, each with its title (the last 3 heading levels) and its WHOLE content: SKB
 * once cut evidence at 2,000 characters while chunks go to 4,000 and the model missed the second
 * half of 43 of 105 chunks. 8 x 4,000 characters is about 9k tokens, inside num_ctx 16384.
 */
public final class PromptBuilder {

    static final int EVIDENCE_CHARS = Pack.MAX_CHARS;
    private static final ObjectMapper JSON = new ObjectMapper();

    static final Map<String, Object> ANSWER_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "answerable", Map.of("type", "boolean"),
                    "answer", Map.of("type", "string"),
                    "details", Map.of("type", "string"), // optional second paragraph: further findings
                    "citations", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("label", Map.of("type", "string"), "quote", Map.of("type", "string")),
                            "required", List.of("label", "quote")))),
            "required", List.of("answerable", "answer", "citations"));

    static final String ANSWER_SYSTEM = """
            You answer questions for SIP staff using ONLY the evidence provided. Rules:
            1. Every factual sentence ends with the marker of the evidence it comes from, e.g. [E1]. \
            Use several markers if several items support it. An answer without markers is rejected.
            Cite only the 1-3 evidence items that most directly support the answer, not every item that \
            mentions the topic.
            2. For each marker you use, add a citation: its label (e.g. E1) and a quote of 5-25 words copied \
            EXACTLY from that evidence item. For a fact from a table, quote the whole row starting with its \
            first cell, e.g. 'SourceSystem | The source system sending the message. | YES | NO | String(100)'. \
            Do not put quotes in the answer text.
            3. If the evidence does not contain the answer, set answerable=false and say so in one sentence. \
            Never use outside knowledge, never guess.
            4. Plain text, English, two parts:
            - answer: the direct answer in 1-2 sentences, from the evidence that supports it most clearly.
            - details: 2-4 more sentences with other relevant findings in the evidence that the answer did not \
            cover, e.g. format, allowed values, conditions, related fields, where or how it is used. Every \
            sentence ends with its marker, like the answer. Do not repeat the answer, do not pad, add nothing \
            the evidence does not say. Leave details empty if the evidence has nothing more.""";

    private PromptBuilder() {}

    static String formatEvidence(Map<String, Evidence> evidence) {
        List<String> parts = new ArrayList<>();
        evidence.forEach((label, e) -> {
            String title = e.title() == null || e.title().isEmpty() ? e.sourceId() : lastLevels(e.title(), 3);
            String content = e.content().length() > EVIDENCE_CHARS ? e.content().substring(0, EVIDENCE_CHARS) : e.content();
            parts.add("[" + label + "] " + title + "\n" + content);
        });
        return String.join("\n\n", parts);
    }

    private static String lastLevels(String title, int n) {
        List<String> levels = Arrays.asList(title.split(" > "));
        return String.join(" > ", levels.subList(Math.max(0, levels.size() - n), levels.size()));
    }

    /** The user message: evidence, question, and on the one retry the problems found and the previous answer. */
    static String answerPrompt(String question, Map<String, Evidence> evidence, List<String> problems, Evidence.Draft previous) {
        String user = "Evidence:\n" + formatEvidence(evidence) + "\n\nQuestion: " + question;
        if (problems != null && !problems.isEmpty()) {
            String prev;
            try {
                prev = JSON.writeValueAsString(previous);
            } catch (Exception e) {
                prev = String.valueOf(previous);
            }
            user += "\n\nYour previous answer failed the citation check:\n- " + String.join("\n- ", problems)
                    + "\nPrevious answer: " + (prev.length() > 1500 ? prev.substring(0, 1500) : prev) + "\nAnswer again, fixing these problems.";
        }
        return user;
    }
}
