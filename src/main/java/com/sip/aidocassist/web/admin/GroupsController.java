package com.sip.aidocassist.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin: Groups (port of SKB admin_groups.py). Group NAMES are stored as JSON text in
 * knowledge_records.allowed_group_ids and sync_sources.default_group_ids (what retrieval filters on), so:
 * rename rewrites those arrays in the same transaction and is refused while a sync runs (it would
 * write the old name back); delete is only allowed for a group nothing uses, since deleting a used
 * one would silently hide every document tagged only with it (default-deny) and cut users' access.
 */
@Controller
@RequestMapping("/admin/groups")
public class GroupsController {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");

    public record Group(String id, String name, int users, int syncs, int records) {
        public boolean inUse() { return users + syncs + records > 0; }
    }

    private final JdbcClient db;

    public GroupsController(JdbcClient db) {
        this.db = db;
    }

    List<Group> list() {
        return db.sql("""
                        SELECT g.id, g.name,
                               (SELECT count(*) FROM user_groups ug WHERE ug.group_id = g.id) AS users,
                               (SELECT count(*) FROM sync_sources s WHERE s.deleted_at IS NULL
                                   AND EXISTS (SELECT 1 FROM json_each(s.default_group_ids) WHERE value = g.name)) AS syncs,
                               (SELECT count(*) FROM knowledge_records k
                                   WHERE EXISTS (SELECT 1 FROM json_each(k.allowed_group_ids) WHERE value = g.name)) AS records
                        FROM groups g ORDER BY g.name""")
                .query((rs, i) -> new Group(rs.getString("id"), rs.getString("name"), rs.getInt("users"),
                        rs.getInt("syncs"), rs.getInt("records")))
                .list();
    }

    private Group get(String id) {
        return list().stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
    }

    private List<String> validate(String name, String exceptId) {
        if (!NAME.matcher(name).matches())
            return new ArrayList<>(List.of("Use 2–40 characters: lowercase letters, digits and hyphens, starting with a letter or digit (e.g. hr-cv)."));
        boolean taken = db.sql("SELECT count(*) FROM groups WHERE name = ? AND id <> ?")
                .params(name, exceptId == null ? "" : exceptId).query(Integer.class).single() > 0;
        return new ArrayList<>(taken ? List.of("A group with that name already exists.") : List.of());
    }

    @GetMapping("")
    String listPage(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "") String saved,
                    @RequestParam(defaultValue = "") String deleted, HttpServletRequest req, Model model) {
        var groups = list(); // ponytail: few groups, so slice in Java rather than SQL
        Page p = Page.of(groups.size(), page, req);
        model.addAllAttributes(Map.of("tab", "groups", "groups", groups.subList(p.offset(), p.last()), "p", p,
                "saved", saved, "deleted", deleted));
        return "admin/groups";
    }

    private String form(Model model, Group group, String name, List<String> errors, HttpServletResponse res) {
        if (!errors.isEmpty()) res.setStatus(400);
        model.addAttribute("tab", "groups");
        model.addAttribute("group", group);
        model.addAttribute("name", name);
        model.addAttribute("errors", errors);
        return "admin/group_form";
    }

    @GetMapping("/new")
    String newPage(Model model, HttpServletResponse res) {
        return form(model, null, "", List.of(), res);
    }

    @PostMapping("/new")
    String create(@RequestParam(defaultValue = "") String name, Model model, HttpServletResponse res) {
        name = name.strip().toLowerCase();
        var errors = validate(name, null);
        if (!errors.isEmpty()) return form(model, null, name, errors, res);
        db.sql("INSERT INTO groups (id, name) VALUES (?, ?)").params(UUID.randomUUID().toString(), name).update();
        return "redirect:/admin/groups?saved=" + name;
    }

    @GetMapping("/{id}")
    String editPage(@PathVariable String id, Model model, HttpServletResponse res) {
        var group = get(id);
        if (group == null) return "redirect:/admin/groups";
        return form(model, group, group.name(), List.of(), res);
    }

    @PostMapping("/{id}")
    @Transactional
    public String rename(@PathVariable String id, @RequestParam(defaultValue = "") String name, Model model,
                         HttpServletResponse res) {
        name = name.strip().toLowerCase();
        var group = get(id);
        if (group == null || name.equals(group.name())) return "redirect:/admin/groups";
        var errors = validate(name, id);
        if (db.sql("SELECT count(*) FROM sync_sources WHERE last_status = 'syncing'").query(Integer.class).single() > 0)
            errors.add("A sync is running; rename after it finishes (it would write the old name back).");
        if (!errors.isEmpty()) return form(model, group, name, errors, res);
        String old = group.name();
        // One transaction: the group and every place its name is stored. Order inside each array is kept.
        db.sql("UPDATE groups SET name = ? WHERE id = ?").params(name, id).update();
        for (String[] t : new String[][]{{"knowledge_records", "allowed_group_ids"}, {"sync_sources", "default_group_ids"}})
            db.sql("UPDATE " + t[0] + " SET " + t[1] + " = (SELECT json_group_array(CASE WHEN value = :old THEN :new ELSE value END ORDER BY key) "
                            + "FROM json_each(" + t[1] + ")) WHERE EXISTS (SELECT 1 FROM json_each(" + t[1] + ") WHERE value = :old)")
                    .param("old", old).param("new", name).update();
        return "redirect:/admin/groups?saved=" + name;
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable String id, Model model, HttpServletResponse res) {
        var group = get(id);
        if (group == null) return "redirect:/admin/groups";
        if (group.inUse())
            return form(model, group, group.name(),
                    List.of("This group is still in use; remove it from all users, sync entries and documents first."), res);
        db.sql("DELETE FROM groups WHERE id = ?").param(id).update();
        return "redirect:/admin/groups?deleted=" + group.name();
    }
}
