package reactive.httpwebclientservice.controllers;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactive.httpwebclientservice.exceptions.ApiException;

@RestControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApi(ApiException ex) {
        // include correlation ID so clients can quote it
        return ResponseEntity.status(ex.getStatus() != null ? ex.getStatus() : 502)
                .header("X-Correlation-Id", ex.getCorrelationId() != null ? ex.getCorrelationId() : "N/A")
                .body(ex.getMessage());
    }


    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<String> handleRatelimit(RequestNotPermitted ex) {
        return ResponseEntity.status(429).body("Client-side rate limit exceeded");
    }
}
