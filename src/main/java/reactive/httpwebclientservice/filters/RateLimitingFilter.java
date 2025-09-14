package reactive.httpwebclientservice.filters;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Outermost filter that throttles outgoing requests using a Resilience4j RateLimiter.
 * Choose the limiter key (global / per-service / per-route) via keySelector.
 */
public class RateLimitingFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final RateLimiterRegistry registry;
    private final Function<ClientRequest, String> keySelector;

    public RateLimitingFilter(RateLimiterRegistry registry,
                              Function<ClientRequest, String> keySelector) {
        this.registry = Objects.requireNonNull(registry);
        this.keySelector = Objects.requireNonNull(keySelector);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String key = keySelector.apply(request);
        RateLimiter rl = registry.rateLimiter(key); // creates or retrieves

        // Use defer so permission is attempted BEFORE the real exchange starts.
        return Mono.defer(() -> next.exchange(request))
                .transformDeferred(RateLimiterOperator.of(rl))
                .doOnSubscribe(s -> log.debug("Rate limiting key='{}' {} {}", key, request.method(), request.url()));
    }
}
