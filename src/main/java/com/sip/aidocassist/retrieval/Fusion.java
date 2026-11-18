package com.sip.aidocassist.retrieval;

import com.sip.aidocassist.llm.Embedder;
import com.sip.aidocassist.retrieval.RareTokenRetriever.Picked;
import com.sip.aidocassist.retrieval.Visible.Ranked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval (port of SKB retrieval/fusion.py): vector + full-text + rare-token, each
 * restricted to the user's groups in its own SQL, fused with Reciprocal Rank Fusion. No model
 * judges relevance here (no reranker, no context expansion: CLAUDE.md).
 *
 * score = sum over the retrievers that found a record of weight / (60 + rank); then dedupe on
 * source:source_id, at most 3 chunks per document, top 8.
 */
@Service
public class Fusion {

    private static final Logger log = LoggerFactory.getLogger(Fusion.class);

    static final int RRF_K = 60;
    static final int PER_RETRIEVER = 20;
    static final int PER_PARENT_CAP = 3;
    public static final int FINAL_K = 8;
    public static final int MAX_QUESTION = 500;
    /** Retriever name -> RRF weight (all 1.0 to start, as SKB). */
    static final Map<String, Double> WEIGHTS = Map.of("vector", 1.0, "fulltext", 1.0, "raretoken", 1.0);
    public static final List<String> RETRIEVERS = List.of("vector", "fulltext", "raretoken");

    /**
     * One piece of evidence. ranks = retriever -> 1-based rank in its list (only those that found it),
     * for inspection. Title, source, updated_at and source_url come from knowledge_records, never generated.
     */
    public record Hit(String id, String source, String sourceId, String parentId, String title, String content,
                      String sourceUrl, String updatedAt, double score, Map<String, Integer> ranks) {
        /** The file name: the last part of parent_id (a path, or "<entry>/<file>" for an upload). */
        public String file() { return parentId.substring(parentId.lastIndexOf('/') + 1); }
        /** The heading path below the document title ("3. Auth > 3.1 Flow"), or "" for a document without headings. */
        public String section() { int i = title.indexOf(" > "); return i < 0 ? "" : title.substring(i + 3); }
        /** The content on one line, cut to 400 characters. */
        public String excerpt() { String s = String.join(" ", content.strip().split("\\s+")); return s.length() <= 400 ? s : s.substring(0, 399) + "…"; }
    }

    /** The fused evidence, and the rare tokens the rare-token retriever searched on (for inspection). */
    public record Result(List<Hit> hits, List<Picked> rareTokens) {
        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    private final JdbcClient db;
    private final Embedder ollama;
    private final VectorRetriever vector;
    private final FullTextRetriever fulltext;
    private final RareTokenRetriever raretoken;

    public Fusion(JdbcClient db, Embedder ollama, VectorRetriever vector, FullTextRetriever fulltext, RareTokenRetriever raretoken) {
        this.db = db;
        this.ollama = ollama;
        this.vector = vector;
        this.fulltext = fulltext;
        this.raretoken = raretoken;
    }

    /**
     * Top evidence for {@code question}, only from records whose allowed_group_ids overlap
     * {@code groups} (and whose sync entry is enabled). No groups: nothing, and nothing is queried.
     */
    public Result search(String question, List<String> groups) {
        String q = question == null ? "" : question.strip();
        if (q.length() > MAX_QUESTION) q = q.substring(0, MAX_QUESTION);
        if (q.isEmpty() || groups == null || groups.isEmpty()) return Result.EMPTY;
        // ponytail: retrievers run one after another; the embedding call dominates. A thread each is the
        // upgrade if latency matters (SKB has the same note).
        List<Picked> rare = raretoken.pick(q, groups);
        Map<String, List<Ranked>> lists = new LinkedHashMap<>();
        // One vector list per embedding model (Gemini / Ollama spaces can't be compared). A record is in
        // exactly one of them, so it still gets at most one vector vote. A model that can't embed the
        // question only loses its vector list; when every model fails, the search fails as before.
        RuntimeException failed = null;
        int ok = 0;
        for (String model : vector.models(ollama.legacy(), groups)) {
            try {
                lists.put("vector " + model, vector.search(ollama.embedQuery(q, model), model, ollama.legacy(), groups, PER_RETRIEVER));
                ok++;
            } catch (RuntimeException e) {
                log.warn("query embedding with {} failed, its records are searched by text only: {}", model, e.getMessage());
                failed = e;
            }
        }
        if (ok == 0 && failed != null) throw failed;
        lists.put("fulltext", fulltext.search(q, groups, PER_RETRIEVER));
        lists.put("raretoken", raretoken.search(q, groups, rare, PER_RETRIEVER));
        return new Result(fuse(lists), rare);
    }

    /** RRF over the retrievers' lists, then dedupe, per-document cap, top 8. */
    List<Hit> fuse(Map<String, List<Ranked>> lists) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Map<String, Integer>> ranks = new HashMap<>();
        lists.forEach((name, list) -> {
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i).id();
                String kind = name.split(" ", 2)[0]; // "vector <model>" counts as "vector"
                scores.merge(id, WEIGHTS.get(kind) / (RRF_K + i + 1), Double::sum);
                ranks.computeIfAbsent(id, x -> new LinkedHashMap<>()).put(kind, i + 1);
            }
        });
        if (scores.isEmpty()) return List.of();
        Map<String, Hit> rows = new HashMap<>();
        List<String> ids = new ArrayList<>(scores.keySet());
        db.sql("SELECT id, source, source_id, parent_id, title, content, source_url, updated_at FROM knowledge_records WHERE id IN ("
                        + String.join(",", ids.stream().map(x -> "?").toList()) + ")")
                .params(ids)
                .query(rs -> {
                    String id = rs.getString(1);
                    rows.put(id, new Hit(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5) == null ? "" : rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), scores.get(id), ranks.get(id)));
                });
        // Best first; ties by id so the same question always gives the same order.
        ids.sort((a, b) -> scores.get(b).equals(scores.get(a)) ? a.compareTo(b) : Double.compare(scores.get(b), scores.get(a)));
        List<Hit> hits = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Map<String, Integer> perParent = new HashMap<>();
        for (String id : ids) {
            Hit h = rows.get(id);
            if (h == null) continue; // deleted between the retrievers and here
            String parent = h.source() + ":" + h.parentId();
            // One long document mustn't fill the whole list and crowd out the rest.
            if (!seen.add(h.source() + ":" + h.sourceId()) || perParent.getOrDefault(parent, 0) >= PER_PARENT_CAP) continue;
            perParent.merge(parent, 1, Integer::sum);
            hits.add(h);
            if (hits.size() == FINAL_K) break;
        }
        return hits;
    }
}
