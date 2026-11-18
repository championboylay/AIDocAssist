package com.sip.aidocassist.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Set;

/**
 * Shared pagination for the admin tables (SKB skb/api/pagination.py). Out-of-range pages show the
 * first/last page. prevUrl/nextUrl are null when there is no such page.
 */
public record Page(int page, int pages, int total, int size, int offset, int first, int last,
                   String prevUrl, String nextUrl) {

    static final int SIZE = 25;
    /** One-off messages after a POST: dropped from paging links so they don't reappear. */
    private static final Set<String> FLASH = Set.of("msg", "saved", "deleted", "imported");

    static Page of(int total, int page) {
        return of(total, page, null);
    }

    static Page of(int total, int page, HttpServletRequest req) {
        int pages = Math.max(1, (total + SIZE - 1) / SIZE);
        page = Math.min(Math.max(page, 1), pages);
        int offset = (page - 1) * SIZE;
        return new Page(page, pages, total, SIZE, offset, total == 0 ? 0 : offset + 1, Math.min(offset + SIZE, total),
                req != null && page > 1 ? url(req, page - 1) : null,
                req != null && page < pages ? url(req, page + 1) : null);
    }

    /** This page's URL with page=n, keeping every other query parameter except flash messages. */
    private static String url(HttpServletRequest req, int n) {
        var b = UriComponentsBuilder.fromPath(req.getRequestURI());
        req.getParameterMap().forEach((k, v) -> {
            if (!FLASH.contains(k) && !k.equals("page")) b.queryParam(k, (Object[]) v);
        });
        return b.queryParam("page", n).encode().build().toUriString();
    }
}
