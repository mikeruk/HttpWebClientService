package reactive.httpwebclientservice.exceptions;

public class UpstreamConnectException extends ApiException {
    public UpstreamConnectException(String m, String method, String url, String corr, Throwable cause) {
        super(m, null, method, url, corr, null, cause);
    }
}
