package com.sip.aidocassist.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Login, logout (Spring's, POST /logout) and change own password. Port of SKB app.py. "/" is the chat (ChatController). */
@Controller
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Every template can use ${user}: the logged-in person, or null on the login page. */
    @ControllerAdvice
    static class CurrentUser {
        @ModelAttribute("user")
        User user(@AuthenticationPrincipal User user) {
            return user;
        }
    }

    /** Only same-site paths: never redirect to another host after login (open redirect). */
    static String safeNext(String target) {
        if (target != null && target.startsWith("/") && !target.startsWith("//") && !target.contains("\\")) return target;
        return "/";
    }

    @GetMapping("/login")
    String loginPage(@RequestParam(defaultValue = "/") String next, @AuthenticationPrincipal User user, Model model) {
        if (user != null) return "redirect:" + safeNext(next);
        model.addAttribute("next", safeNext(next));
        return "login";
    }

    @PostMapping("/login")
    String login(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
                 @RequestParam(defaultValue = "/") String next, HttpServletRequest req, HttpServletResponse res,
                 Model model) {
        String error;
        if (auth.lockedOut(username)) {
            error = "Too many failed attempts. Try again in 15 minutes, or ask your admin.";
        } else {
            User user = auth.authenticate(username, password);
            if (user != null) {
                SecurityConfig.signIn(user, req, res);
                return "redirect:" + safeNext(next);
            }
            error = "Incorrect username or password.";
        }
        res.setStatus(401);
        model.addAttribute("error", error);
        model.addAttribute("username", AuthService.normalize(username));
        model.addAttribute("next", safeNext(next));
        return "login";
    }

    @GetMapping("/account/password")
    String passwordPage() {
        return "password";
    }

    @PostMapping("/account/password")
    String changePassword(@RequestParam(defaultValue = "") String current, @RequestParam(defaultValue = "") String next,
                          @RequestParam(defaultValue = "") String confirm, @AuthenticationPrincipal User user,
                          HttpServletRequest req, HttpServletResponse res, Model model) {
        String error = auth.changePassword(user, current, next, confirm);
        if (error != null) {
            res.setStatus(400);
            model.addAttribute("error", error);
            return "password";
        }
        // Other sessions of this user are now invalid (their fingerprint no longer matches); keep this one.
        SecurityConfig.signIn(auth.load(user.id()), req, res);
        model.addAttribute("done", true);
        return "password";
    }
}
