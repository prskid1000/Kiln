package app.kiln.toolserver;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * The warm JVM behind every Kiln build.
 *
 * Starting a JVM and JIT-warming the Kotlin compiler costs seconds on a phone;
 * paying that once per session instead of once per build is the single largest
 * speed win in the build engine. Kiln keeps this process alive and sends it one
 * request per line on stdin:
 *
 *     base64( id \0 tool \0 arg1 \0 arg2 … )
 *
 * and reads one line per request on stdout:
 *
 *     RES <id> <exitCode> <base64(combined tool output)>
 *
 * Tools run in-process, one at a time. Paths must be absolute (the JVM cannot
 * chdir). A tool calling System.exit is trapped by a SecurityManager, so the
 * server survives it; needs -Djava.security.manager=allow on JDK 21.
 *
 * Tools: kotlinc, javac, d8, apksigner, ping.
 */
public final class ToolServer {

    /** Thrown in place of exiting the VM. */
    static final class ExitTrap extends SecurityException {
        final int status;
        ExitTrap(int status) { super("exit " + status); this.status = status; }
    }

    @SuppressWarnings("removal")
    static final class NoExit extends SecurityManager {
        volatile boolean trapping = false;
        @Override public void checkExit(int status) { if (trapping) throw new ExitTrap(status); }
        @Override public void checkPermission(java.security.Permission perm) { }
        @Override public void checkPermission(java.security.Permission perm, Object context) { }
    }

    private static final PrintStream REAL_OUT = System.out;

    @SuppressWarnings("removal")
    public static void main(String[] args) throws Exception {
        NoExit guard = new NoExit();
        System.setSecurityManager(guard);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        REAL_OUT.println("READY " + Runtime.version());
        REAL_OUT.flush();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] f = new String(Base64.getDecoder().decode(line.trim()), StandardCharsets.UTF_8).split("\0", -1);
            String id = f[0], tool = f[1];
            String[] toolArgs = Arrays.copyOfRange(f, 2, f.length);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
            PrintStream oldOut = System.out, oldErr = System.err;
            int code;
            System.setOut(cap);
            System.setErr(cap);
            guard.trapping = true;
            try {
                code = run(tool, toolArgs, cap);
            } catch (ExitTrap e) {
                code = e.status;
            } catch (Throwable t) {
                Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                if (c instanceof ExitTrap) code = ((ExitTrap) c).status;
                else { c.printStackTrace(cap); code = 70; }
            } finally {
                guard.trapping = false;
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
            cap.flush();
            REAL_OUT.println("RES " + id + " " + code + " " + Base64.getEncoder().encodeToString(buf.toByteArray()));
            REAL_OUT.flush();
        }
    }

    private static int run(String tool, String[] a, PrintStream out) throws Exception {
        switch (tool) {
            case "ping":
                out.println("pong");
                return 0;
            case "kotlinc": {
                Class<?> c = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
                Object compiler = c.getDeclaredConstructor().newInstance();
                Method exec = c.getMethod("exec", PrintStream.class, String[].class);
                Object exit = exec.invoke(compiler, out, (Object) a);
                return (Integer) exit.getClass().getMethod("getCode").invoke(exit);
            }
            case "javac": {
                javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
                if (javac == null) { out.println("javac: jdk.compiler module missing"); return 2; }
                return javac.run(null, out, out, a);
            }
            case "d8": {
                Class<?> cmd = Class.forName("com.android.tools.r8.D8Command");
                Class<?> origin = Class.forName("com.android.tools.r8.origin.Origin");
                Object root = origin.getMethod("root").invoke(null);
                Object builder = cmd.getMethod("parse", String[].class, origin).invoke(null, a, root);
                Object command = builder.getClass().getMethod("build").invoke(builder);
                Class.forName("com.android.tools.r8.D8").getMethod("run", cmd).invoke(null, command);
                return 0;
            }
            case "apksigner": {
                Class.forName("com.android.apksigner.ApkSignerTool").getMethod("main", String[].class)
                    .invoke(null, (Object) a);
                return 0;
            }
            default:
                out.println("unknown tool: " + tool);
                return 64;
        }
    }
}
