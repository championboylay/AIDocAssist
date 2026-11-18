package com.sip.aidocassist.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security does the plumbing (session, CSRF, logout, headers, the admin gate); the
 * credential check itself is {@link AuthService#authenticate}, called from {@link AuthController}.
 */
@Configuration
@ConditionalOnWebApplication // the `seed` command runs without a web server
public class SecurityConfig {

    private static final HttpSessionSecurityContextRepository SESSIONS = new HttpSessionSecurityContextRepository();

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthService auth) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/static/**", "/error").permitAll()
                        .requestMatchers("/admin", "/admin/**").hasAuthority("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> toLogin(req, res)))
                .requestCache(c -> c.disable())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login"))
                // SKB's headers: no inline scripts, no outside origins, no framing.
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(
                                "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .addFilterAfter(new ReloadUser(auth), SecurityContextHolderFilter.class);
        return http.build();
    }

    /**
     * The H2 web console, used as a JDBC browser on the SQLite file. Admins only, and H2 itself
     * refuses non-localhost requests (web-allow-others is false). It needs frames and inline
     * scripts, so it gets its own chain without the app's CSP; CSRF is off here because the console
     * posts without our token and guards its own forms with a per-session jsessionid in the URL.
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty("spring.h2.console.enabled")
    SecurityFilterChain h2ConsoleChain(HttpSecurity http, AuthService auth) throws Exception {
        http.securityMatcher(PathRequest.toH2Console()) // the console is its own servlet, not MVC
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("ADMIN"))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> toLogin(req, res)))
                .requestCache(c -> c.disable())
                .csrf(c -> c.disable())
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .addFilterAfter(new ReloadUser(auth), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** Not logged in: to the login page, remembering where they were going (SKB's ?next=). */
    private static void toLogin(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.sendRedirect(req.getContextPath() + "/login?next=" + URLEncoder.encode(pathAndQuery(req), StandardCharsets.UTF_8));
    }

    /** Log this browser in as {@code user}: new session id (no fixation), user stored in the session. */
    public static void signIn(User user, HttpServletRequest req, HttpServletResponse res) {
        req.getSession();
        req.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token(user));
        SecurityContextHolder.setContext(context);
        SESSIONS.saveContext(context, req, res);
    }

    static UsernamePasswordAuthenticationToken token(User u) {
        return UsernamePasswordAuthenticationToken.authenticated(u, null,
                u.isAdmin() ? List.of(new SimpleGrantedAuthority("ADMIN")) : List.of());
    }

    private static String pathAndQuery(HttpServletRequest req) {
        // Context-relative: login redirects with "redirect:" + next, which adds the context path again.
        return req.getRequestURI().substring(req.getContextPath().length()) + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
    }

    /**
     * Reloads the user from the database on every request (SKB current_user()), so deactivation,
     * deletion, admin and group changes apply on the next click, and a password change ends every
     * other session (its fingerprint no longer matches the one stored at login).
     */
    static final class ReloadUser extends OncePerRequestFilter {
        private final AuthService auth;

        ReloadUser(AuthService auth) { this.auth = auth; }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            var current = SecurityContextHolder.getContext().getAuthentication();
            if (current != null && current.getPrincipal() instanceof User stored) {
                User fresh = auth.load(stored.id());
                if (fresh == null || !fresh.sessionKey().equals(stored.sessionKey())) {
                    SecurityContextHolder.clearContext();
                    var session = req.getSession(false);
                    if (session != null) session.invalidate();
                } else {
                    // This request only; the session keeps the id + fingerprint it had at login.
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(token(fresh));
                    SecurityContextHolder.setContext(context);
                }
            }
            chain.doFilter(req, res);
        }
    }
}
