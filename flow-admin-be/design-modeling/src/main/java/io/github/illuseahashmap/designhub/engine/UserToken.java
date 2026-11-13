package io.github.illuseahashmap.designhub.engine;

/**
 * The access token of the user of the current request. Calls to the Admin API of Flow Engine carry it, so Flow
 * Engine applies the rights of that user and nobody else's.
 */
public final class UserToken {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private UserToken() {
    }

    public static void set(String token) {
        TOKEN.set(token);
    }

    public static String current() {
        return TOKEN.get();
    }

    public static void clear() {
        TOKEN.remove();
    }
}
