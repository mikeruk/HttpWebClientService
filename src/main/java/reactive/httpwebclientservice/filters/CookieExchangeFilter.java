package reactive.httpwebclientservice.filters;

import org.springframework.web.reactive.function.client.*;
import reactive.httpwebclientservice.cookies.StickyCookieStore;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Adds cookies before the call; captures Set-Cookie after the call. */
public final class CookieExchangeFilter implements ExchangeFilterFunction {

    private final StickyCookieStore store;

    public CookieExchangeFilter(StickyCookieStore store) {
        this.store = store;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        URI uri = request.url();

        // 1) add Cookie header for this URI
        ClientRequest mutated = ClientRequest.from(request)
                .headers(h -> store.addCookieHeader(uri, h))
                .build();

        // 2) capture Set-Cookie from the response
        return next.exchange(mutated)
                .doOnNext(resp -> store.rememberFromResponse(uri, resp.cookies()));
    }
}
