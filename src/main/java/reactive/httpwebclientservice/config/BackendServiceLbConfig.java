package reactive.httpwebclientservice.config;

import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerClientRequestTransformer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.cloud.client.ServiceInstance;

import java.util.List;

/**
 * Per-service LoadBalancer chain applied only for "backend-service" via @LoadBalancerClient.
 * Features:
 *  - Discovery-backed list (Eureka)
 *  - Zone preference (prefer instances in same zone as this client)
 *  - Hint-based routing (built-in: X-SC-LB-Hint header vs instance metadata "hint")
 *  - Weighted strategy (instance metadata key "weight", default 1)
 *  - Caching (perf)
 *  - OPTIONAL: extra filtering by custom metadata key "version" using header "X-Version"
 */
//@Configuration this annotation is not necessary - does not seem to make any difference. It still works without it.
public class BackendServiceLbConfig
{

    /** Build the supplier chain for THIS service id. */
    @Bean
    ServiceInstanceListSupplier backendServiceInstanceSupplier(ConfigurableApplicationContext context) {
        // Built-in chain builder
        ServiceInstanceListSupplier base =
                ServiceInstanceListSupplier.builder()
                        .withDiscoveryClient()        // pull from Eureka
                        .withCaching()                // cache list for perf
                        .withZonePreference()         // prefer same-zone instances
                        .withHints()                  // enable X-SC-LB-Hint / metadata: hint
                        .withWeighted()               // use metadata: weight
                        //.withCaching()                // cache list for perf
                        .build(context);

        // OPTIONAL: add our custom metadata filter by key "version" driven by header "X-Version"
        return new VersionMetadataFilteringSupplier(base, "version");

    }

    /**
     * Optional helper: stamp the chosen instance id into the outgoing request headers
     * so you can SEE which instance actually served the call (helpful in logs).
     */
    @Bean
    public LoadBalancerClientRequestTransformer addChosenInstanceHeader() {
        return (request, instance) -> ClientRequest.from(request)
                .header("X-InstanceId", safeInstanceId(instance))
                .build();
    }

    private static String safeInstanceId(ServiceInstance si) {
        try { return si.getInstanceId(); }
        catch (Exception ignored) { return si.getHost() + ":" + si.getPort(); }
    }
}
