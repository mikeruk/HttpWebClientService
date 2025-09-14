

                                    My custom WebClient -  Docs

    https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-webclient

    https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html

    https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html    


NB!!! Relates to task: 23. Custom Connection Pool Settings
When implementing Actuator to and /actuator/metrics/ to get metrics from the App, please be 
advised that these /actuator/metrics/ FOR the 'Reactor Netty' will never be available for query right 
after you have started the application, even if you have applied all the settings correctly!
To make them available under their standard paths like:
http://localhost:8080/actuator/metrics/reactor.netty.connection.provider.active.connections
YOU MUST FIRST make the WebClient send a very basic HTTP connection to the backend service.
You must make any calls using the WebClient yet (i.e., hit the proxy endpoints), because these metrics
are emitted lazily—only after actual connection usage.
SECOND, if you still dont see any ACTUATOR METRICs, then please be advised that
Reactor Netty publishes them to Micrometer’s global registry, while Spring Boot Actuator exposes its 
own registry. They’re not bridged by default.
QUICK fixes:
Option A — easiest (Boot ≥ 3.2):
```yaml
management:
  metrics:
    use-global-registry: true

```
Option B — code bridge: create this bean
```java
@Bean
MeterRegistryCustomizer<MeterRegistry> addBootRegistryToGlobal() {
  return registry -> Metrics.addRegistry(registry);
}
```

NB!!! By design the WebClient is fully asynchronous (none-blocking) client!

NB!!! WebClient is from reactive stack. When Uploading files towards backend-service, which is not built
on reactive stack - the back-end service controllers must be adapted.
OR best approach would be to create backend-service with reactive stack too! - Maybe as separate 
module!

NB!!!
Making an individual call asynchronous (“imperative style”)
If you ever need to call the client inside a blocking component (e.g. a scheduled @Component 
or a Spring MVC servlet), do not do this:
```java
UserDTO dto = webClient.get().uri("/user/{id}", id)
.retrieve().bodyToMono(UserDTO.class)
.block();                 // ❌ thread is parked
```

NB!!! A simple comparison between Spring Boot with Virtual Threads vs. WebFlux?
    https://medium.com/@umeshcapg/spring-boot-with-virtual-threads-vs-webflux-which-is-better-73f474251fd9


Instead, delegate off the main thread pool:
```java
Mono<UserDTO> deferred = webClient.get().uri("/user/{id}", id)
.retrieve().bodyToMono(UserDTO.class);

deferred.publishOn(Schedulers.boundedElastic())
.subscribe(dto -> log.info("Result = {}", dto));
```
But in a pure WebFlux application you rarely need this pattern—controller returns should be 
reactive already.

Before continue, I want to say few words about WebFlux, just have some context and knowledge about it.
What “WebFlux” actually is — and why you keep meeting it
| Aspect                | Spring MVC (classic stack)                                    | **Spring WebFlux** (reactive stack)                                                                        |
| --------------------- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **Introduced**        | 2003 (Spring 2)                                               | 2017 (Spring 5)                                                                                            |
| **Programming model** | *Blocking, imperative*                                        | *Non-blocking, reactive*                                                                                   |
| **Thread model**      | One servlet-container thread stays busy for the whole request | Small, event-loop threads handle thousands of requests; user code runs on Reactor schedulers               |
| **Core types**        | `HttpServletRequest/Response`, `ModelAndView`                 | `Mono<T>` (0–1 value) and `Flux<T>` (0–∞ values) from **Project Reactor**                                  |
| **Default transport** | Servlet API on Tomcat/Jetty/Undertow                          | **Reactor Netty** (event-loop), or non-blocking servlet 3.1                                                |
| **When to pick**      | CRUD apps, blocking DBs, simple workloads                     | High concurrency, streaming, SSE/WebSocket, back-pressure, large uploads ([symflower.com][1], [dev.to][2]) |

[1]: https://symflower.com/en/company/blog/2024/spring-mvc-spring-webflux/?utm_source=chatgpt.com "Spring Web MVC vs Spring WebFlux: differences between ... - Symflower"
[2]: https://dev.to/jottyjohn/spring-mvc-vs-spring-webflux-choosing-the-right-framework-for-your-project-4cd2?utm_source=chatgpt.com "Spring MVC vs. Spring WebFlux: Choosing the Right Framework for ..."

What exactly is WebFlux? 
- A reactive web framework that sits next to (not on top of) Spring MVC.
- Uses non-blocking I/O
- Built on Project Reactor → that’s where Mono and Flux come from.

How WebFlux relates to WebClient?
- WebClient lives in the spring-webflux module. Even if you stick to Spring MVC for your controllers, importing WebClient drags in WebFlux’s
reactive primitives.
- The client always issues requests asynchronously; whether you block on the Mono is your choice (as you saw with .block() vs. returning Mono).
- When you build a pure WebFlux application (only spring-boot-starter-webflux on the class-path), Spring Boot auto-configures an embedded Reactor Netty server, so every incoming HTTP request is handled on the same reactive event-loop model the client uses.

    Choosing between MVC and WebFlux?
Stick with Spring MVC if:
- Your app mostly does blocking operations (JPA, JDBC) and < 2 000 concurrent users.
- Team is new to reactive programming and the extra throughput isn’t worth the complexity.

Choose WebFlux if:
- You need streaming, Server-Sent Events, WebSockets, or must handle 10 k+ concurrent requests.
- You mix with other reactive pieces (R2DBC, reactive Redis, Reactor Kafka) or run on resource-restricted pods.
- Your public API promises very low latency (sub-100 ms) under burst load.


NEXT, here I present you a minimal required setup you need to implement WebClient inside a 
HTTP Client and make calls to backend-service using Eureka for service name resolution;

Here are the files:

build.gradle:

```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'reactive'
version = '0.0.1-SNAPSHOT'
sourceCompatibility = '17'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation group: 'jakarta.validation', name: 'jakarta.validation-api', version: '3.1.1'
    implementation platform("org.springframework.cloud:spring-cloud-dependencies:2024.0.1")
    implementation "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client"

    //for implementing 1. Adjusting Timeouts (Connect, Read, Write)
    implementation 'org.springframework.boot:spring-boot-starter-webflux'

    // for implementing Custom Load-Balancing rules for  8. Custom Load-Balancing Rules (Zone/Affinity, Metadata-based Routing)
    // https://mvnrepository.com/artifact/org.springframework.cloud/spring-cloud-starter-loadbalancer
    implementation group: 'org.springframework.cloud', name: 'spring-cloud-starter-loadbalancer', version: '4.3.0'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

application.yml:

```yml
spring:
  application:
    name: "HttpWebClientService"
eureka:
  client:
    service-url:
      defaultZone: "http://localhost:8761/eureka"
    register-with-eureka: true     # register as HttpWebClientService
    fetch-registry: true           # fetch registry to enable load-balancing




# ────────────────────────────────────────────────────────────────────────────
# This is your custom namespace. You could name it anything (e.g. "client:",
# "remote-api:", "user-service:", etc.). Here we chose "dservice:" to remind us
# that this is the base URL for our “dservice” backend, but we don't user it anymore.
# Now we added also service-id:backend-service , because we will use Eureka to resolve by service-id, which is 'backend-service'.
dservice:
  #base-url: http://localhost:8081 - hard coded URL is no longer needed, because we will use Eureka and resolve by service-id:
  service-id: "backend-service"
  use-eureka: true

#  dservice: is not special to Spring. It’s simply a grouping key (a map) under which you put your settings.
#  Under dservice:, you create a property called base-url whose value is http://localhost:8081.
#  Later, your code will read user.base-url instead of having builder.baseUrl("http://localhost:8081") hard-coded.

```

Next:
```java
@SpringBootApplication
public class HttpWebClientServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpWebClientServiceApplication.class, args);
    }

}
```


```java
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


```

That WebClient is fully asynchronous (none-blocking) client!

```java
package reactive.httpwebclientservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactive.httpwebclientservice.HttpClientInterface;

@Configuration
public class ApplicationBeanConfiguration {

    private final DserviceClientProperties props;

    // Constructor injection of our properties holder
    public ApplicationBeanConfiguration(DserviceClientProperties props) {
        this.props = props;
    }


    /**
     * A builder that applies the LoadBalancerExchangeFilterFunction
     * so URIs like http://backend-service are resolved via Eureka.
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public HttpClientInterface userHttpInterface(WebClient.Builder builder)
    {
        // Always point at service-ID: the load balancer will resolve it.
        // That WebClient is fully asynchronous (none-blocking) client!
        String host = "http://" + props.getServiceId();
        WebClient webClient = builder
                .baseUrl(host)
                .build();

        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(HttpClientInterface.class);
    }
}

```

Controller – stay reactive end-to-end.
Returning the Mono lets Spring WebFlux handle subscription and write the JSON to the socket when the response arrives.
Nothing blocks the servlet (now Netty) thread.
```java
package reactive.httpwebclientservice.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactive.httpwebclientservice.DTOs.db.UserDTO;
import reactive.httpwebclientservice.DTOs.db.UserDbDTO;
import reactive.httpwebclientservice.HttpClientInterface;
import reactor.core.publisher.Mono;

import java.util.Map;


@RestController
@RequestMapping("/proxy")       // <— choose any prefix you like
public class UserProxyController {

    private final HttpClientInterface users;

    public UserProxyController(HttpClientInterface users) {
        this.users = users;
    }

    @PostMapping("/create-new-user")
    public Mono<ResponseEntity<UserDbDTO>> create(@RequestBody UserDbDTO body)
    {


        //Always return the Mono (or Flux).
        //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
        //that 'return value' is what ties that channel to your reactive pipeline.
        return users.create(body);                 // non-blocking
    }

    @GetMapping("/user/{id}")
    public Mono<ResponseEntity<UserDTO>> getById(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Version", required = false) String ver) {


        //Always return the Mono (or Flux).
        //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
        //that 'return value' is what ties that channel to your reactive pipeline.
        return users.getById(id, ver);             // non-blocking
    }

    @GetMapping("/user-with-data/{id}")
    public Mono<ResponseEntity<UserDbDTO>> getWithData(
            @PathVariable Long id,
            @RequestHeader Map<String, String> headers) {


        //Always return the Mono (or Flux).
        //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
        //that 'return value' is what ties that channel to your reactive pipeline.
        return users.getWithData(id, headers);     // non-blocking
    }

    @GetMapping("/proxy-http-status/{code}")
    public Mono<ResponseEntity<String>> getCustomErrorResponse(@PathVariable int code) {


        //Always return the Mono (or Flux).
        //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
        //that 'return value' is what ties that channel to your reactive pipeline.
        return users.proxyGetCustomErrorResponse(code);    // non-blocking
    }

    @GetMapping("/ping")
    public Mono<Map<String, String>> getPing() {


        //Always return the Mono (or Flux).
        //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
        //that 'return value' is what ties that channel to your reactive pipeline.
        return users.ping();                       // non-blocking
    }


}
```

In the code below - Return values are now Mono<…>.
No more ResponseEntity; WebClient will decode the body directly into your DTO.
Declarative HTTP interface – switch return types to reactive
```java
package reactive.httpwebclientservice;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import reactive.httpwebclientservice.DTOs.db.UserDTO;
import reactive.httpwebclientservice.DTOs.db.UserDbDTO;

import org.springframework.core.io.Resource;
import reactor.core.publisher.Mono;

import java.util.Map;

@HttpExchange(url = "/api/v1", accept = MediaType.APPLICATION_JSON_VALUE)
public interface HttpClientInterface {

    @PostExchange("/create-new-user")
    Mono<ResponseEntity<UserDbDTO>> create(@RequestBody UserDbDTO body);

    @GetExchange("/user/{id}")
    Mono<ResponseEntity<UserDTO>> getById(
            @PathVariable Long id,
            @RequestHeader(name = "X-API-Version", required = false) String apiVersion);

    @GetExchange("/user-with-data/{id}")
    Mono<ResponseEntity<UserDbDTO>> getWithData(
            @PathVariable Long id,
            @RequestHeader Map<String, String> dynamicHeaders);

    @GetExchange("/http-status/{code}")
    Mono<ResponseEntity<String>> proxyGetCustomErrorResponse(@PathVariable int code);

    @HttpExchange(method = "GET", url = "/ping", accept = MediaType.APPLICATION_JSON_VALUE)
    Mono<Map<String, String>> ping();

    @PostExchange(
            url         = "/upload",
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            accept      = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    Mono<ResponseEntity<Void>> uploadFile(@RequestBody Resource file);
}
```


                        General explanations:

1 How WebClient works under the hood
| Fact                                                                                                                                                                              | What it means for you                                                                                                                                        |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Reactive, event-loop based** – every request returns a `Mono` or `Flux`. No thread is parked waiting for the response. ([medium.com][1])                                        | As soon as you **avoid calling `.block()`**, all I/O happens off the main JVM threads and scales with a tiny, fixed thread-pool.                             |
| **Lazy** – nothing is sent until the publisher is *subscribed*.                                                                                                                   | In a Spring controller, returning the `Mono` itself counts as a subscribe; the WebFlux runtime does it for you.                                              |
| **Back-pressure aware** – downstream can slow the producer.                                                                                                                       | Large file uploads/downloads won’t OOM as long as you stream (`Flux<DataBuffer>`).                                                                           |
| **Synchronous is only a convenience wrapper** – if the method’s return type is not reactive, `HttpServiceProxyFactory` will call `.block(timeout)` for you. ([docs.spring.io][2]) | Declaring `ResponseEntity<UserDTO>` in your interface forces blocking; declare `Mono<UserDTO>` (or `Mono<ResponseEntity<UserDTO>>`) to keep it non-blocking. |

[1]: https://medium.com/%40ia_taras/efficient-api-communication-with-spring-webclient-5c5dea18a6ba?utm_source=chatgpt.com "Efficient API Communication with Spring WebClient - Medium"
[2]: https://docs.spring.io/spring-framework/docs/6.0.0-M4/javadoc-api/org/springframework/web/service/invoker/HttpServiceProxyFactory.Builder.html?utm_source=chatgpt.com "HttpServiceProxyFactory.Builder (Spring Framework 6.0.0-M4 API)"


“Async vs Sync” in WebClient — how to flip the switch safely
Short answer:
You don’t flip a flag on the WebClient itself.
The same WebClient can be used either non-blocking (reactive) or blocking (synchronous); it 
all depends on what you do with the Mono/Flux it returns.

1 Two ways to turn a reactive call into a blocking one:
| Style                   | Code sketch                                                                                                                                                                         | When it is safe                                                                                                                                                               |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Ad-hoc `.block()`**   | `java Mono<UserDTO> mono = client.get().uri("/user/{id}", id)                      .retrieve().bodyToMono(UserDTO.class); UserDTO dto = mono.block();  // ← blocks current thread ` | Only if you are **already on a worker thread** (e.g. a Spring Batch step, a `@Scheduled` task, or a plain main method). Never call `.block()` on a WebFlux event-loop thread. |
| **Shift, *then* block** | `java UserDTO dto = mono .publishOn(Schedulers.boundedElastic())  // hop to worker pool .block();                                          // safe to wait `                        | Use this inside a **WebFlux controller or filter** where the executing thread is a Reactor-Netty event loop. The hop prevents event-loop starvation. ([docs.spring.io][1])    |

[1]: https://docs.spring.io/projectreactor/reactor-core/docs/3.6.x/api/reactor/core/scheduler/Schedulers.html?utm_source=chatgpt.com "Schedulers (reactor-core 3.7.6)"

FIRST way is this - simply add '.block();' to the call, so:
return mono.publishOn(Schedulers.boundedElastic()).block();
, here a more detailed code example:
```java
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserClient client;   // returns Mono<UserDTO>

    public UserController(UserClient client) { this.client = client; }

    @GetMapping("/{id}")
    public Object getUser(@PathVariable Long id,
                          @RequestParam(defaultValue = "async") String mode) {

        Mono<UserDTO> mono = client.getById(id);

        if ("sync".equalsIgnoreCase(mode)) {
            // hop off the event loop, then block
            return mono.publishOn(Schedulers.boundedElastic()).block();
        }
        // async: just hand the publisher back to WebFlux
        return mono;
    }
}
```

SECOND way,  A cleaner pattern: two typed clients:

Instead of sprinkling .block() all over, expose two Spring beans backed by the same WebClient.


```java
/**
 * Reactive client – never blocks
 */
@Primary
@Bean
UserClient reactiveClient(WebClient wc) {
    return HttpServiceProxyFactory
            .builderFor(WebClientAdapter.create(wc))
            .build()
            .createClient(UserClient.class);          // Mono-returning methods
}

/**
 * Blocking façade – proxy does the blocking for you
 * (setBlockTimeout appeared in Spring 6; defaults to 5 s) :contentReference[oaicite:1]{index=1}
 */
@Bean
BlockingUserClient blockingClient(WebClient wc) {
    return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(wc))
            .blockTimeout(Duration.ofSeconds(10))    // optional
            .build()
            .createClient(BlockingUserClient.class); // DTO-returning methods
}

```
Your controller can now inject whichever flavour it needs:

```java
@RestController
@RequiredArgsConstructor
class MixedModeController {
    private final UserClient reactive;
    private final BlockingUserClient blocking;

    @GetMapping("/reactive/{id}")      public Mono<UserDTO>  r(@PathVariable Long id){ return reactive.getById(id); }
    @GetMapping("/blocking/{id}")      public UserDTO        b(@PathVariable Long id){ return blocking.getById(id); }
}

```

In my project I will try to implement the FIRST way, because it seems to be more simple.

But first, lets explain the asynchronous WorkFlow - how the asynchronous call happen.

Imagine this:
We have a client (browser, Postman, or another Microservice) sending Request to our Controller Endpoint URL method.
Our Controller method receives the request and starts to execute its code logic, as usual.
At some point the Controller method will reach our webclient call: 
```java
@GetMapping("/user/{id}")
public Mono<ResponseEntity<UserDTO>> getById(
        @PathVariable Long id,
        @RequestHeader(value = "X-API-Version", required = false) String ver) {
    Mono<ResponseEntity<UserDbDTO>> resultSomeMonoObject =
            webClient.post()
                    .uri("/users")
                    .bodyValue(body)
                    .retrieve()         // ← builds the I/O pipeline, **doesn’t run it**
                    .bodyToMono(UserDbDTO.class)
                    .map(ResponseEntity::ok);


    //Always return the Mono (or Flux).
    //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
    //that 'return value' is what ties that channel to your reactive pipeline.
    return resultSomeMonoObject;             // non-blocking
}
```
Then, inside the Controller's method body - the webclient 'call' will be called, so: result = webClient.post()...
That Mono object 'Mono<ResponseEntity<UserDbDTO>> resultSomeMonoObject' - is the “publisher”. 
It contains information about the (itself) - the method call.
The publisher publishes that Mono object to the WebFlux framework. In that way, the WebFlux framework creates a
subscription between the Mono object and TCP 'Netty channel' of the initial incoming request which arrived at the 
controller endpoint in the beginning.
The actual subscription happens when the controller method return the 'return resultSomeMonoObject;' That's why
you must always return the Mono (or Flux) object.
The Netty TCP connection currently stays open and waits for the WebFlux framework to process the request (to send the 
request to its destination and to receive a response).
After the WebFlux framework receives the response, the Controller Endpoint method will have finished its code execution, 
but its TCP Netty channel will still be open and waiting. The WebFlux framework already knows that the Mono object is 
subscribed to a particular Netty channel, which is still open and still waiting, and will know that
this is the TCP channel where the received response should be sent to. 
Postman (or a browser or another client) is just another TCP client waiting on that same TCP Netty connection. 
Once WebFlux finally writes the response to the Netty channel, the client (e.g. Postman) receives it. 

To summarize the above explanation in just few words: the @GetMapping controller method finishes early, but the 
Netty channel, which has brought the request from the network to that @GetMapping controller stays open because 
the framework holds on to the subscription (the subscription between Publisher - the Mono object, and the Subscriber -
the WebFlux framework). When the publisher terminates, WebFlux commits (or aborts) the HTTP response on that same TCP
channel. Whether the caller is a browser, Postman, or another service is irrelevant: they all hold the TCP connection open 
and get the response once the publisher terminates.
The controller method “finishes” in the sense that it has returned its Mono - 'return resultSomeMonoObject;' , but 
under the covers WebFlux holds onto the TCP Netty channel, which initially has brought the incoming GetRequest to the
@GetMapping controller, keeps that TCP Netty channel open for your asynchronous pipeline, and only commits the HTTP 
response once your Mono terminates.

How to handle error responses?
First, you don't need to use try-catch blocks to wrap around the webclient call.
A Mono can emit exactly one of three terminal signals:
- onNext (success), 
- onComplete (empty) or 
- onError (failure)
The errors are handled inside the Mono pipeline, so:
```java
return webClient.get()
                .uri("/user/{id}", id)
                .retrieve()
                // 1 turn selected statuses into *your* exception type
                .onStatus(HttpStatus::is4xxClientError,
                          r -> r.bodyToMono(String.class)
                                .flatMap(msg -> Mono.error(
                                       new UserNotFoundException(msg))))
                // 2 produce the body for successes
                .bodyToMono(UserDTO.class)
                // 3 map it to a ResponseEntity
                .map(ResponseEntity::ok)
                // 4 decide what to do with *any* error
                .onErrorResume(UserNotFoundException.class,
                               e -> Mono.just(ResponseEntity.notFound().build()));
```
  When to use try-catch blocks on webclient calls? What about blocking - switching from asynchronous to synchronous?
Calling .block() or .blockOptional() executes the pipeline imperatively and gives you back the result 
(or throws the exception). That lets you use a regular try-catch, but:
If you call it from inside a reactive thread you can dead-lock the event loop.
In a @RestController that already returns Mono/Flux, prefer to stay non-blocking and let Spring do the subscription.
In other words - always be careful - if you are inside some method, which is meant to execute itself asynchronously, but 
inside that method you define 'calls', which are synchronous (blocking) - they would block the execution of the whole
method - will wait until they finish, and only then the code below can be further read and executed.

A simple clarification: Can I return null value on reactive method, like so:
```java
@GetMapping("/user/{id}")
public Mono<ResponseEntity<UserDTO>> getById() {
    Mono<ResponseEntity<UserDbDTO>> result =
            webClient.post()
                    .uri("/users")
                    .bodyValue(body)
                    .retrieve()         // ← builds the I/O pipeline, **doesn’t run it**
                    .bodyToMono(UserDbDTO.class)
                    .map(ResponseEntity::ok);    
    
    return null;  // instead 'return result'        
}
```
Answer is: if you return null Spring‐WebFlux has nothing to subscribe to, and you’ll crash with a NullPointerException 
(or a 500) before any HTTP response ever goes out.
Spring needs your return value to be a Publisher.
When you return Mono<ResponseEntity<…>>, Spring immediately does roughly:
```java
Publisher<?> publisher = invokeControllerMethod();  
subscriber = new HandlerResultSubscriber(exchange);  
publisher.subscribe(subscriber);
```
If invokeControllerMethod() returns 'null', Spring can’t subscribe, and you get a hard error.
WebFlux doesn’t independently “remember” your pipeline.
The only thing WebFlux can subscribe to is the object you return. If you return null, there is no pipeline for 
WebFlux to hook into, so nothing ever fires your WebClient call or sends back a response.

Always return the Mono (or Flux).
That return value is the contract you make with WebFlux. It’s what ties the incoming request’s Netty channel to 
your reactive pipeline and — only after that Mono emits (onNext/onError) — causes Spring to write the HTTP response.
So keep your method like this:
```java
@GetMapping("/user/{id}")
public Mono<ResponseEntity<UserDTO>> getById() {
    Mono<ResponseEntity<UserDbDTO>> result =
            webClient.post()
                    .uri("/users")
                    .bodyValue(body)
                    .retrieve()         // ← builds the I/O pipeline, **doesn’t run it**
                    .bodyToMono(UserDbDTO.class)
                    .map(ResponseEntity::ok);    
    
    return result;       
}
```
Returning anything else (especially null) will break the reactive dispatch and you won’t get a response back to 
Postman, the browser, or any client at all.




               START of experiment to customize the WebClient -  1. Adjusting Timeouts (Connect, Read, Write)

1. Adjusting Timeouts (Connect, Read, Write)
   By default, the underlying HTTP client (used by Spring’s RestClient) may have timeouts that are too long (or too short) for your
   environment. If you expect slow endpoints or want to fail fast, customizing connect/read/write timeouts is critical.

        NB !!!! Few important remarks:
First thing to notice! Setting a timeout on the  Reactor Netty client level like so:  .addHandlerLast(new ReadTimeoutHandler(10)),
really has effect and applies this timeout. I can set up it up to 30 sec and it still throws my exceptions:
```text
HttpWebClientService] [nio-8080-exec-3] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] threw exception
io.netty.handler.timeout.ReadTimeoutException: null
```
, BUT the moment I increase the value to 31 or even 100 - it is not applied any more. Some underlying layer kicks in
with its default timeout and throws its own exception and the postman client also receives the response:
```text
[HttpWebClientService] [nio-8080-exec-3] .w.s.m.s.DefaultHandlerExceptionResolver : Resolved [org.springframework.web.context.request.async.AsyncRequestTimeoutException]
```
What causes this? Answer is:
It’s the server-side async request timeout from Spring MVC/Tomcat, which kicks in and aborts the request with:
```text
Resolved [org.springframework.web.context.request.async.AsyncRequestTimeoutException]
```
The project is built with dependency of 'implementation 'org.springframework.boot:spring-boot-starter-web'.
So if you need a pure WebClient and Reactor Netty server, consider removing that spring-boot-starter-web
dependency.

Next, here is how to implement Timeouts (Connect, Read, Write)

First, 1) Set global timeouts on the WebClient (via Reactor Netty), 
Update the ApplicationBeanConfiguration class to be so:

```java
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
        return WebClient.builder()
                .clientConnector(connector);
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

```
What each timeout does
- Connect timeout: fail fast if the TCP connection can’t be established in time.
- Response timeout: fail if the server doesn’t send the first response byte/headers within the window.
- Read/Write timeouts: fail if there’s no IO activity on the socket for the given seconds (good for stalled/slow connections and large uploads).
These are transport-level timeouts—set once on the connector and they apply to every call made through this WebClient.


2) (Optional) Per-call overrides right in the controller
Sometimes you want a stricter timeout only for a specific endpoint. 
Then add the timeout on the method level for the specific request:
```java
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;

@GetMapping("/user-fast/{id}")
    public Mono<ResponseEntity<UserDTO>> getByIdFast(@PathVariable Long id) {
        return users.getById(id, null)
                .timeout(Duration.ofSeconds(2)) // stricter for this call only
                .onErrorResume(TimeoutException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build()));
    }
```
3) Tips you’ll appreciate later
Response vs. read timeout: If the server sends headers promptly but then stalls mid-body, responseTimeout won’t help—read timeout will.
Timeout + retry: If you add retries, make sure the timeout < server-side processing time, or you’ll start retry storms.
Keep Eureka working: The @LoadBalanced WebClient.Builder keeps the LoadBalancer filter even when you swap connectors or clone() the builder.

That’s it—pure builder-based configuration



               END of experiment to customize the WebClient -  1. Adjusting Timeouts (Connect, Read, Write)





               START of experiment to customize the WebClient -  2. Adding a Retry/Backoff Strategy


Great next step. For retries/backoff you’ve got two solid options:
1. “Pure Reactor” (lightweight, zero extra deps) — use reactor.util.retry.Retry with a WebClient filter.
2. Resilience4j via Spring Cloud CircuitBreaker — more features (metrics, bulkhead, CB), a bit heavier.

Since we want to learn about tweaking the builder, here’s the 1. “Pure Reactor” way described.

1) Add a retry/backoff filter (code-only)
   Create this class: public class RetryBackoffFilter implements ExchangeFilterFunction
```java
package reactive.httpwebclientservice.filters;

import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class RetryBackoffFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(RetryBackoffFilter.class);

    private final int maxAttempts;
    private final Duration minBackoff;
    private final Duration maxBackoff;
    private final double jitter;

    private static final Set<Integer> RETRYABLE_STATUS =
            Set.of(500, 502, 503, 504, 429);

    public RetryBackoffFilter(int maxAttempts, Duration minBackoff, Duration maxBackoff, double jitter) {
        this.maxAttempts = maxAttempts;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
        this.jitter = jitter;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return next.exchange(request)
                .flatMap(response -> {
                    // Convert retryable HTTP statuses into an error to trigger retryWhen.
                    if (shouldRetryForStatus(request, response)) {
                        // Important: drain/release the body so the connection can be reused.
                        return response.releaseBody()
                                .then(Mono.error(new RetryableStatusException(
                                        response.statusCode(), request.method(), request.url().toString())));
                    }
                    return Mono.just(response);
                })
                .retryWhen(buildRetrySpec(request));
    }

    private Retry buildRetrySpec(ClientRequest request) {
        final boolean idempotent = isIdempotent(request);

        // If not idempotent, do not retry (maxAttempts=1 effectively).
        int attempts = idempotent ? maxAttempts : 1;

        RetryBackoffSpec spec = Retry.backoff(attempts, minBackoff)
                .maxBackoff(maxBackoff)
                .jitter(jitter)
                .filter(this::isRetryableError)
                .onRetryExhaustedThrow((signal, failure) -> failure.failure());

        return spec.doBeforeRetry(rs ->
                log.warn("Retrying {} {} (attempt #{}, cause: {})",
                        request.method(), request.url(), rs.totalRetries() + 1, rs.failure().toString()));
    }

    private boolean shouldRetryForStatus(ClientRequest req, ClientResponse resp) {
        return isIdempotent(req) && RETRYABLE_STATUS.contains(resp.statusCode().value());
    }

    private boolean isIdempotent(ClientRequest req) {
        HttpMethod m = req.method();
        // RFC says DELETE and PUT are idempotent; keep them here. POST allowed only if caller set Idempotency-Key.
        if (m == HttpMethod.GET || m == HttpMethod.HEAD || m == HttpMethod.OPTIONS
                || m == HttpMethod.DELETE || m == HttpMethod.PUT) {
            return true;
        }
        if (m == HttpMethod.POST && req.headers().containsKey("Idempotency-Key")) {
            return true;
        }
        return false;
    }

    private boolean isRetryableError(Throwable t) {
        // Transport-level / transient failures
        if (t instanceof WebClientRequestException wcre) {
            Throwable cause = wcre.getCause();
            return cause instanceof ConnectException
                    || cause instanceof ReadTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof SocketException
                    || cause instanceof IOException;
        }
        // Our synthesized error for 5xx/429
        if (t instanceof RetryableStatusException) return true;

        // Bare TimeoutException (e.g., from .timeout())
        return t instanceof TimeoutException;
    }

    /** Marker exception to represent retryable HTTP status codes. */
    static class RetryableStatusException extends RuntimeException {
        private final HttpStatusCode status;
        private final HttpMethod method;
        private final String url;

        RetryableStatusException(HttpStatusCode status, HttpMethod method, String url) {
            super("Retryable HTTP status " + status + " for " + method + " " + url);
            this.status = status; this.method = method; this.url = url;
        }

        public HttpStatusCode getStatus() { return status; }
        public HttpMethod getMethod() { return method; }
        public String getUrl() { return url; }
    }
}

```
Defaults used in the example
maxAttempts = 3 (original try + 2 retries)
minBackoff = 200ms, maxBackoff = 2s
jitter = 0.5 (±50% randomness)

You can tune those in a single place.

2) Register the filter on your load-balanced builder. The ApplicationBeanConfiguration is now this:
```java
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

        return WebClient.builder()
                .clientConnector(connector)
                .filters(list -> list.add(retryFilter));
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
```
Because we add our filter to the builder, the retry is global (applies to your HttpClientInterface calls). 
If you later need different retry rules for certain endpoints, you can builder.clone() and attach a different 
filter for those clients only.


At this point the retry must work. Make the backend-service to throw some exception 
like that throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
upon calling the controller. Then here the webclient will print our RetryException on the console:

This error will be printed only after the last failed retry. So, you will not see it 3 times if you have set 3 times to retry.

```text
2025-08-24T13:10:28.846+02:00 ERROR 47040 --- [HttpWebClientService] [nio-8080-exec-3] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] threw exception

reactive.httpwebclientservice.filters.RetryBackoffFilter$RetryableStatusException: Retryable HTTP status 503 SERVICE_UNAVAILABLE for GET http://backend-service/api/v1/user/2
	at reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:53) ~[main/:na]
	Suppressed: reactor.core.publisher.FluxOnAssembly$OnAssemblyException: 
Assembly trace from producer [reactor.core.publisher.MonoIgnoreThen] :
	reactor.core.publisher.Mono.then(Mono.java:4879)
	reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:52)
Error has been observed at the following site(s):
	*_____________Mono.then ⇢ at reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:52)
	*__________Mono.flatMap ⇢ at reactive.httpwebclientservice.filters.RetryBackoffFilter.filter(RetryBackoffFilter.java:47)
	|_       Mono.retryWhen ⇢ at reactive.httpwebclientservice.filters.RetryBackoffFilter.filter(RetryBackoffFilter.java:57)
	*________Flux.concatMap ⇢ at reactor.util.retry.RetryBackoffSpec.lambda$generateCompanion$5(RetryBackoffSpec.java:593)
	|_     Flux.onErrorStop ⇢ at reactor.util.retry.RetryBackoffSpec.lambda$generateCompanion$5(RetryBackoffSpec.java:656)
	*__Flux.deferContextual ⇢ at reactor.util.retry.RetryBackoffSpec.generateCompanion(RetryBackoffSpec.java:591)
	*____________Mono.defer ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:468)
	|_           checkpoint ⇢ Request to GET http://backend-service/api/v1/user/2 [DefaultWebClient]
	|_   Mono.switchIfEmpty ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:473)
	|_        Mono.doOnNext ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:479)
	|_       Mono.doOnError ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:480)
	|_       Mono.doFinally ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:481)
	|_    Mono.contextWrite ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:488)
	*__Mono.deferContextual ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.exchange(DefaultWebClient.java:452)
	|_         Mono.flatMap ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultResponseSpec.toEntity(DefaultWebClient.java:616)
Original Stack Trace:
		at reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:53) ~[main/:na]
		

2025-08-24T13:10:28.853+02:00 ERROR 47040 --- [HttpWebClientService] [nio-8080-exec-3] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: reactive.httpwebclientservice.filters.RetryBackoffFilter$RetryableStatusException: Retryable HTTP status 503 SERVICE_UNAVAILABLE for GET http://backend-service/api/v1/user/2] with root cause

reactive.httpwebclientservice.filters.RetryBackoffFilter$RetryableStatusException: Retryable HTTP status 503 SERVICE_UNAVAILABLE for GET http://backend-service/api/v1/user/2
	at reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:53) ~[main/:na]
	Suppressed: reactor.core.publisher.FluxOnAssembly$OnAssemblyException: 
Assembly trace from producer [reactor.core.publisher.MonoIgnoreThen] :
	reactor.core.publisher.Mono.then(Mono.java:4879)
	reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:52)
Error has been observed at the following site(s):
	*_____________Mono.then ⇢ at reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:52)
	*__________Mono.flatMap ⇢ at reactive.httpwebclientservice.filters.RetryBackoffFilter.filter(RetryBackoffFilter.java:47)
	|_       Mono.retryWhen ⇢ at reactive.httpwebclientservice.filters.RetryBackoffFilter.filter(RetryBackoffFilter.java:57)
	*________Flux.concatMap ⇢ at reactor.util.retry.RetryBackoffSpec.lambda$generateCompanion$5(RetryBackoffSpec.java:593)
	|_     Flux.onErrorStop ⇢ at reactor.util.retry.RetryBackoffSpec.lambda$generateCompanion$5(RetryBackoffSpec.java:656)
	*__Flux.deferContextual ⇢ at reactor.util.retry.RetryBackoffSpec.generateCompanion(RetryBackoffSpec.java:591)
	*____________Mono.defer ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:468)
	|_           checkpoint ⇢ Request to GET http://backend-service/api/v1/user/2 [DefaultWebClient]
	|_   Mono.switchIfEmpty ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:473)
	|_        Mono.doOnNext ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:479)
	|_       Mono.doOnError ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:480)
	|_       Mono.doFinally ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:481)
	|_    Mono.contextWrite ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.lambda$exchange$12(DefaultWebClient.java:488)
	*__Mono.deferContextual ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultRequestBodyUriSpec.exchange(DefaultWebClient.java:452)
	|_         Mono.flatMap ⇢ at org.springframework.web.reactive.function.client.DefaultWebClient$DefaultResponseSpec.toEntity(DefaultWebClient.java:616)
Original Stack Trace:
		at reactive.httpwebclientservice.filters.RetryBackoffFilter.lambda$filter$0(RetryBackoffFilter.java:53) ~[main/:na]
		at reactor.core.publisher.MonoFlatMap$FlatMapMain.onNext(MonoFlatMap.java:132) ~[reactor-core-3.7.6.jar:3.7.6]
		at reactor.core.publisher.MonoFlatMap$FlatMapMain.secondComplete(MonoFlatMap.java:245) ~[reactor-core-3.7.6.jar:3.7.6]
	

Disconnected from the target VM, address: '127.0.0.1:35901', transport: 'socket'
```
That's it, it works!

You will notice that the filer has this method:

```java
private boolean isIdempotent(ClientRequest req) {
        HttpMethod m = req.method();
        // RFC says DELETE and PUT are idempotent; keep them here. POST allowed only if caller set Idempotency-Key.
        if (m == HttpMethod.GET || m == HttpMethod.HEAD || m == HttpMethod.OPTIONS
                || m == HttpMethod.DELETE || m == HttpMethod.PUT) {
            return true;
        }
        if (m == HttpMethod.POST && req.headers().containsKey("Idempotency-Key")) {
            return true;
        }
        return false;
    }

```
Why is it what it does? 
By default all POST requests in the WebClient do not apply the retry functionality as defined above.
BUT you can still make a certain POST request to be executed with Retries! The filter will be checking for POST
request with Header:.header("Idempotency-Key", "user:42") , but you also have to customize that particular request, 
like so:
This peice of code is not implemented, because I dont have any POST Requset going out from that WebClient. For that reason
the filer currently implements that if (m == HttpMethod.POST && req.headers().containsKey("Idempotency-Key"))  in vain - it better be removed.
But if you decide to use that check, then make sure your webclient also adds such headers to POST requests.
```java
// In your controller, for a POST that *is* safe to retry:
return WebClient.create("http://backend-service")
        .post()
        .uri("/api/v1/create-new-user")
        .header("Idempotency-Key", "user:42") // tells the filter it’s safe to retry
        .bodyValue(body)
        .retrieve()
        .toEntity(UserDbDTO.class);

```


So far we implemented the Retry Backoff strategy on the webclient level with a Filter.

The alternative of that strategy is the:
5) When to consider Resilience4j - this is not implemented in this project. Here is just general information:
You want metrics (attempts, successes, failures), bulkhead, circuit-breaker, or rate limiting.
You need policies per endpoint name rather than per HTTP method.
You’d wrap the Mono with RetryOperator.of(retry) from Resilience4j, but for learning the basics, the Reactor
filter above is the clearest, least magic approach.



               END of experiment to customize the WebClient -  2. Adding a Retry/Backoff Strategy





               START of experiment to customize the WebClient -  3. Inserting Custom Headers (e.g., Correlation ID, Auth Token)

3. Inserting Custom Headers (e.g., Correlation ID, Auth Token)
   In a microservice world, you often want to propagate a “correlation ID” (for tracing across services) or inject an
   Authorization: Bearer <token> header automatically on every request.

Our goal will be - when the webclient sends out a request towards the backend service, the request will be injected with
a custom header and value. To achieve this, we first write the header values into a context relative to the webclient.
Then, the webclient utilizes a Filter. Inside that filter the custom header is extracted from the context and added 
to the outgoing request. Below is the code implementation of this workflow.

You can inject headers in two complementary ways:
1. Globally (every request) via the WebClient.Builder
2. Per inbound request by seeding a correlation ID into the Reactor Context and letting a filter read it

Below is a clean, code-only setup (no application.yml) that does both.

A. Correlation ID: propagate per inbound request
1) A tiny helper with constants

```java
// Correlation.java
package reactive.httpwebclientservice.headers;

import java.util.UUID;

public final class Correlation {
    private Correlation() {}

    public static final String HEADER = "X-Correlation-Id";
    public static final String CTX_KEY = "corrId";

    public static String newId() { return UUID.randomUUID().toString(); }
}


```

2) A WebClient filter that adds the header from Reactor Context (or generates one)

```java
// CorrelationHeaderFilter.java
package reactive.httpwebclientservice.headers;

import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

public class CorrelationHeaderFilter implements ExchangeFilterFunction {

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


```

3) Seed the Reactor Context in your controller (one line per handler)
```java

// UserProxyController.java (snippet)
import static reactive.httpwebclientservice.headers.Correlation.*;

@GetMapping("/user-tih-headers/{id}")
public Mono<ResponseEntity<UserDTO>> getByIdWithHeaders(
        @PathVariable Long id,
        @RequestHeader(value = "X-API-Version", required = false) String ver,
        @RequestHeader(value = HEADER, required = false) String incomingCorrId) {

    String corrId = (incomingCorrId == null || incomingCorrId.isBlank()) ? newId() : incomingCorrId;

    return users.getById(id, ver)
            .contextWrite(ctx -> ctx.put(CTX_KEY, corrId)); // ← seed once; filter reads it
}

```

Why context? Because reactive code hops threads; using ThreadLocal/MDC won’t reliably flow to the WebClient. 
The Reactor Context is the right place to carry per-request metadata.

B. Authorization header: static token or pluggable supplier
```java
// AuthHeaderFilter.java
package reactive.httpwebclientservice.headers;

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

```
and add the authToken  authToken: "superSecretToken" to the application.yml:
```yml
dservice:
  service-id: "backend-service"
  use-eureka: true
  authToken: "superSecretToken"

```
C. Wire both filters into your existing builder (order matters)

```java
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

```
Putting correlation/auth before the retry filter ensures every retry attempt carries the same headers 
(including the correlation ID), and if you rotate tokens per call, retries see the latest token.

D. How to test
Send a postman request to: http://localhost:8080/proxy/user-with-headers/2
Add a header:
X-Correlation-Id = abc-123      - this is the header we are interested for our experiment

Optional, add another header, just because the backend service can receive it too:
X-API-Version = someVersion1

What should we expect to happen: When the controller receives the header X-Correlation-Id = abc-123 ,
it will check is here:
```java
String corrId = (incomingCorrId == null || incomingCorrId.isBlank()) ? newId() : incomingCorrId;
```
and will set its value 'corrId' in the filter via the context, so:
```java
return users.getById(id, ver)
                .contextWrite(ctx -> ctx.put(CTX_KEY, corrId)); // ← seed once; filter reads it
```

Next the Filter 'public class CorrelationHeaderFilter implements ExchangeFilterFunction '
will execute its method and will also print the debug line:
```java
System.out.println("CorrelationFilter setting header: " + corrId); // Debug
```
 and we see this line printed on the console:
```text
CorrelationFilter setting header: abc-123

```
And we expect that this header is now successfully added to the request which goues out to the backend service.

In the backend-service the controller receives that request, so:
```java
@GetMapping("/user/{id}")
    public ResponseEntity<EntityModel<User>> getUserById(@PathVariable Long id,
                                                         @RequestHeader(value = "X-API-Version", required = false) String apiVersion,
                                                         @RequestHeader(value = "X-Correlation-Id", required = false) String xCorrelationId) throws JsonProcessingException {

        System.out.println(">>>> Received X-API-Version: " + apiVersion);
        System.out.println(">>>> X-Correlation-Id: " + xCorrelationId);
        User user = userService.selectUserByPrimaryKey(id).orElse(null);
```

and indees prints the headers on the console:
```text
>>>> Received X-API-Version: v1
>>>> X-Correlation-Id: 00c18abc-b581-45cd-ba78-8f0c126183b3
```

, BUT if case we dont excplicitly set such header via the Postman client:
```text
X-Correlation-Id = abc-123      - this is the header we are interested for our experiment
```
then our code will assign a random value for that header, so:
```java
String corrId = (incomingCorrId == null || incomingCorrId.isBlank()) ? newId() : incomingCorrId;
```
the method newId() will generate a random value and next that value will be set as a header and
the Filter 'public class CorrelationHeaderFilter implements ExchangeFilterFunction ' 
will execute its method and will also print the debug line:
```java
System.out.println("CorrelationFilter setting header: " + corrId); // Debug
```
and we see this line printed on the console:
```text
CorrelationFilter setting header: 9ac5d91b-5912-47d8-9e8f-67e71fed48e6
```

, logically the backend service will also receive that value and will print it so:
```text
>>>> Received X-API-Version: v1
>>>> X-Correlation-Id: 00c18abc-b581-45cd-ba78-8f0c126183b3
```


This is how we tested our new functionality.





               END of experiment to customize the WebClient -  3. Inserting Custom Headers (e.g., Correlation ID, Auth Token)






               START of experiment to customize the WebClient -  4. Custom Error Decoding & Mapping to Exceptions

4. Custom Error Decoding & Mapping to Exceptions
   By default, non‐2xx responses are turned into a generic form. You might want to map, say, a 404 to a
   UserNotFoundException or a 401 to UnauthorizedException.

Below I is:
1. a small exception hierarchy,
2. an ErrorMappingFilter that:
- maps 4xx/5xx to your exceptions (reading the error body once, releasing the connection),
- maps transport errors (timeouts, connect refused) and our RetryableStatusException (after retries) to domain exceptions,
3. the builder wiring (order matters!), and
4. an optional @ControllerAdvice so your proxy returns meaningful HTTP status codes.


1) Exceptions (lean but useful)

```java
// errors/ApiException.java
package reactive.httpwebclientservice.errors;

public abstract class ApiException extends RuntimeException {
    private final Integer status;
    private final String method;
    private final String url;
    private final String correlationId;
    private final String body;

    protected ApiException(String message, Integer status, String method, String url, String correlationId, String body, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.method = method;
        this.url = url;
        this.correlationId = correlationId;
        this.body = body;
    }
    public Integer getStatus() { return status; }
    public String getMethod() { return method; }
    public String getUrl() { return url; }
    public String getCorrelationId() { return correlationId; }
    public String getBody() { return body; }
}

```

```java
// errors/BadRequestException.java
package reactive.httpwebclientservice.errors;
public class BadRequestException extends ApiException {
    public BadRequestException(String m, String method, String url, String corr, String body) {
        super(m, 400, method, url, corr, body, null);
    }
}

```
```java
// errors/UnauthorizedException.java
package reactive.httpwebclientservice.errors;
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String m, String method, String url, String corr, String body) {
        super(m, 401, method, url, corr, body, null);
    }
}

```
```java
// errors/UnauthorizedException.java
package reactive.httpwebclientservice.errors;
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String m, String method, String url, String corr, String body) {
        super(m, 401, method, url, corr, body, null);
    }
}

```
```java
// errors/ForbiddenException.java
package reactive.httpwebclientservice.errors;
public class ForbiddenException extends ApiException {
    public ForbiddenException(String m, String method, String url, String corr, String body) {
        super(m, 403, method, url, corr, body, null);
    }
}

```

```java
// errors/NotFoundException.java
package reactive.httpwebclientservice.errors;
public class NotFoundException extends ApiException {
    public NotFoundException(String m, String method, String url, String corr, String body) {
        super(m, 404, method, url, corr, body, null);
    }
}

```

```java
// errors/ConflictException.java
package reactive.httpwebclientservice.errors;
public class ConflictException extends ApiException {
    public ConflictException(String m, String method, String url, String corr, String body) {
        super(m, 409, method, url, corr, body, null);
    }
}

```
```java
// errors/TooManyRequestsException.java
package reactive.httpwebclientservice.errors;
public class TooManyRequestsException extends ApiException {
    private final String retryAfter;
    public TooManyRequestsException(String m, String method, String url, String corr, String body, String retryAfter) {
        super(m, 429, method, url, corr, body, null);
        this.retryAfter = retryAfter;
    }
    public String getRetryAfter(){ return retryAfter; }
}

```
```java
// errors/ServiceUnavailableException.java
package reactive.httpwebclientservice.errors;
public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String m, String method, String url, String corr, String body, Throwable cause) {
        super(m, 503, method, url, corr, body, cause);
    }
}

```
```java
// errors/GatewayTimeoutException.java
package reactive.httpwebclientservice.errors;
public class GatewayTimeoutException extends ApiException {
    public GatewayTimeoutException(String m, String method, String url, String corr, String body, Throwable cause) {
        super(m, 504, method, url, corr, body, cause);
    }
}

```

```java
// errors/UpstreamConnectException.java
package reactive.httpwebclientservice.errors;
public class UpstreamConnectException extends ApiException {
    public UpstreamConnectException(String m, String method, String url, String corr, Throwable cause) {
        super(m, null, method, url, corr, null, cause);
    }
}

```
```java
// errors/UpstreamTimeoutException.java
package reactive.httpwebclientservice.errors;
public class UpstreamTimeoutException extends ApiException {
    public UpstreamTimeoutException(String m, String method, String url, String corr, Throwable cause) {
        super(m, null, method, url, corr, null, cause);
    }
}

```

2) The error-mapping filter

```java
// filters/ErrorMappingFilter.java
package reactive.httpwebclientservice.filters;

import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.*;
import reactive.httpwebclientservice.errors.*;
import reactive.httpwebclientservice.utils.Correlation;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class ErrorMappingFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // Wrap the rest of the chain
        return next.exchange(request)
                // Map non-2xx responses to exceptions (read body once, release connection)
                .flatMap(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return Mono.just(resp);
                    }
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.deferContextual(ctx -> {
                                String corr = ctx.hasKey(Correlation.CTX_KEY) ? ctx.get(Correlation.CTX_KEY) : null;
                                RuntimeException ex = mapStatusToException(resp.statusCode(), body, request, resp.headers().asHttpHeaders(), corr);
                                return Mono.error(ex);
                            }));
                })
                // Also map transport-level errors and our retry marker after retries
                .onErrorMap(throwable -> mapTransportOrRetryErrors(throwable, request));
    }

    private RuntimeException mapStatusToException(HttpStatusCode status, String body, ClientRequest req, HttpHeaders headers, String corrId) {
        int sc = status.value();
        String method = req.method().name();
        String url = req.url().toString();

        // nice-to-have: Retry-After signal for 429
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);

        return switch (sc) {
            case 400 -> new BadRequestException("Bad request", method, url, corrId, body);
            case 401 -> new UnauthorizedException("Unauthorized", method, url, corrId, body);
            case 403 -> new ForbiddenException("Forbidden", method, url, corrId, body);
            case 404 -> new NotFoundException("Not found", method, url, corrId, body);
            case 409 -> new ConflictException("Conflict", method, url, corrId, body);
            case 429 -> new TooManyRequestsException("Too Many Requests", method, url, corrId, body, retryAfter);
            case 503 -> new ServiceUnavailableException("Service unavailable", method, url, corrId, body, null);
            case 504 -> new GatewayTimeoutException("Upstream timed out", method, url, corrId, body, null);
            default -> new ApiException("Upstream error " + sc, sc, method, url, corrId, body, null) {};
        };
    }

    private RuntimeException mapTransportOrRetryErrors(Throwable t, ClientRequest req) {
        String method = req.method().name();
        String url = req.url().toString();

        // Errors thrown by WebClient itself on 4xx/5xx (if any slipped through)
        if (t instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            return mapStatusToException(wcre.getStatusCode(), body, req, wcre.getHeaders(), null);
        }

        // Our retry filter synthesizes this for 5xx/429 (after draining) → after retries, map to 503/429 equivalents
        if (t instanceof RetryBackoffFilter.RetryableStatusException rse) {
            int sc = rse.getStatus().value();
            return switch (sc) {
                case 429 -> new TooManyRequestsException("Too Many Requests (after retries)", method, url, null, null, null);
                case 503, 500, 502, 504 -> new ServiceUnavailableException("Service unavailable (after retries)", method, url, null, null, t);
                default -> new ApiException("Retryable upstream error " + sc, sc, method, url, null, null, t) {};
            };
        }

        // Transport problems (connect refused, DNS, read timeout, Reactor timeout)
        if (t instanceof WebClientRequestException wcre) {
            Throwable cause = wcre.getCause();
            if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException) {
                return new UpstreamTimeoutException("I/O timeout", method, url, null, t);
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof SocketException) {
                return new UpstreamConnectException("Connection problem", method, url, null, t);
            }
        }
        if (t instanceof TimeoutException) {
            return new UpstreamTimeoutException("Upstream timed out", method, url, null, t);
        }

        // otherwise pass it through
        return (t instanceof RuntimeException re) ? re : new RuntimeException(t);
    }
}

```

3) Wire it into your builder (make it the OUTERMOST filter)
Put the error mapping first so it can see:
normal error responses (4xx/5xx) and
errors thrown by inner filters (e.g., our retry filter after retries).
Also keep your request-mutating filters (correlation/auth) before retry so retries carry headers.

```java
// ApplicationBeanConfiguration.java (builder bean)
@Bean
@LoadBalanced
public WebClient.Builder loadBalancedWebClientBuilder(ReactorClientHttpConnector connector) {

    var errorMapping = new ErrorMappingFilter();
    var correlationFilter = new CorrelationHeaderFilter();
    var authFilter = new AuthHeaderFilter(props::getAuthToken);

    var retryFilter = new RetryBackoffFilter(
            2, Duration.ofSeconds(1), Duration.ofSeconds(1), 0.0);

    return WebClient.builder()
            .clientConnector(connector)
            .filters(list -> {
                // OUTERMOST
                list.add(errorMapping);

                // mutate requests, then allow retry to re-run with headers
                list.add(correlationFilter);
                list.add(authFilter);

                // INNER
                list.add(retryFilter);
            });
}

```

4) (Optional) Turn exceptions into HTTP responses for your proxy
   If you want your /proxy/** endpoints to respond with the same status (404/401/429/503…) instead of a 
generic 500, add:

```java
// errors/GlobalErrorHandler.java
package reactive.httpwebclientservice.errors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApi(ApiException ex) {
        // include correlation ID so clients can quote it
        return ResponseEntity.status(ex.getStatus() != null ? ex.getStatus() : 502)
                .header("X-Correlation-Id", ex.getCorrelationId() != null ? ex.getCorrelationId() : "N/A")
                .body(ex.getMessage());
    }
}
```
(You can of course return a structured JSON error with code/message/details.)

How this behaves
401 from backend → UnauthorizedException → your advice returns 401 to caller.
404 → NotFoundException.
429 → retry filter may retry; if still failing, TooManyRequestsException (with Retry-After if present).
503/5xx → retried; after retries, ServiceUnavailableException.
Connect refused / DNS / read timeout → UpstreamConnectException or UpstreamTimeoutException.
All done in code (no YAML), via your builder & filters—exactly the “tweakable WebClient” style you’re practicing.


How to test the custom mapping?
The webclient has a controller which accepts parameter for the number of Error Status code, so:
```java
@GetMapping("/proxy-http-status/{code}")
    public Mono<ResponseEntity<String>> getCustomErrorResponse(@PathVariable int code) {
        
        //Always return the Mono (or Flux).
        //That 'return value' is the contract you make with WebFlux. The incoming request has a Netty channel and
        //that 'return value' is what ties that channel to your reactive pipeline.
        return users.proxyGetCustomErrorResponse(code);    // non-blocking
    }
```
Thus the web cilent will send the request to the backend-service, which will accept the request with this controller:
```java
@GetMapping("/http-status/{code}")
    public ResponseEntity<String> getCustomErrorResponse(@PathVariable int code)
    {
        try {
            HttpStatus status = HttpStatus.valueOf(code);

            ResponseEntity<String> responseEntity = ResponseEntity
                    .status(status)
                    .body("Custom error with status: " + code);

            return responseEntity;

        } catch (IllegalArgumentException ex)
        {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Invalid HTTP status code: " + code);
        }
    }
```
As you see the backend-service will generate a Response with the status code, which it received.

Now, since we have created custom mappings for most common status codes, we will not receive generic error messages
for the relevant codes, but we will receive our custom error messages. This is how it works:
The postman cilent sends this request:
```text
http://localhost:8080/proxy/proxy-http-status/404

```
The webcilent receives it sends it further to the backend-service. And the webclient will continue executing
its code, because its non-blocking, asynchronous.
The backend service receives the request with the value 404 and it and respond with this code:
```text
404 NOT_FOUND

```
The webcilent service and particularrly its Netty Channel will receive this custom message and will reply to 
the Postman client.

Same logic will be performed with any other code status which we also defined:

If Postman sends: http://localhost:8080/proxy/proxy-http-status/504 , postman will receive:  
503 Service Unavailable - 'Service unavailable (after retries)', 
because this message we defined in 'public class ServiceUnavailableException extends ApiException'.
NOTE: we send 504, but our definition is for 503. Therefore we also receive 503 as code. Little bit above, we
already said these mappings are so:
```text
How this behaves
401 from backend → UnauthorizedException → your advice returns 401 to caller.
404 → NotFoundException.
429 → retry filter may retry; if still failing, TooManyRequestsException (with Retry-After if present).
503/5xx → retried; after retries, ServiceUnavailableException.
```




               END of experiment to customize the WebClient -  4. Custom Error Decoding & Mapping to Exceptions








               START of experiment to customize the WebClient -  5. Custom JSON (Jackson) Configuration



5. Custom JSON (Jackson) Configuration
   Suppose you want to use a custom ObjectMapper—for example, enabling a special date format, ignoring unknown fields,
   or registering a module (e.g. JSR310, Kotlin, Protobuf). You need to tell the WebClient to use your ObjectMapper when
   serializing/deserializing request and response bodies.

Working and customizing JSON and ObjectMapper requires the knowledge and explanation we already gave in the README.md
file of the project: HTTPrestClientService. There we explain about what is ObjectMapper, what is spring-aware ObjectMapper,
what is Jackson2ObjectMapperBuilder and what is Jackson2ObjectMapperBuilderCustomizer.

These explanations are important because they help us chose the correct way to customize , depending on the
context we have.

A short summery of what we explained and researched in the README.md file of the project: HTTPrestClientService is this:

```text
        The mental model (3 layers)

Where does the mapper come from?
    G0 — Global, Spring-aware (preferred base): the auto-configured ObjectMapper or the Spring-provided Jackson2ObjectMapperBuilder. These already include Boot defaults (JavaTimeModule, Kotlin/Hibernate if present, spring.jackson.* props).
    G1 — Spring-aware derived: build a new mapper from the injected Jackson2ObjectMapperBuilder → fresh instance, still Spring-aware.
    G2 — Raw Jackson: new ObjectMapper() → clean but not Spring-aware (you’ll miss Boot’s defaults unless you add them yourself).

Where will it be used? (scope/target)
    S0 — Global app-wide: affects server @RestController, WebClient, RestClient, everything.
    S1 — Per-client: only a specific WebClient (and HTTP Interface on top of it) or a specific RestClient.
    S2 — Per-call: an occasional one-off call with different rules.

How do you apply it? (wiring point)
    W0 — Global customizer / mutation: Jackson2ObjectMapperBuilderCustomizer or mutate the auto-configured ObjectMapper.
    W1 — WebClient codecs: WebClient.Builder → ExchangeStrategies with Jackson2JsonEncoder/Decoder.
    W2 — RestClient converters: replace/add MappingJackson2HttpMessageConverter with your mapper.
    W3 — Per-call derived client: webClient.mutate().exchangeStrategies(...).build() just for that one request.
    W4 — DTO-directed knobs: @JsonFormat, @JsonView, mix-ins (applies regardless of mapper source).

```

That information about the context of these objects help us take decisions when -> what should we implement:
```text
Decision GUIDE (quick):
“I want one JSON policy everywhere (controllers + clients) and keep Boot defaults.”
→ Use W0: Jackson2ObjectMapperBuilderCustomizer (preferred) or mutate the injected global ObjectMapper.

“Keep controllers on stock settings, but make my clients different.”
→ Use G1 + S1: build a new, Spring-aware mapper from injected Jackson2ObjectMapperBuilder, then wire via W1 (WebClient) / W2 (RestClient).

“I need two different client policies (e.g., legacy vs new API).”
→ Multiple S1 builders, each with its own mapper from G1.

“Just this one call needs a special content type or date format.”
→ S2 via W3: create a derived WebClient for that call with tailored ExchangeStrategies (or switch MediaType to NDJSON, etc.).

“I must control Protobuf/Smile/NDJSON too.”
→ Add non-JSON codecs alongside Jackson in W1.

```

Now lets focus on the current task - a Custom JSON (Jackson) Configuration.  
But what does the 'Custom JSON (Jackson) Configuration' actually mean? - it means to adjust how Jackson (the JSON library used by 
Spring Boot) serializes and deserializes objects — instead of using only the defaults. That adjusting can include things like:
- Changing date/time formats
- Ignoring null fields
- Enabling/disabling features (WRITE_DATES_AS_TIMESTAMPS, FAIL_ON_UNKNOWN_PROPERTIES, etc.)
- Adding custom serializers/deserializers
- Registering extra modules (e.g., JavaTimeModule)
In short: it’s about telling Jackson how you want your JSON to look and behave in your Spring app.

This 'adjusting' has this chain of roles: 
Jackson2ObjectMapperBuilderCustomizer → customizes → Jackson2ObjectMapperBuilder → builds → JsonMapper (a specialized ObjectMapper) → does JSON work.
As we see the final role in that chain is played by the JsonMapper.
Ok, but what is difference between JsonMapper and ObjectMapper? How do they relate to each other?
    Quick relationships:
- ObjectMapper is the databind workhorse in Jackson: it turns JSON (or other data formats) ↔ Java objects.
- JsonMapper is a subclass of ObjectMapper introduced later (Jackson 2.10+) that’s JSON-specific and comes with a builder (JsonMapper.builder()) and a few safer defaults for JSON out of the box.
So: JsonMapper is not lower than ObjectMapper—it is an ObjectMapper (specialized for JSON) with a nicer builder API. You can use either in Spring; Spring just needs an ObjectMapper instance.

There are other formats similar to JsonMapper:
XmlMapper (XML), CBORMapper, SmileMapper, YAMLMapper — they are all specialized ObjectMappers tied to a specific JsonFactory.

And short description of the conversion-chain. The steps the converting goes to:
```text

        The conversion chain (conceptual)

When Jackson converts between bytes and objects, this is the pipeline:
1. the HTTP layer (Spring):
- Server: MappingJackson2HttpMessageConverter (MVC) or Jackson2JsonEncoder/Decoder (WebFlux)
- Client: same classes on the client side (RestClient uses converters; WebClient uses codecs)

2. the Databind layer (your ObjectMapper, which is actually the JsonMapper )
- Looks up a serializer or deserializer for the target type
- Applies modules you registered (e.g., JavaTimeModule, Jdk8Module, Afterburner, Kotlin)
- Honors features & inclusion rules (FAIL_ON_UNKNOWN_PROPERTIES, NON_NULL, etc.)
- Honors naming strategy (SNAKE_CASE, etc.) and date format

3. Streaming layer (core)
- Uses a JsonFactory → creates a JsonParser (read) / JsonGenerator (write) to actually read/write bytes

Think of it as:
HTTP wrapper → (your) Mapper config → (under the hood) streaming read/write.
```

    Summery:
- JsonMapper extends ObjectMapper; it’s just the JSON-focused flavor with a builder.
- The mapper (whichever you use) sits in the databind step between Spring’s HTTP codecs/converters and Jackson’s low-level streaming.
- In Spring Boot projects, prefer Spring-aware builders/customizers when you want Boot’s defaults; otherwise JsonMapper.builder() is a great, explicit way to craft a JSON mapper.



Now after we have clarified the 'subject' of our task - a 'Custom JSON (Jackson) Configuration', lets pick up
one of all options to perform (implement) this customization - to customize globally, per client or per call?

We decide to customize only on the level of the current existing WebClient, without affecting any other global configurations of
other objects.

You can give just this WebClient its own Jackson settings—without touching Spring Boot’s global ObjectMapper—by installing a custom encoder/decoder on the builder.
Below is a drop-in update to your ApplicationBeanConfiguration which now applies customizations 
for the JSON (Jackson) as per the task:

```java
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
    public WebClient.Builder loadBalancedWebClientBuilder(ReactorClientHttpConnector connector,
                                                          Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder) {


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

        // Build the LB-aware WebClient.Builder with custom per-client codecs
        return WebClient.builder()
                .clientConnector(connector)
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonEncoder(encoder);
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    // (optional) increase if you parse large payloads
                    // c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                })
                .filters(list -> {
                    // OUTERMOST
                    list.add(errorMapping);

                    // request-mutating filters should run BEFORE retry (so each retry has headers)
                    // mutate requests, then allow retry to re-run with headers
                    list.add(correlationFilter);
                    list.add(authFilter);

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
}

```
To test if the configuration is applied modify the
```text
.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
```
set it from false to true - and you will see that some HTTP Calls simply fail.




               END of experiment to customize the WebClient -  5. Custom JSON (Jackson) Configuration






               START of experiment to customize the WebClient -  6. Metrics & Instrumentation



6. Metrics & Instrumentation
In production, you’ll want to track how many calls you’re making, response times, error rates, etc. 
You can hook in Micrometer or Spring Boot’s MeterRegistry and record metrics around every request.

Before I continue with implementing task 06 for the WEbCilent, I want to have a clear view of the available 
options, consider which are suitable for WebClient or for which scenarios?

Here’s the quick mental model you asked for, then I’ll show exactly how to wire it for WebClient.

    "Mental model" (who does what):
- Micrometer (MeterRegistry) → numbers/metrics (counters, timers, histograms).
- Micrometer Observation (ObservationRegistry) → the bridge that times things and can emit both metrics and traces.
- Spring WebClient Observability → built into Spring Framework: when you give the builder an ObservationRegistry, it adds an observation filter so every call records http.client.requests with tags like method, status, uri, clientName, etc.
- MeterFilter → tweaks/filters metrics (add common tags, rename/deny, sanitize tag values).
- Feign’s MicrometerCapability/MicrometerObservationCapability → Feign-specific. Not used for WebClient.


Could there be any additional options in Spring Galaxy, which potentially can be used for the same purpose, 
but I probably did not ask about?

    Here is "ADDITIONAL LIST" :
- Micrometer Tracing (OpenTelemetry/Zipkin): micrometer-tracing + -bridge-otel or -bridge-brave for spans of WebClient calls.
- Reactor Netty I/O metrics: HttpClient.create().metrics(true) for connection/pool/socket stats.
- MeterFilter / ObservationFilter beans: shape/deny/rename metrics or enrich observations globally.
- WebClientCustomizer: drop-in bean to auto-add observation/filters to all WebClient builders.
- Spring Cloud LoadBalancer metrics: per-service instance stats.
- Resilience4j + Micrometer: circuit-breaker/retry/bulkhead metrics around WebClient calls.
- Actuator metrics endpoints: /actuator/metrics, /actuator/prometheus for scraping/export.

, compared the ones on the "Mental Model" with the ones in the "ADDITIONAL LIST",
what we will do here as DEMO will be the standard, recommended way for WebClient in Spring 6 / Boot 3:

- Add Actuator → you get ObservationRegistry + MeterRegistry.
- Call .observationRegistry(...) (and optionally .observationConvention(...)) on your WebClient.Builder.
- You get http.client.requests timers with sane tags out of the box.
- Use MeterFilter for histograms/common tags; optionally enable Reactor Netty I/O metrics.

This is the typical, production-ready approach for WebClient; Feign’s Micrometer capabilities are Feign-specific
and not needed here.


First, add new dependencies:
```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus' // optional

```

, and then we enable Reactor Netty client I/O metrics (Micrometer-backed), so:
```java

@Bean
ReactorClientHttpConnector clientHttpConnector() {
    HttpClient http = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
        .responseTimeout(Duration.ofSeconds(100))
        .doOnConnected(conn -> conn
            .addHandlerLast(new ReadTimeoutHandler(30))
            .addHandlerLast(new WriteTimeoutHandler(10)))
        // enable Reactor Netty client I/O metrics (Micrometer-backed)  <<---- THIS IS THE NEW ADDITION
        .metrics(true, uri -> uri); // <— use this public overload      <<---- THIS IS THE NEW ADDITION

    return new ReactorClientHttpConnector(http);
}
```

Next, we add /** Optional: system-wide meter filters (apply to all registries). */, so:
```java
/** Optional: system-wide meter filters (apply to all registries). */
@Bean
MeterFilter commonTags() {
    return MeterFilter.commonTags(Tags.of("app", "HttpWebClientService"));
}

```
Thus we are not overriding anything. it’s additive, not a replacement.
- Declaring a @Bean MeterFilter adds your filter to the registry; it doesn’t wipe out Spring Boot’s property-driven filters (e.g., from management.metrics.*) or other MeterFilter beans.
- Spring Boot orders all MeterFilter beans (by @Order/Ordered) and then registers them in order. Use this if precedence matters:
- Watch out for duplicate tag keys (e.g., management.metrics.tags.app + your commonTags("app", ...)). Prefer a single source or ensure unique keys.

Next, add /** Optional: configure percentiles/histograms for http client timers. */, so:

```java
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
```
It tweaks how the http.client.requests timer aggregates latency:
- Targets the meter by name and applies a DistributionStatisticConfig.
- percentiles(0.5, 0.95, 0.99) → publishes p50/p95/p99.
- percentilesHistogram(true) → adds histogram buckets (for Prometheus histograms/heatmaps).
- serviceLevelObjectives(...) → explicit SLO bucket bounds (in nanos for timers).
- Trade-off: more time series & memory, but you get rich latency visibility and SLO-aligned alerts.


Next, add  /** A custom observation convention to add low-cardinality tags (e.g., serviceId, apiVersion). */
, so:
```java
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
```
It’s a hook that customizes the tags Micrometer adds to each WebClient metric/trace.
By providing a ClientRequestObservationConvention, you can add low-cardinality key–values (e.g., service.id,
api.version, corr.present) to http.client.requests while keeping default tags. You then pass this bean to the
builder via .observationConvention(...). Low-cardinality = safe for metrics (no explosion of time series).

Next, add these two:
.observationRegistry(observationRegistry)
.observationConvention(webClientObservationConvention)
, to the return WebClient.builder(). ..... 

And the final public class ApplicationBeanConfiguration looks so:

```java

package reactive.httpwebclientservice.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
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
import reactive.httpwebclientservice.filters.AuthHeaderFilter;
import reactive.httpwebclientservice.filters.CorrelationHeaderFilter;
import reactive.httpwebclientservice.filters.ErrorMappingFilter;
import reactive.httpwebclientservice.filters.RetryBackoffFilter;
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
    @Bean
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
    public WebClient.Builder loadBalancedWebClientBuilder(ReactorClientHttpConnector connector,
                                                          Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder,
                                                          ObservationRegistry observationRegistry,
                                                          ClientRequestObservationConvention webClientObservationConvention) {

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
                    // c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                })
                .filters(list -> {
                    // OUTERMOST
                    list.add(errorMapping);

                    // request-mutating filters should run BEFORE retry (so each retry has headers)
                    // mutate requests, then allow retry to re-run with headers
                    list.add(correlationFilter);
                    list.add(authFilter);

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
}
```

The App starts with success. To test and see the new metrics, create an endpoint in the RestController, so:
Add the private final MeterRegistry registry in the class UserProxyController  so:
```java
@RestController
@RequestMapping("/proxy")       // <— choose any prefix you like
public class UserProxyController {

    private final HttpClientInterface users;
    private final MeterRegistry registry;

    public UserProxyController(HttpClientInterface users, MeterRegistry registry) {
        this.users = users;
        this.registry = registry;
    }
    
    
    //........
}

```
And create the endpoint RequestController:
```java
@GetMapping("/debug/http-client-metrics")
  List<Map<String,Object>> httpClient() {
    return registry.get("http.client.requests").timers().stream()
      .map(t -> Map.of(
          "tags", t.getId().getTags(),            // includes method, uri, status, clientName, + your custom tags
          "count", t.count(),
          "meanMs", t.mean(TimeUnit.MILLISECONDS),
          "p95Ms", t.takeSnapshot().percentileValues().length > 0
                    ? t.takeSnapshot().percentileValues()[0].value(TimeUnit.MILLISECONDS) : null
      ))
      .toList();
  }

```
Now send a request to it via Postman: http://localhost:8080/proxy/debug/http-client-metrics
The result is:
```json
[
    {
        "meanMs": 10.6894125,
        "p95Ms": 4.456448,
        "tags": [
            {
                "key": "app",
                "value": "HttpWebClientService"
            },
            {
                "key": "client.name",
                "value": "localhost"
            },
            {
                "key": "error",
                "value": "none"
            },
            {
                "key": "exception",
                "value": "none"
            },
            {
                "key": "method",
                "value": "PUT"
            },
            {
                "key": "outcome",
                "value": "SUCCESS"
            },
            {
                "key": "status",
                "value": "200"
            },
            {
                "key": "uri",
                "value": "none"
            }
        ],
        "count": 2
    },
    {
        "meanMs": 39.283089,
        "p95Ms": 6.029312,
        "tags": [
            {
                "key": "app",
                "value": "HttpWebClientService"
            },
            {
                "key": "client.name",
                "value": "localhost"
            },
            {
                "key": "error",
                "value": "none"
            },
            {
                "key": "exception",
                "value": "none"
            },
            {
                "key": "method",
                "value": "GET"
            },
            {
                "key": "outcome",
                "value": "SUCCESS"
            },
            {
                "key": "status",
                "value": "200"
            },
            {
                "key": "uri",
                "value": "none"
            }
        ],
        "count": 3
    },
    {
        "meanMs": 37.499568,
        "p95Ms": 0.0,
        "tags": [
            {
                "key": "app",
                "value": "HttpWebClientService"
            },
            {
                "key": "client.name",
                "value": "localhost"
            },
            {
                "key": "error",
                "value": "none"
            },
            {
                "key": "exception",
                "value": "none"
            },
            {
                "key": "method",
                "value": "POST"
            },
            {
                "key": "outcome",
                "value": "SUCCESS"
            },
            {
                "key": "status",
                "value": "204"
            },
            {
                "key": "uri",
                "value": "none"
            }
        ],
        "count": 1
    }
]

```

Another way and actually the recommended way to see the metrics we activated is possible if we have added the:
implementation 'org.springframework.boot:spring-boot-starter-actuator'

And since we really added it, we can use only this in the application.yml:
```yml
management:
  endpoints:
    web:
      exposure:
        include: metrics,prometheus
```
Now do this:

List timer:
GET http://localhost:8080/actuator/metrics/http.client.requests

Drill into a series (example):
GET http://localhost:8080/actuator/metrics/http.client.requests?tag=method:GET&tag=client.name:localhost
GET http://localhost:8080/actuator/metrics/http.client.requests?tag=method:GET
For Netty I/O meters (if you enabled .metrics(true, uri -> uri)): look for names like
reactor.netty.http.client.data.received, reactor.netty.connection.provider.active.connections, etc.





               END of experiment to customize the WebClient -  6. Metrics & Instrumentation




               START of experiment to customize the WebClient -  7. Circuit Breaker / Bulkhead (Resilience4j Integration)


7. Circuit Breaker / Bulkhead (Resilience4j Integration)
   Repeated failures to your backend (e.g. DB down) should not cascade into your entire system. A circuit breaker lets you “trip”
   after N failures and avoid hammering a bad endpoint.



1) build.gradle — add Resilience4j (reactor)
First, add these new dependencies:
```gradle
// For the purpose of 7. Circuit Breaker / Bulkhead (Resilience4j Integration)
    // Resilience4j for Reactor (CircuitBreaker, Bulkhead operators)
    implementation 'io.github.resilience4j:resilience4j-reactor:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-bulkhead:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    // (optional) metrics auto-binding if you want:
    implementation 'io.github.resilience4j:resilience4j-micrometer:2.2.0'

```

Expose the endpoints in yml:
```yml
management:
  endpoints:
    web:
      exposure:
        include: metrics,prometheus,circuitbreakers,circuitbreakerevents
```

2) New filter that wraps each call with Bulkhead and CircuitBreaker

```java
// src/main/java/reactive/httpwebclientservice/filters/Resilience4jFilter.java
package reactive.httpwebclientservice.filters;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Wraps the WebClient exchange Publisher with Bulkhead and CircuitBreaker operators.
 * Name resolution is pluggable to support per-service or per-endpoint breakers.
 */
public class Resilience4jFilter implements ExchangeFilterFunction {

    private final CircuitBreakerRegistry cbRegistry;
    private final BulkheadRegistry bhRegistry;
    private final Function<ClientRequest, String> nameResolver;

    public Resilience4jFilter(CircuitBreakerRegistry cbRegistry,
                              BulkheadRegistry bhRegistry,
                              Function<ClientRequest, String> nameResolver) {
        this.cbRegistry = Objects.requireNonNull(cbRegistry);
        this.bhRegistry = Objects.requireNonNull(bhRegistry);
        this.nameResolver = Objects.requireNonNull(nameResolver);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String name = nameResolver.apply(request);
        CircuitBreaker cb = cbRegistry.circuitBreaker(name);
        Bulkhead bh = bhRegistry.bulkhead(name);

        // Ensure Bulkhead limits are applied, then CB records the overall result.
        // Because we’ll place this filter OUTERMOST, it will also “see” errors produced by inner filters (e.g., ErrorMapping).
        return next.exchange(request)
                .transformDeferred(BulkheadOperator.of(bh))
                .transformDeferred(CircuitBreakerOperator.of(cb));
    }
}

```

3) Update ApplicationBeanConfiguration with this code:
```java
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
```

        How this behaves (quickly)

- Bulkhead caps concurrent in-flight calls (fails fast when saturated).

- Circuit Breaker:
  a) Ignores 4xx (your ErrorMappingFilter maps them to ApiException with 4xx → we don’t record them).
  b) Counts 5xx/429 and transport/timeouts as failures.
  c) With the filter placed outermost, it “sees” final success/failure after your internal retry attempts and error mapping.

- You can rename breakers per endpoint by changing the nameResolver.

If you want per-endpoint policies (e.g., stricter CB for /user-with-data/**), create additional 
registries/configs or use registry.circuitBreaker("GET /api/v1/user-with-data") via the resolver.

Test on this endpoint:
http://localhost:8080/actuator
Result is:

```json
{
    "names": [
        "application.ready.time",
        "application.started.time",
        "disk.free",
        "disk.total",
        "executor.active",
        "executor.completed",
        "executor.pool.core",
        "executor.pool.max",
        "executor.pool.size",
        "executor.queue.remaining",
        "executor.queued",
        "http.client.requests",
        "http.client.requests.active",
        "http.server.requests",
        "http.server.requests.active",
        "jvm.buffer.count",
        "jvm.buffer.memory.used",
        "jvm.buffer.total.capacity",
        "jvm.classes.loaded",
        "jvm.classes.unloaded",
        "jvm.compilation.time",
        "jvm.gc.live.data.size",
        "jvm.gc.max.data.size",
        "jvm.gc.memory.allocated",
        "jvm.gc.memory.promoted",
        "jvm.gc.overhead",
        "jvm.gc.pause",
        "jvm.info",
        "jvm.memory.committed",
        "jvm.memory.max",
        "jvm.memory.usage.after.gc",
        "jvm.memory.used",
        "jvm.threads.daemon",
        "jvm.threads.live",
        "jvm.threads.peak",
        "jvm.threads.started",
        "jvm.threads.states",
        "logback.events",
        "process.cpu.time",
        "process.cpu.usage",
        "process.files.max",
        "process.files.open",
        "process.start.time",
        "process.uptime",
        "system.cpu.count",
        "system.cpu.usage",
        "system.load.average.1m",
        "tomcat.sessions.active.current",
        "tomcat.sessions.active.max",
        "tomcat.sessions.alive.max",
        "tomcat.sessions.created",
        "tomcat.sessions.expired",
        "tomcat.sessions.rejected"
    ]
}
```

More extensive testing would require additional configurations.





               END of experiment to customize the WebClient -  7. Circuit Breaker / Bulkhead (Resilience4j Integration)





               START of experiment to customize the WebClient -  8. Custom Load-Balancing Rules (Zone/Affinity, Metadata-based Routing)


8. Custom Load-Balancing Rules (Zone/Affinity, Metadata-based Routing)
   By default, Spring Cloud LoadBalancer uses a simple round-robin. Sometimes you want:
   Zone Affinity: Prefer instances in the same zone/region as the client.
   Metadata Filtering: Only use instances that have a specific metadata label (e.g. version=v2).
   Weighting: Give some instances higher “weight” if they’re more powerful.

From a variety of ways to implement LoadBalancer lets try to distinguish the contexts:

Here’s a compact “mental model” + exactly how to wire per-service (not global) load-balancing behavior for your 
WebClient talking to Eureka-registered backend-service.

            Mental model (quick)

- Client-side LB (you already use this): Your @LoadBalanced WebClient.Builder installs a 
filter that asks Spring Cloud LoadBalancer to choose a ServiceInstance (from Eureka) before the HTTP call is sent.
Default algorithm is round-robin.

- Where LB can live:
  1. Inside your client (WebClient + Spring Cloud LoadBalancer) → what we customize here.
     Spring Cloud LoadBalancer can work with any client! But only with clients! Spring Cloud LoadBalancer “the one” for 
     any client - including the Spring WebClient. Spring Cloud LoadBalancer is only client side LB.
     It’s the maintained, Ribbon-successor client-side LB in Spring Cloud (2020+). It works with Eureka/Consul/etc.,
     integrates with WebClient via @LoadBalanced, and is the standard choice in Spring apps.
     If you need more advanced LB (outlier detection, rich routing), that’s usually done server-side (Spring Cloud 
     Gateway) - read below point 2. 'edge/gateway' → server-side. Or via a service mesh (Envoy/Istio/Linkerd) — not in the WebClient itself.
   2. At the edge/gateway (e.g., Spring Cloud Gateway) → server-side.
   3. Outside the app (Nginx/ALB/Envoy/service mesh) → infrastructure level.
  
- How to scope config:
  1. Per service ID: @LoadBalancerClient(name="backend-service", configuration=...) gives only that target a 
  custom chain (best for you).
  2. Global: set spring.cloud.loadbalancer.configurations=... or provide a primary ServiceInstanceListSupplier 
bean (affects all).

    
        Global vs per-service

- Per-service (recommended here): the @LoadBalancerClient(name="backend-service", configuration=...) you saw 
gives you precise control without affecting other outbound clients.
- Global: set spring.cloud.loadbalancer.configurations: zone-preference (or weighted, health-check, 
same-instance-preference, request-based-sticky-session, subset) to switch all clients to those behaviors, or 
expose a global ServiceInstanceListSupplier bean. Useful only if every target should behave the same.


        The current setup in the project:

With just @LoadBalanced WebClient.Builder and no custom supplier/config, I am using the default round-robin over 
healthy instances from Eureka. No zone preference, weights, hints, or metadata filtering are active unless I
add them.


        Other LB “families” to be aware of

- Client-side (in your app): Spring Cloud LoadBalancer (what you have).
- Gateway/server-side: Spring Cloud Gateway / API gateways.
- Infrastructure: Mesh/proxy (Envoy/NGINX/K8s Service/ALB).
- Protocol-specific: gRPC’s pick_first/round_robin (if using gRPC).

4) Good questions to decide before customizing

- Scope: Per-service vs. global policy? (Use @LoadBalancerClient(name="…", configuration=…) for per-service.)
- Routing policy: Round-robin vs. zone-affinity, weights, hints/metadata (canary version=v2).
- Stickiness: Need request-based sticky sessions or same-instance preference for idempotent flows?
- Retries interplay: On retry, pick a different instance or the same? (Affects resilience.)
- Health/outlier handling: How do you avoid bad instances (health checks, circuit breaker per instance)?
- Observability: Enable LB DEBUG logs/metrics to verify distribution & chosen instances.
- Fallbacks: What if Eureka returns no instances? (Static URL fallback or fail fast.)
Where to place LB: Keep in client, or move to gateway/mesh if you need richer policies at the edge.


The project already implements the Spring Cloud LoadBalancer for the WebClient. Now let's add custom
for backend-service (zone-affinity + weights + hint/metadata routing), without changing your WebClient code.

1) Add per-service LB configuration hook

HttpWebClientServiceApplication (ADD the annotation @LoadBalancerClient, keep the rest as-is), so:

```java
// ⬇️ ADD: import
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;

// ⬇️ ADD this annotation to bind a custom LB config ONLY for "backend-service"
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

```

2) New per-service LB config class
   Add NEW file: BackendServiceLbConfig.java
```java

package reactive.httpwebclientservice.config;

import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerClientRequestTransformer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
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
                        .withCaching()     //<<---- PUT it up here !!!
                        .withZonePreference()         // prefer same-zone instances
                        .withHints()                  // enable X-SC-LB-Hint / metadata: hint
                        .withWeighted()               // use metadata: weight
                        //.withCaching()     <<---- REMOVE it from here !!!
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
```

Add NEW file: VersionMetadataFilteringSupplier.java , so:

```java
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

```

3) (Optional) Tell LB what client zone this app is in — without YAML
   Zone preference compares the client’s zone to instance metadata "zone". If you don’t want to put it in 
application.yml, you can define the config bean in code (demo value shown):
   / ⬇️ Add this BEAN anywhere in your @Configuration (e.g., in ApplicationBeanConfiguration)
   //     so zone-preference knows your client's zone.
   //     If you already set spring.cloud.loadbalancer.zone in YAML, omit this.
```java

// ⬇️ Add this BEAN anywhere in your @Configuration (e.g., in ApplicationBeanConfiguration)
//     so zone-preference knows your client's zone.
//     If you already set spring.cloud.loadbalancer.zone in YAML, omit this.
import org.springframework.cloud.loadbalancer.config.LoadBalancerZoneConfig;

@Bean  // <<< OPTIONAL (code-based client zone)
public LoadBalancerZoneConfig loadBalancerZoneConfig() {
    return new LoadBalancerZoneConfig("eu-west-1a"); // set YOUR client zone here
}

```
I added it in the ApplicationBeanConfiguration.

Next, Backend instances should advertise zone & other metadata in Eureka:
```yml

eureka:
  instance:
    metadata-map:
      zone: eu-west-1a
      weight: "5"       # stronger node
      hint: v2          # for built-in Hint supplier
      version: v2       # for our custom VersionMetadataFilteringSupplier
```

After adding all these cinfigurations above, what do we expect to happen? Answer is:
- when we send any GET request via Postman client and when we add a header:
  X-Version=v1 - this will always connect to the backend-service which is configured to use the v1
  X-Version=v2 - this will always connect to the other backend-service which is configured to use the v2

- when we send any GET request via Postman client and when we add a header:
  X-SC-LB-Hint=v1 - this will always connect to the backend-service which is configured to use the v1
  X-SC-LB-Hint=v2 - this will always connect to the backend-service which is configured to use the v2

The difference between the two headers is this:
- X-SC-LB-Hint — built-in Spring Cloud LoadBalancer hint.
    If your supplier chain includes .withHints() and the outgoing WebClient request carries this header, 
    SCLB prefers instances whose Eureka metadata hint equals the header value (e.g., hint=v2). No custom code needed.
- X-Version — your custom routing header.
  It works because we added VersionMetadataFilteringSupplier, which reads X-Version and prefers instances whose 
Eureka metadata version matches (e.g., version=v2). This is per-service and fully under your control.

What this gives you: header-driven, per-request targeting—perfect for canary/blue-green routing and A/B tests.
With a match, SCLB round-robins within the matched subset; if no match, our custom filter falls back to the full 
set.


4) How to drive it per request (no code changes required)
- Weights: just set metadata.weight on instances.
- Zone affinity: set client zone (bean above) + instance metadata.zone.
- Built-in hint routing: add header X-SC-LB-Hint: v2 on a request; it prefers instances with metadata.hint=v2.
- Custom version routing (our decorator): add header X-Version: v2; it prefers instances with metadata.version=v2.
You can add those headers in your controller or via a small ExchangeFilterFunction if you want it automatic for specific endpoints
But at this point I have not added anything from that point 4).

Let's move on:

5) Nothing else in your code must change
Your existing @LoadBalanced WebClient.Builder continues to work as-is. Because we bound BackendServiceLbConfig 
only to backend-service via @LoadBalancerClient, all calls to http://backend-service/... now go through the custom
chain.


Add this to your application.yml:
```yml
logging:
  level:
    org.springframework.cloud.client.loadbalancer: DEBUG    # this plays general for later to see the debug when testing
    org.springframework.cloud.loadbalancer: DEBUG
    org.springframework.cloud.loadbalancer.core: DEBUG

```
You’ll see logs about chosen instances & filters. 


Since we expect that different headers will always call different backend-services, we have to have also
 backend-services, which are configured to look for the specific headers. 
This configuration we did in the backend-service in the application.yml
like so (above):
```yml
eureka:
  ....
  instance:
    metadata-map:
      zone: "eu-west-1a"
      weight: "5"       # stronger node
      hint: "v2"          # for built-in Hint supplier
      version: "v2"       # for our custom VersionMetadataFilteringSupplier
```
, but this will be applied only to one running instance. But we need two instances with different hint and version values;
To achieve this we start the app on the command line terminal so:
Switch to the other project - backend-service
Open terminal in the project folder.
./gradlew clean
export DB_USERNAME=BlaBla
export DB_PASSWORD=BlaBla
java -jar build/libs/dservice-0.0.1-SNAPSHOT.jar   --server.port=8081   --eureka.instance.metadata-map.version=v1   --eureka.instance.metadata-map.hint=v1
(NB! see the command above sets the version=v1 and hint=v1 for that particular instance.)

, THEN open second separate terminal in the same project backend-service:
Open terminal in the project folder.
./gradlew clean
export DB_USERNAME=BlaBla
export DB_PASSWORD=BlaBla
java -jar build/libs/dservice-0.0.1-SNAPSHOT.jar   --server.port=8079   --eureka.instance.metadata-map.version=v2   --eureka.instance.metadata-map.hint=v2
(NB! see the command above sets the version=v2 and hint=v2 for that particular instance.)

Now we have two instances and we can distinguish between then using differen header values:
version=v2 and hint=v2
and
version=v1 and hint=v1

Now lets use Postman and send GET request to an endpoint. But which endpoint? Well, any endpoint which extracts
the headers from the incoming request would do! In our existing controllers such endpoint we already have:
for example:
```java

@GetMapping("/user-with-data/{id}")
    public Mono<ResponseEntity<UserDbDTO>> getWithData(
            @PathVariable Long id,
            @RequestHeader Map<String, String> headers) //<<---- this plays crucial role !!!
{

        return users.getWithData(id, headers);    
    }
```
Send GET request from Postman like this:
http://localhost:8080/proxy/user-with-data/1
with header: X-Version = v2
Watch the printed console logs: you will always get response from the backend-service which was started with 
the parameters version = v2
Now change the headers from v2 to v1. Now you will always get response from the other instance which was 
started with the parameters version = v1

Next, we want to test the other header: X-SC-LB-Hint - serves the same purpose, but its built-in. No need for me to manually create it.
Send GET reqeut from postman like this:

Add a brand new endpoint in the UserProxyController class just for the testing purpose:
```java
 // ⬇️ NEW: forward Postman headers to the WebClient so LB can use them
    @GetMapping("/user-hinted/{id}")
    public Mono<ResponseEntity<UserDTO>> getByIdHinted(
            @PathVariable Long id,
            @RequestHeader Map<String, String> headers) {
        // you can still set/override headers here if you want:
        // headers.putIfAbsent("X-API-Version", "v1");
        return users.getByIdWithHints(id, headers);
    }

```
Now send the request: http://localhost:8080/proxy/user-hinted/3
with a header: X-SC-LB-Hint = v2
, then with a header: X-SC-LB-Hint = v1

UNFORTUNATELY, this had no effect.  
This header: X-SC-LB-Hint = v2 was returning responses from both instances, randomly. But it was supposed to return response 
only from the instace configured with the v2 value.
Not sure yet, what is the problem there. 
I continued my investigation and it turned out that the problem was in the 'caching' in the class
BackendServiceLbConfig in the method: backendServiceInstanceSupplier , so:
This must be commented out: .withCaching()
```java
ServiceInstanceListSupplier.builder()
                        .withDiscoveryClient()        // pull from Eureka
                        .withZonePreference()         // prefer same-zone instances
                        .withHints()                  // enable X-SC-LB-Hint / metadata: hint
                        .withWeighted()               // use metadata: weight
                        //.withCaching()                // cache list for perf
                        .build(context);
```
Once I have disabled it by commenting it out, the built-in Hint started to work and the LoadBalancer successfully
started to pass the requests with a header 'X-SC-LB-Hint' to the correct backend-service instance.

Works success!!

        Why did this problem happen?
Why it happened: when .withCaching() wraps the hint filter, the first result (often unfiltered) gets cached and 
reused for later requests—so per-request headers like X-SC-LB-Hint can’t change the instance list.

        How to solve the problem, but to keep the caching because we want to have it active!?

Keep caching (for perf) and keep hints working by caching before per-request filters. In other words change the
place when you call the caching - move it up in the method chain call so:
````java
@Bean
    ServiceInstanceListSupplier backendServiceInstanceSupplier(ConfigurableApplicationContext context) {
        // Built-in chain builder
        ServiceInstanceListSupplier base =
                ServiceInstanceListSupplier.builder()
                        .withDiscoveryClient()        // pull from Eureka
                        .withCaching()     //<<---- PUT it up here !!!
                        .withZonePreference()         // prefer same-zone instances
                        .withHints()                  // enable X-SC-LB-Hint / metadata: hint
                        .withWeighted()               // use metadata: weight
                        //.withCaching()     <<---- REMOVE it from here !!!
                        .build(context);

        // OPTIONAL: add our custom metadata filter by key "version" driven by header "X-Version"
        return new VersionMetadataFilteringSupplier(base, "version");

    }
````

Now I tested it and it still works correctly!!!


P.S. tip:
Prod tip: add Caffeine so SCLB uses a real cache (gets rid of the startup warning):
```gradle
implementation 'com.github.ben-manes.caffeine:caffeine'

```





               END of experiment to customize the WebClient -  8. Custom Load-Balancing Rules (Zone/Affinity, Metadata-based Routing)






               START of experiment to customize the WebClient -  9. Circuit-Breaker with Fallback to a Local Stub




        I AM SKIPPING THIS next task. I will implement it later !!!


9. Circuit-Breaker with Fallback to a Local Stub
   Sometimes, instead of throwing an exception when the breaker is open, you want to return a default “fallback” response
   (e.g. cached data, empty user, placeholder).




               END of experiment to customize the WebClient -  9. Circuit-Breaker with Fallback to a Local Stub





               START of experiment to customize the WebClient -  10. Uploading Large Files: Tune Buffer Size / Memory Limits




10. Uploading Large Files: Tune Buffer Size / Memory Limits
    If you need to send or receive large payloads (e.g. >10 MB), the default in-memory buffering may not suffice. You might want to raise
    the max in-memory size or switch to streaming chunks.

The files which are uploaded can be of different size - small, medium and big, e.g. 2TB. Depending on the file size
there can be different file-upload methods suitable.


        Mental model:

- Small bodies (≤ ~10 MB)
  Simple encoders are fine. You can pass byte[], String, or a Resource. Memory impact is modest.

- Medium bodies (~10 MB – few GB)
  Still use Resource or multipart/form-data (if the server expects a form). Avoid accumulating the whole payload; let WebClient stream from disk.

- Huge bodies (tens of GB → TB)
  You must stream from disk (NIO) as a Flux<DataBuffer> and send chunked or with a known Content-Length. Disable/raise write-idle timeouts for this path. Don’t retry POST, and use a bulkhead so uploads don’t starve everything else.

- Codec memory limit (maxInMemorySize)
  This controls how much of a body is buffered for (de)serialization. Keep it low for uploads (responses are small), and use pure streaming for the request body so you never hold the whole file in RAM.


    Code: builder tweaks + three upload strategies

Below I keep existing code/comments and only add what’s new, clearly marked.

1) ApplicationBeanConfiguration – add an “upload” connector, set codec limit, and a helper upload WebClient

Here is the updated content of the ApplicationBeanConfiguration class:
```java
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


}

```
If Builder.clone() isn’t available in your Spring version, construct the uploadWebClient similarly to your main 
builder (re-apply filters and codecs) and add lbFilter manually so http://backend-service still resolves via Eureka.

2) HTTP interface – I already have method for upload there ,but I will create a new one like so:
This one will be used for small file sizes. 
```java
@PostExchange(
        url         = "/upload-small-file",
        contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        accept      = MediaType.APPLICATION_OCTET_STREAM_VALUE
)
Mono<ResponseEntity<Void>> uploadSmallFile(@RequestBody Resource file);
```
Optionally add a multipart form endpoint if your server expects multipart/form-data:
```java
@PostExchange(
    url = "/upload-mp",
    contentType = MediaType.MULTIPART_FORM_DATA_VALUE
)
Mono<ResponseEntity<Void>> uploadMultipart(@RequestPart("file") Resource file);

```
Tip: keeping both gives you flexibility to choose the best strategy per file size.

3) A dedicated service class LargeFileUploadService with three strategies

```java
package reactive.httpwebclientservice.services;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactive.httpwebclientservice.HttpClientInterface;
import reactive.httpwebclientservice.config.DserviceClientProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class LargeFileUploadService {

    private final HttpClientInterface http;           // for small/medium convenience
    private final WebClient uploadClient;             // for huge streaming
    private final WebClient.Builder lbBuilder;        // if you need ad-hoc tweaks
    private final String base;                        // http://backend-service

    public LargeFileUploadService(HttpClientInterface http,
                                  @Qualifier("uploadWebClient") WebClient uploadClient,
                                  WebClient.Builder lbBuilder,
                                  DserviceClientProperties props) {
        this.http = http;
        this.uploadClient = uploadClient;
        this.lbBuilder = lbBuilder;
        this.base = "http://" + props.getServiceId();
    }

    /** Strategy A — small files (≤ ~10 MB): simplest; uses the Http Interface with a Resource. */
    public Mono<ResponseEntity<Void>> uploadSmall(Path path) {
        FileSystemResource res = new FileSystemResource(path);
        return http.uploadSmallFile(res);
    }

    /** Strategy B — medium files (10 MB – multi-GB): multipart/form-data, still streams from disk. */
    public Mono<ResponseEntity<Void>> uploadMultipart(Path path) {
        FileSystemResource res = new FileSystemResource(path);
        return http.uploadMultipart(res);   // <-- use your HttpClientInterface method
    }

    /** Strategy C — HUGE files (hundreds of GB → TB): pure streaming via DataBuffer Flux, chunked or content-length. */
    public Mono<ResponseEntity<Void>> uploadStreaming(Path path) throws IOException {
        // quick sanity check to avoid 500 on missing path
        if (!Files.exists(path)) {
            return Mono.error(new IllegalArgumentException("File not found: " + path));
        }

        int chunkSize = 64 * 1024; // 64 KB; tune if needed

        Flux<DataBuffer> body = DataBufferUtils
                .read(path, new DefaultDataBufferFactory(), chunkSize)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);

        return uploadClient
                .post()
                .uri("/api/v1/upload-large-files")         // RELATIVE uri (baseUrl already set)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // omit Content-Length → Transfer-Encoding: chunked
                .body(BodyInserters.fromDataBuffers(body))
                .retrieve()
                .toBodilessEntity();
    }

}

```
Notes:
- FileSystemResource is already streaming; it does not load the whole file in memory.
- For huge streaming we bypass Jackson entirely—no encoding involved, just raw bytes as DataBuffers.
- If your server requires Content-Length, set it via Files.size(path); otherwise chunked is fine.
- Never keep your general write-idle timeout low for TB uploads—use the upload connector.


4) Controller: let the user caller pick a strategy (for testing)

```java
@RestController
@RequestMapping("/proxy")
public class UploadProxyController {

    private final LargeFileUploadService service;

    public UploadProxyController(LargeFileUploadService service) {
        this.service = service;
    }

    @PostMapping("/upload-small")
    public Mono<ResponseEntity<Void>> upSmall(@RequestParam("path") String path) {
        return service.uploadSmall(Path.of(path));
    }

    @PostMapping("/upload-mp")
    public Mono<ResponseEntity<Void>> upMultipart(@RequestParam("path") String path) {
        return service.uploadMultipart(Path.of(path));
    }

    @PostMapping("/upload-stream")
    public Mono<ResponseEntity<Void>> upStream(@RequestParam("path") String path) throws IOException {
        return service.uploadStreaming(Path.of(path));
    }
}


```
Next, before we send test requests with Postman client, we need to create new files and simulate that they are
with small, medium and very big size:
Create sparse files (Linux):    
Avoid copying/moving them to other locations. It requires special flags when copying.
Best is to delete the files after finish testing.
```bash
# Small ~10 MB (sparse)
truncate -s 10M ~/small.bin

# Medium ~2 GB (sparse)
truncate -s 2G  ~/medium.bin

# Huge ~2 TB (sparse)
truncate -s 2T  ~/huge.bin

```
I created and located such test files in the Documents folder - will provide the absolute path to file.


Verify they don’t take space on the disk:
```bash
ls -lh ~/small.bin ~/medium.bin ~/huge.bin   # logical sizes (10M / 2G / 2T)
du -h  ~/small.bin ~/medium.bin ~/huge.bin   # actual disk usage (should be ~0)

```
Notes:
- Reading a sparse file returns zeros for the “holes”, so your upload will stream zeros—good enough to stress 
    timeouts, throughput, and memory.
- Don’t use fallocate -l for this; it preallocates blocks (consumes space). Stick to truncate/dd seek.
- If you ever copy them, preserve sparseness: cp --sparse=always src dst or rsync -S src dst.


Now you can test with Postman:

Small: POST http://localhost:8080/proxy/upload-small?path=/absolute/path/to/file.bin
For testing with smallFileSizes coming from Reactive stack, the backend service needs to process
which files request properly. NB! backend-service is build on MVC, which is not reactive stack. Therefore
backend and its controller in particular must be adapted to communicate with reactive stack. Create a
controller in the other project - dservice (backend-service) like so:
```java
@RestController
@RequestMapping("/api/v1")
public class UploadController {

    @PostMapping(value = "/upload-small-file", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream();
             OutputStream out = OutputStream.nullOutputStream()) { // <-- discards data
            long bytes = in.transferTo(out);  // streams everything; writes nowhere
            // log if you want: System.out.println("Received bytes = " + bytes);
        }
        return ResponseEntity.accepted().build();
    }
}

```
Now when you send POST http://localhost:8080/proxy/upload-small?path=/absolute/path/to/file.bin 
works with success! The Postman client receives 202 ACCEPTED!

Note:
(In real life, you’d pass the file itself as multipart from Postman; this endpoint accepts a local path just to exercise WebClient behaviors end-to-end.)


Next, test with:
Medium: POST http://localhost:8080/proxy/upload-mp?path=/absolute/path/to/file.bin
The backend-service does not have controller to accept multipart fileuploads, so create one
on the project dservice (backend-service), like so:
```java
/**
 * Multipart receiver for medium-sized uploads.
 * Matches your WebClient interface:
 *
 *   @PostExchange(url="/upload-mp", contentType=MediaType.MULTIPART_FORM_DATA_VALUE)
 *   Mono<ResponseEntity<Void>> uploadMultipart(@RequestPart("file") Resource file);
 *
 * The part name **must** be "file".
 */
@PostMapping(value = "/upload-mp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<Void> uploadMultipart(@RequestPart("file") MultipartFile file) throws IOException {
    // Discard bytes to avoid storing anything (safe for testing)
    try (InputStream in = file.getInputStream();
         OutputStream out = OutputStream.nullOutputStream()) {
        in.transferTo(out);
    }
    return ResponseEntity.accepted().build();
}
```
Now when you send the request via postman it works with success! 202 Accepted is respocnse 
received in Postman client.

Next, test with:
Huge: POST http://localhost:8080/proxy/upload-stream?path=/absolute/path/to/file.bin
Make sure the other project - dservice (backend-service) has this controller:
```java
/**
     * (Optional) Your raw octet-stream endpoint from earlier — kept here for completeness.
     * Streams request body without buffering to disk.
     */
    @PostMapping(value = "/upload-large-files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> uploadOctet(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream();
             OutputStream out = OutputStream.nullOutputStream()) {
            in.transferTo(out);
        }
        return ResponseEntity.accepted().build();
    }

```
It will successfully receive the incoming request. With file sizes > 10G there is no problem,
But when I send filesize 2T - the method successfully started executing this code line here:
```text
in.transferTo(out);
```
but after a while the Postman received reply 503 Service unavailable:
```text
{
    "timestamp": "2025-09-02T10:54:19.143+00:00",
    "status": 503,
    "error": "Service Unavailable",
    "path": "/proxy/upload-stream"
}
```
,and the webclient service logs this on the console:
```text

[HttpWebClientService] [nio-8080-exec-3] .w.s.m.s.DefaultHandlerExceptionResolver : Resolved [org.springframework.web.context.request.async.AsyncRequestTimeoutException]

```
Why does this timeout happen?
I’ve hit the Servlet async timeout on your WebClient app (it’s running on Tomcat / Spring MVC, not Netty).
When a controller method takes longer than the MVC async timeout, Spring throws AsyncRequestTimeoutException,
and the default resolver returns 503—exactly what you saw.
The 2 TB stream is fine on the client side (we already removed write-idle and raised the 
Reactor client timeouts for uploadWebClient). The server side (your WebClient app acting as 
an HTTP server for Postman) is what’s timing out.

Here is how this can be fixed: - BUT I WILL NOT implement any of these fixes now. Its just good to know:

The fixes are to be implemented in the WebClient service (the app receiving Postman’s request).
That’s where the AsyncRequestTimeoutException is thrown by Spring MVC.        

        Fix (programmatic, no YAML):
Add a small MVC config to raise (or disable) the async timeout:
```java
// e.g., in ApplicationBeanConfiguration (or any @Configuration class)
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class MvcAsyncConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Set to a very large value for huge uploads, or 0 for "no timeout".
        configurer.setDefaultTimeout(Duration.ofHours(12).toMillis()); // or 0L for unlimited
        // Optionally choose a dedicated executor:
        // configurer.setTaskExecutor(yourThreadPoolTaskExecutor);
    }
}


```
That controls how long Spring MVC will keep the request “open” while your reactive pipeline is working.


(Optional FIX) Property-based equivalent
If you don’t mind YAML/properties, the same can be done with:
```yml
spring:
  mvc:
    async:
      request-timeout: 12h   # or 0 for no timeout

```
Double-check other timeouts

Postman timeout: ensure it isn’t cutting off the client early.
Reverse proxies (Nginx/Apache) if present: raise proxy_read_timeout/RequestReadTimeout, etc.
Backend service: you already accept raw octet-stream and simply transferTo(out), which is fine.
After raising the MVC async timeout, your /proxy/upload-stream should no longer 503 on multi-hour transfers.
But I have not really tested it!

Open remains the idea to create one URL entry point like: "/upload-all-filesize" which to accept all
possible file sizes and based on the size to call one of the existing methods - for small, for medium
and for huge filesizes.







               END of experiment to customize the WebClient -  10. Uploading Large Files: Tune Buffer Size / Memory Limits





               START of experiment to customize the WebClient - 11. Proxy or Custom SSL (TrustStore) Configuration


This task WILL NOT BE implemented now. Here just a common knowledge base and explanations:

11. Proxy or Custom SSL (TrustStore) Configuration:
    In corporate environments, you sometimes have to route outgoing HTTP calls through an HTTP proxy (say, corporate-proxy:8080).


        Proxy or Custom SSL (TrustStore)
Related but separate concerns:
- HTTP/S Proxy: a middleman your client must go through to reach the internet/intranet (e.g., corporate-proxy:8080). 
For HTTPS, the client usually does a CONNECT tunnel via the proxy. Proxies can also TLS-inspect traffic (MITM), 
in which case they re-sign server certs with a corporate root CA you must trust.
- 
- Custom SSL / TrustStore: controls what CAs/certs your client trusts (and optionally your client cert for mTLS). 
You use this when calling:
1) internal servers with private CAs or self-signed certs, or
2) a TLS-inspecting corporate proxy (you must trust the proxy’s root CA).

- Certbot: a server-side tool to obtain public TLS certs from Let’s Encrypt. It’s not needed to call services with WebClient. You’d use it only if you operate the backend and want a valid public cert.


Here’s a compact mental model that makes TLS and certificates click.

          What TLS is for (the goals)
When your client talks to a server over HTTPS (TLS), it wants three things:

- Confidentiality – nobody can read the traffic.

- Integrity – nobody can tamper with it undetected.

- Authenticity – you’re really talking to the server you intended (and, optionally, the server knows who you are).


        How a TLS handshake works (high level)
1. ClientHello: the client says “I support TLS vX, these cipher suites, SNI=host.com, ALPN=http/1.1 or h2…”.
2. ServerHello + Certificate: server picks algorithms and sends its certificate chain (leaf cert for host.com plus one or more intermediates).
3. Server authentication: client validates the server chain:
   ◦ Each cert is signed by the next CA up to a trusted root in the client’s trust store.
   ◦ The leaf cert’s SAN (Subject Alternative Name) includes the hostname (host.com).
   ◦ Certs are within validity dates and not revoked (OCSP/CRL).
4. Key exchange (e.g., ECDHE) → both derive symmetric keys.
5. (Optional) Client authentication (mTLS): server asks for a client certificate; client sends its cert chain and proves it has the private key.
6. Finished → app data flows encrypted.


        Certificates & PKI in short explanation
• A certificate binds a public key to an identity (a DNS name for servers; a person/service for clients).
• A CA (Certificate Authority) signs certificates. Validation follows the chain: leaf → intermediate(s) → root.
• Clients trust a set of root CAs (OS/JVM trust store). Anything chaining to those is accepted (if hostname/time match).
• Self-signed or private CA certs aren’t trusted by default. You add the CA’s root cert to your trust store to trust them.

        Server certs vs Client certs (mTLS)
• Server certificate: proves the server’s identity to the client (HTTPS norm).
• Client certificate: proves the client’s identity to the server (mutual TLS). Used inside enterprises, zero-trust meshes, etc.


        Truststore vs Keystore (Java terms)
• Truststore = “who I trust” → contains CA/root certificates (public). Used to verify the peer (server, or client in mTLS).
• Keystore = “who I am” → contains your private key + your certificate chain. Used when you must present an identity (server TLS or client mTLS).
Common formats: PKCS#12 (.p12/.pfx), JKS (Java), PEM (Base64). Convert with keytool/openssl.

        Proxies and TLS
• HTTP proxy for HTTPS (CONNECT tunnel): the proxy just tunnels bytes; TLS is end-to-end between client and server. No extra trust needed if server uses a public CA.
• TLS-inspecting proxy (MITM): the proxy terminates TLS and re-issues a cert signed by the corporate root CA. Your client must trust that corporate root (add it to the truststore), or you’ll get cert errors.

        Where Let’s Encrypt / certbot fits
• Certbot is a server-side tool to obtain valid public server certificates via ACME.
• You do not need certbot to call services with WebClient. You use it only if you operate the server and want a public TLS cert.


        Practical mapping to Spring/WebClient
• By default, WebClient (via JVM) trusts public CAs in $JAVA_HOME/lib/security/cacerts.
• If calling an internal service or TLS-inspecting proxy:
    ◦ Put the internal CA root into a truststore and point your client to it.
• If the server requires mTLS:
    ◦ Provide a keystore with your client cert + private key.
• Hostname verification must match the SAN; don’t disable it except in controlled tests.
• For HTTP/2, ALPN is negotiated automatically by modern stacks.

        Common pitfalls & tips
• Expired certs / wrong SAN → handshake fails.
• Forgetting to include the intermediate CA on the server → clients fail to build the chain.
• Mixing up keystore vs truststore.
• Storing secrets (passwords) in code—prefer env/secret manager.
• Use openssl s_client -connect host:443 -servername host.com to debug chains; curl --cacert corp-root.pem https://… to test trust.
That’s the core. Once this model is clear, wiring a custom truststore (who you trust) and/or keystore (who you are) into your WebClient is just configuration.






               END of experiment to customize the WebClient - 11. Proxy or Custom SSL (TrustStore) Configuration






               START of experiment to customize the WebClient -  12. Request/Response Logging (Full Body + Headers)



12. Request/Response Logging (Full Body + Headers)
    While debugging, you often want to log every outgoing request (method, URI, headers, body) and every incoming response
    (status, headers, body). Spring’s ExchangeFilterFunction can do this, without you sprinkling logs in every controller.

logging.level in application.yml only turns up/down categories that already log something.
WebClient (and Spring Cloud LB) don’t log request/response bodies by default, and they log only limited
headers/lines. Cranking log level to DEBUG/TRACE won’t magically add body-logging because that behavior 
isn’t implemented in those components.

We already have Netty wiretap on the upload connector; now let’s add application-level 
request/response logging (headers + body) via an ExchangeFilterFunction. This lets you see pretty 
JSON/text, correlate with your correlation-id, and safely pass the response downstream.

An ExchangeFilterFunction actually adds logging behavior at the WebClient layer.
It intercepts the request/response, can pretty-print JSON, redact secrets, cap body size, sample by 
header, and still pass the body downstream. That capability doesn’t exist out of the box—so you 
implement it as a filter.

Netty wiretap(true) vs filter:
Wiretap logs raw bytes at the TCP level (hex dumps, very noisy, no redaction, no correlation-id 
awareness). The filter logs application-level info (method/URI/headers/body) in a controlled, safe way.

You can still control verbosity with logging.level.
The filter uses a logger; logging.level.your.package.HttpLoggingFilter=INFO/DEBUG lets you toggle it 
without code changes. But the ability to log bodies comes from the filter itself.

Important note! - there is big difference between server-side WebFlux and client-side WebFlux. We are
using WebClient and we use client-side functionality of WebFlux.

Here’s a working, client-side logging filter that compiles with your deps. It logs method/URL/headers 
and the response body (safely truncated), then rebuilds the response so downstream code can still 
consume it. Request-body logging is intentionally skipped (it’s tricky to do generically without server 
APIs); when you need raw request/response bytes, rely on Netty wiretap during debugging.


1) Client logging filter (headers + response body, per-request toggle)

```java

package reactive.httpwebclientservice.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class HttpLoggingFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);

    private static final Set<String> REDACT = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"
    );

    private final int maxBodyBytes;

    public HttpLoggingFilter(int maxBodyBytes) {
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        boolean enabledForThisRequest = isEnabledByHeaderOrQuery(request);
        boolean doLog = enabledForThisRequest || log.isDebugEnabled();

        if (!doLog) {
            // fast path
            return next.exchange(request);
        }

        Logger target = enabledForThisRequest ? LoggerFactory.getLogger("http.trace") : log;

        // ── Request line + headers (no body) ───────────────────────────────────
        target.info("--> {} {}", request.method(), request.url());
        request.headers().forEach((k, v) -> target.info("    {}: {}", k, redact(k, v)));

        long start = System.nanoTime();

        return next.exchange(request)
                .flatMap(resp -> bufferAndLogResponse(resp, request, target, start))
                .onErrorResume(err -> {
                    target.info("<-- network error for {} {}: {}", request.method(), request.url(), err.toString());
                    return Mono.error(err);
                });
    }

    private Mono<ClientResponse> bufferAndLogResponse(ClientResponse resp,
                                                      ClientRequest req,
                                                      Logger target,
                                                      long startNanos) {
        DataBufferFactory factory = new DefaultDataBufferFactory();
        MediaType ct = resp.headers().contentType().orElse(null);

        boolean textual = ct != null && (
                MediaType.APPLICATION_JSON.isCompatibleWith(ct) ||
                        MediaType.TEXT_PLAIN.isCompatibleWith(ct) ||
                        MediaType.APPLICATION_XML.isCompatibleWith(ct) ||
                        MediaType.TEXT_XML.isCompatibleWith(ct) ||
                        ct.getSubtype().endsWith("+json") ||
                        ct.getSubtype().endsWith("+xml")
        );

        Mono<byte[]> bodyBytesMono = textual
                ? resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                : Mono.just(new byte[0]);

        return bodyBytesMono.flatMap(bytes -> {
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            target.info("<-- {} {} ({} ms)", resp.statusCode().value(), req.url(), tookMs);
            resp.headers().asHttpHeaders().forEach((k, v) -> target.info("    {}: {}", k, String.join(",", v)));

            if (textual && bytes.length > 0) {
                String preview = preview(bytes, maxBodyBytes);
                target.info("⤷ body ({} bytes{})\n{}", bytes.length,
                        bytes.length > maxBodyBytes ? ", truncated" : "", preview);
            } else if (!textual) {
                resp.headers().contentLength().ifPresentOrElse(
                        len -> target.info("⤷ [binary body {} bytes; not logged]", len),
                        ()  -> target.info("⤷ [binary body; length unknown; not logged]")
                );
            }

            Flux<DataBuffer> bodyFlux = (textual && bytes.length > 0)
                    ? Flux.just(factory.wrap(bytes))
                    : resp.bodyToFlux(DataBuffer.class); // pass-through if we didn’t buffer

            return Mono.just(resp.mutate().body(bodyFlux).build());
        });
    }

    private static List<String> redact(String key, List<String> vals) {
        return REDACT.contains(key.toLowerCase()) ? List.of("***") : vals;
    }

    private static String preview(byte[] bytes, int limit) {
        int n = Math.min(bytes.length, limit);
        String s = new String(bytes, 0, n, StandardCharsets.UTF_8);
        return bytes.length > limit ? s + "\n…(truncated)" : s;
    }

    private static boolean isEnabledByHeaderOrQuery(ClientRequest req) {
        // Header first
        String hv = req.headers().getFirst("X-Debug-Log");
        if (hv != null && hv.equalsIgnoreCase("true")) return true;

        // Query param fallback
        URI uri = req.url();
        String q = uri.getQuery();
        if (q == null || q.isBlank()) return false;
        // crude parse: X-Debug-Log=true anywhere
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase("X-Debug-Log")
                    && kv[1].equalsIgnoreCase("true")) {
                return true;
            }
        }
        return false;
    }
}




```
Why no request body?
In a generic ExchangeFilterFunction you don’t reliably have the body bytes 
(they can be a one-shot streaming Flux<DataBuffer>). Capturing them risks consuming/duplicating large 
streams (e.g., TB uploads) and breaking backpressure. For full raw bodies, flip on Netty wiretap 
temporarily.


Add once to your existing loadBalancedWebClientBuilder(...):

2) Register it (outer portion of your chain)

```java
// In the ApplicationBeanConfiguration file
var loggingFilter = new HttpLoggingFilter(64 * 1024); // log up to 64 KB of response body

return WebClient.builder()
    .clientConnector(connector)
    .observationRegistry(observationRegistry)
    .observationConvention(webClientObservationConvention)
    .codecs(c -> {
        c.defaultCodecs().jackson2JsonEncoder(encoder);
        c.defaultCodecs().jackson2JsonDecoder(decoder);
        c.defaultCodecs().maxInMemorySize(256 * 1024);
    })
            .filters(list -> {
        // Put logging after request-mutators so you see those headers,
        // but still fairly outer so you can observe retries/resilience.
        list.add(loggingFilter);

        list.add(0, r4jFilter);
        list.add(errorMapping);
        list.add(correlationFilter);
        list.add(authFilter);
        list.add(retryFilter);
    });


```

3) Minimal logging config

```yaml
logging:
  level:
    http.trace: DEBUG    # per-request (X-Debug-Log=true) logs go here
    reactive.httpwebclientservice.filters.HttpLoggingFilter: DEBUG  # optional for always-on
# For raw bytes (both directions), temporarily:
#    reactor.netty.http.client.HttpClient: DEBUG

```

4) How to use / verify
   Normal calls: no extra logs (unless you set the class logger to DEBUG).
Per-request enable: in Postman add either
header: X-Debug-Log: true, or
query: ?X-Debug-Log=true

You’ll now see lines like:
```cpp
--> GET http://backend-service/api/v1/user/5
    Authorization: ***
    X-Correlation-Id: 9dd2...
<-- 200 http://backend-service/api/v1/user/5 (12 ms)
    Content-Type: application/json
⤷ body (512 bytes)
{ ...truncated json... }


```
Bottom line:

With this filter you now have request line + headers and response status + headers + body logging, 
toggleable per-request.

For full request body logging (including big streams), the safe/standard approach is Netty wiretap 
(turn on only when debugging), because application-level duplication of arbitrary reactive bodies is
not safe for large/streaming payloads.

Why we added X-Debug-Log??
It was just a safe-on-by-request demo toggle so you wouldn’t flood logs in prod. It’s convenient 
during manual testing (“turn on verbose logging for this one call”) without changing log levels globally.
The risk:
Because the header is client-controlled, you shouldn’t depend on it for critical observability. 
A “malicious” or simply unaware client won’t send it—so you’d miss logs if you rely on it exclusively.
The preferred place to log it is on the server side.



               END of experiment to customize the WebClient -  12. Request/Response Logging (Full Body + Headers)




               START of experiment to customize the WebClient -  13. Dynamic Base URL Resolution (Non-Eureka Fallback)


13. Dynamic Base URL Resolution (Non-Eureka Fallback)
    You currently use Eureka (serviceId = "backend-service") in your HttpClientInterface. But you might want a fallback to a fixed URL
    if Eureka is down (or if the user configures some base-url in a properties file for testing).

Unfortunately that task had no success, although the implementation was relative simple. Somehow the fallback
did not work. To avoid messing up the order of the filters, I decided not to implement it, because changing
the order of the filters will not signal me if I break some of the older tasks, I already implemented.


               END of experiment to customize the WebClient -  13. Dynamic Base URL Resolution (Non-Eureka Fallback)




               START of experiment to customize the WebClient -  14. Custom Cookie Management



14. Custom Cookie Management
    If your backend sets a session cookie (e.g. Set-Cookie: SESSION=abc123; Path=/; HttpOnly), you may need to send that cookie
    automatically on subsequent calls (sticky session).


Browsers do cookie jar semantics automatically; WebClient does not. The usual pattern is:
• Keep a tiny, thread-safe cookie store (per service).
• A request filter that adds the right Cookie: header for the target call.
• A response filter that captures any Set-Cookie: and remembers it for next time.
Below is a minimal, production-friendly version that respects Secure, Path, Max-Age (incl. delete on 
Max-Age=0), and expiry. It scopes cookies per serviceId host (e.g. backend-service) which works well
behind Spring Cloud LoadBalancer.

1) NEW: sticky cookie store

```java

package reactive.httpwebclientservice.cookies;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Very small in-memory cookie jar, keyed by "service key" (URI host, e.g. backend-service).
 * Handles Path, Secure, Max-Age (incl. delete on zero). Expires cookies in-memory.
 */
public final class StickyCookieStore {

    private static final class StoredCookie {
        final String name;
        volatile String value;
        final String path;   // default "/"
        final boolean secure;
        volatile Instant expiresAt; // null => session cookie (kept until app restarts)

        StoredCookie(String name, String value, String path, boolean secure, Instant expiresAt) {
            this.name = name;
            this.value = value;
            this.path = (path == null || path.isBlank()) ? "/" : path;
            this.secure = secure;
            this.expiresAt = expiresAt;
        }

        boolean expired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    // serviceKey -> (cookieName -> StoredCookie)
    private final Map<String, Map<String, StoredCookie>> jar = new ConcurrentHashMap<>();

    /** Compute a service key. We scope by serviceId host (e.g. "backend-service"). */
    private String keyFor(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** Capture Set-Cookie from a response for the service identified by the request URI. */
    public void rememberFromResponse(URI requestUri, MultiValueMap<String, ResponseCookie> respCookies) {
        if (respCookies == null || respCookies.isEmpty()) return;
        String key = keyFor(requestUri);
        Map<String, StoredCookie> bag = jar.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Instant now = Instant.now();

        respCookies.forEach((name, list) -> {
            for (ResponseCookie rc : list) {
                // Delete if Max-Age = 0
                Duration maxAge = rc.getMaxAge();
                if (maxAge != null && maxAge.isZero()) {
                    bag.remove(name);
                    continue;
                }
                Instant expires = null;
                if (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) {
                    expires = now.plus(maxAge);
                }
                bag.put(name, new StoredCookie(name, rc.getValue(), rc.getPath(), rc.isSecure(), expires));
            }
        });
    }

    /** Add a Cookie header for this request if we have matching, non-expired cookies. */
    public void addCookieHeader(URI requestUri, HttpHeaders headers) {
        String key = keyFor(requestUri);
        Map<String, StoredCookie> bag = jar.get(key);
        if (bag == null || bag.isEmpty()) return;

        String path = requestUri.getPath() == null ? "/" : requestUri.getPath();
        boolean https = "https".equalsIgnoreCase(requestUri.getScheme());
        Instant now = Instant.now();

        // Filter applicable cookies
        List<String> pairs = new ArrayList<>();
        bag.values().removeIf(c -> c.expired(now)); // purge expired
        for (StoredCookie c : bag.values()) {
            if (c.secure && !https) continue;              // respect Secure
            if (!path.startsWith(c.path)) continue;        // respect Path
            pairs.add(c.name + "=" + c.value);
        }
        if (pairs.isEmpty()) return;

        // Merge with any existing Cookie header(s)
        List<String> existing = headers.get(HttpHeaders.COOKIE);
        if (existing != null && !existing.isEmpty()) {
            pairs.add(String.join("; ", existing));
        }
        headers.set(HttpHeaders.COOKIE, String.join("; ", pairs));
    }

    /** For debugging only. */
    @Override public String toString() { return "StickyCookieStore{keys=" + jar.keySet() + "}"; }
}



```

2) NEW: cookie filter

```java

package reactive.httpwebclientservice.filters;

import org.springframework.web.reactive.function.client.*;
import reactive.httpwebclientservice.cookies.StickyCookieStore;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Adds cookies before the call; captures Set-Cookie after the call. */
public final class CookieExchangeFilter implements ExchangeFilterFunction {

    private final StickyCookieStore store;

    public CookieExchangeFilter(StickyCookieStore store) {
        this.store = store;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        URI uri = request.url();

        // 1) add Cookie header for this URI
        ClientRequest mutated = ClientRequest.from(request)
                .headers(h -> store.addCookieHeader(uri, h))
                .build();

        // 2) capture Set-Cookie from the response
        return next.exchange(mutated)
                .doOnNext(resp -> store.rememberFromResponse(uri, resp.cookies()));
    }
}


```

3) Wire it into your existing builder (per-client, not global)

In ApplicationBeanConfiguration add a bean and include it in .filters(...) before retry 
(so retries also carry cookies) and after your other request mutators (so your logging sees them 
if you want):
```java

// NEW: a singleton cookie store (per application)
@Bean
public reactive.httpwebclientservice.cookies.StickyCookieStore stickyCookieStore() {
    return new reactive.httpwebclientservice.cookies.StickyCookieStore();
}


```

And inside your loadBalancedWebClientBuilder(...).filters(list -> { ... }):


```java

// NEW
var cookieFilter = new reactive.httpwebclientservice.filters.CookieExchangeFilter(stickyCookieStore());

// Suggested order (outer → inner):
list.add(loggingFilter);          // you already have this
list.add(0, r4jFilter);           // circuit breaker outermost
list.add(errorMapping);
list.add(correlationFilter);
list.add(authFilter);

// ⬇️ add cookies here so mutations above are already applied;
// and retries below will include cookies on each attempt
list.add(cookieFilter);

list.add(retryFilter);



```


No other code needs to change. Your upload client built from the @LoadBalanced builder will 
inherit this filter too.


4) How to test quickly
   In backend-service, add a tiny endpoint that sets a session cookie, then requires it:

```java
@RestController
@RequestMapping("/api/v1")
class CookieDemoController {

  @GetMapping("/cookie/set")
  ResponseEntity<String> set() {
    return ResponseEntity.ok()
        .header("Set-Cookie", "SESSION=abc123; Path=/; HttpOnly") // demo cookie
        .body("cookie set");
  }

  @GetMapping("/cookie/need")
  ResponseEntity<String> need(@CookieValue(name="SESSION", required=false) String session) {
    if (session == null) return ResponseEntity.status(401).body("no cookie");
    return ResponseEntity.ok("have " + session);
  }
}


```
Add endpoints to your HttpClientInterface
```java

@HttpExchange(url = "/api/v1", accept = MediaType.APPLICATION_JSON_VALUE)
public interface HttpClientInterface {

    // ...existing methods...

    @GetExchange("/cookie/set")
    Mono<ResponseEntity<String>> cookieSet();

    @GetExchange("/cookie/need")
    Mono<ResponseEntity<String>> cookieNeed();
}


```
Add proxy endpoints to your controller (e.g., UserProxyController)

```java
@RestController
@RequestMapping("/proxy")
public class UserProxyController {

    private final HttpClientInterface users;

    public UserProxyController(HttpClientInterface users) {
        this.users = users;
    }

    // ...existing endpoints...

    @GetMapping("/cookie/set")
    public Mono<ResponseEntity<String>> proxyCookieSet() {
        return users.cookieSet();
    }

    @GetMapping("/cookie/need")
    public Mono<ResponseEntity<String>> proxyCookieNeed() {
        return users.cookieNeed();
    }
}



```


Now you can test from Postman against the WebClient service:
From Postman:
GET http://localhost:8080/proxy/cookie/set → stores the cookie captured from backend response.
GET http://localhost:8080/proxy/cookie/need → Cookie: SESSION=... is automatically attached by the 
cookie filter; should return 200.
Use your existing proxy controller methods to route to those new endpoints, or just call them via your HttpClientInterface temporarily.)

And it was tests with success!!!


                END of experiment to customize the WebClient -  14. Custom Cookie Management





                START of experiment to customize the WebClient -  15. Defining Custom Error Handling Strategies by Status Family



This task is not implemented, because Custom Error Handling Strategies was implemented in Task: 4. Custom Error Decoding & Mapping to Exceptions
Therefore, skipping this task below:

15. Defining Custom Error Handling Strategies by Status Family
    Maybe you want to treat all 4xx as “client failures” but still parse the body, while all 5xx should throw an exception immediately
    (and never convert into a DTO).




                END of experiment to customize the WebClient -  15. Defining Custom Error Handling Strategies by Status Family




                START of experiment to customize the WebClient -  16. Custom DNS Resolution / Hostname Verification

This task will not be implemented, because it is not really that critical. But below is a short explanation in which
cases such custom Custom DNS Resolution can become handy.

16. Custom DNS Resolution / Hostname Verification
    If you need to bypass DNS resolution (e.g. to hardcode an IP → hostname mapping for testing), or if you need to skip hostname
    verification (for internal certs).


This topic mixes three layers that often get conflated: service discovery, DNS resolution, and TLS hostname verification. Here’s the clean mental model.
The normal path (who does what?)
1. Your code (WebClient)
• Builds a request to http://backend-service/... (a service id, not a DNS hostname).
2. Service discovery / load balancing (Spring Cloud LoadBalancer + Eureka)
• Looks up backend-service in Eureka, gets a list of instances (each has host + port + metadata).
• Picks one instance (round-robin, zone pref, your custom filters, etc.).
• Produces a concrete target like http://hostA.company.local:8081.
3. DNS resolution (OS/JVM/Netty)
• If hostA.company.local is a hostname, Reactor Netty asks the DNS resolver to turn it into an IP.
◦ If Eureka already returned an IP (many setups do), DNS is not used for that call.
◦ If it returned a hostname, DNS is used.
4. TLS (if https)
• The client performs a TLS handshake.
• Hostname verification checks that the cert’s CN/SAN matches the hostname you connected to, and SNI is set accordingly.
Why might you bypass/override DNS?
• Testing/debugging: Pin api.company.local to a specific IP (blue/green canary, reproduce a bug on a node).
• Private networks / split-horizon DNS: Your client can’t reach the corp DNS, so you point to specific DNS servers or hardcode.
• Eureka returns hostnames, but you want to force v4 / a particular resolver.
• Temporary workarounds while DNS is being updated/propagated.
Ways to do it (from blunt to precise):
• OS-level: /etc/hosts entry → affects the whole machine (quick + universal, but global).
• JVM/Netty-level: Provide a custom AddressResolverGroup to Reactor Netty (map certain names to specific IPs, or use custom DNS servers).
• Avoid DNS entirely: Make Eureka return IPs instead of hostnames (common in container/K8s setups).
How does this relate to Eureka?
• Eureka is not DNS. It’s an application-level registry returning service instances (host/port).
• If those hosts are IPs, you don’t use DNS for that hop.
• If they are names, DNS is still needed after discovery.
Hostname verification (TLS)
• Purpose: ensure you’re talking to the intended server (mitigates MITM).
• It compares the requested hostname to the certificate’s CN/SAN.
• If you connect by IP address, the certificate must include that IP in SAN, or hostname verification fails.
• Skipping hostname verification is unsafe; only do it for local/dev, and document it clearly. Better:
◦ Install your internal CA into a truststore and configure the client to use it (keeps verification on).
◦ Or issue certs whose SAN includes the hostname/IP you actually connect to.
When would you disable hostname verification?
• Dev-only, with self-signed or mismatched certs, to unblock flow while you fix PKI.
• Even then, prefer to trust the right CA instead of disabling verification altogether.
Certbot?
• Certbot obtains public TLS certs from Let’s Encrypt for publicly reachable domains.
• In corporate/private networks you usually don’t use Certbot; you use:
◦ Corporate CA,
◦ or self-signed CA and distribute its root to clients,
◦ or your platform’s cert manager (Kubernetes cert-manager, etc.).





                END of experiment to customize the WebClient -  16. Custom DNS Resolution / Hostname Verification






                START of experiment to customize the WebClient -  17. Conditional Logic Based on Request Path or Headers




17. Conditional Logic Based on Request Path or Headers
    Suppose you want different behavior when calling /user/{id} vs /user-with-data/{id}. For instance, maybe calls to /user-with-data/…
    must carry an extra header like X-Internal-Auth: secret, whereas /user/… should not.


Task 17 uses the same mechanism as Task 3 (an ExchangeFilterFunction) but adds routing logic so the filter
mutates headers only for certain paths / conditions. Below is a drop-in filter that does exactly that, plus the 
tiny builder tweak.

A) Route-aware header filter (NEW)
```java

package reactive.httpwebclientservice.filters;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.Function;

public class RouteAwareHeaderFilter implements ExchangeFilterFunction {

    /**
     * Optional: allow overriding the internal auth value via Reactor Context key "internalAuth".
     * If not found, fall back to this defaultSupplier.
     */
    private final Function<ClientRequest, String> defaultInternalAuthSupplier;

    public static final String CTX_INTERNAL_AUTH = "internalAuth";           // <- context key
    public static final String HDR_INTERNAL_AUTH = "X-Internal-Auth";        // <- header we add
    public static final String HDR_PREVIEW_FLAG  = "X-Use-Preview";          // <- if present, bump API version
    public static final String HDR_API_VERSION   = "X-API-Version";

    public RouteAwareHeaderFilter(Function<ClientRequest, String> defaultInternalAuthSupplier) {
        this.defaultInternalAuthSupplier = defaultInternalAuthSupplier;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        final URI uri = request.url();
        final String path = uri.getPath(); // e.g. "/api/v1/user-with-data/123"

        return Mono.deferContextual(ctxView -> {
            ClientRequest.Builder builder = ClientRequest.from(request);

            // 1) If caller sent X-Use-Preview, force an API version header (example of header→header logic).
            if (request.headers().containsKey(HDR_PREVIEW_FLAG)) {
                builder.headers(h -> {
                    if (!StringUtils.hasText(h.getFirst(HDR_API_VERSION))) {
                        h.set(HDR_API_VERSION, "preview");
                    }
                });
            }

            // 2) Path-based rule: for /user-with-data/** add internal auth; for /user/** ensure it’s absent.
            if (path.startsWith("/api/v1/user-with-data/")) {
                // Resolve internal auth value: prefer Reactor Context, else fallback supplier
                String internalAuth = ctxView.hasKey(CTX_INTERNAL_AUTH)
                        ? ctxView.get(CTX_INTERNAL_AUTH)
                        : defaultInternalAuthSupplier.apply(request);

                if (internalAuth != null && !internalAuth.isBlank()) {
                    builder.headers(h -> h.set(HDR_INTERNAL_AUTH, internalAuth));
                }
            } else if (path.startsWith("/api/v1/user/")) {
                // Make sure we don't leak the internal header on the simple user endpoint
                builder.headers(h -> h.remove(HDR_INTERNAL_AUTH));
            }

            // 3) (Optional) Normalize/guard other headers here
            builder.headers(h -> ensureNoEmptyValues(h));

            return next.exchange(builder.build());
        });
    }

    private static void ensureNoEmptyValues(HttpHeaders h) {
        // tiny safety: drop empty preview flag
        if (h.containsKey(HDR_PREVIEW_FLAG) && (h.getFirst(HDR_PREVIEW_FLAG) == null || h.getFirst(HDR_PREVIEW_FLAG).isBlank())) {
            h.remove(HDR_PREVIEW_FLAG);
        }
    }
}



```
How it works
• Looks at request.url().getPath() and applies headers only for matching routes.
• Optionally reads internalAuth from the Reactor Context (seeded per request), else uses a default supplier.

B) Wire it into your existing builder (one line)
In your ApplicationBeanConfiguration.loadBalancedWebClientBuilder(...), where you add request-mutating filters 
(correlation/auth) before retry, add the new filter alongside them:

```java
// add this near your other filter instantiations
var routeAwareFilter = new RouteAwareHeaderFilter(req -> "secret-default-token"); // or pull from props

// ...

.filters(list -> {
    // logging (if you keep it), then OUTERMOST resilience (as you have)
    list.add(loggingFilter);
    list.add(0, r4jFilter);

    // error mapping
    list.add(errorMapping);

    // request-mutating filters should run BEFORE retry so each retry includes headers
    list.add(correlationFilter);
    list.add(authFilter);
    list.add(routeAwareFilter);   // <— NEW: conditional header logic lives with other mutators

    // retry last (inner)
    list.add(retryFilter);
});


```
That’s it. 
Calls to /api/v1/user-with-data/** will carry X-Internal-Auth, calls to /api/v1/user/** won’t.
BUT after the test, I still dont see the newlly added header. Why?

X-Internal-Auth is a request header of the WebClient. Postman shows response headers, so you 
won’t see it there unless your backend echoes it back.
Verify it is in the WebClient’s request logs. - BUT ITS NOT THERE!? WHY? Because out logging 
filter is triggered before the outgoing request is mutated by adding the new header. So we need
to log also right after the outgoing request is mutated. How to achieve this?
Simply add a second filter a little bit lower in the list, like so:
```java
                    list.add(routeAwareFilter);  
                    list.add(loggingFilter); // SECOND time added same logging filter, to ensure any mutated requests are alo logged
                    // INNER
                    list.add(retryFilter);
                });

```

NB!! Adding logging filter twice is very bad idea, because later it will break other functionality.
Add it twice only for testing or debuggnig purposes!
Later for other tasks I will remove one of the logging filters.

Now when postman client sends this:
GET http://localhost:8080/proxy/user-with-data/2?X-Debug-Log=true (note: the parameter ?X-Debug-Log=true does not play any role here )
, this is logged:
```cpp
 --> GET http://backend-service/api/v1/user-with-data/2
Accept: [application/json, */*]
user-agent: [PostmanRuntime/7.46.0]
postman-token: [e617c4ec-647d-4f8d-a4c3-852abd0835dd]
host: [localhost:8080]
accept-encoding: [gzip, deflate, br]
connection: [keep-alive]
X-Correlation-Id: [14965d50-5106-4700-9c16-01a42a91cf1a]
Authorization: [***]
X-Internal-Auth: [secret-default-token]         <<<----- THIS IS OUR CUSTOM HEADER
```
but when postman sends request to that path:
GET http://localhost:8080/proxy/user/5?X-Debug-Log=true (note: the parameter ?X-Debug-Log=true does not play any role here )
, there is no such X-Internal-Auth: header in the outgoing request of the WebClient.





                END of experiment to customize the WebClient -  17. Conditional Logic Based on Request Path or Headers






                START of experiment to customize the WebClient -  18. Capturing Response Cookies and Propagating Them




18. Capturing Response Cookies and Propagating Them
    If your backend returns Set-Cookie: SESSION=xyz on one call, you may want to store and reuse that in subsequent calls
    (similar to “sticky sessions” in #11 but here perhaps for a different domain).

The Backend-service currently has few Endpoints which really return such Set-Cookie header , like so:
```java

@RestController
@RequestMapping("/api/v1")
class CookieDemoController {

    @GetMapping("/cookie/set")
    ResponseEntity<String> set() {
        return ResponseEntity.ok()
                .header("Set-Cookie", "SESSION=abc123; Path=/; HttpOnly") // demo cookie
                .body("cookie set");
    }

    @GetMapping("/cookie/need")
    ResponseEntity<String> need(@CookieValue(name="SESSION", required=false) String session) {
        if (session == null) return ResponseEntity.status(401).body("no cookie");
        return ResponseEntity.ok("have " + session);
    }
}

```
As you see the backend-service (as in here above shown) now has cookie endpoints.

Here’s exactly how to capture Set-Cookie on one call and automatically send the cookie on 
later calls from your WebClient app.
Below I keep your existing code and add new classes/beans, clearly marked with // NEW (Task 18)
comments, and show where to wire the filter in.


1) Add a small cookie jar + filter (WebClient app)

InMemoryCookieJar.java — NEW (Task 18)
```java
package reactive.httpwebclientservice.cookies;

import org.springframework.http.ResponseCookie;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Very simple in-memory cookie jar that stores cookies per host+path.
 * - Domain rule: if Set-Cookie has Domain=X, we do suffix match; else host-only.
 * - Path rule: requestPath startsWith(cookiePath, default "/").
 * - Expiry: Max-Age=0 removes; positive Max-Age sets expiresAt; negative => session cookie.
 */
public final class InMemoryCookieJar {

    private static final class StoredCookie {
        final String name;
        volatile String value;
        final String domain;   // nullable
        final String path;     // never null; default "/"
        final boolean secure;
        volatile Instant expiresAt; // nullable => session cookie

        StoredCookie(ResponseCookie rc, Instant now) {
            this.name = rc.getName();
            this.value = rc.getValue();
            this.domain = emptyToNull(rc.getDomain());
            this.path = (rc.getPath() == null || rc.getPath().isBlank()) ? "/" : rc.getPath();
            this.secure = rc.isSecure();
            if (rc.getMaxAge() != null) {
                long seconds = rc.getMaxAge().getSeconds();
                if (seconds == 0) {
                    this.expiresAt = Instant.EPOCH; // use as deletion marker
                } else if (seconds > 0) {
                    this.expiresAt = now.plusSeconds(seconds);
                } else {
                    this.expiresAt = null; // session cookie
                }
            } else {
                this.expiresAt = null; // session cookie
            }
        }

        boolean expired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }

        private static String emptyToNull(String s) {
            return (s == null || s.isBlank()) ? null : s;
        }
    }

    // host -> cookies (we keep all and match by path/expiry each request)
    private final Map<String, List<StoredCookie>> store = new ConcurrentHashMap<>();

    public void saveFrom(URI origin, Collection<ResponseCookie> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return;
        String host = origin.getHost();
        Instant now = Instant.now();
        store.compute(host, (h, list) -> {
            if (list == null) list = new ArrayList<>();
            for (ResponseCookie rc : setCookies) {
                StoredCookie sc = new StoredCookie(rc, now);
                // deletion?
                if (sc.expiresAt != null && sc.expiresAt.equals(Instant.EPOCH)) {
                    // remove by name+path(+domain match)
                    list.removeIf(old -> namesMatch(old, sc) && domainEq(old, sc) && pathEq(old, sc));
                    continue;
                }
                // upsert by name+path(+domain)
                boolean updated = false;
                for (StoredCookie old : list) {
                    if (namesMatch(old, sc) && domainEq(old, sc) && pathEq(old, sc)) {
                        old.value = sc.value;
                        old.expiresAt = sc.expiresAt;
                        updated = true;
                        break;
                    }
                }
                if (!updated) list.add(sc);
            }
            // cleanup expired
            list.removeIf(c -> c.expired(now));
            return list;
        });
    }

    public Map<String, String> cookiesFor(URI requestUri, boolean requireSecure) {
        String host = requestUri.getHost();
        String path = requestUri.getPath();
        if (path == null || path.isBlank()) path = "/";
        boolean isHttps = "https".equalsIgnoreCase(requestUri.getScheme());

        Instant now = Instant.now();
        List<StoredCookie> list = store.get(host);
        if (list == null) return Collections.emptyMap();

        Map<String, String> out = new LinkedHashMap<>();
        for (StoredCookie c : list) {
            if (c.expired(now)) continue;
            // domain
            if (c.domain != null) {
                // suffix match (host ends with domain & not a partial label)
                if (!domainMatches(host, c.domain)) continue;
            } // else host-only, already keyed by host

            // path
            if (!path.startsWith(c.path)) continue;

            // secure
            if (c.secure && !(isHttps)) continue;
            if (requireSecure && !c.secure) continue;

            out.put(c.name, c.value);
        }
        return out;
    }

    private static boolean namesMatch(StoredCookie a, StoredCookie b) {
        return Objects.equals(a.name, b.name);
    }

    private static boolean domainEq(StoredCookie a, StoredCookie b) {
        return Objects.equals(a.domain, b.domain);
    }

    private static boolean pathEq(StoredCookie a, StoredCookie b) {
        return Objects.equals(a.path, b.path);
    }

    private static boolean domainMatches(String host, String domain) {
        String h = host.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        if (h.equals(d)) return true;
        return h.endsWith("." + d);
    }
}



```


CookieFilter.java — NEW (Task 18)

```java

package reactive.httpwebclientservice.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactive.httpwebclientservice.cookies.InMemoryCookieJar;

import java.net.URI;
import java.util.*;

public class CookieFilter implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(CookieFilter.class);
    private final InMemoryCookieJar jar;
    private final boolean logCookies; // toggle debug logs

    public CookieFilter(InMemoryCookieJar jar, boolean logCookies) {
        this.jar = jar;
        this.logCookies = logCookies;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // 1) OUTBOUND: inject cookies for this URI
        URI uri = request.url();
        Map<String, String> toSend = jar.cookiesFor(uri, false);
        ClientRequest mutated = (toSend.isEmpty())
                ? request
                : ClientRequest.from(request).cookies(c -> {
                    toSend.forEach(c::add); // adds as Cookie: name=value ...
                }).build();

        if (logCookies && !toSend.isEmpty()) {
            log.debug("CookieFilter → sending cookies to {}: {}", uri, toSend.keySet());
        }

        // 2) INBOUND: capture Set-Cookie from response and store
        return next.exchange(mutated).doOnSuccess(resp -> {
            MultiValueMap<String, ResponseCookie> cookies = resp.cookies(); // parsed cookies
            if (cookies != null && !cookies.isEmpty()) {
                List<ResponseCookie> all = new ArrayList<>();
                cookies.values().forEach(all::addAll);
                jar.saveFrom(uri, all);
                if (logCookies) {
                    log.debug("CookieFilter ← stored cookies from {}: {}", uri, all.stream().map(ResponseCookie::getName).toList());
                }
            } else {
                // Some servers only send raw header; still covered by resp.cookies()
                List<String> raw = resp.headers().asHttpHeaders().get(HttpHeaders.SET_COOKIE);
                if (raw != null && !raw.isEmpty() && logCookies) {
                    log.debug("CookieFilter ← received raw Set-Cookie from {}: {}", uri, raw);
                }
            }
        });
    }
}

```


2) Wire the cookie filter into your existing WebClient builder
   In your WebClient app ApplicationBeanConfiguration, add a cookie jar bean, create the 
filter, and add it alongside your other request-mutating filters (so it runs before retry
and before the post-logger if you want to see the cookies in logs).
Update the ApplicationBeanConfiguration file with the single lines as shown here:
```java
// ... imports omitted for brevity
import reactive.httpwebclientservice.cookies.InMemoryCookieJar;
import reactive.httpwebclientservice.filters.CookieFilter;

@Configuration
public class ApplicationBeanConfiguration {

    // ... your existing fields/beans unchanged

    // NEW (Task 18): a singleton, in-memory cookie jar
    @Bean
    public InMemoryCookieJar inMemoryCookieJar() {
        return new InMemoryCookieJar();
    }

    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder(
            @Qualifier("defaultConnector") ReactorClientHttpConnector connector,
            Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder,
            ObservationRegistry observationRegistry,
            ClientRequestObservationConvention webClientObservationConvention,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            InMemoryCookieJar cookieJar // <-- NEW (Task 18)
    ) {
        // ... your existing ObjectMapper / encoder / decoder setup unchanged

        var retryFilter       = new RetryBackoffFilter(2, Duration.ofSeconds(1), Duration.ofSeconds(1), 0.0);
        var errorMapping      = new ErrorMappingFilter();
        var correlationFilter = new CorrelationHeaderFilter();
        var authFilter        = new AuthHeaderFilter(props::getAuthToken);
        var r4jFilter         = new Resilience4jFilter(circuitBreakerRegistry, bulkheadRegistry, req -> props.getServiceId());
        var loggingFilter     = new HttpLoggingFilter(64 * 1024);

        // NEW (Task 18): cookie filter (set logCookies=true if you want to see its debug lines)
        var cookieJarFilter      = new CookieFilter(cookieJar, true);

        return WebClient.builder()
                .clientConnector(connector)
                .observationRegistry(observationRegistry)
                .observationConvention(webClientObservationConvention)
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonEncoder(encoder);
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    c.defaultCodecs().maxInMemorySize(256 * 1024);
                })
                .filters(list -> {
                    // PRE logger (if you keep two) — optional
                    // list.add(preLogger);

                    // Resilience outer
                    list.add(0, r4jFilter);

                    // Request mutators (headers/cookies) BEFORE retry so retries carry them
                    list.add(correlationFilter);
                    list.add(authFilter);
                    list.add(cookieJarFilter); // <-- NEW (Task 18)

                    // POST logger to show final outbound headers (including Cookie)
                    list.add(loggingFilter);

                    // inner bits
                    list.add(retryFilter);
                    list.add(errorMapping);
                });
    }

    // ... rest unchanged (userHttpInterface, uploadWebClient, etc.)
}


```

3) Add 2 proxy endpoints in WebClient to drive the demo
   Extend your HTTP interface — add these methods. But the below example is already present
in the project.
```java

@HttpExchange(url = "/api/v1", accept = MediaType.APPLICATION_JSON_VALUE)
public interface HttpClientInterface {

    // ... your existing methods

    // NEW (Task 18)
    @GetExchange("/cookie/set")
    Mono<ResponseEntity<String>> cookieSet();

    @GetExchange("/cookie/need")
    Mono<ResponseEntity<String>> cookieNeed();
}


```


Add simple proxy methods in your controller (WebClient app)
In whichever controller you’re already using (e.g., UserProxyController), so, 
but luckily the below endpoints are already present in the project:
```java
// NEW (Task 18): drive cookie capture
    @GetMapping("/cookie/set")
    public Mono<ResponseEntity<String>> proxyCookieSet() {
        return users.cookieSet();
    }

    // NEW (Task 18): should reuse the stored cookie automatically
    @GetMapping("/cookie/need")
    public Mono<ResponseEntity<String>> proxyCookieNeed() {
        return users.cookieNeed();
    }

```

4) Test
    1. Call through the WebClient app:
       ◦ GET http://localhost:8080/proxy/cookie/set
       ▪ Backend returns Set-Cookie: SESSION=abc123; Path=/; HttpOnly
       ▪ CookieFilter stores it (you’ll see CookieFilter ← stored cookies… if DEBUG enabled).
    2. Next call:
       ◦ GET http://localhost:8080/proxy/cookie/need
       ▪ CookieFilter injects Cookie: SESSION=abc123 on the outbound request automatically.
       ▪ Backend returns 200 "have abc123".
       Your existing HttpLoggingFilter will also show the outgoing Cookie: header on the second call.
       If you want to reduce noise in prod, set new CookieFilter(cookieJar, false) (but the cookie still works).

Tested with Success!!

But if the Postman client first sends GET request to the: /proxy/cookie/need (which asks for cookie, but its not set yet)
instead of first sending GET to: /proxy/cookie/set (to actually set the cookie)
, in such cases the response is:
```cpp
<-- 401 http://backend-service/api/v1/cookie/need (546 ms)
   Content-Type: application/json
  Content-Length: 9
  Date: Sat, 13 Sep 2025 15:33:22 GMT
⤷ body (9 bytes)

no cookie

network error for GET http://backend-service/api/v1/cookie/need: reactive.httpwebclientservice.exceptions.UnauthorizedException:Unauthorized
```

Notes:
• If your backend starts returning Max-Age=0 (delete), the jar will remove it. This functionality is not implemented yet.

• The backend demo cookie doesn’t set Domain, so it’s a host-only cookie. The jar stores it 
against whatever host your WebClient sees (backend-service), which is correct for LB/Eureka calls.

• This is a simple memory-only jar. For multi-instance WebClient apps, you might store cookies per-user or per-tenant; in that case, use a scoped jar (e.g., keyed by session id) or a persistent store.
That’s it—now your WebClient captures Set-Cookie and propagates cookies automatically on subsequent requests.




                END of experiment to customize the WebClient -  18. Capturing Response Cookies and Propagating Them




                START of experiment to customize the WebClient -  19. Bulkhead (Thread Pool) Isolation


This task is really important because it improves the performace in case of problematic (big in size)
work loads. However, I will not implemet it now in the project. Here is just a short explanation
of the problem and the benefits:

19. Bulkhead (Thread Pool) Isolation
    If your HTTP calls are expensive (e.g. large payload, slow DB), you may not want them to exhaust your main reactive event loops.
    You can isolate them in a dedicated thread pool (“bulkhead”) so that a spike in these calls doesn’t starve CPU for other traffic.


“Bulkhead” is a resilience pattern that keeps one slow/expensive thing from sinking the whole ship—like watertight compartments on a boat.
Here’s the mental model:

• #1 Event-loop threads are precious
WebClient + Reactor Netty run on a small pool of non-blocking event-loop threads. If you block them (slow DNS, big JSON write, file/db calls, TLS handshake hiccups), all requests stall. So you must prevent blocking work from living on those threads.

• #2 Two bulkhead flavors (different tools/purposes)
◦ Semaphore bulkhead (what you already wired with resilience4j-bulkhead):
Limits how many calls can run concurrently (e.g., max 50). Extra calls fail fast or wait a tiny bit. It does not move work to other threads; it just caps concurrency. Super low overhead; still runs on the same scheduler.
◦ Thread-pool bulkhead (Resilience4j’s thread-pool-bulkhead or Reactor schedulers):
Offloads the work to a separate, bounded thread pool with its own queue. Spikes or slow I/O are contained to that pool. If the pool is full, you get fast failure rather than starving event loops.

• #3 When to use which
◦ Your WebClient flow is fully non-blocking and you only want to prevent overload → Semaphore bulkhead is ideal (what you have).
◦ You have any blocking sections (filesystem, JDBC, gRPC stub that blocks, huge serialization, legacy libs), or a dependency that can stall unpredictably → Thread-pool bulkhead to isolate that latency and protect the rest.

• #4 How this plays with Reactor
◦ Reactor rule: don’t block event loops. If something blocks, use publishOn/subscribeOn with a dedicated bounded scheduler (or Resilience4j thread-pool bulkhead operator). That moves that segment off the event loop.
◦ You can combine both: thread-pool bulkhead to isolate + semaphore bulkhead to cap concurrency upstream.

• #5 Trade-offs
◦ Thread-pool bulkhead adds context switches and queueing—tiny latency overhead, but strong isolation.
◦ Semaphore bulkhead is lighter/faster but won’t help if the code inside is blocking the same threads.

• #6 Observability/limits
◦ Both types emit Micrometer metrics (successful/failed calls, queue usage, rejections).
◦ Set timeouts alongside bulkheads so queued work doesn’t wait forever.

In short: you already have concurrency capping (semaphore). “Thread Pool Isolation” is the next step when you need to offload slow or blocking parts to their own small, bounded pool so bursts or slowness can’t freeze the whole app.



                END of experiment to customize the WebClient -  19. Bulkhead (Thread Pool) Isolation






                START of experiment to customize the WebClient -  20. Custom SSL Pinning (Pin a Specific Certificate Fingerprint)



I am not using any server side certificates, so I will skip this task for now:

20. Custom SSL Pinning (Pin a Specific Certificate Fingerprint)
    For maximum security, you might want to verify that the server’s certificate matches a known fingerprint (public-key pinning),
    not just that it’s signed by the CA in your trust store.




                END of experiment to customize the WebClient -  20. Custom SSL Pinning (Pin a Specific Certificate Fingerprint)





                START of experiment to customize the WebClient -  21. Customizing HTTP/2 or HTTP/1.1 Features



21. Customizing HTTP/2 or HTTP/1.1 Features
    You might want to force HTTP/2 (for multiplexing) or explicitly disable HTTP/2 if your server doesn’t support it (and your client
    negotiates it automatically). You can also tweak “keep-alive” settings.


To understand what multiplexing is, lets give example with a browser loading an html page. First, the
browser sends one single HTTP GET request. It is handled via one TCP connection, which opens a port.
The response contains html page, with <link> url, <style> resources urls, etc. The browser parses them
and will start executing them - meaning will start sending request for each <link> or css to fetch it.
If the remote server supports only HTTTP version 1 protocol, then each fetch GET request for the
<link> will generate one TCP connection, which will open a port - these open ports mean more work
for the core to process data. The browser can send many GET request simultaneously, but each request
will open its own TCP connection and port. Yes - they are concurrent (simultaneous)  but it is
not yet multiplexing!
What is multiplexing and when is it possible!? 
Multiplexing is possible only with HTTP protocol version 2. The browser opens only one TCP connection
with one open port, but over that TCP connection flow concurrently (simultaneously) more than one
HTTP Get Requests. That is multiplexing. It reduces the number of open ports which reduces the work load
for the processing core.


Here’s a clean, per-client way to control HTTP/2 vs HTTP/1.1 (including h2c), 
plus keep-alive/connection-pool knobs—without disturbing the rest of your app. I’ll keep your
original code and clearly mark only the new bits.

1) Add per-client HTTP options to your properties
The new code is marked with a comment:
```java
// ─────────────────────────────────────────────────────────────────────────────
// DserviceClientProperties  (ADD NEW FIELDS + NESTED TYPES)
// ─────────────────────────────────────────────────────────────────────────────
@Component
@ConfigurationProperties(prefix = "dservice")
public class DserviceClientProperties {

    private String baseUrl;
    private String serviceId;
    private boolean isUseEureka;
    private String authToken;

    /* ── NEW: per-client HTTP options (protocol + pool/keepalive) ─────────── */
    private HttpOptions http = new HttpOptions();

    // getters/setters …

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


```
Optional YAML (keeps defaults unless you override)
```yaml
dservice:
  http:
    protocol: AUTO        # AUTO | H2 | H2C | H1
    tcp-keep-alive: true
    pool:
      max-connections: 200
      pending-acquire-timeout: 45s
      max-idle: 30s
      max-life: 5m
      evict-in-background: 60s

```
Notes
• H2 = HTTP/2 over TLS (ALPN). Your backend must enable HTTP/2 (e.g., server.http2.enabled=true on the server).
• H2C = HTTP/2 cleartext. Most servlet servers don’t support it; Netty does. Use only if your server actually speaks h2c.
• AUTO = let Reactor Netty negotiate (HTTP/1.1 by default; can upgrade to H2 if TLS+ALPN and server supports it).

2) Wire HTTP/2 / HTTP/1.1 + keep-alive/pool in your connectors
The ApplicationBeanConfiguration is updated with these new lines:
```java
// ─────────────────────────────────────────────────────────────────────────────
// ApplicationBeanConfiguration  (ONLY ADDED/CHANGED SECTIONS ARE MARKED)
// ─────────────────────────────────────────────────────────────────────────────
@Configuration
public class ApplicationBeanConfiguration {

    // NEW - added a logger
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApplicationBeanConfiguration.class);

    private final DserviceClientProperties props;

    public ApplicationBeanConfiguration(DserviceClientProperties props) {
        this.props = props;
    }

    /* ── NEW: build a ConnectionProvider (pool) driven by properties ──────── */
    private ConnectionProvider connectionProvider(String name) {
        var p = props.getHttp().getPool();
        return ConnectionProvider.builder(name)
                .maxConnections(p.getMaxConnections())
                .pendingAcquireTimeout(p.getPendingAcquireTimeout())
                .maxIdleTime(p.getMaxIdle())
                .maxLifeTime(p.getMaxLife())
                .evictInBackground(p.getEvictInBackground())
                .lifo() // prefer recently-used
                .build();
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
    ReactorClientHttpConnector clientHttpConnector() {
        // CHANGED: use a named pool + protocol settings
        ConnectionProvider provider = connectionProvider("default-pool");

        HttpClient http = HttpClient.create(provider)
                // CONNECT timeout (TCP handshake)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                // RESPONSE timeout (first byte)
                .responseTimeout(Duration.ofSeconds(100))
                // READ/WRITE inactivity timeouts
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30))
                        .addHandlerLast(new WriteTimeoutHandler(10)))
                .metrics(true, uri -> uri);

        /* NEW: apply protocol, TLS/H2 and TCP keepalive */
        http = applyHttpVersionAndKeepAlive(http, "defaultConnector");

        return new ReactorClientHttpConnector(http);
    }

    // NEW: a more tolerant connector specifically for VERY large uploads.
    @Bean("uploadConnector")
    ReactorClientHttpConnector uploadClientHttpConnector() {
        ConnectionProvider provider = connectionProvider("upload-pool");

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

    // … (everything else in this class stays as you already have it: metrics, observation,
    //     resilience4j, builders, filters, uploadWebClient bean, etc.)
}


```

Why this works?

HttpClient.protocol(HttpProtocol…) sets which wire protocols the client offers/uses.

For H2 (TLS) you also need ALPN: .secure(...).protocol(H2, HTTP11).

For H2C (cleartext) there’s no TLS; use .protocol(H2C, HTTP11) (only if server supports h2c).

Connection reuse/HTTP keep-alive behavior is controlled by the connection pool 
(ConnectionProvider) plus normal HTTP semantics—your client will naturally reuse idle 
connections up to maxConnections, within maxIdle/maxLife.


How to verify which protocol you’re actually using? Look at your app logs (we log it on connect):
```text
[defaultConnector] Protocol AUTO (negotiate H2 when possible, else HTTP/1.1) 
[uploadConnector] Protocol AUTO (negotiate H2 when possible, else HTTP/1.1)
```
True! I found that log text.
Those log lines (“Protocol AUTO…”) are your setup logs that mean you configured the client to 
offer H2 with fallback to HTTP/1.1. The actual protocol is negotiated per connection at runtime.
You’ll only see “Negotiated application protocol: h2” after a real request with DEBUG logging enabled.

Another way to verify: but I already have this.
```yaml
logging.level.reactor.netty.http.client.HttpClient=DEBUG

```

(Server) If your backend is Spring Boot, enable HTTP/2 there when you want TLS/H2:
```yaml
server:
  http2:
    enabled: true

```

Quick tips:
If your backend doesn’t support HTTP/2 yet, set dservice.http.protocol: H1 to avoid any ALPN negotiation overhead.
If you want the benefits of multiplexing (many concurrent requests per connection), enable H2 on both sides and set protocol: H2 (client) + server.http2.enabled: true (server with TLS).
Keep your upload connector lenient (long timeouts, no write idle) and your default connector stricter, as you already did.




                END of experiment to customize the WebClient -  21. Customizing HTTP/2 or HTTP/1.1 Features





                START of experiment to customize the WebClient -  22. Custom Request Throttling (Rate Limiting)



22. Custom Request Throttling (Rate Limiting)
    To avoid overwhelming your backend (or to respect the third-party’s rate limits), you might want to throttle outgoing
    requests to, say, 10 QPS.


        First let's explain what this is all about. Here’s the quick mental model:

What is throttling?
• Throttling / rate limiting = deliberately slowing or capping how many requests you send over time (e.g., no more than 10 per second).
• It’s done client-side to protect your backend (or to respect a third-party’s published limits), and server-side to protect their service.

Why do it?
• Protect backends from traffic spikes and self-inflicted DDoS.
• Stay within third-party quotas (avoid 429s, bans, extra costs).
• Stabilize latency (smooth bursts), reduce cascading failures.
• Fairness: share capacity among tenants/users.

Key terms (super short)
• QPS: Queries Per Second—a throughput cap (e.g., 10 QPS).
• Burst: short spikes allowed above the steady rate (e.g., 10 QPS with burst 20).
• Token bucket: common algorithm—tokens drip in at a rate; each request consumes one; if empty, you wait or drop.
• Leaky bucket: smooth, constant outflow; buffers bursts.
• 429 Too Many Requests: server telling you it rate-limited you (often with Retry-After header).
• Global vs per-key: limit everything together (global), or per user/API key/route.
• Queue vs shed: when over limit, either queue (adds latency) or shed/drop (fail fast).

Throttling vs other controls
• Throttling: controls rate over time.
• Concurrency limits (bulkhead): controls how many are in-flight at once.
• Circuit breaker: opens after repeated failures to avoid hammering a bad endpoint.
• Retry: tries again after failure—must be coordinated with throttling to avoid stampedes.

Where to apply (for WebClient)
• Per-client (all calls), per-route (/user/** tighter), or per-tenant (header/API key).
• In a single instance you can keep counters in-memory; in a cluster you need a shared limiter (e.g., Redis/Bucket4j/Resilience4j with distributed state) or limit per instance.

Typical policies
• Steady rate: 10 QPS, no bursts.
• Bursty: 10 QPS with burst = 20 (bucket size 20).
• Windowed: 600 requests / minute (rolling or fixed windows).
• Server-hint-aware: back off if you see 429 and honor Retry-After.

When you’re ready, I can show two idiomatic implementations for WebClient:
1. Resilience4j RateLimiter (simple, in-JVM, integrates with Reactor), and
2. Bucket4j (more flexible; can be local or Redis-backed for cluster-wide limits).


Let’s wire client-side throttling into your existing WebClient using Resilience4j RateLimiter. 
We’ll keep it per-client (and optionally per-route), outermost in your filter chain so it gates 
everything (retry/CB/etc.)
```gradle

// build.gradle
dependencies {
    // …existing deps…
    implementation 'io.github.resilience4j:resilience4j-ratelimiter:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-reactor:2.2.0'   // already present (keep)
    implementation 'io.github.resilience4j:resilience4j-micrometer:2.2.0' // for metrics (you already have)
}
```

1) NEW filter: RateLimitingFilter.java

```java
package reactive.httpwebclientservice.filters;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Outermost filter that throttles outgoing requests using a Resilience4j RateLimiter.
 * Choose the limiter key (global / per-service / per-route) via keySelector.
 */
public class RateLimitingFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final RateLimiterRegistry registry;
    private final Function<ClientRequest, String> keySelector;

    public RateLimitingFilter(RateLimiterRegistry registry,
                              Function<ClientRequest, String> keySelector) {
        this.registry = Objects.requireNonNull(registry);
        this.keySelector = Objects.requireNonNull(keySelector);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String key = keySelector.apply(request);
        RateLimiter rl = registry.rateLimiter(key); // creates or retrieves

        // Use defer so permission is attempted BEFORE the real exchange starts.
        return Mono.defer(() -> next.exchange(request))
                .transformDeferred(RateLimiterOperator.of(rl))
                .doOnSubscribe(s -> log.debug("Rate limiting key='{}' {} {}", key, request.method(), request.url()));
    }
}



```

2) Register a RateLimiterRegistry and plug the filter (keep your original code, add NEW blocks)
```java

// ApplicationBeanConfiguration.java  (add imports)
import io.github.resilience4j.ratelimiter.*;
import reactive.httpwebclientservice.filters.RateLimitingFilter;

// …inside your @Configuration class…

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


```

Now, where you assemble the loadBalancedWebClientBuilder:

```java

@Bean
@LoadBalanced
public WebClient.Builder loadBalancedWebClientBuilder(
        @Qualifier("defaultConnector") ReactorClientHttpConnector connector,
        Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder,
        ObservationRegistry observationRegistry,
        ClientRequestObservationConvention webClientObservationConvention,
        CircuitBreakerRegistry circuitBreakerRegistry,
        BulkheadRegistry bulkheadRegistry,
        // ───────────── NEW ─────────────
        RateLimiterRegistry rateLimiterRegistry
) {
    // … your existing encoder/decoder setup, filters, etc …

    // existing:
    var retryFilter       = new RetryBackoffFilter(2, Duration.ofSeconds(1), Duration.ofSeconds(1), 0.0);
    var errorMapping      = new ErrorMappingFilter();
    var correlationFilter = new CorrelationHeaderFilter();
    var authFilter        = new AuthHeaderFilter(props::getAuthToken);
    var r4jFilter         = new Resilience4jFilter(circuitBreakerRegistry, bulkheadRegistry, req -> props.getServiceId());
    var loggingFilter     = new HttpLoggingFilter(64 * 1024);

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

    return WebClient
            .builder()
            .clientConnector(connector)
            .observationRegistry(observationRegistry)
            .observationConvention(webClientObservationConvention)
            .codecs(c -> {
                // …your existing codecs including maxInMemorySize…
                c.defaultCodecs().jackson2JsonEncoder(encoder);
                c.defaultCodecs().jackson2JsonDecoder(decoder);
                c.defaultCodecs().maxInMemorySize(256 * 1024);
            })
            .filters(list -> {
                // ───────────────── ORDER MATTERS ─────────────────
                // Put RATE LIMITING OUTERMOST → it gates everything (retry, CB, etc.)
                list.add(0, rateLimitFilter);  // <-- NEW (outermost)

                // Your existing chain
                list.add(loggingFilter);
                list.add(0, r4jFilter);
                list.add(errorMapping);
                list.add(correlationFilter);
                list.add(authFilter);
                list.add(retryFilter);
            });
}


```
Optional: also limit the upload client separately (often a different budget):
Here is example code, but I do not implement it now, this is this as example:
```java
@Bean
@Qualifier("uploadWebClient")
public WebClient uploadWebClient(
        WebClient.Builder lbBuilder,
        @Qualifier("uploadConnector") ReactorClientHttpConnector uploadConnector,
        ObservationRegistry observationRegistry,
        ClientRequestObservationConvention webClientObservationConvention,
        DserviceClientProperties props,
        // ───────────── NEW ─────────────
        RateLimiterRegistry rateLimiterRegistry
) {
    // Separate limiter bucket for uploads (e.g., 2 QPS)
    RateLimiterConfig uploadCfg = RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .limitForPeriod(2)
            .timeoutDuration(Duration.ofMillis(100))
            .build();
    rateLimiterRegistry.rateLimiter("upload-" + props.getServiceId(), uploadCfg);

    var uploadRlFilter = new RateLimitingFilter(
            rateLimiterRegistry,
            req -> "upload-" + props.getServiceId()
    );

    return lbBuilder.clone()
            .clientConnector(uploadConnector)
            .baseUrl("http://" + props.getServiceId())
            .observationRegistry(observationRegistry)
            .observationConvention(webClientObservationConvention)
            .filters(list -> {
                // gate uploads too
                list.add(0, uploadRlFilter);
            })
            .build();
}

```

3) Map “over limit” to a clean 429 (optional but nice)

If you want pretty 429s back to Postman:
```java

// GlobalErrorHandler.java  (add one more handler)
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

@RestControllerAdvice
public class GlobalErrorHandler {

    // …existing @ExceptionHandler(ApiException.class) …

    // ─────────────────────────────────────────────
    // NEW: when rate limiter denies a request
    // ─────────────────────────────────────────────
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<String> handleRatelimit(RequestNotPermitted ex) {
        return ResponseEntity.status(429).body("Client-side rate limit exceeded");
    }
}

```

(If you prefer to reuse your TooManyRequestsException, you could .onErrorMap(RequestNotPermitted.class, e -> new TooManyRequestsException(...)) 
inside your ErrorMappingFilter, but the advice above is simpler.)


4) How to test quickly?
Temporarily set rate limit down to 1 or 2, like so:
```java
    @Bean
    RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitRefreshPeriod(java.time.Duration.ofSeconds(1))  
                .limitForPeriod(1)                      //<<--  1 permits added each second
                .timeoutDuration(java.time.Duration.ofMillis(100))    
                .build();
        return RateLimiterRegistry.of(cfg);
    }
```
Then open postman client and manually start clicking fast to send requests to any URL, like:
http://localhost:8080/proxy/user/5?X-Debug-Log=true
Eventually you will get this response:
429 Too Many Requests
Client-side rate limit exceeded

Tested it with success.


        Metrics (since you have micrometer + resilience4j):

If you like more professional data, here you can try to use metrics:
Add a brand new bean so:
```java
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
```
The MeterBinder rateLimiterMetricsBinder(RateLimiterRegistry rlRegistry) from above allows you to query this Endpoint:
GET http://localhost:8080/actuator/metrics
, but if you like to be able to also query this endpoint below:
GET http://localhost:8080/actuator/metrics/resilience4j.ratelimiter.available.permissions
, then you must have also the RateLimiter backendServiceRateLimiter(RateLimiterRegistry registry, DserviceClientProperties props)
as shown above. And then you see the result - it confirms that we have set limits:
```json
{
    "name": "resilience4j.ratelimiter.available.permissions",
    "description": "The number of available permissions",
    "measurements": [
        {
            "statistic": "VALUE",
            "value": 10.0
        }
    ],
    "availableTags": [
        {
            "tag": "app",
            "values": [
                "HttpWebClientService"
            ]
        },
        {
            "tag": "name",
            "values": [
                "backend-service"
            ]
        }
    ]
}
```



That’s it! 
You now have per-service rate limiting guarding every WebClient call, with an easy path to switch to per-route or per-tenant keys if you want finer control.

BUT if you like to generate QPS above the limit, then you need to use tools like:
How to generate load above your QPS
   Pick any:
   a) hey (nice and simple)
   hey -z 10s -q 100 -c 20 "http://localhost:8080/proxy/user/5"
   • -q 100 = 100 requests/second target
   • -c 20 = 20 concurrent workers
   • -z 10s = run for 10 seconds
   b) ApacheBench
   ab -n 500 -c 50 http://localhost:8080/proxy/user/5
   • 500 total requests, 50 concurrent (often enough to exceed your 10 QPS).
   c) Pure bash + curl (parallel)
   seq 200 | xargs -n1 -P20 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
   "http://localhost:8080/proxy/user/5"
   • 20 parallel curls, 200 total requests.




                END of experiment to customize the WebClient -  22. Custom Request Throttling (Rate Limiting)





                START of experiment to customize the WebClient -  23. Custom Connection Pool Settings



23. Custom Connection Pool Settings
    By default, Reactor Netty’s connection pool size might be too small for high concurrency. You can tune max connections, pending
    acquisition, idle time, etc.

Here’s the quick mental model for connection pooling with WebClient (Reactor Netty) and why you’d tune it:
What is it?
• Every outbound HTTP call needs a TCP connection. Creating/tearing these down is expensive (TCP handshake, TLS, congestion warm-up).
• Reactor Netty keeps keep-alive connections in a pool so requests can reuse them instead of reconnecting each time.
• The pool has limits: how many connections may be open, how many callers may wait for one, how long idle connections are kept, and when old ones are closed.

How it fits WebClient
• WebClient → Reactor Netty HttpClient → ConnectionProvider (the pool).
• The pool is per host:port (so with load-balancing, each chosen instance ends up with its own sub-pool).
• If you create several HttpClients with the same provider instance, they share the pool; different providers → separate pools.

Why tune it?
• High concurrency (lots of concurrent requests) can exhaust a small/default pool → callers block waiting for a connection (pending acquisition) → latency spikes/timeouts.
• Long-lived calls (file uploads/streams) keep connections busy, reducing effective throughput unless you raise the max.
• Server/NAT/file-descriptor limits: too large a pool can blow limits and cause resets/port exhaustion. You need the right size, not the biggest size.

Key knobs (conceptually)
• maxConnections: upper bound of simultaneous open connections per host. Raise for higher parallelism; keep reasonable to avoid server/socket limits.
• pendingAcquireMaxCount: max callers allowed to wait for a connection. Acts like a back-pressure guard (fail fast if pool saturated).
• pendingAcquireTimeout: how long a caller will wait to get a connection before failing.
• maxIdleTime / maxLifeTime: evict idle/stale connections to avoid server closing them behind your back; trade off reuse vs freshness.
• evictInBackground: periodic reaper to clean idle/stale connections without waiting for use.
• LIFO/FIFO strategy**:** LIFO often improves cache locality/warm connections.
• HTTP/2 note: H2 can multiplex many streams on one connection → you usually need fewer connections than with H1. Don’t just copy H1 pool sizes.

Signals you need tuning
• Rising pending acquisition counts/times.
• Client timeouts despite server being healthy.
• Many short-lived connections in server logs (poor reuse).
• Latency improves when you temporarily raise the pool.

How it interacts with your other pieces
• Load balancer: pool is per resolved host:port; more instances → more sub-pools.
• Rate limiter / bulkhead: use them to cap demand so you don’t drown the pool.
• Circuit breaker: prevents hammering a sick instance, which also helps pool health.


Let’s wire custom Reactor Netty connection pools and plug them into your two connectors. 
I’m keeping original code/comments and only adding what’s needed; updated spots are clearly marked in
the public class ApplicationBeanConfiguration.
Add only these new lines on these places:
```java
private ConnectionProvider connectionProvider(String name) {
    var p = props.getHttp().getPool();
    return ConnectionProvider.builder(name)
            .metrics(true) // <-- NEW: expose reactor.netty.connection.provider.* metrics




    /* ── NEW: publish the two providers as beans so we can inject them ─────────────── */
    @Bean("defaultConnectionProvider") // <-- NEW
    ConnectionProvider defaultConnectionProvider() {
        return connectionProvider("default-http-pool");
    }

    @Bean("uploadConnectionProvider") // <-- NEW
    ConnectionProvider uploadConnectionProvider() {
        return connectionProvider("upload-http-pool");
    }


    @Bean("defaultConnector")
    ReactorClientHttpConnector clientHttpConnector(
            @Qualifier("defaultConnectionProvider") ConnectionProvider provider
    )
    {
        HttpClient http = HttpClient.create(provider)  //the provider must be of @Qualifier("defaultConnectionProvider")



        @Bean("uploadConnector")
        ReactorClientHttpConnector uploadClientHttpConnector(
            @Qualifier("uploadConnectionProvider") ConnectionProvider provider
    ) {
        HttpClient http = HttpClient.create(provider)  //the provider must be of  @Qualifier("uploadConnectionProvider")



```

How to test!?
First, always when using metrics for Reactor Netty, make sure to first make a single simple Request
from the WebClient to the backend-service. Only after that the metric for Reactor Netty will be
available in the list:
GET http://localhost:8080/actuator/metrics so:
```text
        "process.uptime",
        "reactor.netty.bytebuf.allocator.active.direct.memory",
        "reactor.netty.bytebuf.allocator.active.heap.memory",
        "reactor.netty.bytebuf.allocator.chunk.size",
        "reactor.netty.bytebuf.allocator.direct.arenas",
        "reactor.netty.bytebuf.allocator.heap.arenas",
        "reactor.netty.bytebuf.allocator.normal.cache.size",
        "reactor.netty.bytebuf.allocator.small.cache.size",
        "reactor.netty.bytebuf.allocator.threadlocal.caches",
        "reactor.netty.bytebuf.allocator.used.direct.memory",
        "reactor.netty.bytebuf.allocator.used.heap.memory",
        "reactor.netty.connection.provider.active.connections",
        "reactor.netty.connection.provider.idle.connections",
        "reactor.netty.connection.provider.max.connections",
        "reactor.netty.connection.provider.max.pending.connections",
        "reactor.netty.connection.provider.pending.connections",
        "reactor.netty.connection.provider.pending.connections.time",
        "reactor.netty.connection.provider.total.connections",
        "reactor.netty.eventloop.pending.tasks",
        "reactor.netty.http.client.connect.time",
        "reactor.netty.http.client.data.received",
        "reactor.netty.http.client.data.received.time",
        "reactor.netty.http.client.data.sent",
        "reactor.netty.http.client.data.sent.time",
        "reactor.netty.http.client.response.time",
        "resilience4j.ratelimiter.available.permissions",
```
And yuu can query them so:
/actuator/metrics/reactor.netty.connection.provider.active.connections
/actuator/metrics/reactor.netty.connection.provider.idle.connections
/actuator/metrics/reactor.netty.connection.provider.pending.connections

Examples:
List names:
GET http://localhost:8080/actuator/metrics

Active connections:
GET http://localhost:8080/actuator/metrics/reactor.netty.connection.provider.active.connections

Idle connections:
GET http://localhost:8080/actuator/metrics/reactor.netty.connection.provider.idle.connections

Pending acquisitions:
GET http://localhost:8080/actuator/metrics/reactor.netty.connection.provider.pending.connections

Use the response’s availableTags to see which tag keys are present (often a pool id/name like default-http-pool / upload-http-pool) and then query with ?tag=id:default-http-pool (or whatever the tag key is called in your build).

That’s it—you now have explicit, per-client connection pools tuned for your traffic patterns.





                END of experiment to customize the WebClient -  23. Custom Connection Pool Settings



                START of experiment to customize the WebClient -  24. Custom Codec for XML, YAML, or Protobuf



24. Custom Codec for XML, YAML, or Protobuf
    Maybe you’re talking to a legacy service that uses XML or a partner that uses Protobuf. You need to register an additional codec
    so RestClient can automatically marshal/unmarshal.


How this plays with HTTP & Spring
• The server picks what it sends via Content-Type.
• The client asks for what it wants with Accept.
• Your WebClient needs the right codec to (de)serialize that media type.
Spring WebFlux already has built-ins for XML (JAXB/Jackson XML) and Protobuf. YAML isn’t built in—you can add it via Jackson’s YAML dataformat with a tiny custom codec.

What these formats are (in HTTP terms)
• XML
Text, tag-based (think <user><id>1</id></user>). Old but very common in legacy/enterprise APIs (SOAP/REST).
MIME types: application/xml, text/xml.
Pros: mature tooling, schemas (XSD), validation.
Cons: verbose, heavier than JSON.
• YAML
Text, indentation-based (a superset of JSON). Humans love editing it; it’s huge in config files (Kubernetes, CI). Rare as an API wire format, but possible.
MIME types: application/yaml, application/x-yaml.
Pros: human-friendly.
Cons: easy to misindent, not universally supported by API clients/servers.
• Protocol Buffers (Protobuf)
Binary serialization from Google. You define a .proto schema, generate classes, then send compact bytes. Often used with gRPC (HTTP/2) but can be used over plain HTTP.
MIME types: application/x-protobuf, application/protobuf.
Pros: tiny payloads, fast, strict schema.
Cons: not human-readable; requires codegen and agreed schemas.


Here’s everything you need to wire all three codecs (XML, Protobuf, YAML) into your existing 
WebClient without touching the rest of your stack.

1) Add dependencies
```yaml
dependencies {
  // XML
  implementation 'org.glassfish.jaxb:jaxb-runtime'   // XML (JAXB)
  // YAML
  implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml'
  // Protobuf runtime (for generated message classes)
  implementation 'com.google.protobuf:protobuf-java'  // Protobuf

}

```

2) Register codecs in your existing builder
Add the XML, Protobuf and Yaml coders/decoders to the codecs:
```java

.codecs(c -> {
    // JSON (keep)
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

    c.defaultCodecs().maxInMemorySize(256 * 1024);
})

```
Note: JAXB requires your DTOs to have JAXB annotations (e.g., @XmlRootElement, @XmlElement) to serialize/deserialize.

If you prefer Jackson XML

Upgrade to a Spring Framework / Spring Boot version that includes org.springframework.http.codec.xml.Jackson2XmlEncoder/Decoder (e.g., recent Boot 3.3.x), and add:
```yml
implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-xml'

```
That’s it — your WebClient can now automatically (de)serialize XML, YAML, and Protobuf whenever a server responds with those Content-Types or when you set Accept/Content-Type accordingly.




                END of experiment to customize the WebClient -  24. Custom Codec for XML, YAML, or Protobuf




                START of experiment to customize the WebClient -  25. Conditional Circuit Breaker Per Endpoint


This task will be skipped now. It's not that critical now. And the project already implements such or similar functionality.

25. Conditional Circuit Breaker Per Endpoint
    Perhaps you trust /user/{id} to be quick, but /user-with-data/{id} is slow (joins multiple tables). You might want a tighter
    circuit breaker on the slow path (e.g., trip after 3 failures), but leave the simple GET alone.


What you have:
• Resilience4j is wired in and applied via a Resilience4jFilter.
• 4xx are not counted as failures (your ErrorMappingFilter + CB recordException logic).
• Metrics infra is in place.
What’s missing for “per-endpoint” CB:
1. Name the breaker per route, not per service (right now you use just props.getServiceId()).
2. Provide a stricter CB config for the slow endpoint and register it in the registry.
3. (Optional) Bind CB metrics explicitly so you can see both breakers.




                END of experiment to customize the WebClient -  25. Conditional Circuit Breaker Per Endpoint




                START of experiment to customize the WebClient -  26. Dynamic Connection Pool Adjustment at Runtime




This task will be skipped now. It's not that critical now. And the project already implements such or similar functionality.


26. Dynamic Connection Pool Adjustment at Runtime
    Maybe you want to throttle performance during off-peak hours (e.g. only 10 connections at night) and allow more during business
    hours (e.g. 100 connections). You could expose an actuator endpoint to tweak connection pool sizes on the fly.


    

                END of experiment to customize the WebClient -  26. Dynamic Connection Pool Adjustment at Runtime





                START of experiment to customize the WebClient -  27. Per-Client Logging Level (Wiretap)


        NB !!!!

        Caveats
        • Wiretap logs raw bytes (post-TLS decryption). Do not enable in prod or with secrets in flight.
        • It’s expensive; keep it scoped to the one connector you’re investigating.

        NB !!!!



27. Per-Client Logging Level (Wiretap)
    If you want to log TCP-level details (headers, wire bytes), Reactor Netty’s “wiretap” can dump low-level frames. Useful only
    when debugging SSL handshakes or subtle protocol issues.


Wiretap = Netty’s byte-level logging. We’ll make it per-connector and toggleable from YAML.
What you’ll add
• Per-client (default vs upload) wiretap switches.
• Pickable format: SIMPLE (headers), TEXTUAL (decoded text), or HEX (hex dump).
• Separate SLF4J categories so you can enable logs for one client without blasting all.

Code changes (in ApplicationBeanConfiguration)
Add imports:

```java
import io.netty.handler.logging.LogLevel;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import org.springframework.beans.factory.annotation.Value;

```

Add properties (fields):
```java

@Value("${dservice.http.wiretap.format:SIMPLE}")
private String wiretapFormat; // SIMPLE | TEXTUAL | HEX / HEX_DUMP

@Value("${dservice.http.wiretap.default.enabled:false}")
private boolean wiretapDefaultEnabled;

@Value("${dservice.http.wiretap.upload.enabled:false}")
private boolean wiretapUploadEnabled;

```

Helper to map format:
```java
private AdvancedByteBufFormat wiretapFmt() {
    String f = wiretapFormat == null ? "SIMPLE" : wiretapFormat.toUpperCase();
    return switch (f) {
        case "HEX", "HEX_DUMP" -> AdvancedByteBufFormat.HEX_DUMP;
        case "TEXT", "TEXTUAL" -> AdvancedByteBufFormat.TEXTUAL;
        default -> AdvancedByteBufFormat.SIMPLE;
    };
}


```
Default connector — add wiretap (remove none here; you currently don’t wiretap default)
update that file:
the new things are marked 
```java
@Bean("defaultConnector")
ReactorClientHttpConnector clientHttpConnector(
                @Qualifier("defaultConnectionProvider") ConnectionProvider provider) {

    HttpClient http = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(100))
            .doOnConnected(conn -> conn
                    .addHandlerLast(new ReadTimeoutHandler(30))
                    .addHandlerLast(new WriteTimeoutHandler(10)))
            .metrics(true, uri -> uri);

    http = applyHttpVersionAndKeepAlive(http, "defaultConnector");

    // NEW: per-client wiretap
    if (wiretapDefaultEnabled) {
        http = http.wiretap(
                "reactor.netty.http.client.HttpClient.default",
                LogLevel.DEBUG,
                wiretapFmt()
        );
    }

    return new ReactorClientHttpConnector(http);
}


```

Upload connector — replace .wiretap(true) with conditional advanced wiretap

```java
@Bean("uploadConnector")
ReactorClientHttpConnector uploadClientHttpConnector(
        @Qualifier("uploadConnectionProvider") ConnectionProvider provider) {

    HttpClient http = HttpClient.create(provider)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
        .responseTimeout(Duration.ofHours(24))
        .doOnConnected(conn -> conn
            .addHandlerLast(new ReadTimeoutHandler(0))
            .addHandlerLast(new WriteTimeoutHandler(0)))
        .metrics(true, uri -> uri);

    http = applyHttpVersionAndKeepAlive(http, "uploadConnector");

    // NEW: per-client wiretap
    if (wiretapUploadEnabled) {
        http = http.wiretap(
            "reactor.netty.http.client.HttpClient.upload",
            LogLevel.DEBUG,
            wiretapFmt()
        );
    }

    return new ReactorClientHttpConnector(http);
}


```
Note: I intentionally removed your previous .wiretap(true) to avoid blanket logging; the advanced form gives you category + format control.


application.yml (toggle it per client)
```yml
dservice:
  http:
    wiretap:
      format: TEXTUAL           # SIMPLE | TEXTUAL | HEX (aka HEX_DUMP)
      default:
        enabled: false          # leave normal traffic quiet
      upload:
        enabled: true           # only noisy when debugging uploads

```

Logging levels (per-category)

Use the exact categories we set above:

```yml
logging:
  level:
    # Narrow scope: only log the upload connector’s wiretap
    reactor.netty.http.client.HttpClient.upload: DEBUG
    reactor.netty.http.client.HttpClient.default: INFO
    # Make sure the parent isn’t forcing DEBUG for all:
    reactor.netty.http.client.HttpClient: INFO

```
    Why it matters whether parent -  reactor.netty.http.client.HttpClient: INFO - isn’t forcing DEBUG for all? 
Because the child logger (e.g. ...HttpClient.upload) inherits its level unless you set the child explicitly.
• Set parent to INFO → only the categories you explicitly turn on (e.g. ...HttpClient.upload: DEBUG) will be chatty.
• If parent is DEBUG, then every child without its own level becomes DEBUG too (lots of noisy Netty logs).
You can still override specific children (e.g. set ...HttpClient.default: INFO), but many other Netty categories will remain DEBUG.

In my case, because of earlier tasks, its already in DEBUG: reactor.netty.http.client.HttpClient: DEBUG
but I wont change it. It won't hurt if its noisy.

Knowing this, then how to use it?
• Flip enabled in YAML and set the matching logger category to DEBUG.
• TEXTUAL is usually friendlier than raw hex; use HEX if you’re chasing binary frames.
• For TLS handshake internals, also set:
```yml
logging.level.io.netty.handler.ssl: DEBUG

```
(only while debugging!)
Caveats
• Wiretap logs raw bytes (post-TLS decryption). Do not enable in prod or with secrets in flight.
• It’s expensive; keep it scoped to the one connector you’re investigating.

How to verify
1. Call any endpoint using uploadWebClient → you should see log lines with logger wiretap.upload containing WRITE/READ plus headers and body bytes (HTTP/1.1) or frame info (HTTP/2).
2. Call via the normal WebClient → no wiretap logs should appear.
3. Flip wiretap.upload to INFO to silence it without code changes.
⚠️ Wiretap logs bodies; avoid in prod or mask secrets.

4. Unfortunately testing with: http://localhost:8080/proxy/upload-small?path=/......./small.bin
 throws error: java.lang.IllegalStateException  , because I have filters added here: 

```java
.filters(list -> {
                    // ───────────────── ORDER MATTERS ─────────────────
                    // Put RATE LIMITING OUTERMOST → it gates everything (retry, CB, etc.)
                    list.add(0, rateLimitFilter);  // <-- NEW (outermost)
                    // Put logging fairly outer so you see what's retried, but AFTER request-mutation,
                    // so headers (auth/correlation) appear in logs.
                    //list.add(loggingFilter); //- this is added below again. It must be added only once, not twice.
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
```
And these filter break it. I cannot have two filtern. But even if I remove one, it still breaks.




                END of experiment to customize the WebClient -  27. Per-Client Logging Level (Wiretap)















28. Implementing a Custom “Fallback to Cache” on 404
    If your user data is sometimes stale, you want to first check a local cache. If the remote call returns 404, then you serve from
    the cache. Otherwise, you return the remote data and repopulate cache.

29. Request Batching (Combining Multiple Calls into One)
    If you have to fetch user A, B, and C in quick succession, it’s often more efficient to call /api/v1/users?ids=A,B,C once rather
    than 3 separate /user/{id} calls. You can implement a small “batcher” layer on top of your RestClient.

30. Custom Authorization Flow (OAuth2 Client Credentials)
    If your backend is secured by OAuth2, you need to fetch an access token from an auth server (e.g. Keycloak) and attach it as
    Authorization: Bearer <token> on every request. The token needs automatic refresh before expiry.















