package reactive.httpwebclientservice.controllers;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactive.httpwebclientservice.DTOs.db.UserDTO;
import reactive.httpwebclientservice.DTOs.db.UserDbDTO;
import reactive.httpwebclientservice.HttpClientInterface;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static reactive.httpwebclientservice.utils.Correlation.*;


@RestController
@RequestMapping("/proxy")       // <— choose any prefix you like
public class UserProxyController {

    private final HttpClientInterface users;
    private final MeterRegistry registry;

    public UserProxyController(HttpClientInterface users, MeterRegistry registry) {
        this.users = users;
        this.registry = registry;
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

    @GetMapping("/user-fast/{id}")
    public Mono<ResponseEntity<UserDTO>> getByIdFast(@PathVariable Long id) {
        return users.getById(id, null)
                .timeout(Duration.ofSeconds(2)) // stricter for this call only
                .onErrorResume(TimeoutException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build()));
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


    @GetMapping("/user-with-headers/{id}")
    public Mono<ResponseEntity<UserDTO>> getByIdWithHeaders(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Version", required = false) String ver,
            @RequestHeader(value = HEADER, required = false) String incomingCorrId) {

        String corrId = (incomingCorrId == null || incomingCorrId.isBlank()) ? newId() : incomingCorrId;

        return users.getById(id, ver)
                .contextWrite(ctx -> ctx.put(CTX_KEY, corrId)); // ← seed once; filter reads it
    }


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

    // forward Postman headers to the WebClient so LB can use them
    @GetMapping("/user-hinted/{id}")
    public Mono<ResponseEntity<UserDTO>> getByIdHinted(
            @PathVariable Long id,
            @RequestHeader Map<String, String> headers) {
        // you can still set/override headers here if you want:
        // headers.putIfAbsent("X-API-Version", "v1");
        return users.getByIdWithHints(id, headers);
    }


}