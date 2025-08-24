package reactive.httpwebclientservice.utils;

import java.util.UUID;

public final class Correlation {
    private Correlation() {}

    public static final String HEADER = "X-Correlation-Id";
    public static final String CTX_KEY = "corrId";

    public static String newId()
    {
        return UUID.randomUUID().toString();
    }
}
