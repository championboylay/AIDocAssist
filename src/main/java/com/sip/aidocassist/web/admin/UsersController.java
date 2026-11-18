package com.sip.aidocassist.web.admin;

import com.sip.aidocassist.web.auth.AuthService;
import com.sip.aidocassist.web.auth.SecurityConfig;
import com.sip.aidocassist.web.auth.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import java.net.URLEncoder;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Admin: Users (port of SKB admin_users.py routes). /admin/** is admin-only (SecurityConfig). */
@Controller
@RequestMapping("/admin")
public class UsersController {

    private static final int MAX_IMPORT_BYTES = 1_000_000;

    private final UserAdmin users;
    private final AuthService auth;

    public UsersController(UserAdmin users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    @GetMapping("")
    String adminRoot() {
        return "redirect:/admin/users";
    }

    @GetMapping("/users")
    String list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "") String saved,
                @RequestParam(defaultValue = "") String deleted, @RequestParam(defaultValue = "-1") int imported,
                HttpServletRequest req, Model model) {
        int[] counts = users.counts();
        Page p = Page.of(counts[0], page, req);
        model.addAllAttributes(Map.of("tab", "users", "users", users.list(p.size(), p.offset()), "total", counts[0],
                "activeCount", counts[1], "p", p, "saved", saved, "deleted", deleted, "imported", imported));
        return "admin/users";
    }

    private String formPage(Model model, UserAdmin.Row target, UserAdmin.Form form, List<String> errors,
                            HttpServletResponse res) {
        if (!errors.isEmpty()) res.setStatus(400);
        model.addAttribute("tab", "users");
        model.addAttribute("target", target);
        model.addAttribute("isNew", target == null);
        model.addAttribute("form", form);
        model.addAttribute("allGroups", users.allGroups());
        model.addAttribute("errors", errors);
        return "admin/user_form";
    }

    private static UserAdmin.Form readForm(String username, String email, String name, String password, List<String> groups,
                                           String isAdmin, String active) {
        return new UserAdmin.Form(AuthService.normalize(username), AuthService.normalize(email), name.strip(), password,
                groups == null ? List.of() : List.copyOf(new TreeSet<>(groups)), isAdmin != null, active != null);
    }

    @GetMapping("/users/new")
    String newPage(Model model, HttpServletResponse res) {
        List<String> groups = users.allGroups().contains("general") ? List.of("general") : List.of();
        return formPage(model, null, new UserAdmin.Form("", "", "", "", groups, false, true), List.of(), res);
    }

    @PostMapping("/users/new")
    String create(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String email,
                  @RequestParam(defaultValue = "") String name,
                  @RequestParam(defaultValue = "") String password, @RequestParam(required = false) List<String> groups,
                  @RequestParam(name = "is_admin", required = false) String isAdmin,
                  @RequestParam(required = false) String active, Model model, HttpServletResponse res) {
        var form = readForm(username, email, name, password, groups, isAdmin, active);
        var errors = users.validate(form, users.allGroups(), true, false, users.usernameTaken(form.username(), null),
                users.emailTaken(form.email(), null, form.username()));
        if (!errors.isEmpty()) return formPage(model, null, form, errors, res);
        users.create(form);
        return "redirect:/admin/users?saved=" + URLEncoder.encode(form.username(), StandardCharsets.UTF_8);
    }

    @GetMapping("/users/{id}")
    String editPage(@PathVariable String id, Model model, HttpServletResponse res) {
        var target = users.get(id);
        if (target == null) return "redirect:/admin/users";
        return formPage(model, target, formOf(target), List.of(), res);
    }

    @PostMapping("/users/{id}")
    String update(@PathVariable String id, @RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String email,
                  @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String password,
                  @RequestParam(required = false) List<String> groups,
                  @RequestParam(name = "is_admin", required = false) String isAdmin,
                  @RequestParam(required = false) String active, @AuthenticationPrincipal User me,
                  Model model, HttpServletRequest req, HttpServletResponse res) {
        var target = users.get(id);
        if (target == null) return "redirect:/admin/users";
        var form = readForm(username, email, name, password, groups, isAdmin, active);
        boolean self = id.equals(me.id());
        var errors = users.validate(form, users.allGroups(), false, self, users.usernameTaken(form.username(), id),
                users.emailTaken(form.email(), id, form.username()));
        if (!errors.isEmpty()) return formPage(model, target, form, errors, res);
        users.update(id, form);
        if (self && !password.isEmpty()) SecurityConfig.signIn(auth.load(id), req, res); // keep my own session
        return "redirect:/admin/users?saved=" + URLEncoder.encode(form.username(), StandardCharsets.UTF_8);
    }

    @PostMapping("/users/{id}/delete")
    String delete(@PathVariable String id, @AuthenticationPrincipal User me, Model model, HttpServletResponse res) {
        var target = users.get(id);
        if (target == null) return "redirect:/admin/users";
        if (id.equals(me.id())) {
            return formPage(model, target, formOf(target), List.of("You can't delete your own account."), res);
        }
        users.delete(id);
        return "redirect:/admin/users?deleted=" + URLEncoder.encode(target.username(), StandardCharsets.UTF_8);
    }

    private static UserAdmin.Form formOf(UserAdmin.Row u) {
        return new UserAdmin.Form(u.username(), u.email() == null ? "" : u.email(), u.name(), "", u.groups(), u.isAdmin(), u.active());
    }

    // ---------- CSV import ----------

    private String importPage(Model model, HttpServletResponse res, Map<String, ?> attrs) {
        model.addAttribute("tab", "users");
        model.addAllAttributes(attrs);
        if (attrs.get("errors") instanceof List<?> e && !e.isEmpty()) res.setStatus(400);
        return "admin/import";
    }

    @GetMapping("/users-import")
    String importForm(Model model, HttpServletResponse res) {
        return importPage(model, res, Map.of());
    }

    @PostMapping("/users-import")
    String importPreview(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal User me, Model model,
                         HttpServletResponse res) throws Exception {
        if (file.getSize() > MAX_IMPORT_BYTES)
            return importPage(model, res, Map.of("errors", List.of("That file is over 1 MB; a user list should be much smaller.")));
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes())).toString();
        } catch (CharacterCodingException e) {
            return importPage(model, res, Map.of("errors", List.of("Save the file as CSV UTF-8 (in Excel: Save As → CSV UTF-8).")));
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        return preview(model, res, text, filename, 1, users.parseImport(text, me));
    }

    /** Another page of the preview. The file lives only in the page, so it is re-sent and re-checked. */
    @PostMapping("/users-import/page")
    String importPreviewPage(@RequestParam(name = "csv_text", defaultValue = "") String csvText,
                             @RequestParam(defaultValue = "") String filename, @RequestParam(defaultValue = "1") int page,
                             @AuthenticationPrincipal User me, Model model, HttpServletResponse res) {
        return preview(model, res, csvText, filename, page, users.parseImport(csvText, me));
    }

    private String preview(Model model, HttpServletResponse res, String text, String filename, int page, UserAdmin.Import imp) {
        var changes = imp.changes();
        Page p = Page.of(changes.size(), page);
        Map<String, Long> counts = Map.of(
                "add", changes.stream().filter(c -> c.action().equals("add")).count(),
                "update", changes.stream().filter(c -> c.action().equals("update")).count(),
                "unchanged", changes.stream().filter(c -> c.action().equals("unchanged")).count());
        return importPage(model, res, Map.of("preview", true, "changes", changes.subList(p.offset(), p.last()),
                "errors", imp.errors(), "csvText", text, "filename", filename, "p", p, "counts", counts));
    }

    @PostMapping("/users-import/confirm")
    String importConfirm(@RequestParam(name = "csv_text", defaultValue = "") String csvText,
                         @AuthenticationPrincipal User me, Model model, HttpServletResponse res) {
        // Re-validate the whole file: the database may have changed since the preview.
        var imp = users.parseImport(csvText, me);
        if (!imp.errors().isEmpty()) return preview(model, res, csvText, "", 1, imp);
        int n = users.applyImport(imp.changes());
        return "redirect:/admin/users?imported=" + n;
    }
}
