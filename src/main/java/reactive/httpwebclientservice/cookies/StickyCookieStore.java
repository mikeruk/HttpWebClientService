package reactive.httpwebclientservice.cookies;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Very small in-memory cookie jar, keyed by "service key" (URI host, e.g. backend-service).
 * Handles Path, Secure, Max-Age (incl. delete on zero). Expires cookies in-memory.
 */
public final class StickyCookieStore {

    private static final class StoredCookie {
        final String name;
        volatile String value;
        final String path;   // default "/"
        final boolean secure;
        volatile Instant expiresAt; // null => session cookie (kept until app restarts)

        StoredCookie(String name, String value, String path, boolean secure, Instant expiresAt) {
            this.name = name;
            this.value = value;
            this.path = (path == null || path.isBlank()) ? "/" : path;
            this.secure = secure;
            this.expiresAt = expiresAt;
        }

        boolean expired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    // serviceKey -> (cookieName -> StoredCookie)
    private final Map<String, Map<String, StoredCookie>> jar = new ConcurrentHashMap<>();

    /** Compute a service key. We scope by serviceId host (e.g. "backend-service"). */
    private String keyFor(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** Capture Set-Cookie from a response for the service identified by the request URI. */
    public void rememberFromResponse(URI requestUri, MultiValueMap<String, ResponseCookie> respCookies) {
        if (respCookies == null || respCookies.isEmpty()) return;
        String key = keyFor(requestUri);
        Map<String, StoredCookie> bag = jar.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Instant now = Instant.now();

        respCookies.forEach((name, list) -> {
            for (ResponseCookie rc : list) {
                // Delete if Max-Age = 0
                Duration maxAge = rc.getMaxAge();
                if (maxAge != null && maxAge.isZero()) {
                    bag.remove(name);
                    continue;
                }
                Instant expires = null;
                if (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) {
                    expires = now.plus(maxAge);
                }
                bag.put(name, new StoredCookie(name, rc.getValue(), rc.getPath(), rc.isSecure(), expires));
            }
        });
    }

    /** Add a Cookie header for this request if we have matching, non-expired cookies. */
    public void addCookieHeader(URI requestUri, HttpHeaders headers) {
        String key = keyFor(requestUri);
        Map<String, StoredCookie> bag = jar.get(key);
        if (bag == null || bag.isEmpty()) return;

        String path = requestUri.getPath() == null ? "/" : requestUri.getPath();
        boolean https = "https".equalsIgnoreCase(requestUri.getScheme());
        Instant now = Instant.now();

        // Filter applicable cookies
        List<String> pairs = new ArrayList<>();
        bag.values().removeIf(c -> c.expired(now)); // purge expired
        for (StoredCookie c : bag.values()) {
            if (c.secure && !https) continue;              // respect Secure
            if (!path.startsWith(c.path)) continue;        // respect Path
            pairs.add(c.name + "=" + c.value);
        }
        if (pairs.isEmpty()) return;

        // Merge with any existing Cookie header(s)
        List<String> existing = headers.get(HttpHeaders.COOKIE);
        if (existing != null && !existing.isEmpty()) {
            pairs.add(String.join("; ", existing));
        }
        headers.set(HttpHeaders.COOKIE, String.join("; ", pairs));
    }

    /** For debugging only. */
    @Override public String toString() { return "StickyCookieStore{keys=" + jar.keySet() + "}"; }
}

