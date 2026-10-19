import java.lang.reflect.InvocationTargetException;

public class Runner {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--java-home")) {
            System.out.println(java.util.Base64.getEncoder().encodeToString(
                    System.getProperty("java.home").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return;
        }
        boolean npe = false;
        try { Class.forName("initprobe.Cases").getMethod(args[0]).invoke(null); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof NullPointerException) npe = true;
            else throw failure;
        }
        if (npe != args[0].endsWith("Unsafe") || external.Api.calls != 1)
            throw new AssertionError(args[0] + ": npe=" + npe + ", calls=" + external.Api.calls);
        System.out.println(args[0] + "\t" + npe + "\t" + external.Api.calls);
    }
}
