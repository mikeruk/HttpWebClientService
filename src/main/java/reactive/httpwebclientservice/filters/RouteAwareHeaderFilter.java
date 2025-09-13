package reactive.httpwebclientservice.filters;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.Function;

public class RouteAwareHeaderFilter implements ExchangeFilterFunction {

    /**
     * Optional: allow overriding the internal auth value via Reactor Context key "internalAuth".
     * If not found, fall back to this defaultSupplier.
     */
    private final Function<ClientRequest, String> defaultInternalAuthSupplier;

    public static final String CTX_INTERNAL_AUTH = "internalAuth";           // <- context key
    public static final String HDR_INTERNAL_AUTH = "X-Internal-Auth";        // <- header we add
    public static final String HDR_PREVIEW_FLAG  = "X-Use-Preview";          // <- if present, bump API version
    public static final String HDR_API_VERSION   = "X-API-Version";

    public RouteAwareHeaderFilter(Function<ClientRequest, String> defaultInternalAuthSupplier) {
        this.defaultInternalAuthSupplier = defaultInternalAuthSupplier;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        final URI uri = request.url();
        final String path = uri.getPath(); // e.g. "/api/v1/user-with-data/123"

        return Mono.deferContextual(ctxView -> {
            ClientRequest.Builder builder = ClientRequest.from(request);

            // 1) If caller sent X-Use-Preview, force an API version header (example of header→header logic).
            if (request.headers().containsKey(HDR_PREVIEW_FLAG)) {
                builder.headers(h -> {
                    if (!StringUtils.hasText(h.getFirst(HDR_API_VERSION))) {
                        h.set(HDR_API_VERSION, "preview");
                    }
                });
            }

            // 2) Path-based rule: for /user-with-data/** add internal auth; for /user/** ensure it’s absent.
            if (path.startsWith("/api/v1/user-with-data/")) {
                // Resolve internal auth value: prefer Reactor Context, else fallback supplier
                String internalAuth = ctxView.hasKey(CTX_INTERNAL_AUTH)
                        ? ctxView.get(CTX_INTERNAL_AUTH)
                        : defaultInternalAuthSupplier.apply(request);

                if (internalAuth != null && !internalAuth.isBlank()) {
                    builder.headers(h -> h.set(HDR_INTERNAL_AUTH, internalAuth));
                }
            } else if (path.startsWith("/api/v1/user/")) {
                // Make sure we don't leak the internal header on the simple user endpoint
                builder.headers(h -> h.remove(HDR_INTERNAL_AUTH));
            }

            // 3) (Optional) Normalize/guard other headers here
            builder.headers(h -> ensureNoEmptyValues(h));

            return next.exchange(builder.build());
        });
    }

    private static void ensureNoEmptyValues(HttpHeaders h) {
        // tiny safety: drop empty preview flag
        if (h.containsKey(HDR_PREVIEW_FLAG) && (h.getFirst(HDR_PREVIEW_FLAG) == null || h.getFirst(HDR_PREVIEW_FLAG).isBlank())) {
            h.remove(HDR_PREVIEW_FLAG);
        }
    }
}
