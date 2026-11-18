package com.sip.aidocassist;

import com.sip.aidocassist.chunking.Chunker;
import com.sip.aidocassist.db.Migrator;
import com.sip.aidocassist.ingestion.SyncService;
import com.sip.aidocassist.generation.AnswerService;
import com.sip.aidocassist.generation.Reformer;
import com.sip.aidocassist.retrieval.Fusion;
import com.sip.aidocassist.db.Seeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import com.sip.aidocassist.web.auth.AuthService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

// No generated in-memory user: AuthService checks credentials against the users table.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AiDocAssistApplication {

    private static final Logger log = LoggerFactory.getLogger(AiDocAssistApplication.class);

    /** {@code seed <init-data dir>} loads SKB's CSVs and exits; no arguments starts the web app. */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("chunks")) { // inspect chunking: no Spring, no database
            printChunks(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("seed")) {
            if (args.length < 2) throw new IllegalArgumentException("usage: seed <path to skb/init-data>");
            try (var ctx = new SpringApplicationBuilder(AiDocAssistApplication.class)
                    .web(WebApplicationType.NONE).run()) {
                ctx.getBean(Seeder.class).seed(Path.of(args[1]));
            }
            return;
        }
        if (args.length > 0 && args[0].equals("ask")) {
            try (var ctx = new SpringApplicationBuilder(AiDocAssistApplication.class)
                    .web(WebApplicationType.NONE).run()) {
                askCommand(ctx.getBean(JdbcClient.class), ctx.getBean(AnswerService.class), Arrays.copyOfRange(args, 1, args.length));
            }
            return;
        }
        if (args.length > 0 && args[0].equals("search")) {
            try (var ctx = new SpringApplicationBuilder(AiDocAssistApplication.class)
                    .web(WebApplicationType.NONE).run()) {
                searchCommand(ctx.getBean(JdbcClient.class), ctx.getBean(Fusion.class), Arrays.copyOfRange(args, 1, args.length));
            }
            return;
        }
        if (args.length > 0 && args[0].equals("sync")) {
            try (var ctx = new SpringApplicationBuilder(AiDocAssistApplication.class)
                    .web(WebApplicationType.NONE).run()) {
                syncCommand(ctx.getBean(JdbcClient.class), ctx.getBean(SyncService.class), Arrays.copyOfRange(args, 1, args.length));
            }
            return;
        }
        SpringApplication.run(AiDocAssistApplication.class, args);
    }

    /**
     * {@code ask "question" ["follow-up" ...] [--groups a,b]}: the questions as ONE conversation (each
     * turn becomes history for the next), printing what the chat will show: the rewrite, the outcome,
     * the answer, its citations and the suggestions. Without --groups: every group.
     */
    static void askCommand(JdbcClient db, AnswerService answers, String[] args) {
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        List<String> groups = db.sql("SELECT name FROM groups ORDER BY name").query(String.class).list();
        List<String> questions = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--groups") && i + 1 < args.length)
                groups = Arrays.stream(args[++i].split(",")).map(String::strip).filter(g -> !g.isEmpty()).toList();
            else questions.add(args[i]);
        }
        if (questions.isEmpty()) throw new IllegalArgumentException("usage: ask \"question\" [\"follow-up\" ...] [--groups a,b]");
        out.println("groups: " + groups);
        List<Reformer.Exchange> history = new java.util.ArrayList<>();
        for (String q : questions) {
            var t = answers.answer(q, groups, history);
            out.println("\n========== Q: " + q);
            if (t.reformedQuery() != null) out.println("resolved '" + q + "' -> '" + t.reformedQuery() + "', from thread context");
            out.println("outcome: " + t.outcome() + " · retrieved " + t.retrieved() + " · attempts " + t.attempts() + " · ms " + t.millis()
                    + (t.problems().isEmpty() ? "" : "\nproblems: " + t.problems()));
            out.println("\n" + t.answer());
            for (var c : t.citations()) out.println("  [" + c.label() + "] " + c.sourceId() + "\n       \"" + c.excerpt() + "\"");
            if (!t.suggested().isEmpty())
                out.println("  suggested: " + t.suggested().stream().map(x -> "#" + x.rank() + " " + x.sourceId()).toList());
            history.add(new Reformer.Exchange(q, t.answer()));
        }
    }

    /**
     * {@code search "question" [--groups a,b]}: the fused evidence, one line per hit (score, rank per
     * retriever, file, section), the same shape as SKB's {@code python -m skb.retrieval.fusion}, for
     * comparing the two. Without --groups: every group (an admin's view).
     */
    static void searchCommand(JdbcClient db, Fusion fusion, String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("usage: search \"question\" [--groups a,b]");
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        List<String> groups = db.sql("SELECT name FROM groups ORDER BY name").query(String.class).list();
        for (int i = 1; i < args.length - 1; i++)
            if (args[i].equals("--groups")) groups = Arrays.stream(args[i + 1].split(",")).map(String::strip).filter(g -> !g.isEmpty()).toList();
        var result = fusion.search(args[0], groups);
        out.println("Q: " + args[0] + "\ngroups: " + groups + "\nrare tokens: "
                + result.rareTokens().stream().map(t -> String.format("('%s', %.2f)", t.token(), t.idf())).toList() + "\n");
        for (var h : result.hits()) {
            String ranks = String.join("  ", Fusion.RETRIEVERS.stream()
                    .map(r -> r.substring(0, 4) + ":" + String.format("%2s", h.ranks().containsKey(r) ? h.ranks().get(r) : "-")).toList());
            String file = h.file().length() > 28 ? h.file().substring(0, 28) : h.file();
            String where = h.section().length() > 80 ? h.section().substring(0, 80) : h.section();
            out.println(String.format("%.4f  [%s]  %-28s  %s", h.score(), ranks, file, where));
        }
    }

    /** {@code sync} lists the entries; {@code sync <entry id>|all [--reread]} runs them (all = enabled ones). */
    static void syncCommand(JdbcClient db, SyncService sync, String[] args) {
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        if (args.length == 0) {
            out.println("Sync entries (run one with: sync <id> [--reread], or: sync all):");
            db.sql("SELECT id, name, source, config, default_group_ids, enabled, coalesce(last_status, 'never synced') FROM sync_sources "
                    + "WHERE deleted_at IS NULL ORDER BY name").query((rs, i) -> {
                out.println("  " + rs.getString(1) + "  " + rs.getString(2) + " [" + rs.getString(3) + "] " + rs.getString(4)
                        + " tags=" + rs.getString(5) + (rs.getInt(6) == 1 ? "" : " DISABLED") + " last=" + rs.getString(7));
                return null;
            }).list();
            return;
        }
        boolean reread = Arrays.asList(args).contains("--reread");
        List<String> ids = args[0].equals("all")
                ? db.sql("SELECT id FROM sync_sources WHERE enabled = 1 AND deleted_at IS NULL ORDER BY name").query(String.class).list()
                : List.of(args[0]);
        for (String id : ids) {
            String runId = sync.run(id, null, reread);
            if (runId == null) { out.println(id + ": not run (deleted, or already syncing)"); continue; }
            out.println(db.sql("SELECT status || ': inserted ' || records_inserted || ', updated ' || records_updated || ', deleted ' "
                    + "|| records_deleted || ', unchanged ' || records_skipped || coalesce(' -- ' || error_message, '') FROM sync_runs WHERE id = ?")
                    .param(runId).query(String.class).single());
        }
    }

    /** {@code chunks <file>...}: each file's chunks as "===== #n (chars) title" + content, for checking by hand. */
    static void printChunks(String[] files) throws Exception {
        if (files.length == 0) throw new IllegalArgumentException("usage: chunks <file>...");
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        for (String f : files) {
            var result = Chunker.chunk(Path.of(f));
            var chunks = result.chunks();
            out.println("##### " + f + ": " + chunks.size() + " chunks"
                    + (result.hiddenSheets().isEmpty() ? "" : ", hidden sheets skipped: " + result.hiddenSheets()));
            for (int i = 0; i < chunks.size(); i++)
                out.println("===== #" + i + " (" + chunks.get(i).text().length() + " chars) " + chunks.get(i).title()
                        + "\n" + chunks.get(i).text() + "\n");
        }
    }

    @Bean
    DataSource dataSource(@Value("${db.path}") String dbPath) throws IOException {
        Path file = Path.of(dbPath).toAbsolutePath();
        Files.createDirectories(file.getParent());
        // Pragmas are applied by sqlite-jdbc on every new connection. WAL is persistent in the file;
        // foreign_keys and busy_timeout are per connection, so they must be here, not in a one-off statement.
        // IMMEDIATE: a transaction takes the write lock when it begins, so two writers (a sync, a chat
        // message) wait on busy_timeout instead of failing when a read-then-write transaction upgrades.
        return DataSourceBuilder.create()
                .url("jdbc:sqlite:" + file + "?journal_mode=WAL&foreign_keys=true&busy_timeout=5000&transaction_mode=IMMEDIATE")
                .build();
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Web app only: runs a restart interrupted are marked failed (a command-line run must not touch a live web sync). */
    @Bean
    @Order(2)
    @ConditionalOnWebApplication
    ApplicationRunner resetInterruptedSyncs(SyncService sync) {
        return args -> {
            int n = sync.resetInterrupted();
            if (n > 0) log.warn("Marked {} interrupted sync run(s) as failed", n);
        };
    }

    @Bean
    @Order(1)
    ApplicationRunner startup(DataSource ds, JdbcClient db, BCryptPasswordEncoder encoder,
                              @Value("${admin.username}") String adminUsername,
                              @Value("${admin.email}") String adminEmail,
                              @Value("${admin.password}") String adminPassword) {
        return args -> {
            Migrator.migrate(ds);
            boolean hasAdmin = db.sql("SELECT count(*) FROM users WHERE is_admin = 1 AND active = 1 AND deleted_at IS NULL")
                    .query(Integer.class).single() > 0;
            if (hasAdmin) return;
            String username = AuthService.normalize(adminUsername);
            if (username.isEmpty() || adminPassword.isBlank()) {
                log.warn("No active admin exists. Set ADMIN_USERNAME and ADMIN_PASSWORD and restart to create one.");
                return;
            }
            if (adminPassword.length() < AuthService.MIN_PASSWORD_LEN)
                throw new IllegalStateException("ADMIN_PASSWORD needs at least " + AuthService.MIN_PASSWORD_LEN + " characters");
            String email = AuthService.normalize(adminEmail); // optional contact address
            int created = db.sql("INSERT INTO users (id, username, email, name, password_hash, is_admin) VALUES (?, ?, ?, ?, ?, 1) "
                            + "ON CONFLICT (username) DO NOTHING")
                    .params(UUID.randomUUID().toString(), username, email.isEmpty() ? null : email, "Administrator", encoder.encode(adminPassword))
                    .update();
            if (created == 1) log.info("Created first admin {}", username);
            else log.warn("ADMIN_USERNAME {} already belongs to a non-admin user; no admin created.", username);
        };
    }
}
