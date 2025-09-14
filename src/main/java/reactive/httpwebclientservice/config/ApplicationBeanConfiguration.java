package reactive.httpwebclientservice.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
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
import reactive.httpwebclientservice.cookies.InMemoryCookieJar;
import reactive.httpwebclientservice.exceptions.ApiException;
import reactive.httpwebclientservice.filters.*;
import reactive.httpwebclientservice.utils.Correlation;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;

import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;


@Configuration
public class ApplicationBeanConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApplicationBeanConfiguration.class);

    private final DserviceClientProperties props;


    // Constructor injection of our properties holder
    public ApplicationBeanConfiguration(DserviceClientProperties props) {
        this.props = props;
    }

    /* ── NEW: build a ConnectionProvider (pool) driven by properties ──────── */
    private ConnectionProvider connectionProvider(String name) {
        var p = props.getHttp().getPool();
        return ConnectionProvider.builder(name)
                .metrics(true) // <-- NEW: expose reactor.netty.connection.provider.* metrics
                .maxConnections(p.getMaxConnections())
                .pendingAcquireTimeout(p.getPendingAcquireTimeout())
                .maxIdleTime(p.getMaxIdle())
                .maxLifeTime(p.getMaxLife())
                .evictInBackground(p.getEvictInBackground())
                .lifo() // prefer recently-used
                .build();
    }

    /* ── NEW: publish the two providers as beans so we can inject them ─────────────── */
    @Bean("defaultConnectionProvider") // <-- NEW
    ConnectionProvider defaultConnectionProvider() {
        return connectionProvider("default-http-pool");
    }

    @Bean("uploadConnectionProvider") // <-- NEW
    ConnectionProvider uploadConnectionProvider() {
        return connectionProvider("upload-http-pool");
    }

    /* ── NEW: apply protocol + TLS/H2 settings + TCP keepalive + logging ──── */
    private HttpClient applyHttpVersionAndKeepAlive(HttpClient http, String connectorName) {
        var httpOpts = props.getHttp();
        var proto = httpOpts.getProtocol();

        // TCP keep-alive (kernel-level probing; separate from HTTP keep-alive)
        http = http.option(ChannelOption.SO_KEEPALIVE, httpOpts.isTcpKeepAlive());

        switch (proto) {
            case H1 -> {
                http = http.protocol(HttpProtocol.HTTP11);
                log.info("[{}] Forcing HTTP/1.1", connectorName);
            }
            case H2 -> {
                // TLS + ALPN with HTTP/2
                http = http
                        .secure(ssl -> {
                            try {
                                ssl.sslContext(SslContextBuilder.forClient().build());
                            } catch (SSLException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .protocol(HttpProtocol.H2, HttpProtocol.HTTP11);
                log.info("[{}] Forcing HTTP/2 (TLS/ALPN)", connectorName);
            }
            case H2C -> {
                // Cleartext HTTP/2
                http = http.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11);
                log.info("[{}] Forcing HTTP/2 cleartext (h2c)", connectorName);
            }
            case AUTO -> {
                // Let Reactor/ALPN negotiate (HTTP/1.1 will be used unless TLS+ALPN+server H2)
                http = http.protocol(HttpProtocol.HTTP11, HttpProtocol.H2);
                log.info("[{}] Protocol AUTO (negotiate H2 when possible, else HTTP/1.1)", connectorName);
            }
        }

        // Log what got negotiated at runtime (TLS only)
        http = http.doOnConnected(conn -> {
            var ch = conn.channel();
            var ssl = ch.pipeline().get(io.netty.handler.ssl.SslHandler.class);
            if (ssl != null) {
                String ap = ssl.applicationProtocol();
                if (ap != null && !ap.isBlank()) {
                    log.info("[{}] Negotiated application protocol: {}", connectorName, ap);
                }
            } else {
                log.debug("[{}] Cleartext connection (no TLS/ALPN)", connectorName);
            }
        });

        return http;
    }

    /** Low-level Reactor Netty client with timeouts. */
    @Bean("defaultConnector")
    ReactorClientHttpConnector clientHttpConnector(
            @Qualifier("defaultConnectionProvider") ConnectionProvider provider
    )
    {
        HttpClient http = HttpClient.create(provider)
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

        /* NEW: apply protocol, TLS/H2 and TCP keepalive */
        http = applyHttpVersionAndKeepAlive(http, "defaultConnector");

        return new ReactorClientHttpConnector(http);
    }

    // NEW: a more tolerant connector specifically for VERY large uploads.
    @Bean("uploadConnector")
    ReactorClientHttpConnector uploadClientHttpConnector(
            @Qualifier("uploadConnectionProvider") ConnectionProvider provider
    ) {
        HttpClient http = HttpClient.create(provider)
                .wiretap(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofHours(24))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(0))
                        .addHandlerLast(new WriteTimeoutHandler(0)))
                .metrics(true, uri -> uri);

        /* NEW: apply protocol, TLS/H2 and TCP keepalive */
        http = applyHttpVersionAndKeepAlive(http, "uploadConnector");

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


    // NEW (Task 18): a singleton, in-memory cookie jar
    @Bean
    public InMemoryCookieJar inMemoryCookieJar() {
        return new InMemoryCookieJar();
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
                                                          BulkheadRegistry bulkheadRegistry,
                                                          InMemoryCookieJar cookieJar,
                                                          RateLimiterRegistry rateLimiterRegistry)
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

        // add this near your other filter instantiations
        var routeAwareFilter = new RouteAwareHeaderFilter(req -> "secret-default-token"); // or pull from props

        // NEW (Task 18): cookie filter (set logCookies=true if you want to see its debug lines)
        var cookieJarFilter      = new CookieFilter(cookieJar, true);

        // ───────────────────────────────────────────────────────────────
        // NEW: Rate limiter filter.
        // Key strategy options:
        //  - GLOBAL single limiter: req -> "global"
        //  - Per serviceId (good default): req -> props.getServiceId()
        //  - Per route: req -> req.method().name() + " " + req.url().getPath()
        //  - Per tenant from header: req -> "tenant:" + Optional.ofNullable(req.headers().getFirst("X-Tenant")).orElse("anon")
        // Pick one:
        // ───────────────────────────────────────────────────────────────
        var rateLimitFilter = new RateLimitingFilter(
                rateLimiterRegistry,
                req -> props.getServiceId() // ← per-backend-service limiter (recommended here)
        );




        // Build the LB-aware WebClient.Builder with custom per-client codecs
        return WebClient.builder()
                .clientConnector(connector)
                // <<< this enables WebClient Observations/metrics
                .observationRegistry(observationRegistry)
                .observationConvention(webClientObservationConvention)
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonEncoder(encoder);
                    c.defaultCodecs().jackson2JsonDecoder(decoder);


                    // XML via JAXB (works on all Spring 6 / Boot 3 versions)
                    c.defaultCodecs().jaxb2Encoder(new org.springframework.http.codec.xml.Jaxb2XmlEncoder());
                    c.defaultCodecs().jaxb2Decoder(new org.springframework.http.codec.xml.Jaxb2XmlDecoder());

                    // Protobuf
                    c.customCodecs().encoder(new org.springframework.http.codec.protobuf.ProtobufEncoder());
                    c.customCodecs().decoder(new org.springframework.http.codec.protobuf.ProtobufDecoder());

                    // YAML via Jackson YAML
                    var yamlMapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
                    var yamlTypes = new org.springframework.util.MimeType[] {
                            org.springframework.util.MimeType.valueOf("application/x-yaml"),
                            org.springframework.util.MimeType.valueOf("application/yaml"),
                            org.springframework.util.MimeType.valueOf("text/yaml"),
                            org.springframework.util.MimeType.valueOf("application/*+yaml")
                    };
                    c.customCodecs().encoder(new org.springframework.http.codec.json.Jackson2JsonEncoder(yamlMapper, yamlTypes));
                    c.customCodecs().decoder(new org.springframework.http.codec.json.Jackson2JsonDecoder(yamlMapper, yamlTypes));






                    // (optional) increase if you parse large payloads
                    // ─────────────────────────────────────────────────────────────
                    // NEW: keep codec buffering small so uploads don’t blow memory.
                    // This limits (de)serialization buffers; it does NOT limit streaming bodies.
                    // ─────────────────────────────────────────────────────────────
                    c.defaultCodecs().maxInMemorySize(256 * 1024); // 256 KB
                })
                .filters(list -> {
                    // ───────────────── ORDER MATTERS ─────────────────
                    // Put RATE LIMITING OUTERMOST → it gates everything (retry, CB, etc.)
                    list.add(0, rateLimitFilter);  // <-- NEW (outermost)
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
                    list.add(cookieJarFilter); // <-- NEW (Task 18)
                    // ⬇️ add cookies here so mutations above are already applied;
                    // and retries below will include cookies on each attempt
                    list.add(cookieFilter);
                    list.add(routeAwareFilter);   // <— NEW: conditional header logic lives with other mutators
                    list.add(loggingFilter); // SECOND time added same logging filter, to ensure any mutated requests are alo logged
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


    // ───────────────────────────────────────────────────────────────
    // NEW: Central RateLimiter registry (code-based config, no YAML).
    //   Example policy: ≤10 QPS with burst 20, and wait up to 100 ms
    //   to acquire a permit (otherwise fail fast).
    //   Tune these numbers as you need, or externalize to properties.
    // ───────────────────────────────────────────────────────────────
    @Bean
    RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitRefreshPeriod(java.time.Duration.ofSeconds(1))  // "per second"
                .limitForPeriod(10)                                   // 10 permits added each second
                .timeoutDuration(java.time.Duration.ofMillis(100))    // wait up to 100ms for a token
                .build();
        return RateLimiterRegistry.of(cfg);
    }

    @Bean
    MeterBinder rateLimiterMetricsBinder(RateLimiterRegistry rlRegistry) {
        // This publishes:
        //  - resilience4j.ratelimiter.calls
        //  - resilience4j.ratelimiter.available.permissions
        //  - resilience4j.ratelimiter.waiting.threads
        return TaggedRateLimiterMetrics.ofRateLimiterRegistry(rlRegistry);
    }

    // Eagerly create a named limiter so meters can be bound immediately
    @Bean
    RateLimiter backendServiceRateLimiter(RateLimiterRegistry registry, DserviceClientProperties props) {
        return registry.rateLimiter(props.getServiceId()); // e.g. "backend-service"
    }

}
