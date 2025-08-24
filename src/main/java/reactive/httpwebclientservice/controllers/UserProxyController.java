package reactive.httpwebclientservice.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactive.httpwebclientservice.DTOs.db.UserDTO;
import reactive.httpwebclientservice.DTOs.db.UserDbDTO;
import reactive.httpwebclientservice.HttpClientInterface;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static reactive.httpwebclientservice.utils.Correlation.*;


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


}