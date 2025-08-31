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

    // lets us pass arbitrary headers (e.g., X-Version, X-SC-LB-Hint)
    @GetExchange("/user/{id}")
    Mono<ResponseEntity<UserDTO>> getByIdWithHints(
            @PathVariable Long id,
            @RequestHeader Map<String, String> headers);
}
