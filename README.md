

                                    My custom WebClient -  Docs

    https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-webclient

    https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html

    https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html    



NB!!! By design the WebClient is fully asynchronous (none-blocking) client!

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




               START of experiment to customize the RestClient -  1. Adjusting Timeouts (Connect, Read, Write)

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



               END of experiment to customize the RestClient -  1. Adjusting Timeouts (Connect, Read, Write)





               START of experiment to customize the RestClient -  2. Adding a Retry/Backoff Strategy


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
Why is it waht it does? 
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



               END of experiment to customize the RestClient -  2. Adding a Retry/Backoff Strategy





               START of experiment to customize the RestClient -  3. Inserting Custom Headers (e.g., Correlation ID, Auth Token)

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





               END of experiment to customize the RestClient -  3. Inserting Custom Headers (e.g., Correlation ID, Auth Token)







4. Custom Error Decoding & Mapping to Exceptions
   By default, non‐2xx responses are turned into a generic RestClientResponseException. You might want to map, say, a 404 to a
   UserNotFoundException or a 401 to UnauthorizedException.

5. Custom JSON (Jackson) Configuration
   Suppose you want to use a custom ObjectMapper—for example, enabling a special date format, ignoring unknown fields,
   or registering a module (e.g. JSR310, Kotlin, Protobuf). You need to tell the RestClient to use your ObjectMapper when
   serializing/deserializing request and response bodies.

6. Metrics & Instrumentation
   In production, you’ll want to track how many calls you’re making, response times, error rates, etc. You can hook in Micrometer or
   Spring Boot’s MeterRegistry and record metrics around every request.

7. Circuit Breaker / Bulkhead (Resilience4j Integration)
   Repeated failures to your backend (e.g. DB down) should not cascade into your entire system. A circuit breaker lets you “trip”
   after N failures and avoid hammering a bad endpoint.

8. Custom Load-Balancing Rules (Zone/Affinity, Metadata-based Routing)
   By default, Spring Cloud LoadBalancer uses a simple round-robin. Sometimes you want:
   Zone Affinity: Prefer instances in the same zone/region as the client.
   Metadata Filtering: Only use instances that have a specific metadata label (e.g. version=v2).
   Weighting: Give some instances higher “weight” if they’re more powerful.

9. Circuit-Breaker with Fallback to a Local Stub
   Sometimes, instead of throwing an exception when the breaker is open, you want to return a default “fallback” response
   (e.g. cached data, empty user, placeholder).

10. Uploading Large Files: Tune Buffer Size / Memory Limits
    If you need to send or receive large payloads (e.g. >10 MB), the default in-memory buffering may not suffice. You might want to raise
    the max in-memory size or switch to streaming chunks.

11. Proxy or Custom SSL (TrustStore) Configuration
    In corporate environments, you sometimes have to route outgoing HTTP calls through an HTTP proxy (say, corporate-proxy:8080).

12. Request/Response Logging (Full Body + Headers)
    While debugging, you often want to log every outgoing request (method, URI, headers, body) and every incoming response
    (status, headers, body). Spring’s ExchangeFilterFunction can do this, without you sprinkling logs in every controller.

13. Dynamic Base URL Resolution (Non-Eureka Fallback)
    You currently use Eureka (serviceId = "backend-service") in your HttpClientInterface. But you might want a fallback to a fixed URL
    if Eureka is down (or if the user configures some base-url in a properties file for testing).

14. Custom Cookie Management
    If your backend sets a session cookie (e.g. Set-Cookie: SESSION=abc123; Path=/; HttpOnly), you may need to send that cookie
    automatically on subsequent calls (sticky session).

15. Defining Custom Error Handling Strategies by Status Family
    Maybe you want to treat all 4xx as “client failures” but still parse the body, while all 5xx should throw an exception immediately
    (and never convert into a DTO).

16. Custom DNS Resolution / Hostname Verification
    If you need to bypass DNS resolution (e.g. to hardcode an IP → hostname mapping for testing), or if you need to skip hostname
    verification (for internal certs).

17. Conditional Logic Based on Request Path or Headers
    Suppose you want different behavior when calling /user/{id} vs /user-with-data/{id}. For instance, maybe calls to /user-with-data/…
    must carry an extra header like X-Internal-Auth: secret, whereas /user/… should not.

18. Capturing Response Cookies and Propagating Them
    If your backend returns Set-Cookie: SESSION=xyz on one call, you may want to store and reuse that in subsequent calls
    (similar to “sticky sessions” in #11 but here perhaps for a different domain).

19. Bulkhead (Thread Pool) Isolation
    If your HTTP calls are expensive (e.g. large payload, slow DB), you may not want them to exhaust your main reactive event loops.
    You can isolate them in a dedicated thread pool (“bulkhead”) so that a spike in these calls doesn’t starve CPU for other traffic.

20. Custom SSL Pinning (Pin a Specific Certificate Fingerprint)
    For maximum security, you might want to verify that the server’s certificate matches a known fingerprint (public-key pinning),
    not just that it’s signed by the CA in your trust store.

21. Customizing HTTP/2 or HTTP/1.1 Features
    You might want to force HTTP/2 (for multiplexing) or explicitly disable HTTP/2 if your server doesn’t support it (and your client
    negotiates it automatically). You can also tweak “keep-alive” settings.

22. Custom Request Throttling (Rate Limiting)
    To avoid overwhelming your backend (or to respect the third-party’s rate limits), you might want to throttle outgoing
    requests to, say, 10 QPS.

23. Custom Connection Pool Settings
    By default, Reactor Netty’s connection pool size might be too small for high concurrency. You can tune max connections, pending
    acquisition, idle time, etc.

24. Custom Codec for XML, YAML, or Protobuf
    Maybe you’re talking to a legacy service that uses XML or a partner that uses Protobuf. You need to register an additional codec
    so RestClient can automatically marshal/unmarshal.

25. Conditional Circuit Breaker Per Endpoint
    Perhaps you trust /user/{id} to be quick, but /user-with-data/{id} is slow (joins multiple tables). You might want a tighter
    circuit breaker on the slow path (e.g., trip after 3 failures), but leave the simple GET alone.

26. Dynamic Connection Pool Adjustment at Runtime
    Maybe you want to throttle performance during off-peak hours (e.g. only 10 connections at night) and allow more during business
    hours (e.g. 100 connections). You could expose an actuator endpoint to tweak connection pool sizes on the fly.

27. Per-Client Logging Level (Wiretap)
    If you want to log TCP-level details (headers, wire bytes), Reactor Netty’s “wiretap” can dump low-level frames. Useful only
    when debugging SSL handshakes or subtle protocol issues.

28. Implementing a Custom “Fallback to Cache” on 404
    If your user data is sometimes stale, you want to first check a local cache. If the remote call returns 404, then you serve from
    the cache. Otherwise, you return the remote data and repopulate cache.

29. Request Batching (Combining Multiple Calls into One)
    If you have to fetch user A, B, and C in quick succession, it’s often more efficient to call /api/v1/users?ids=A,B,C once rather
    than 3 separate /user/{id} calls. You can implement a small “batcher” layer on top of your RestClient.

30. Custom Authorization Flow (OAuth2 Client Credentials)
    If your backend is secured by OAuth2, you need to fetch an access token from an auth server (e.g. Keycloak) and attach it as
    Authorization: Bearer <token> on every request. The token needs automatic refresh before expiry.















