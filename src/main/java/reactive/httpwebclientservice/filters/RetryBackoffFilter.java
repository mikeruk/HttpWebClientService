package reactive.httpwebclientservice.filters;

import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class RetryBackoffFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(RetryBackoffFilter.class);

    private final int maxAttempts;
    private final Duration minBackoff;
    private final Duration maxBackoff;
    private final double jitter;

    private static final Set<Integer> RETRYABLE_STATUS =
            Set.of(500, 502, 503, 504, 429);

    public RetryBackoffFilter(int maxAttempts, Duration minBackoff, Duration maxBackoff, double jitter) {
        this.maxAttempts = maxAttempts;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
        this.jitter = jitter;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return next.exchange(request)
                .flatMap(response -> {
                    // Convert retryable HTTP statuses into an error to trigger retryWhen.
                    if (shouldRetryForStatus(request, response)) {
                        // Important: drain/release the body so the connection can be reused.
                        return response.releaseBody()
                                .then(Mono.error(new RetryableStatusException(
                                        response.statusCode(), request.method(), request.url().toString())));
                    }
                    return Mono.just(response);
                })
                .retryWhen(buildRetrySpec(request));
    }

    private Retry buildRetrySpec(ClientRequest request) {
        final boolean idempotent = isIdempotent(request);

        // If not idempotent, do not retry (maxAttempts=1 effectively).
        int attempts = idempotent ? maxAttempts : 1;

        RetryBackoffSpec spec = Retry.backoff(attempts, minBackoff)
                .maxBackoff(maxBackoff)
                .jitter(jitter)
                .filter(this::isRetryableError)
                .onRetryExhaustedThrow((signal, failure) -> failure.failure());

        return spec.doBeforeRetry(rs ->
                log.warn("Retrying {} {} (attempt #{}, cause: {})",
                        request.method(), request.url(), rs.totalRetries() + 1, rs.failure().toString()));
    }

    private boolean shouldRetryForStatus(ClientRequest req, ClientResponse resp) {
        return isIdempotent(req) && RETRYABLE_STATUS.contains(resp.statusCode().value());
    }

    private boolean isIdempotent(ClientRequest req) {
        HttpMethod m = req.method();
        // RFC says DELETE and PUT are idempotent; keep them here. POST allowed only if caller set Idempotency-Key.
        if (m == HttpMethod.GET || m == HttpMethod.HEAD || m == HttpMethod.OPTIONS
                || m == HttpMethod.DELETE || m == HttpMethod.PUT) {
            return true;
        }
        if (m == HttpMethod.POST && req.headers().containsKey("Idempotency-Key")) {
            return true;
        }
        return false;
    }

    private boolean isRetryableError(Throwable t) {
        // Transport-level / transient failures
        if (t instanceof WebClientRequestException wcre) {
            Throwable cause = wcre.getCause();
            return cause instanceof ConnectException
                    || cause instanceof ReadTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof SocketException
                    || cause instanceof IOException;
        }
        // Our synthesized error for 5xx/429
        if (t instanceof RetryableStatusException) return true;

        // Bare TimeoutException (e.g., from .timeout())
        return t instanceof TimeoutException;
    }

    /** Marker exception to represent retryable HTTP status codes. */
    static class RetryableStatusException extends RuntimeException {
        private final HttpStatusCode status;
        private final HttpMethod method;
        private final String url;

        RetryableStatusException(HttpStatusCode status, HttpMethod method, String url) {
            super("Retryable HTTP status " + status + " for " + method + " " + url);
            this.status = status; this.method = method; this.url = url;
        }

        public HttpStatusCode getStatus() { return status; }
        public HttpMethod getMethod() { return method; }
        public String getUrl() { return url; }
    }
}
