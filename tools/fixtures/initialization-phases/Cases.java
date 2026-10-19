package initprobe;

import external.Api;

/** Each entry runs in a fresh JVM. Initializer failure must retain its partial store. */
public class Cases {
    static Runnable service;
    static void bind() { service = () -> {}; }
    static void read() { service.run(); }
    static void fail() { throw new RuntimeException("initializer failure"); }
    static class Clearing { static { service = null; } static void touch() {} }
    static class Binding { static { bind(); } static void touch() {} }
    static class FailClear { static { service = null; fail(); } static void touch() {} }
    static class FailBind { static { bind(); fail(); } static void touch() {} }

    public static void clearOnInitUnsafe() { bind(); Clearing.touch(); Api.register(Cases::read); }
    public static void bindOnInitSafe() { service = null; Binding.touch(); Api.register(Cases::read); }
    public static void failedClearUnsafe() {
        bind();
        try { FailClear.touch(); } catch (ExceptionInInitializerError expected) {}
        Api.register(Cases::read);
    }
    public static void failedBindSafe() {
        service = null;
        try { FailBind.touch(); } catch (ExceptionInInitializerError expected) {}
        Api.register(Cases::read);
    }
    public static void erroneousRetrySafe() {
        try { FailClear.touch(); } catch (ExceptionInInitializerError expected) {}
        bind();
        try { FailClear.touch(); } catch (NoClassDefFoundError expected) {}
        Api.register(Cases::read);
    }
    public static void erroneousRetryUnsafe() {
        try { FailBind.touch(); } catch (ExceptionInInitializerError expected) {}
        service = null;
        try { FailBind.touch(); } catch (NoClassDefFoundError expected) {}
        Api.register(Cases::read);
    }
}
