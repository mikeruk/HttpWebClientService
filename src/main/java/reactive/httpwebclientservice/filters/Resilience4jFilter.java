package reactive.httpwebclientservice.filters;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Wraps the WebClient exchange Publisher with Bulkhead and CircuitBreaker operators.
 * Name resolution is pluggable to support per-service or per-endpoint breakers.
 */
public class Resilience4jFilter implements ExchangeFilterFunction {

    private final CircuitBreakerRegistry cbRegistry;
    private final BulkheadRegistry bhRegistry;
    private final Function<ClientRequest, String> nameResolver;

    public Resilience4jFilter(CircuitBreakerRegistry cbRegistry,
                              BulkheadRegistry bhRegistry,
                              Function<ClientRequest, String> nameResolver) {
        this.cbRegistry = Objects.requireNonNull(cbRegistry);
        this.bhRegistry = Objects.requireNonNull(bhRegistry);
        this.nameResolver = Objects.requireNonNull(nameResolver);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String name = nameResolver.apply(request);
        CircuitBreaker cb = cbRegistry.circuitBreaker(name);
        Bulkhead bh = bhRegistry.bulkhead(name);

        // Ensure Bulkhead limits are applied, then CB records the overall result.
        // Because we’ll place this filter OUTERMOST, it will also “see” errors produced by inner filters (e.g., ErrorMapping).
        return next.exchange(request)
                .transformDeferred(BulkheadOperator.of(bh))
                .transformDeferred(CircuitBreakerOperator.of(cb));
    }
}
