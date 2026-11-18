package com.sip.aidocassist.web.admin;

import com.sip.aidocassist.llm.Embedder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin: each Gemini key's use as this app counts it (an AI Studio key can't ask Google for its
 * remaining quota; Google's own figures are in AI Studio), and how many records each embedding
 * model holds, which shows how much fell back to Ollama.
 */
@Controller
@RequestMapping("/admin/quota")
public class QuotaController {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Embedder embedder;
    private final JdbcClient db;

    public QuotaController(Embedder embedder, JdbcClient db) {
        this.embedder = embedder;
        this.db = db;
    }

    @GetMapping("")
    String page(Model model) {
        Map<String, Integer> records = new LinkedHashMap<>();
        db.sql("SELECT coalesce(embed_model, ?), count(*) FROM knowledge_records WHERE embedding IS NOT NULL GROUP BY 1 ORDER BY 2 DESC")
                .param(embedder.legacy()).query((rs, i) -> records.put(rs.getString(1), rs.getInt(2))).list();
        model.addAttribute("tab", "quota");
        model.addAttribute("keys", embedder.usage());
        model.addAttribute("model", embedder.geminiModel());
        model.addAttribute("preferred", embedder.preferred());
        model.addAttribute("records", records);
        model.addAttribute("time", TIME);
        return "admin/quota";
    }
}
