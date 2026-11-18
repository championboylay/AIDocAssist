package com.sip.aidocassist.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.AiDocAssistApplicationAccess;
import com.sip.aidocassist.db.Migrator;
import com.sip.aidocassist.generation.Evidence.Draft;
import com.sip.aidocassist.generation.Evidence.Quote;
import com.sip.aidocassist.generation.Reformer.Exchange;
import com.sip.aidocassist.llm.Chat;
import com.sip.aidocassist.ollama.OllamaClient;
import com.sip.aidocassist.retrieval.Fusion;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: SKB's self-checks (citation_validation.py, answer.py's suggest and details, the
 * refers_back table in docs/generation.md) and the whole turn with a scripted stand-in model.
 */
class GenerationTest {

    // ---------- citation validation (SKB citation_validation.py self-check) ----------

    static Map<String, Evidence> ev() {
        Map<String, Evidence> ev = new LinkedHashMap<>();
        ev.put("E1", new Evidence("r1", "filesystem", "a#0", "Spec > Errors", "Error results can be recognized by the resultItem type which is error.", null, 0.05));
        ev.put("E2", new Evidence("r2", "filesystem", "b#0", "Ticket", "Summary: the fix batches all owners.",
                "Alicia: confirmed the validation service now requires all beneficial owners in one request.", 0.04));
        return ev;
    }

    static Quote q(String label, String quote) { return new Quote(label, quote); }

    @Test
    void citationValidationRules() {
        var ev = ev();
        var ok = List.of(q("E1", "Error results can be recognized by the resultItem type"));
        assertEquals(List.of(), CitationValidator.validate("Look at the resultItem type [E1].", ok, ev));
        assertEquals(List.of(), CitationValidator.validate("Look at the type [ E1 ].", List.of(q("[E1]", ok.get(0).quote())), ev), "label/marker normalisation");
        // a real quote from the original text passes via raw_content even though content lacks it
        assertEquals(List.of(), CitationValidator.validate("All owners in one request [E2].", List.of(q("E2", "requires all beneficial owners in one request")), ev));
        assertTrue(CitationValidator.validate("x [E1].", List.of(q("E1", "the payload is rejected by the CDD service")), ev).stream().anyMatch(p -> p.contains("does not appear")));
        assertTrue(CitationValidator.validate("An answer with no markers.", ok, ev).stream().anyMatch(p -> p.contains("no [E#]")));
        assertTrue(CitationValidator.validate("x [E7].", List.of(q("E7", "error results can be recognized")), ev).stream().anyMatch(p -> p.contains("not one of the evidence")));
        assertTrue(CitationValidator.validate("x [E1].", List.of(), ev).stream().anyMatch(p -> p.contains("has no quote")));
        assertTrue(CitationValidator.validate("x [E1].", List.of(q("E1", "error")), ev).stream().anyMatch(p -> p.contains("too short")));
        // small wording drift (<20% of words) still passes; a paraphrase doesn't
        assertEquals(List.of(), CitationValidator.validate("x [E1].", List.of(q("E1", "error results can be recognised by the resultItem type which is error")), ev));
        assertNotEquals(List.of(), CitationValidator.validate("x [E1].", List.of(q("E1", "failures are flagged through a special item category")), ev));
        // One bad quote among good ones is dropped, not fatal...
        var cites = List.of(q("E1", ok.get(0).quote()), q("E2", "totally unrelated words that appear nowhere"));
        var kept = CitationValidator.dropBad(cites, ev);
        assertEquals(List.of(cites.get(0)), kept);
        assertEquals(List.of(), CitationValidator.validate("Look at the resultItem type [E1].", kept, ev));
        // ...but a sentence that cites the dropped item is still unsupported.
        assertTrue(CitationValidator.validate("Type [E1]. Also [E2].", kept, ev).stream().anyMatch(p -> p.contains("has no quote")));
        // docs/generation.md's overlap arithmetic: 10 of 11 words = 91% passes; 2 words never pass
        assertEquals(10.0 / 11, CitationValidator.overlap("error results can be recognised by the resultItem type which is error", ev.get("E1").content()), 1e-9);
        assertFalse(CitationValidator.quoteOk(q("E1", "the error"), ev));
    }

    // ---------- suggest / details / markers / renumber (SKB answer.py self-check) ----------

    static Fusion.Hit hit(int i, double score) {
        return new Fusion.Hit(String.valueOf(i), "filesystem", "M-" + i + "#0", "M-" + i, "T", "c", null, "t", score, Map.of());
    }

    @Test
    void suggestDetailsMarkersRenumber() {
        var hits = List.of(hit(1, .049), hit(2, .040), hit(3, .030), hit(4, .024), hit(5, .016));
        // Cited #1 and #3: floor 0.5 x 0.030 = 0.015 keeps #2, #4, #5; cited ones never repeat.
        assertEquals(List.of(2, 4, 5), AnswerService.suggest(hits, Set.of("M-1#0", "M-3#0")).stream().map(AnswerService.Suggestion::rank).toList());
        // Cited only #1: floor 0.0245 keeps #2 and #3, drops #4 (0.024) and #5.
        assertEquals(List.of(2, 3), AnswerService.suggest(hits, Set.of("M-1#0")).stream().map(AnswerService.Suggestion::rank).toList());
        // Not answered: top 3 by rank, no cutoff.
        assertEquals(List.of(1, 2, 3), AnswerService.suggest(hits, Set.of()).stream().map(AnswerService.Suggestion::rank).toList());
        assertEquals(List.of(), AnswerService.suggest(List.of(), Set.of()));

        var ok = List.of(q("E1", "q"), q("E2", "q"));
        var base = new Draft(true, "Main [E1].", "More [E2].", ok);
        assertEquals("Main [E1].\n\nMore [E2].", AnswerService.joinDetails(base, ok).answer());
        assertEquals("Main [E1].", AnswerService.joinDetails(new Draft(true, "Main [E1].", "More [E3].", ok), ok).answer(), "E3 has no verified quote: dropped");
        assertEquals("Main [E1].", AnswerService.joinDetails(new Draft(true, "Main [E1].", "More, uncited.", ok), ok).answer(), "no marker: dropped");

        // Markers appended only when the answer has none and there are (verified) citations.
        assertEquals("A fact. [E2] [E1]", AnswerService.attachMarkers(new Draft(true, "A fact.", "", List.of(q("e2", "x"), q("E1", "y"), q("E2", "z")))).answer());
        assertEquals("A fact.", AnswerService.attachMarkers(new Draft(true, "A fact.", "", List.of())).answer());

        // Renumbered in order of first use; source/source_id from the evidence, never the model.
        var ev = ev();
        var r = AnswerService.renumber("First [E2]. Then [E1]. Again [E2].", List.of(q("E1", " one "), q("E2", "two")), ev);
        assertEquals("First [E1]. Then [E2]. Again [E1].", r.answer());
        assertEquals(List.of(new AnswerService.Citation("E1", "filesystem", "b#0", "two"), new AnswerService.Citation("E2", "filesystem", "a#0", "one")), r.citations());
    }

    @Test
    void whichQuestionsAreRewritten() {
        // SKB docs/generation.md's table
        assertTrue(Reformer.refersBack("What does it contain?"));
        assertTrue(Reformer.refersBack("and the response?"), "3 words, names nothing specific");
        assertFalse(Reformer.refersBack("What is ConnectedParty/PercentageOwnership?"), "names its own subject");
        assertFalse(Reformer.refersBack("Show MANT-401"), "short, but MANT-401 is an identifier");
        assertTrue(Reformer.refersBack("who fixed the July one?"), "one points back");
        assertFalse(Reformer.refersBack("What does error code E1001 mean?"));
        assertEquals(Reformer.gist("Explain me  X"), Reformer.gist("explain me X."), "case and punctuation don't count as a rewrite");
    }

    // ---------- the whole turn, with a scripted model ----------

    /** Replies from a queue; records every prompt; embed is never needed (search is stubbed). */
    static class ScriptedModel extends OllamaClient {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        ScriptedModel() { super("http://unused", "unused"); }
        @Override public JsonNode chat(String system, String user, Map<String, Object> schema) {
            prompts.add(user);
            try { return new ObjectMapper().readTree(replies.removeFirst()); } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    /** Search stub: returns the canned hits and remembers the query it was given. */
    static class CannedSearch extends Fusion {
        List<Hit> hits = List.of();
        String lastQuery;
        CannedSearch() { super(null, null, null, null, null); }
        @Override public Result search(String question, List<String> groups) {
            lastQuery = question;
            return groups.isEmpty() ? Result.EMPTY : new Result(hits, List.of());
        }
    }

    @TempDir Path tmp;
    HikariDataSource ds;
    ScriptedModel model;
    CannedSearch search;
    AnswerService answers;

    @BeforeEach
    void setUp() throws Exception {
        ds = (HikariDataSource) AiDocAssistApplicationAccess.dataSource(tmp.resolve("g.db").toString());
        Migrator.migrate(ds);
        var db = JdbcClient.create(ds);
        db.sql("INSERT INTO sync_sources (id, source, name, config) VALUES ('s', 'filesystem', 'Docs', '{}')").update();
        String[][] rows = {
                {"r1", "C:/d/acra.pdf#28", "ACRA > 4.6 Error Responses > 400 — Bad Request",
                        "| E1001 | Both uen and name provided — specify only one at a time. | Remove either uen or name from the request. |"},
                {"r2", "C:/d/acra.pdf#29", "ACRA > 4.6 Error Responses > Other HTTP Status Codes", "| 401 | Unauthorised | Check the token header. |"},
                {"r3", "C:/d/cimb.docx#15", "CIMBSG > 3.2.4 Error Message", "Error results can be recognized by the resultItem type which is error."}};
        for (String[] r : rows)
            db.sql("INSERT INTO knowledge_records (id, source, source_id, parent_id, source_type, title, content, raw_content, updated_at, content_checksum, sync_source_id) "
                    + "VALUES (?, 'filesystem', ?, ?, 'doc-section', ?, ?, ?, 't', 'x', 's')").params(r[0], r[1], r[1].split("#")[0], r[2], r[3], r[3]).update();
        model = new ScriptedModel();
        search = new CannedSearch();
        search.hits = List.of(
                new Fusion.Hit("r1", "filesystem", rows[0][1], "C:/d/acra.pdf", rows[0][2], rows[0][3], null, "t", 0.049, Map.of()),
                new Fusion.Hit("r2", "filesystem", rows[1][1], "C:/d/acra.pdf", rows[1][2], rows[1][3], null, "t", 0.040, Map.of()),
                new Fusion.Hit("r3", "filesystem", rows[2][1], "C:/d/cimb.docx", rows[2][2], rows[2][3], null, "t", 0.015, Map.of()));
        answers = new AnswerService(search, new Reformer(new Chat(model)), new Chat(model), db);
    }

    @AfterEach
    void tearDown() { ds.close(); }

    static final String GOOD = """
            {"answerable": true, "answer": "E1001 means both uen and name were provided [E1].",
             "details": "The fix is to remove one of them [E1].",
             "citations": [{"label": "E1", "quote": "E1001 | Both uen and name provided — specify only one at a time."}]}""";

    @Test
    void answeredFirstTimeWithEvidencePromptAndSuggestions() {
        model.replies.add(GOOD);
        var t = answers.answer("What does error code E1001 mean?", List.of("general"), List.of());
        assertEquals("answered", t.outcome());
        assertFalse(t.abstained());
        assertEquals(1, t.attempts());
        assertEquals("E1001 means both uen and name were provided [E1].\n\nThe fix is to remove one of them [E1].", t.answer(), "verified details joined");
        assertEquals(List.of(new AnswerService.Citation("E1", "filesystem", "C:/d/acra.pdf#28", "E1001 | Both uen and name provided — specify only one at a time.")), t.citations());
        assertEquals(List.of(2), t.suggested().stream().map(AnswerService.Suggestion::rank).toList(), "0.040 >= half of 0.049; 0.015 isn't");
        String prompt = model.prompts.get(0);
        assertTrue(prompt.startsWith("Evidence:\n[E1] ACRA > 4.6 Error Responses > 400 — Bad Request\n| E1001 |"), "labelled, last 3 heading levels, whole content");
        assertTrue(prompt.contains("[E3] CIMBSG > 3.2.4 Error Message") && prompt.endsWith("Question: What does error code E1001 mean?"));
        assertNull(t.reformedQuery(), "a first question is never rewritten");
    }

    @Test
    void oneRetryToldWhatFailedThenAnsweredOrNotVerified() {
        String paraphrase = """
                {"answerable": true, "answer": "Both fields were sent [E1].", "citations": [{"label": "E1", "quote": "you sent uen together with the name field"}]}""";
        model.replies.add(paraphrase);
        model.replies.add(GOOD);
        var t = answers.answer("What does E1001 mean?", List.of("general"), List.of());
        assertEquals("answered", t.outcome());
        assertEquals(2, t.attempts());
        assertTrue(model.prompts.get(1).contains("Your previous answer failed the citation check:\n- E1 is used in the answer but has no quote."),
                "the dropped quote leaves E1 unsupported, and the retry says exactly that");

        model.prompts.clear();
        model.replies.add(paraphrase);
        model.replies.add(paraphrase);
        var t2 = answers.answer("What does E1001 mean?", List.of("general"), List.of());
        assertEquals("not-verified", t2.outcome());
        assertTrue(t2.abstained());
        assertEquals(AnswerService.NOT_VERIFIED, t2.answer());
        assertEquals(2, model.prompts.size(), "no third try");
        assertEquals(List.of(1, 2, 3), t2.suggested().stream().map(AnswerService.Suggestion::rank).toList(), "unanswered: top 3");
    }

    @Test
    void abstainsWhenNotAnswerableOrNothingFound() {
        model.replies.add("""
                {"answerable": false, "answer": "The evidence does not say.", "citations": []}""");
        var t = answers.answer("How many days of annual leave?", List.of("general"), List.of());
        assertEquals("not-answerable", t.outcome());
        assertEquals(AnswerService.NOT_ANSWERED, t.answer());
        assertEquals(3, t.retrieved());

        search.hits = List.of();
        var none = answers.answer("Anything?", List.of("general"), List.of());
        assertEquals("no-evidence", none.outcome());
        assertEquals(AnswerService.NO_EVIDENCE, none.answer());
        var noGroups = answers.answer("Anything?", List.of(), List.of());
        assertEquals("no-evidence", noGroups.outcome());
        assertEquals(1, model.prompts.size(), "the model is never called without evidence");
    }

    @Test
    void followUpsAreRewrittenBeforeRetrievalAndOnlyWhenTheyReferBack() {
        var history = List.of(new Exchange("What does error code E1001 mean?", "Both uen and name were provided [E1]."));
        model.replies.add("{\"question\": \"How do I fix error code E1001?\"}");
        model.replies.add(GOOD);
        var t = answers.answer("How do I fix it?", List.of("general"), history);
        assertEquals("How do I fix error code E1001?", t.reformedQuery());
        assertEquals("How do I fix error code E1001?", search.lastQuery, "the rewrite is what gets searched");
        assertTrue(model.prompts.get(0).contains("Assistant: Both uen and name were provided.") && !model.prompts.get(0).contains("[E1]"),
                "citation markers are stripped from the history");
        assertTrue(model.prompts.get(1).endsWith("Question: How do I fix error code E1001?"));

        // A self-contained question is searched as typed: no rewrite call.
        model.prompts.clear();
        model.replies.add(GOOD);
        var self = answers.answer("What does error code E1001 mean?", List.of("general"), history);
        assertNull(self.reformedQuery());
        assertEquals(1, model.prompts.size());

        // A "rewrite" that only changes case/punctuation isn't one.
        model.replies.add("{\"question\": \"how do I fix it\"}");
        model.replies.add(GOOD);
        assertNull(answers.answer("How do I fix it?", List.of("general"), history).reformedQuery());
    }
}
