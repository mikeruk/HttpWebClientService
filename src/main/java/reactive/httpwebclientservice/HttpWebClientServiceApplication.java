package reactive.httpwebclientservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import reactive.httpwebclientservice.config.BackendServiceLbConfig;


@LoadBalancerClient(
        name = "backend-service",
        configuration = BackendServiceLbConfig.class   // <- per-service LB config lives here
)
@SpringBootApplication
public class HttpWebClientServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpWebClientServiceApplication.class, args);
    }

}
