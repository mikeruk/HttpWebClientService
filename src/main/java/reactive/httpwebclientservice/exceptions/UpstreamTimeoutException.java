package reactive.httpwebclientservice.exceptions;

public class UpstreamTimeoutException extends ApiException {
    public UpstreamTimeoutException(String m, String method, String url, String corr, Throwable cause) {
        super(m, null, method, url, corr, null, cause);
    }
}
