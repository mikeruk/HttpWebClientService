package reactive.httpwebclientservice.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactive.httpwebclientservice.cookies.InMemoryCookieJar;

import java.net.URI;
import java.util.*;

public class CookieFilter implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(CookieFilter.class);
    private final InMemoryCookieJar jar;
    private final boolean logCookies; // toggle debug logs

    public CookieFilter(InMemoryCookieJar jar, boolean logCookies) {
        this.jar = jar;
        this.logCookies = logCookies;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // 1) OUTBOUND: inject cookies for this URI
        URI uri = request.url();
        Map<String, String> toSend = jar.cookiesFor(uri, false);
        ClientRequest mutated = (toSend.isEmpty())
                ? request
                : ClientRequest.from(request).cookies(c -> {
            toSend.forEach(c::add); // adds as Cookie: name=value ...
        }).build();

        if (logCookies && !toSend.isEmpty()) {
            log.debug("CookieFilter → sending cookies to {}: {}", uri, toSend.keySet());
        }

        // 2) INBOUND: capture Set-Cookie from response and store
        return next.exchange(mutated).doOnSuccess(resp -> {
            MultiValueMap<String, ResponseCookie> cookies = resp.cookies(); // parsed cookies
            if (cookies != null && !cookies.isEmpty()) {
                List<ResponseCookie> all = new ArrayList<>();
                cookies.values().forEach(all::addAll);
                jar.saveFrom(uri, all);
                if (logCookies) {
                    log.debug("CookieFilter ← stored cookies from {}: {}", uri, all.stream().map(ResponseCookie::getName).toList());
                }
            } else {
                // Some servers only send raw header; still covered by resp.cookies()
                List<String> raw = resp.headers().asHttpHeaders().get(HttpHeaders.SET_COOKIE);
                if (raw != null && !raw.isEmpty() && logCookies) {
                    log.debug("CookieFilter ← received raw Set-Cookie from {}: {}", uri, raw);
                }
            }
        });
    }
}
