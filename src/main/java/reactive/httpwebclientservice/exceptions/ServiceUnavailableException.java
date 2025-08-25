package reactive.httpwebclientservice.exceptions;

public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String m, String method, String url, String corr, String body, Throwable cause) {
        super(m, 503, method, url, corr, body, cause);
    }
}
