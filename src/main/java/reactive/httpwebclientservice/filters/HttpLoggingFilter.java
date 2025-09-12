package reactive.httpwebclientservice.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class HttpLoggingFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

    private static final Set<String> REDACT = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"
    );

    private final int maxBodyBytes;

    public HttpLoggingFilter(int maxBodyBytes) {
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        boolean enabledForThisRequest = isEnabledByHeaderOrQuery(request);
        boolean doLog = enabledForThisRequest || log.isDebugEnabled();

        if (!doLog) {
            // fast path
            return next.exchange(request);
        }

        Logger target = enabledForThisRequest ? LoggerFactory.getLogger("http.trace") : log;

        // ── Request line + headers (no body) ───────────────────────────────────
        target.info("--> {} {}", request.method(), request.url());
        request.headers().forEach((k, v) -> target.info("    {}: {}", k, redact(k, v)));

        long start = System.nanoTime();

        return next.exchange(request)
                .flatMap(resp -> bufferAndLogResponse(resp, request, target, start))
                .onErrorResume(err -> {
                    target.info("<-- network error for {} {}: {}", request.method(), request.url(), err.toString());
                    return Mono.error(err);
                });
    }

    private Mono<ClientResponse> bufferAndLogResponse(ClientResponse resp,
                                                      ClientRequest req,
                                                      Logger target,
                                                      long startNanos) {
        DataBufferFactory factory = new DefaultDataBufferFactory();
        MediaType ct = resp.headers().contentType().orElse(null);

        boolean textual = ct != null && (
                MediaType.APPLICATION_JSON.isCompatibleWith(ct) ||
                        MediaType.TEXT_PLAIN.isCompatibleWith(ct) ||
                        MediaType.APPLICATION_XML.isCompatibleWith(ct) ||
                        MediaType.TEXT_XML.isCompatibleWith(ct) ||
                        ct.getSubtype().endsWith("+json") ||
                        ct.getSubtype().endsWith("+xml")
        );

        Mono<byte[]> bodyBytesMono = textual
                ? resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                : Mono.just(new byte[0]);

        return bodyBytesMono.flatMap(bytes -> {
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            target.info("<-- {} {} ({} ms)", resp.statusCode().value(), req.url(), tookMs);
            resp.headers().asHttpHeaders().forEach((k, v) -> target.info("    {}: {}", k, String.join(",", v)));

            if (textual && bytes.length > 0) {
                String preview = preview(bytes, maxBodyBytes);
                target.info("⤷ body ({} bytes{})\n{}", bytes.length,
                        bytes.length > maxBodyBytes ? ", truncated" : "", preview);
            } else if (!textual) {
                resp.headers().contentLength().ifPresentOrElse(
                        len -> target.info("⤷ [binary body {} bytes; not logged]", len),
                        ()  -> target.info("⤷ [binary body; length unknown; not logged]")
                );
            }

            Flux<DataBuffer> bodyFlux = (textual && bytes.length > 0)
                    ? Flux.just(factory.wrap(bytes))
                    : resp.bodyToFlux(DataBuffer.class); // pass-through if we didn’t buffer

            return Mono.just(resp.mutate().body(bodyFlux).build());
        });
    }

    private static List<String> redact(String key, List<String> vals) {
        return REDACT.contains(key.toLowerCase()) ? List.of("***") : vals;
    }

    private static String preview(byte[] bytes, int limit) {
        int n = Math.min(bytes.length, limit);
        String s = new String(bytes, 0, n, StandardCharsets.UTF_8);
        return bytes.length > limit ? s + "\n…(truncated)" : s;
    }

    private static boolean isEnabledByHeaderOrQuery(ClientRequest req) {
        // Header first
        String hv = req.headers().getFirst("X-Debug-Log");
        if (hv != null && hv.equalsIgnoreCase("true")) return true;

        // Query param fallback
        URI uri = req.url();
        String q = uri.getQuery();
        if (q == null || q.isBlank()) return false;
        // crude parse: X-Debug-Log=true anywhere
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase("X-Debug-Log")
                    && kv[1].equalsIgnoreCase("true")) {
                return true;
            }
        }
        return false;
    }
}
