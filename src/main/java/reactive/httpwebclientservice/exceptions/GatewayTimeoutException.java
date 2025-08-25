package reactive.httpwebclientservice.exceptions;

public class GatewayTimeoutException extends ApiException {
    public GatewayTimeoutException(String m, String method, String url, String corr, String body, Throwable cause) {
        super(m, 504, method, url, corr, body, cause);
    }
}
