package reactive.httpwebclientservice.config;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Decorator that prefers instances whose metadata[{metadataKey}] equals request header "X-Version".
 * If no matches, it falls back to the original list.
 */
final class VersionMetadataFilteringSupplier implements ServiceInstanceListSupplier {

    private final ServiceInstanceListSupplier delegate;
    private final String metadataKey;

    VersionMetadataFilteringSupplier(ServiceInstanceListSupplier delegate, String metadataKey) {
        this.delegate = delegate;
        this.metadataKey = metadataKey;
    }

    @Override
    public String getServiceId() {
        return delegate.getServiceId();
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get();
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        String desired = null;

        Object ctx = request.getContext();
        if (ctx instanceof RequestDataContext rdc) {
            RequestData data = rdc.getClientRequest();
            if (data != null && data.getHeaders() != null) {
                desired = data.getHeaders().getFirst("X-Version"); // <- header drives desired metadata value
            }
        }

        final String want = desired;

        return delegate.get(request).map(list -> {
            if (want == null || want.isBlank()) return list;
            List<ServiceInstance> filtered = list.stream()
                    .filter(si -> want.equals(si.getMetadata().get(metadataKey)))
                    .collect(Collectors.toList());
            return filtered.isEmpty() ? list : filtered; // fallback when no match
        });
    }
}
