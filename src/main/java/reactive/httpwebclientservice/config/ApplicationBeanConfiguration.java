package reactive.httpwebclientservice.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactive.httpwebclientservice.HttpClientInterface;
import reactive.httpwebclientservice.filters.AuthHeaderFilter;
import reactive.httpwebclientservice.filters.CorrelationHeaderFilter;
import reactive.httpwebclientservice.filters.RetryBackoffFilter;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class ApplicationBeanConfiguration {

    private final DserviceClientProperties props;

    // Constructor injection of our properties holder
    public ApplicationBeanConfiguration(DserviceClientProperties props) {
        this.props = props;
    }

    /** Low-level Reactor Netty client with timeouts. */
    @Bean
    ReactorClientHttpConnector clientHttpConnector() {
        HttpClient http = HttpClient.create()
                // CONNECT timeout (TCP handshake)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)

                // RESPONSE timeout (time from request write until first response byte/headers)
                .responseTimeout(Duration.ofSeconds(100))

                // READ/WRITE inactivity timeouts (no bytes read/written for N seconds)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30))   // read idle
                        .addHandlerLast(new WriteTimeoutHandler(10))  // write idle
                );

        return new ReactorClientHttpConnector(http);
    }

    /**
     * A builder that applies the LoadBalancerExchangeFilterFunction
     * so URIs like http://backend-service are resolved via Eureka.
     */
    /**
     * Load-balanced builder so "http://backend-service" resolves via Eureka.
     * We plug our connector in here—no YAML required.
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder(ReactorClientHttpConnector connector) {

        // Attach the retry filter here so every client built from this builder gets it.
        RetryBackoffFilter retryFilter =
                new RetryBackoffFilter(
                        2,                       // <- 2 retries (total 3 tries)
                        Duration.ofSeconds(1),   // first backoff
                        Duration.ofSeconds(1),   // cap
                        0.0                      // no jitter (deterministic)
                );

        CorrelationHeaderFilter correlationFilter = new CorrelationHeaderFilter();

        AuthHeaderFilter authFilter = new AuthHeaderFilter(props::getAuthToken);

        return WebClient.builder()
                .clientConnector(connector)
                .filters(list -> {
                    // request-mutating filters should run BEFORE retry (so each retry has headers)
                    list.add(correlationFilter);
                    list.add(authFilter);
                    list.add(retryFilter);
                });
    }

    @Bean
    public HttpClientInterface userHttpInterface(WebClient.Builder builder) {
        String host = "http://" + props.getServiceId();  // e.g. http://backend-service
        WebClient webClient = builder
                .baseUrl(host)
                .build();

        return org.springframework.web.service.invoker.HttpServiceProxyFactory
                .builderFor(org.springframework.web.reactive.function.client.support.WebClientAdapter.create(webClient))
                .build()
                .createClient(HttpClientInterface.class);
    }
}
