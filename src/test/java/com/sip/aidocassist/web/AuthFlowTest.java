package com.sip.aidocassist.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 end to end on a throwaway database: login, CSRF, lockout, the admin gate, per-request
 * reload (deactivation, password change), open-redirect guard, CSV import, group rename.
 * Port of the checks in SKB tests/test_auth.py and tests/test_admin_users.py that apply here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    static final String PW = "correct-horse-1";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        r.add("gemini.api-keys", () -> "fake-key-0001,fake-key-0002"); // fake: nothing here embeds, the Quota page only lists them
        r.add("deepseek.api-key", () -> "");
        r.add("db.path", () -> tmp("auth.db"));
        r.add("admin.username", () -> "");   // don't pick up the real first admin from .env
        r.add("admin.email", () -> "");
        r.add("admin.password", () -> "");
        r.add("sync.scheduler", () -> "false"); // the real ticker would start syncs inside a cached test context
    }

    static String tmp(String name) {
        try {
            return Files.createTempDirectory("aidocassist-test").resolve(name).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired BCryptPasswordEncoder encoder;

    static boolean seeded;

    @BeforeAll
    static void reset() { seeded = false; }

    String user(String username, boolean admin, String... groups) {
        if (!seeded) {
            for (String g : List.of("general", "finance", "contracts"))
                db.sql("INSERT INTO groups (id, name) VALUES (?, ?)").params(UUID.randomUUID().toString(), g).update();
            seeded = true;
        }
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO users (id, username, email, name, password_hash, is_admin) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, username, username + "@x.test", username, encoder.encode(PW), admin).update();
        for (String g : groups)
            db.sql("INSERT INTO user_groups SELECT ?, id FROM groups WHERE name = ?").params(id, g).update();
        return id;
    }

    MockHttpSession login(String username, String password) throws Exception {
        var res = mvc.perform(post("/login").with(csrf()).param("username", username).param("password", password))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) res.getRequest().getSession();
    }

    @Test
    void anonymousGoesToLoginRememberingWhere() throws Exception {
        mvc.perform(get("/admin/users?page=2")).andExpect(redirectedUrl("/login?next=%2Fadmin%2Fusers%3Fpage%3D2"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Forgot it? Ask your admin.")));
    }

    @Test
    void loginNeedsCsrfAndRefusesOpenRedirect() throws Exception {
        user("csrf", false, "general");
        mvc.perform(post("/login").param("username", "csrf").param("password", PW)).andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).param("username", " CSRF ").param("password", PW).param("next", "//evil.test/"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").with(csrf()).param("username", "csrf").param("password", PW).param("next", "/account/password"))
                .andExpect(redirectedUrl("/account/password"));
    }

    @Test
    void userSeesOwnGroupsAndNotTheAdminArea() throws Exception {
        user("mei", false, "contracts", "general");
        var s = login("mei", PW);
        mvc.perform(get("/").session(s)).andExpect(status().isOk())
                .andExpect(content().string(containsString(">Contracts<"))).andExpect(content().string(containsString(">General<")))
                .andExpect(content().string(not(containsString(">Finance<")))).andExpect(content().string(not(containsString("href=\"/admin\""))));
        mvc.perform(get("/admin/users").session(s)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/groups/new").session(s).with(csrf()).param("name", "sneaky")).andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordThenLockout() throws Exception {
        user("lock", false, "general");
        user("unknown-neighbour", false, "general");
        for (int i = 0; i < 5; i++)
            mvc.perform(post("/login").with(csrf()).param("username", "lock").param("password", "wrong-password"))
                    .andExpect(status().isUnauthorized()).andExpect(content().string(containsString("Incorrect username or password.")));
        mvc.perform(post("/login").with(csrf()).param("username", "lock").param("password", PW))
                .andExpect(status().isUnauthorized()).andExpect(content().string(containsString("Too many failed attempts")));
        login("unknown-neighbour", PW); // lockout is per username
        mvc.perform(post("/login").with(csrf()).param("username", "nobody").param("password", PW))
                .andExpect(status().isUnauthorized()).andExpect(content().string(containsString("Incorrect username or password.")));
    }

    @Test
    void deactivationAndDeletionApplyOnTheNextClick() throws Exception {
        String id = user("leaver", false, "general");
        var s = login("leaver", PW);
        mvc.perform(get("/").session(s)).andExpect(status().isOk());
        db.sql("UPDATE users SET active = 0 WHERE id = ?").param(id).update();
        mvc.perform(get("/").session(s)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/login").with(csrf()).param("username", "leaver").param("password", PW)).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeEndsOtherSessionsButKeepsThisOne() throws Exception {
        user("pw", false, "general");
        var laptop = login("pw", PW);
        var phone = login("pw", PW);
        mvc.perform(post("/account/password").session(laptop).with(csrf())
                .param("current", PW).param("next", "short").param("confirm", "short"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("at least 10")));
        mvc.perform(post("/account/password").session(laptop).with(csrf())
                .param("current", PW).param("next", "a-new-password-2").param("confirm", "a-new-password-2"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Password changed.")));
        mvc.perform(get("/").session(laptop)).andExpect(status().isOk());
        mvc.perform(get("/").session(phone)).andExpect(status().is3xxRedirection());
        login("pw", "a-new-password-2");
    }

    @Test
    void adminAddsUserImportsCsvAndRenamesGroup() throws Exception {
        String adminId = user("boss", true, "general");
        var s = login("boss", PW);

        // Add a user, who can then log in with the initial password and sees only their groups.
        mvc.perform(post("/admin/users/new").session(s).with(csrf()).param("username", "New.Hire").param("email", " New.Hire@StartInPoint.com ").param("name", "New Hire")
                .param("password", "first-password-1").param("groups", "finance").param("active", "on"))
                .andExpect(redirectedUrl("/admin/users?saved=new.hire"));
        mvc.perform(get("/").session(login("new.hire", "first-password-1"))).andExpect(content().string(containsString(">Finance<")));
        assertEquals("new.hire@startinpoint.com", db.sql("SELECT email FROM users WHERE username = 'new.hire'").query(String.class).single());
        mvc.perform(post("/login").with(csrf()).param("username", "new.hire@startinpoint.com").param("password", "first-password-1"))
                .andExpect(status().isUnauthorized()); // email is a contact address, not a login

        // Email is optional; a username outside SKB's rule is refused.
        mvc.perform(post("/admin/users/new").session(s).with(csrf()).param("username", "no.mail").param("name", "No Mail")
                .param("password", "first-password-1").param("active", "on"))
                .andExpect(redirectedUrl("/admin/users?saved=no.mail"));
        assertNull(db.sql("SELECT email FROM users WHERE username = 'no.mail'").query(String.class).single());
        mvc.perform(post("/admin/users/new").session(s).with(csrf()).param("username", "bad name!").param("name", "X")
                        .param("password", "first-password-1"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Choose a username")));

        // Can't demote or delete yourself.
        mvc.perform(post("/admin/users/" + adminId).session(s).with(csrf()).param("username", "boss").param("name", "boss").param("active", "on"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("own admin rights")));
        mvc.perform(post("/admin/users/" + adminId + "/delete").session(s).with(csrf())).andExpect(status().isBadRequest());

        // CSV import: preview writes nothing, confirm applies it.
        String csv = "username,name,groups,is_admin,active,initial_password\r\n"
                + "csv.person,CSV Person,general;contracts,false,true,csv-password-1\r\n"
                + "new.hire,,general,,,\r\n";
        mvc.perform(multipart("/admin/users-import").file(new MockMultipartFile("file", "users.csv", "text/csv", csv.getBytes()))
                        .session(s).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("1 to add, 1 to update, 0 unchanged.")))
                .andExpect(content().string(containsString("groups: finance → general")));
        assertEquals(0, db.sql("SELECT count(*) FROM users WHERE username = 'csv.person'").query(Integer.class).single());
        mvc.perform(post("/admin/users-import/confirm").session(s).with(csrf()).param("csv_text", csv))
                .andExpect(redirectedUrl("/admin/users?imported=2"));
        login("csv.person", "csv-password-1");
        mvc.perform(multipart("/admin/users-import").file(new MockMultipartFile("file", "bad.csv", "text/csv",
                        "username,groups\nnope,nosuchgroup\n".getBytes())).session(s).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("unknown group(s) nosuchgroup")));

        // Rename a group (its own, so other tests keep theirs): the tag arrays on sync entries follow, order kept.
        mvc.perform(post("/admin/groups/new").session(s).with(csrf()).param("name", " Old-Tag ")).andExpect(redirectedUrl("/admin/groups?saved=old-tag"));
        String src = UUID.randomUUID().toString();
        db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids) VALUES (?, 'filesystem', 'docs', '{}', '[\"old-tag\",\"general\"]')")
                .param(src).update();
        String tagId = db.sql("SELECT id FROM groups WHERE name = 'old-tag'").query(String.class).single();
        mvc.perform(post("/admin/groups/" + tagId).session(s).with(csrf()).param("name", "new-tag"))
                .andExpect(redirectedUrl("/admin/groups?saved=new-tag"));
        assertEquals("[\"new-tag\",\"general\"]", db.sql("SELECT default_group_ids FROM sync_sources WHERE id = ?").param(src).query(String.class).single());
        mvc.perform(post("/admin/groups/" + tagId + "/delete").session(s).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("still in use")));
        mvc.perform(get("/admin/users").session(s)).andExpect(status().isOk()).andExpect(content().string(containsString("csv.person")))
                .andExpect(content().string(not(containsString("del-" + adminId)))); // no delete offered on my own row

        // Every admin page renders (templates are only checked when rendered).
        String hireId = db.sql("SELECT id FROM users WHERE username = 'new.hire'").query(String.class).single();
        for (String url : List.of("/admin", "/admin/users/new", "/admin/users/" + hireId, "/admin/users/" + adminId,
                "/admin/users-import", "/admin/groups", "/admin/groups/new", "/admin/groups/" + tagId, "/admin/retrieval", "/admin/quota", "/account/password", "/"))
            mvc.perform(get(url).session(s)).andExpect(status().is(url.equals("/admin") ? 302 : 200));
        // The Quota page shows each key by its last 4 characters only, never the key itself.
        mvc.perform(get("/admin/quota").session(s)).andExpect(content().string(containsString("…0002")))
                .andExpect(content().string(containsString("0 / 950"))).andExpect(content().string(not(containsString("fake-key"))));
    }
}
