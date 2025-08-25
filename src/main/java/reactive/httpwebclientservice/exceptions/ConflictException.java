package reactive.httpwebclientservice.exceptions;

public class ConflictException extends ApiException {
    public ConflictException(String m, String method, String url, String corr, String body) {
        super(m, 409, method, url, corr, body, null);
    }
}
