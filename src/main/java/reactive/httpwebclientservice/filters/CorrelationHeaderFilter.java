package reactive.httpwebclientservice.filters;


import org.springframework.web.reactive.function.client.*;
import reactive.httpwebclientservice.utils.Correlation;
import reactor.core.publisher.Mono;

public class CorrelationHeaderFilter implements ExchangeFilterFunction {

    private static final String LOGGED = HttpLoggingFilter.class.getName()+".logged";


    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(ctxView -> {
            String corrId = ctxView.hasKey(Correlation.CTX_KEY)
                    ? ctxView.get(Correlation.CTX_KEY)
                    : Correlation.newId(); // fallback if controller forgot to seed

            System.out.println("CorrelationFilter setting header: " + corrId); // Debug

            ClientRequest mutated = ClientRequest.from(request)
                    .headers(h -> h.set(Correlation.HEADER, corrId))
                    .build();

            return next.exchange(mutated);
        });
    }
}