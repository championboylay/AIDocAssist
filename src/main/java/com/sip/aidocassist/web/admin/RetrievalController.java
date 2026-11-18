package com.sip.aidocassist.web.admin;

import com.sip.aidocassist.retrieval.Fusion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Admin: the raw evidence for a question, no LLM (CLAUDE.md Phase 4). The admin picks which groups
 * to search as (all of them by default, like SKB's `python -m skb.retrieval.fusion`), to see what a
 * user with those groups would get and why: each hit's RRF score and its rank in each retriever.
 */
@Controller
@RequestMapping("/admin/retrieval")
public class RetrievalController {

    static final String UNAVAILABLE = "Search is unavailable right now: the embedding server can't be reached. Try again shortly.";

    private final Fusion fusion;
    private final JdbcClient db;

    public RetrievalController(Fusion fusion, JdbcClient db) {
        this.fusion = fusion;
        this.db = db;
    }

    @GetMapping("")
    String page(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) List<String> groups, Model model) {
        List<String> all = db.sql("SELECT name FROM groups ORDER BY name").query(String.class).list();
        List<String> as = groups == null ? all : groups.stream().filter(all::contains).toList();
        model.addAttribute("tab", "retrieval");
        model.addAttribute("q", q);
        model.addAttribute("allGroups", all);
        model.addAttribute("asGroups", as);
        model.addAttribute("retrievers", Fusion.RETRIEVERS);
        model.addAttribute("result", Fusion.Result.EMPTY);
        model.addAttribute("error", null);
        if (!q.isBlank()) {
            try {
                model.addAttribute("result", fusion.search(q, as));
            } catch (RestClientException e) {
                model.addAttribute("error", UNAVAILABLE);
            }
        }
        return "admin/retrieval";
    }
}
