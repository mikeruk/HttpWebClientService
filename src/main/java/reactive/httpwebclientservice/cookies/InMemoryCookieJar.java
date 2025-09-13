package reactive.httpwebclientservice.cookies;

import org.springframework.http.ResponseCookie;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Very simple in-memory cookie jar that stores cookies per host+path.
 * - Domain rule: if Set-Cookie has Domain=X, we do suffix match; else host-only.
 * - Path rule: requestPath startsWith(cookiePath, default "/").
 * - Expiry: Max-Age=0 removes; positive Max-Age sets expiresAt; negative => session cookie.
 */
public final class InMemoryCookieJar {

    private static final class StoredCookie {
        final String name;
        volatile String value;
        final String domain;   // nullable
        final String path;     // never null; default "/"
        final boolean secure;
        volatile Instant expiresAt; // nullable => session cookie

        StoredCookie(ResponseCookie rc, Instant now) {
            this.name = rc.getName();
            this.value = rc.getValue();
            this.domain = emptyToNull(rc.getDomain());
            this.path = (rc.getPath() == null || rc.getPath().isBlank()) ? "/" : rc.getPath();
            this.secure = rc.isSecure();
            if (rc.getMaxAge() != null) {
                long seconds = rc.getMaxAge().getSeconds();
                if (seconds == 0) {
                    this.expiresAt = Instant.EPOCH; // use as deletion marker
                } else if (seconds > 0) {
                    this.expiresAt = now.plusSeconds(seconds);
                } else {
                    this.expiresAt = null; // session cookie
                }
            } else {
                this.expiresAt = null; // session cookie
            }
        }

        boolean expired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }

        private static String emptyToNull(String s) {
            return (s == null || s.isBlank()) ? null : s;
        }
    }

    // host -> cookies (we keep all and match by path/expiry each request)
    private final Map<String, List<StoredCookie>> store = new ConcurrentHashMap<>();

    public void saveFrom(URI origin, Collection<ResponseCookie> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return;
        String host = origin.getHost();
        Instant now = Instant.now();
        store.compute(host, (h, list) -> {
            if (list == null) list = new ArrayList<>();
            for (ResponseCookie rc : setCookies) {
                StoredCookie sc = new StoredCookie(rc, now);
                // deletion?
                if (sc.expiresAt != null && sc.expiresAt.equals(Instant.EPOCH)) {
                    // remove by name+path(+domain match)
                    list.removeIf(old -> namesMatch(old, sc) && domainEq(old, sc) && pathEq(old, sc));
                    continue;
                }
                // upsert by name+path(+domain)
                boolean updated = false;
                for (StoredCookie old : list) {
                    if (namesMatch(old, sc) && domainEq(old, sc) && pathEq(old, sc)) {
                        old.value = sc.value;
                        old.expiresAt = sc.expiresAt;
                        updated = true;
                        break;
                    }
                }
                if (!updated) list.add(sc);
            }
            // cleanup expired
            list.removeIf(c -> c.expired(now));
            return list;
        });
    }

    public Map<String, String> cookiesFor(URI requestUri, boolean requireSecure) {
        String host = requestUri.getHost();
        String path = requestUri.getPath();
        if (path == null || path.isBlank()) path = "/";
        boolean isHttps = "https".equalsIgnoreCase(requestUri.getScheme());

        Instant now = Instant.now();
        List<StoredCookie> list = store.get(host);
        if (list == null) return Collections.emptyMap();

        Map<String, String> out = new LinkedHashMap<>();
        for (StoredCookie c : list) {
            if (c.expired(now)) continue;
            // domain
            if (c.domain != null) {
                // suffix match (host ends with domain & not a partial label)
                if (!domainMatches(host, c.domain)) continue;
            } // else host-only, already keyed by host

            // path
            if (!path.startsWith(c.path)) continue;

            // secure
            if (c.secure && !(isHttps)) continue;
            if (requireSecure && !c.secure) continue;

            out.put(c.name, c.value);
        }
        return out;
    }

    private static boolean namesMatch(StoredCookie a, StoredCookie b) {
        return Objects.equals(a.name, b.name);
    }

    private static boolean domainEq(StoredCookie a, StoredCookie b) {
        return Objects.equals(a.domain, b.domain);
    }

    private static boolean pathEq(StoredCookie a, StoredCookie b) {
        return Objects.equals(a.path, b.path);
    }

    private static boolean domainMatches(String host, String domain) {
        String h = host.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        if (h.equals(d)) return true;
        return h.endsWith("." + d);
    }
}
