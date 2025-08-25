package reactive.httpwebclientservice.exceptions;

public class TooManyRequestsException extends ApiException {
    private final String retryAfter;
    public TooManyRequestsException(String m, String method, String url, String corr, String body, String retryAfter) {
        super(m, 429, method, url, corr, body, null);
        this.retryAfter = retryAfter;
    }
    public String getRetryAfter(){ return retryAfter; }
}
