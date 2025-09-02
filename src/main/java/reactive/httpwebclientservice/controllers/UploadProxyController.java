package reactive.httpwebclientservice.controllers;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactive.httpwebclientservice.services.LargeFileUploadService;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;

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

