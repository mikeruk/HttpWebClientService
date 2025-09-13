package reactive.httpwebclientservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "dservice")
public class DserviceClientProperties
{
    /**
     * Binds to the 'user.base-url' property in application.yml.
     */
    private String baseUrl; // takes its value from base-url: http://localhost:8081 in application.yml

    private String serviceId; // takes its value from service-id: backend-service in application.yml

    private boolean isUseEureka; // takes its value from use-eureka: true in application.yml

    private String authToken;   // takes its value from authToken: "superSecretToken" in application.yml

    /* ── NEW: per-client HTTP options (protocol + pool/keepalive) ─────────── */
    private HttpOptions http = new HttpOptions();


    // (Optional) If you later want more settings, you can add them here:
    // private int timeoutMs;
    // private String apiKey;
    // …with matching getters & setters.



    public String getBaseUrl() {
        return baseUrl;
    }
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public boolean isUseEureka() {
        return isUseEureka;
    }

    public void setUseEureka(boolean useEureka) {
        isUseEureka = useEureka;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public HttpOptions getHttp() { return http; }
    public void setHttp(HttpOptions http) { this.http = http; }

    /* NEW */
    public static class HttpOptions {
        private Protocol protocol = Protocol.AUTO;      // AUTO | H2 | H2C | H1
        private boolean tcpKeepAlive = true;            // TCP-level keepalive
        private Pool pool = new Pool();

        public Protocol getProtocol() { return protocol; }
        public void setProtocol(Protocol protocol) { this.protocol = protocol; }

        public boolean isTcpKeepAlive() { return tcpKeepAlive; }
        public void setTcpKeepAlive(boolean tcpKeepAlive) { this.tcpKeepAlive = tcpKeepAlive; }

        public Pool getPool() { return pool; }
        public void setPool(Pool pool) { this.pool = pool; }
    }

    /* NEW */
    public enum Protocol { AUTO, H2, H2C, H1 }

    /* NEW */
    public static class Pool {
        private int maxConnections = 200;
        private Duration pendingAcquireTimeout = Duration.ofSeconds(45);
        private Duration maxIdle = Duration.ofSeconds(30);
        private Duration maxLife = Duration.ofMinutes(5);
        private Duration evictInBackground = Duration.ofSeconds(60);

        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

        public Duration getPendingAcquireTimeout() { return pendingAcquireTimeout; }
        public void setPendingAcquireTimeout(Duration pendingAcquireTimeout) { this.pendingAcquireTimeout = pendingAcquireTimeout; }

        public Duration getMaxIdle() { return maxIdle; }
        public void setMaxIdle(Duration maxIdle) { this.maxIdle = maxIdle; }

        public Duration getMaxLife() { return maxLife; }
        public void setMaxLife(Duration maxLife) { this.maxLife = maxLife; }

        public Duration getEvictInBackground() { return evictInBackground; }
        public void setEvictInBackground(Duration evictInBackground) { this.evictInBackground = evictInBackground; }
    }
}

