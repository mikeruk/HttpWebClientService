package reactive.httpwebclientservice.exceptions;

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
