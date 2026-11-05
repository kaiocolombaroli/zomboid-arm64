import java.io.IOException;
import java.io.Writer;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Modo fps do pzarm-agent: conta frames em org.lwjglx.opengl.Display (a classe do jogo que chama
 * GLFW.glfwSwapBuffers) e grava fps-ARCH-TIMESTAMP.csv uma vez por segundo: t_ms,frames,ram_livre_mb.
 * "frames" = quadros no ultimo intervalo; t_ms = tempo desde o primeiro swap. Funciona em Java 25 x64 e ARM64.
 */
public final class FpsCounter {
    static final String TARGET = "org/lwjglx/opengl/Display";
    static final String GLFW = "org/lwjgl/glfw/GLFW";
    static volatile long frames;
    static volatile boolean started;
    static Path csv;

    static void install(Instrumentation inst, Path dir) {
        String arch = System.getProperty("os.arch", "unknown").replaceAll("[^A-Za-z0-9_]", "");
        csv = dir.toAbsolutePath().resolve("fps-" + arch + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        try { Files.createDirectories(csv.getParent()); }
        catch (IOException e) { PzArmAgent.fatal("nao foi possivel criar " + csv.getParent(), e); }
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!TARGET.equals(name)) return null;
                try { return instrument(buf); }
                catch (Throwable t) { PzArmAgent.fatal("falha ao transformar " + name, t); return null; }
            }
        });
        System.err.println("[pzarm-agent] modo fps ativo, csv: " + csv);
    }

    static byte[] instrument(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] replaced = {0};
        ClassDesc me = ClassDesc.of("FpsCounter");
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKESTATIC
                    && ii.owner().asInternalName().equals(GLFW)
                    && ii.name().equalsString("glfwSwapBuffers") && ii.type().equalsString("(J)V")) {
                b.invokestatic(me, "swap", MethodTypeDesc.ofDescriptor("(J)V"));
                replaced[0]++;
            } else b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(ct));
        if (replaced[0] < 1) throw new IllegalStateException("nenhuma chamada a glfwSwapBuffers em " + TARGET);
        return out;
    }

    /** No lugar de GLFW.glfwSwapBuffers(window): chama o original e conta o quadro. */
    public static void swap(long window) {
        org.lwjgl.glfw.GLFW.glfwSwapBuffers(window);
        frames++;
        if (!started) { started = true; startWriter(); }
    }

    static long freeRamMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
                return os.getFreeMemorySize() / (1024 * 1024);
        } catch (Throwable ignored) { }
        return -1;
    }

    static void startWriter() {
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime(), last = 0;
            try (Writer w = Files.newBufferedWriter(csv)) {
                w.write("t_ms,frames,ram_livre_mb\n");
                w.flush();
                while (true) {
                    Thread.sleep(1000);
                    long f = frames, ms = (System.nanoTime() - t0) / 1_000_000;
                    w.write(ms + "," + (f - last) + "," + freeRamMb() + "\n");
                    w.flush();
                    last = f;
                }
            } catch (InterruptedException | IOException e) { /* fim */ }
        }, "pzarm-fps");
        t.setDaemon(true);
        t.start();
    }
}
