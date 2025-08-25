package reactive.httpwebclientservice.exceptions;


public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String m, String method, String url, String corr, String body) {
        super(m, 401, method, url, corr, body, null);
    }
}
