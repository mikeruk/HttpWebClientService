package reactive.httpwebclientservice.exceptions;

public class BadRequestException extends ApiException {
    public BadRequestException(String m, String method, String url, String corr, String body) {
        super(m, 400, method, url, corr, body, null);
    }
}