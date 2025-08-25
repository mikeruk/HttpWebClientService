package reactive.httpwebclientservice.exceptions;

public class ForbiddenException extends ApiException {
    public ForbiddenException(String m, String method, String url, String corr, String body) {
        super(m, 403, method, url, corr, body, null);
    }
}
