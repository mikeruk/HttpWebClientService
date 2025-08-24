package reactive.httpwebclientservice.filters;

import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

public class AuthHeaderFilter implements ExchangeFilterFunction {

    private final Supplier<String> tokenSupplier;

    public AuthHeaderFilter(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String token = tokenSupplier.get(); // can call OAuth provider here if you later swap suppliers
        ClientRequest mutated = ClientRequest.from(request)
                .headers(h -> {
                    if (token != null && !token.isBlank()) {
                        h.setBearerAuth(token);
                    }
                })
                .build();
        return next.exchange(mutated);
    }
}
