package com.sip.aidocassist.web.auth;

import java.io.Serializable;
import java.util.List;

/**
 * The logged-in person, reloaded from the database on every request.
 *
 * @param username   what they log in with; lower case
 * @param email      optional contact address (null when not given); not used to log in
 * @param groups     group names: what retrieval filters allowed_group_ids against
 * @param sessionKey fingerprint of password_hash; a password change invalidates older sessions
 */
public record User(String id, String username, String email, String name, boolean isAdmin, List<String> groups, String sessionKey)
        implements Serializable {

    public String initial() {
        return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }
}
