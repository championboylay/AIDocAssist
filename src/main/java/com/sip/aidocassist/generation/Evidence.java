package com.sip.aidocassist.generation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One evidence item the model is shown, labelled E1..E8 in retrieval order. Everything here comes
 * from knowledge_records, never from the model.
 *
 * @param rawContent the original text; citation checks fall back to it when content doesn't hold the quote
 * @param score      the fused RRF score (for "Suggested")
 */
public record Evidence(String id, String source, String sourceId, String title, String content, String rawContent, double score) {

    /** A citation as the model wrote it: an evidence label and a quote. */
    public record Quote(String label, String quote) {}

    /** The model's answer, parsed from its JSON (the shape enforced by the schema). */
    public record Draft(boolean answerable, String answer, String details, List<Quote> citations) {

        static Draft from(JsonNode n) {
            List<Quote> cites = new ArrayList<>();
            for (JsonNode c : n.path("citations")) cites.add(new Quote(c.path("label").asText(""), c.path("quote").asText("")));
            return new Draft(n.path("answerable").asBoolean(false), n.path("answer").asText(""), n.path("details").asText(""), cites);
        }
    }
}
