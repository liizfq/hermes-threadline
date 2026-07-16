package android.util;

/**
 * Test-only shim for [android.util.Log] so JVM unit tests that exercise
 * Android-dependent code don't crash with "Method d in android.util.Log not
 * mocked".
 *
 * The android.jar that AGP puts on the unit-test classpath is a stub that
 * throws on every method call. Without [android.testOptions.unitTests.
 * returnDefaultValues] enabled (which would require touching Gradle), the
 * shim below shadows the stub with no-op implementations that just return a
 * fixed log level — enough for the [RoomSessionListStore] call sites that
 * log diagnostics during ensureStarted / refresh / teardown.
 *
 * Lives under `src/test/java` so it never ships in the APK; the test
 * classpath places it ahead of the stub android.jar.
 */
public final class Log {
    public static int v(String tag, String msg) { return 2; }
    public static int v(String tag, String msg, Throwable tr) { return 2; }
    public static int d(String tag, String msg) { return 3; }
    public static int d(String tag, String msg, Throwable tr) { return 3; }
    public static int i(String tag, String msg) { return 4; }
    public static int i(String tag, String msg, Throwable tr) { return 4; }
    public static int w(String tag, String msg) { return 5; }
    public static int w(String tag, String msg, Throwable tr) { return 5; }
    public static int w(String tag, Throwable tr) { return 5; }
    public static int e(String tag, String msg) { return 6; }
    public static int e(String tag, String msg, Throwable tr) { return 6; }

    private Log() {}
}
