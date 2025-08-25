package reactive.httpwebclientservice.filters;

import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.*;
import reactive.httpwebclientservice.exceptions.*;
import reactive.httpwebclientservice.utils.Correlation;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class ErrorMappingFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // Wrap the rest of the chain
        return next.exchange(request)
                // Map non-2xx responses to exceptions (read body once, release connection)
                .flatMap(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return Mono.just(resp);
                    }
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.deferContextual(ctx -> {
                                String corr = ctx.hasKey(Correlation.CTX_KEY) ? ctx.get(Correlation.CTX_KEY) : null;
                                RuntimeException ex = mapStatusToException(resp.statusCode(), body, request, resp.headers().asHttpHeaders(), corr);
                                return Mono.error(ex);
                            }));
                })
                // Also map transport-level errors and our retry marker after retries
                .onErrorMap(throwable -> mapTransportOrRetryErrors(throwable, request));
    }

    private RuntimeException mapStatusToException(HttpStatusCode status, String body, ClientRequest req, HttpHeaders headers, String corrId) {
        int sc = status.value();
        String method = req.method().name();
        String url = req.url().toString();

        // nice-to-have: Retry-After signal for 429
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);

        return switch (sc) {
            case 400 -> new BadRequestException("Bad request", method, url, corrId, body);
            case 401 -> new UnauthorizedException("Unauthorized", method, url, corrId, body);
            case 403 -> new ForbiddenException("Forbidden", method, url, corrId, body);
            case 404 -> new NotFoundException("Not found", method, url, corrId, body);
            case 409 -> new ConflictException("Conflict", method, url, corrId, body);
            case 429 -> new TooManyRequestsException("Too Many Requests", method, url, corrId, body, retryAfter);
            case 503 -> new ServiceUnavailableException("Service unavailable", method, url, corrId, body, null);
            case 504 -> new GatewayTimeoutException("Upstream timed out", method, url, corrId, body, null);
            default -> new ApiException("Upstream error " + sc, sc, method, url, corrId, body, null) {};
        };
    }

    private RuntimeException mapTransportOrRetryErrors(Throwable t, ClientRequest req) {
        String method = req.method().name();
        String url = req.url().toString();

        // Errors thrown by WebClient itself on 4xx/5xx (if any slipped through)
        if (t instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            return mapStatusToException(wcre.getStatusCode(), body, req, wcre.getHeaders(), null);
        }

        // Our retry filter synthesizes this for 5xx/429 (after draining) → after retries, map to 503/429 equivalents
        if (t instanceof RetryBackoffFilter.RetryableStatusException rse) {
            int sc = rse.getStatus().value();
            return switch (sc) {
                case 429 -> new TooManyRequestsException("Too Many Requests (after retries)", method, url, null, null, null);
                case 503, 500, 502, 504 -> new ServiceUnavailableException("Service unavailable (after retries)", method, url, null, null, t);
                default -> new ApiException("Retryable upstream error " + sc, sc, method, url, null, null, t) {};
            };
        }

        // Transport problems (connect refused, DNS, read timeout, Reactor timeout)
        if (t instanceof WebClientRequestException wcre) {
            Throwable cause = wcre.getCause();
            if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException) {
                return new UpstreamTimeoutException("I/O timeout", method, url, null, t);
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof SocketException) {
                return new UpstreamConnectException("Connection problem", method, url, null, t);
            }
        }
        if (t instanceof TimeoutException) {
            return new UpstreamTimeoutException("Upstream timed out", method, url, null, t);
        }

        // otherwise pass it through
        return (t instanceof RuntimeException re) ? re : new RuntimeException(t);
    }
}
