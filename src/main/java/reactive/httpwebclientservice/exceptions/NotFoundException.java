package reactive.httpwebclientservice.exceptions;

public class NotFoundException extends ApiException {
    public NotFoundException(String m, String method, String url, String corr, String body) {
        super(m, 404, method, url, corr, body, null);
    }
}
