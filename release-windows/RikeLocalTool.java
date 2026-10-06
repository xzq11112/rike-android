import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Pass non-secret tool arguments as ASCII Base64 across the Windows launcher.
 * JDK 17's Windows launcher converts command-line text through the system ANSI
 * code page. Decode paths inside Java, whose filesystem APIs preserve Unicode.
 * Passwords remain exclusively in the official tool's interactive input.
 */
public final class RikeLocalTool {
    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) throw new IllegalArgumentException("Missing tool arguments");
        String kind = arguments[0];
        String tool = decode(arguments[1]);
        String[] decoded = Arrays.stream(arguments, 2, arguments.length)
                .map(RikeLocalTool::decode).toArray(String[]::new);
        if (kind.equals("keytool")) {
            // Requires only the export supplied by the local PowerShell command.
            invoke(Class.forName("sun.security.tools.keytool.Main"), decoded);
        } else if (kind.equals("apksigner")) {
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[]{new File(tool).toURI().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                invoke(loader.loadClass("com.android.apksigner.ApkSignerTool"), decoded);
            }
        } else {
            throw new IllegalArgumentException("Unsupported official tool");
        }
    }

    private static void invoke(Class<?> tool, String[] arguments) throws Exception {
        try {
            tool.getMethod("main", String[].class).invoke(null, (Object) arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
