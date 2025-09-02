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
import org.springframework.http.codec.multipart.FilePart;
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
