package reactive.httpwebclientservice.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.cloud.loadbalancer.config.LoadBalancerZoneConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientRequestObservationConvention;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactive.httpwebclientservice.HttpClientInterface;
import reactive.httpwebclientservice.exceptions.ApiException;
import reactive.httpwebclientservice.filters.*;
import reactive.httpwebclientservice.utils.Correlation;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

@Configuration
public class ApplicationBeanConfiguration {

    private final DserviceClientProperties props;

    // Constructor injection of our properties holder
    public ApplicationBeanConfiguration(DserviceClientProperties props) {
        this.props = props;
    }

    /** Low-level Reactor Netty client with timeouts. */
    @Bean("defaultConnector")
    ReactorClientHttpConnector clientHttpConnector()
    {
        HttpClient http = HttpClient.create()
                // CONNECT timeout (TCP handshake)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)

                // RESPONSE timeout (time from request write until first response byte/headers)
                .responseTimeout(Duration.ofSeconds(100))

                // READ/WRITE inactivity timeouts (no bytes read/written for N seconds)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30))   // read idle
                        .addHandlerLast(new WriteTimeoutHandler(10))  // write idle
                )
                // (Optional) enable Reactor Netty client I/O metrics (Micrometer-backed) at the socket level:
                .metrics(true, uri -> uri); // <— use this public overload;


        return new ReactorClientHttpConnector(http);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NEW: a more tolerant connector specifically for VERY large uploads.
    //  - no write-idle timeout (or set it very high)
    //  - longer overall response timeout
    // Keep your general connector strict for normal traffic.
    // ──────────────────────────────────────────────────────────────────────────
    @Bean("uploadConnector")
    ReactorClientHttpConnector uploadClientHttpConnector() {
        HttpClient http = HttpClient.create()
                .wiretap(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofHours(24)) // uploads can be LONG
                .doOnConnected(conn -> conn
                        // Disable write-idle timeout for streaming TBs
                        .addHandlerLast(new ReadTimeoutHandler(0))   // 0 = disabled
                        .addHandlerLast(new WriteTimeoutHandler(0))
                )
                .metrics(true, uri -> uri);
        return new ReactorClientHttpConnector(http);
    }


    /** Optional: system-wide meter filters (apply to all registries). */
    @Bean
    MeterFilter commonTags() {
        return MeterFilter.commonTags(Tags.of("app", "HttpWebClientService"));
    }

    /** Optional: configure percentiles/histograms for http client timers. */
    @Bean
    MeterFilter httpClientPercentiles() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if ("http.client.requests".equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentiles(0.5, 0.95, 0.99)
                            .percentilesHistogram(true)
                            .serviceLevelObjectives(
                                    Duration.ofMillis(50).toNanos(),
                                    Duration.ofMillis(100).toNanos(),
                                    Duration.ofMillis(250).toNanos(),
                                    Duration.ofMillis(500).toNanos(),
                                    Duration.ofSeconds(1).toNanos()
                            )
                            .build()
                            .merge(config); // keep existing settings + yours
                }
                return config; // leave others unchanged
            }
        };
    }

    /** A custom observation convention to add low-cardinality tags (e.g., serviceId, apiVersion). */
    @Bean
    ClientRequestObservationConvention webClientObservationConvention()
    {
        return new DefaultClientRequestObservationConvention()
        {
            @Override
            public String getName()
            {
                // keep default meter name "http.client.requests"
                return super.getName();
            }

            @Override
            public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context)
            {
                KeyValues defaults = super.getLowCardinalityKeyValues(context);
                String serviceId = props.getServiceId(); // e.g. "backend-service"
                String apiVersion = context.getRequest() != null
                        ? context.getRequest().headers().getFirst("X-API-Version") : null;
                String corr = context.getRequest() != null
                        ? context.getRequest().headers().getFirst(Correlation.HEADER) : null;

                return defaults.and(
                        KeyValue.of("service.id", serviceId == null ? "unknown" : serviceId),
                        KeyValue.of("api.version", apiVersion == null ? "none" : apiVersion),
                        KeyValue.of("corr.present", corr == null ? "no" : "yes")
                );
            }
        };
    }


    // ──────────────────────────────────────────────────────────────────────────
    // Resilience4j registries with code-based configuration (no YAML)
    // ──────────────────────────────────────────────────────────────────────────
    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)                       // open CB if ≥50% of calls fail
                .slowCallRateThreshold(50f)                      // or ≥50% are "slow"
                .slowCallDurationThreshold(Duration.ofSeconds(2))// calls slower than this are "slow"
                .waitDurationInOpenState(Duration.ofSeconds(10)) // stay OPEN for 10s
                .permittedNumberOfCallsInHalfOpenState(5)        // trial calls when HALF_OPEN
                .minimumNumberOfCalls(10)                        // don’t judge until we have 10 samples
                .slidingWindowSize(50)                           // last 50 calls
                .recordException(t -> {
                    if (t instanceof ApiException api) {
                        Integer s = api.getStatus();
                        // don't trip on 4xx; do trip on 5xx/429
                        return s == null || s >= 500 || s == 429;
                    }
                    return true; // timeouts/connect/etc.
                })  // don’t count 4xx client errors
                .build();
        return CircuitBreakerRegistry.of(cbConfig);
    }

    // Decide which exceptions should open the breaker.
    private boolean recordForCircuitBreaker(Throwable t)
    {
        // Treat 4xx as "business" outcomes: do NOT open CB for them.
        if (t instanceof ApiException api) {
            Integer s = api.getStatus();
            if (s != null && s >= 400 && s < 500) {
                return false; // 4xx don’t trip the breaker
            }
            // 5xx/429 should trip
            return true;
        }
        // Transport/timeouts should trip
        return true;
    }

    @Bean
    BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig bhConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(50)              // cap concurrent in-flight calls
                .maxWaitDuration(Duration.ofMillis(0)) // fail-fast when saturated
                .build();
        return BulkheadRegistry.of(bhConfig);
    }


    @Bean  // <<< OPTIONAL (code-based client zone)
    public LoadBalancerZoneConfig loadBalancerZoneConfig() {
        return new LoadBalancerZoneConfig("eu-west-1a"); // set YOUR client zone here
    }

    /**
     * A builder that applies the LoadBalancerExchangeFilterFunction
     * so URIs like http://backend-service are resolved via Eureka.
     */
    /**
     * Load-balanced builder so "http://backend-service" resolves via Eureka.
     * We plug our connector in here—no YAML required.
     */
    /** Load-balanced builder with: per-client Jackson + observation + your filters. */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder(@Qualifier("defaultConnector") ReactorClientHttpConnector connector,
                                                          Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder,
                                                          ObservationRegistry observationRegistry,
                                                          ClientRequestObservationConvention webClientObservationConvention,
                                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                                          BulkheadRegistry bulkheadRegistry)
    {

        // Per-client, Spring-aware mappers:
        // 1) Build a Spring-aware base mapper (modules & features that Boot would normally register)
        //    IMPORTANT: we do NOT modify the builder bean itself; we just call .build() to get an ObjectMapper.
        ObjectMapper base = jackson2ObjectMapperBuilder.build();

        // 2) Create separate enc/dec mappers so we can keep encode strict and decode lenient
        ObjectMapper encoderMapper = base.copy()
                // encode as ISO-8601 (no timestamps)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                // don't serialize nulls (optional)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // If your upstream is snake_case ONLY for this client, uncomment:
        // .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

        ObjectMapper decoderMapper = base.copy()
                // decode as ISO-8601 and be LENIENT to unknown fields from the upstream
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 3) Support application/json and application/*+json
        List<MediaType> jsonTypes = List.of(
                MediaType.APPLICATION_JSON,
                MediaType.valueOf("application/*+json")
        );

        Jackson2JsonEncoder encoder = new Jackson2JsonEncoder(
                encoderMapper,
                MediaType.APPLICATION_JSON,
                MediaType.parseMediaType("application/*+json") // or new MimeType("application", "*+json") as alternative to MediaType.parseMediaType("application/*+json")
        );

        Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(
                decoderMapper,
                MediaType.APPLICATION_JSON,
                MediaType.parseMediaType("application/*+json") // or new MimeType("application", "*+json") as alternative to MediaType.parseMediaType("application/*+json")
        );





        // Attach the retry filter here so every client built from this builder gets it.
        var retryFilter = new RetryBackoffFilter(2, Duration.ofSeconds(1), Duration.ofSeconds(1), 0.0);
        var errorMapping = new ErrorMappingFilter();
        var correlationFilter = new CorrelationHeaderFilter();
        var authFilter = new AuthHeaderFilter(props::getAuthToken);

        // NEW: CircuitBreaker + Bulkhead filter.
        // Name the CB/Bulkhead after the Eureka serviceId so all calls to that service share the same protections.
        var r4jFilter = new Resilience4jFilter(
                circuitBreakerRegistry,
                bulkheadRegistry,
                req -> props.getServiceId() // e.g. "backend-service"
                // Alternative per-endpoint naming:
                // req -> req.method().name() + " " + req.url().getPath()
        );

        // after you build other filters:
        var loggingFilter = new HttpLoggingFilter(64 * 1024); // log up to 64KB of response body

        var cookieFilter = new reactive.httpwebclientservice.filters.CookieExchangeFilter(stickyCookieStore());


        // Build the LB-aware WebClient.Builder with custom per-client codecs
        return WebClient.builder()
                .clientConnector(connector)
                // <<< this enables WebClient Observations/metrics
                .observationRegistry(observationRegistry)
                .observationConvention(webClientObservationConvention)
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonEncoder(encoder);
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    // (optional) increase if you parse large payloads
                    // ─────────────────────────────────────────────────────────────
                    // NEW: keep codec buffering small so uploads don’t blow memory.
                    // This limits (de)serialization buffers; it does NOT limit streaming bodies.
                    // ─────────────────────────────────────────────────────────────
                    c.defaultCodecs().maxInMemorySize(256 * 1024); // 256 KB
                })
                .filters(list -> {
                    // Put logging fairly outer so you see what's retried, but AFTER request-mutation,
                    // so headers (auth/correlation) appear in logs.
                    list.add(loggingFilter);
                    // We want the CircuitBreaker/Bulkhead to wrap EVERYTHING (including retry + error mapping),
                    // and we want retry to happen INSIDE the breaker (so one logical call is counted once).
                    // So we insert r4jFilter at index 0 (OUTERMOST).
                    list.add(0, r4jFilter);          // <-- NEW (outermost)

                    // OUTERMOST (was) -> now second outermost(now the r4jFilter is OUTERMOST)
                    list.add(errorMapping);

                    // request-mutating filters should run BEFORE retry (so each retry has headers)
                    // mutate requests, then allow retry to re-run with headers
                    list.add(correlationFilter);
                    list.add(authFilter);

                    // ⬇️ add cookies here so mutations above are already applied;
                    // and retries below will include cookies on each attempt
                    list.add(cookieFilter);

                    // INNER
                    list.add(retryFilter);
                });
    }

    @Bean
    public HttpClientInterface userHttpInterface(WebClient.Builder builder) {
        String host = "http://" + props.getServiceId();  // e.g. http://backend-service
        WebClient webClient = builder
                .baseUrl(host)
                .build();

        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(HttpClientInterface.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NEW: A dedicated WebClient for huge uploads, using the “upload” connector.
    // We manually add the LB filter so this client still resolves http://backend-service/.
    // ──────────────────────────────────────────────────────────────────────────
    @Bean
    @Qualifier("uploadWebClient")
    public WebClient uploadWebClient(
            WebClient.Builder lbBuilder, // <-- this is the @LoadBalanced builder
            @Qualifier("uploadConnector") ReactorClientHttpConnector uploadConnector,
            ObservationRegistry observationRegistry,
            ClientRequestObservationConvention webClientObservationConvention,
            DserviceClientProperties props) {

        return lbBuilder
                .clone()
                .clientConnector(uploadConnector)
                .baseUrl("http://" + props.getServiceId()) // or "lb://" + props.getServiceId()
                // DO NOT add the LB filter again here
                .observationRegistry(observationRegistry)
                .observationConvention(webClientObservationConvention)
                .build();
    }


    // NEW: a singleton cookie store (per application)
    @Bean
    public reactive.httpwebclientservice.cookies.StickyCookieStore stickyCookieStore() {
        return new reactive.httpwebclientservice.cookies.StickyCookieStore();
    }


}
