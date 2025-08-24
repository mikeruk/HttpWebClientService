package reactive.httpwebclientservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}

