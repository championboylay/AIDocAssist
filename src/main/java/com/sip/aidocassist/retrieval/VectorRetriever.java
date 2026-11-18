package com.sip.aidocassist.retrieval;

import com.sip.aidocassist.retrieval.Visible.Ranked;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Nearest records by meaning (CLAUDE.md "Retrieval on SQLite"; SKB retrieval/vector.py without
 * pgvector). No vector index: the embeddings of the records the user's groups allow are loaded
 * (filtered in SQL) and compared in Java. Stored and query vectors are unit length, so cosine
 * similarity is their dot product.
 *
 * ponytail: brute force, ceiling ~50k chunks (~400 MB of float32 read per query); then add an
 * in-memory cache or the sqlite-vec extension.
 */
@Component
public class VectorRetriever {

    private final JdbcClient db;

    public VectorRetriever(JdbcClient db) {
        this.db = db;
    }

    /**
     * Records embedded by {@code model} most similar to {@code query} (a unit vector from the same
     * model), best first; score = cosine similarity. {@code legacy} = the model of rows with no embed_model.
     */
    public List<Ranked> search(float[] query, String model, String legacy, List<String> groups, int k) {
        if (groups.isEmpty()) return List.of();
        PriorityQueue<Ranked> best = new PriorityQueue<>(Comparator.comparingDouble(Ranked::score)); // worst on top
        db.sql("SELECT k.id, k.embedding FROM knowledge_records k WHERE k.embedding IS NOT NULL AND coalesce(k.embed_model, ?) = ? AND "
                        + Visible.where(groups))
                .param(legacy).param(model).params(groups)
                .query(rs -> {
                    Ranked r = new Ranked(rs.getString(1), dot(query, rs.getBytes(2)));
                    if (best.size() < k) best.add(r);
                    else if (r.score() > best.peek().score()) { best.poll(); best.add(r); }
                });
        List<Ranked> out = new ArrayList<>(best);
        out.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::id));
        return out;
    }

    /** The embedding models of the records these groups can see (one vector space each). */
    public List<String> models(String legacy, List<String> groups) {
        if (groups.isEmpty()) return List.of();
        return db.sql("SELECT DISTINCT coalesce(k.embed_model, ?) FROM knowledge_records k WHERE k.embedding IS NOT NULL AND "
                        + Visible.where(groups) + " ORDER BY 1")
                .param(legacy).params(groups).query(String.class).list();
    }

    /** Dot product of a query vector and a stored float32 little-endian embedding (no array copy). */
    static double dot(float[] q, byte[] stored) {
        ByteBuffer b = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN);
        int n = Math.min(q.length, stored.length / 4);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += q[i] * b.getFloat(i * 4);
        return sum;
    }
}
