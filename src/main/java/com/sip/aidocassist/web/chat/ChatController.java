package com.sip.aidocassist.web.chat;

import com.sip.aidocassist.web.auth.User;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The chat (port of SKB skb/api/chat.py routes): "/" is the new-thread page, "/t/{id}" a thread.
 * One turn = question -> cited answer or honest abstention, through Phase 5's AnswerService.
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final String UNAVAILABLE = "The model server can't be reached right now, so nothing was asked. Try again shortly.";

    private final ChatService chat;
    private final List<String> examples;

    /** chat.examples: example questions for the new-thread page, separated by "|"; none = the block is hidden. */
    public ChatController(ChatService chat, @Value("${chat.examples:}") String examples) {
        this.chat = chat;
        this.examples = Arrays.stream(examples.split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private String page(Model model, User user, ChatService.ThreadRef thread, List<ChatService.TurnView> turns, String error, String draft) {
        model.addAttribute("threads", chat.threads(user));
        model.addAttribute("thread", thread);
        model.addAttribute("turns", turns);
        model.addAttribute("error", error);
        model.addAttribute("draft", draft);
        model.addAttribute("examples", examples);
        model.addAttribute("maxQ", ChatService.MAX_QUESTION);
        return "chat";
    }

    @GetMapping("/")
    String newThread(@AuthenticationPrincipal User user, Model model) {
        return page(model, user, null, List.of(), null, "");
    }

    @GetMapping("/t/{id}")
    String thread(@PathVariable String id, @AuthenticationPrincipal User user, Model model) throws Exception {
        var thread = chat.owned(user, id);
        if (thread == null) return "redirect:/"; // someone else's thread, or none: never reveal which
        return page(model, user, thread, chat.turns(user, id), null, "");
    }

    @PostMapping("/ask")
    String ask(@RequestParam(defaultValue = "") String question, @RequestParam(name = "thread_id", defaultValue = "") String threadId,
               @AuthenticationPrincipal User user, Model model, HttpServletResponse res) throws Exception {
        String q = question.strip();
        if (q.length() > ChatService.MAX_QUESTION) q = q.substring(0, ChatService.MAX_QUESTION);
        var thread = threadId.isBlank() ? null : chat.owned(user, threadId);
        if (!threadId.isBlank() && thread == null) return "redirect:/";
        if (q.isEmpty()) return thread != null ? "redirect:/t/" + thread.id() : "redirect:/";
        try {
            return "redirect:/t/" + chat.ask(user, q, thread) + "#latest";
        } catch (RestClientException e) { // model server unreachable or refusing: nothing saved, the question kept in the box
            log.warn("model server failed user={}: {}", user.id(), e.getMessage());
            res.setStatus(503);
            return page(model, user, thread, thread != null ? chat.turns(user, thread.id()) : List.of(), UNAVAILABLE, q);
        }
    }

    @PostMapping("/t/{id}/rename")
    String rename(@PathVariable String id, @RequestParam(defaultValue = "") String title, @AuthenticationPrincipal User user) {
        if (chat.owned(user, id) == null) return "redirect:/";
        chat.rename(user, id, title);
        return "redirect:/t/" + id;
    }

    /**
     * The sidebar's delete icon, after its confirm dialog. Owner only; removed for good. Back to the
     * open thread {@code current}, or to the new-thread page when that was the one deleted.
     */
    @PostMapping("/t/{id}/delete")
    String delete(@PathVariable String id, @RequestParam(defaultValue = "") String current, @AuthenticationPrincipal User user) {
        chat.delete(user, id);
        try { // only ever a thread id, never a free path (no open redirect)
            String back = UUID.fromString(current).toString();
            return back.equals(id) ? "redirect:/" : "redirect:/t/" + back;
        } catch (IllegalArgumentException e) {
            return "redirect:/";
        }
    }
}
