import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.system.MemoryStack;

import java.io.PrintWriter;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Sonda de FBO do M4. Reproduz TextureFBO.initInternal (Project Zomboid 42.21) com os mesmos formatos,
 * tamanhos, ordem de chamadas e estado previo, e varia depth/stencil/ordem.
 * Uso: FboProbe <saida.txt> [samples=0] [debugctx=1]
 * Janela GLFW oculta, mesmos hints do jogo (OPENGL_API sem versao, alpha 0, depth 24, stencil 8, samples N).
 */
public class FboProbe {
    static PrintWriter out;
    static final List<String> dbgMsgs = new ArrayList<>();
    static boolean haveDbg = false;

    static void p(String s) { System.out.println(s); out.println(s); out.flush(); }

    static String hex(int v) { return "0x" + Integer.toHexString(v).toUpperCase(); }

    static String statusName(int s) {
        switch (s) {
            case 0x8CD5: return "COMPLETE";
            case 0x8219: return "UNDEFINED";
            case 0x8CD6: return "INCOMPLETE_ATTACHMENT";
            case 0x8CD7: return "INCOMPLETE_MISSING_ATTACHMENT";
            case 0x8CD9: return "INCOMPLETE_DIMENSIONS";
            case 0x8CDA: return "INCOMPLETE_FORMATS";
            case 0x8CDB: return "INCOMPLETE_DRAW_BUFFER";
            case 0x8CDC: return "INCOMPLETE_READ_BUFFER";
            case 0x8CDD: return "UNSUPPORTED";
            case 0x8D56: return "INCOMPLETE_MULTISAMPLE";
            case 0: return "ZERO(erro)";
            default: return hex(s);
        }
    }

    static String errName(int e) {
        switch (e) {
            case 0x500: return "INVALID_ENUM";
            case 0x501: return "INVALID_VALUE";
            case 0x502: return "INVALID_OPERATION";
            case 0x505: return "OUT_OF_MEMORY";
            case 0x506: return "INVALID_FRAMEBUFFER_OPERATION";
            default: return hex(e);
        }
    }

    // ---- erros por passo ----
    static StringBuilder stepErrs;
    static void chk(String step) {
        int e;
        int guard = 0;
        while ((e = GL11C.glGetError()) != 0 && guard++ < 8) {
            stepErrs.append(step).append('=').append(errName(e)).append(' ');
        }
    }

    // ---- configuracao de um caso ----
    // depth: NONE, TEX16, TEX24, TEX32F, TEX24S8, TEX32FS8, RB16, RB24, RB32F, RB24S8, RB32FS8
    // stencilAttach: SEP (DEPTH_ATTACHMENT + STENCIL_ATTACHMENT com o mesmo RB, como o jogo), COMB (DEPTH_STENCIL_ATTACHMENT)
    static class Cfg {
        String group, name;
        int w, h;               // tamanho HW (potencia de dois) usado em glTexImage2D / glRenderbufferStorage
        int dw, dh;             // tamanho da textura de profundidade
        String depth = "NONE";
        String stencilAttach = "SEP";
        boolean depthFirst = false;     // anexa depth antes da cor
        boolean gameSeq = true;         // dupla glTexImage2D como o jogo (TextureID + initInternal)
        int colorInternal = 0x1908;     // GL_RGBA (nao dimensionado), como o jogo
        String pre = "none";            // none | fbo | fbo-s | rb | tex | fbo+rb+tex
        boolean colorAsRb = false;      // cor em renderbuffer (variante)
        Cfg(String group, String name, int w, int h, String depth) {
            this.group = group; this.name = name; this.w = w; this.h = h; this.dw = w; this.dh = h; this.depth = depth;
        }
        Cfg pre(String p) { this.pre = p; return this; }
        Cfg sa(String s) { this.stencilAttach = s; return this; }
        Cfg df() { this.depthFirst = true; return this; }
        Cfg ci(int c) { this.colorInternal = c; return this; }
        Cfg gs(boolean b) { this.gameSeq = b; return this; }
    }

    static class Res {
        int status; String errs; String info; String dbg;
        boolean ok() { return status == 0x8CD5; }
    }

    // internalFormat, format, type para texturas de profundidade
    static int[] depthTexFmt(String d) {
        switch (d) {
            case "TEX16": return new int[]{0x81A5, 0x1902, 0x1403};
            case "TEX24": return new int[]{0x81A6, 0x1902, 0x1405};
            case "TEX32F": return new int[]{0x8CAC, 0x1902, 0x1406};
            case "TEX24S8": return new int[]{0x88F0, 0x84F9, 0x84FA};
            case "TEX32FS8": return new int[]{0x8CAD, 0x84F9, 0x8DAD};
            default: return null;
        }
    }

    static int rbFmt(String d) {
        switch (d) {
            case "RB16": return 0x81A5;
            case "RB24": return 0x81A6;
            case "RB32F": return 0x8CAC;
            case "RB24S8": return 0x88F0;
            case "RB32FS8": return 0x8CAD;
            default: return 0;
        }
    }

    // ---- estado previo ----
    static int preFbo, preFboTex, preFboRb, preTex, preRb;

    static void setupPre(String pre) {
        preFbo = preFboTex = preFboRb = preTex = preRb = 0;
        if (pre.startsWith("fbo")) {
            preFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, preFbo);
            preFboTex = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, preFboTex);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 0x1908, 512, 512, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, preFboTex, 0);
            preFboRb = GL30C.glGenRenderbuffers();
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, preFboRb);
            if (pre.equals("fbo-s")) {
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, 0x88F0, 512, 512);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, preFboRb);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, 0x8D20, GL30C.GL_RENDERBUFFER, preFboRb);
            } else {
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, 0x81A5, 512, 512);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, preFboRb);
            }
            if (!pre.contains("rb")) GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
            if (!pre.contains("tex")) GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        }
        if (pre.equals("rb")) {
            preRb = GL30C.glGenRenderbuffers();
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, preRb);
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, 0x81A5, 64, 64);
        }
        if (pre.equals("tex")) {
            preTex = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, preTex);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 0x1908, 64, 64, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }
        if (pre.contains("pbo")) {
            prePbo = org.lwjgl.opengl.GL15C.glGenBuffers();
            org.lwjgl.opengl.GL15C.glBindBuffer(0x88EC /*PIXEL_UNPACK_BUFFER*/, prePbo);
            org.lwjgl.opengl.GL15C.glBufferData(0x88EC, pre.contains("pbosmall") ? 16L : (64L << 20), 0x88E0 /*STREAM_DRAW*/);
        }
        if (pre.contains("unit3")) org.lwjgl.opengl.GL13C.glActiveTexture(0x84C0 + 3);
        if (pre.contains("rowlen")) { GL11C.glPixelStorei(0x0CF2 /*UNPACK_ROW_LENGTH*/, 33); GL11C.glPixelStorei(0x0CF5 /*UNPACK_ALIGNMENT*/, 8); GL11C.glPixelStorei(0x0CF3, 3); }
        if (pre.contains("enabl")) {
            for (int cap : new int[]{GL11C.GL_DEPTH_TEST, GL11C.GL_BLEND, GL11C.GL_SCISSOR_TEST, GL11C.GL_STENCIL_TEST, 0x8DB9 /*FRAMEBUFFER_SRGB*/, 0x809D /*MULTISAMPLE*/, 0x0BC0, GL11C.GL_CULL_FACE, 0x0BE2})
                GL11C.glEnable(cap);
            GL11C.glScissor(0, 0, 3, 3);
            GL11C.glViewport(0, 0, 7, 7);
        }
        if (pre.contains("drawnone")) { GL11C.glDrawBuffer(GL11C.GL_NONE); GL11C.glReadBuffer(GL11C.GL_NONE); }
        if (pre.contains("cmask")) { GL11C.glColorMask(false, false, false, false); GL11C.glDepthMask(false); }
        while (GL11C.glGetError() != 0) { }
    }
    static int prePbo;

    static void teardownPre() {
        if (prePbo != 0) { org.lwjgl.opengl.GL15C.glBindBuffer(0x88EC, 0); org.lwjgl.opengl.GL15C.glDeleteBuffers(prePbo); prePbo = 0; }
        org.lwjgl.opengl.GL13C.glActiveTexture(0x84C0);
        GL11C.glPixelStorei(0x0CF2, 0); GL11C.glPixelStorei(0x0CF5, 4); GL11C.glPixelStorei(0x0CF3, 0);
        for (int cap : new int[]{GL11C.GL_DEPTH_TEST, GL11C.GL_BLEND, GL11C.GL_SCISSOR_TEST, GL11C.GL_STENCIL_TEST, 0x8DB9, 0x0BC0, GL11C.GL_CULL_FACE, 0x0BE2})
            GL11C.glDisable(cap);
        GL11C.glEnable(0x809D);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GL11C.glDrawBuffer(0x0405); GL11C.glReadBuffer(0x0405);
        GL11C.glColorMask(true, true, true, true); GL11C.glDepthMask(true);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        if (preFbo != 0) GL30C.glDeleteFramebuffers(preFbo);
        if (preFboTex != 0) GL11C.glDeleteTextures(preFboTex);
        if (preFboRb != 0) GL30C.glDeleteRenderbuffers(preFboRb);
        if (preTex != 0) GL11C.glDeleteTextures(preTex);
        if (preRb != 0) GL30C.glDeleteRenderbuffers(preRb);
        while (GL11C.glGetError() != 0) { }
    }

    static int texLevelParam(int tex, int pname) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
        return GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, pname);
    }

    // ---- reproducao de TextureID.generateHwId(false) para a textura de cor (flags=16) ----
    static int makeColorTex(Cfg c) {
        int id = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        if (c.gameSeq) GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, c.colorInternal, c.w, c.h, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return id;
    }

    // flags=512: DEPTH_COMPONENT16, NEAREST, format DEPTH_COMPONENT, type UNSIGNED_SHORT
    static int makeDepthTex(Cfg c, int[] f) {
        int id = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, f[0], c.dw, c.dh, 0, f[1], f[2], (java.nio.ByteBuffer) null);
        return id;
    }

    static Res run(Cfg c) {
        Res r = new Res();
        stepErrs = new StringBuilder();
        dbgMsgs.clear();
        setupPre(c.pre);
        int prevBound = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        int fbo = 0, colorTex = 0, depthTex = 0, rb = 0, colorRb = 0;
        try {
            int[] dtf = depthTexFmt(c.depth);
            // Texture(w,h,16) e Texture(w,h,512) criadas antes do TextureFBO (generateHwId)
            if (!c.colorAsRb) { colorTex = makeColorTex(c); chk("colorTexCreate"); }
            if (dtf != null) { depthTex = makeDepthTex(c, dtf); chk("depthTexCreate"); }

            // --- TextureFBO.initInternal ---
            chk("Enter");
            fbo = GL30C.glGenFramebuffers(); chk("glGenFramebuffers");
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo); chk("glBindFramebuffer");

            Runnable attachColor = () -> {
                if (c.colorAsRb) {
                    int cr = GL30C.glGenRenderbuffers();
                    GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, cr);
                    GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, 0x8058, c.w, c.h);
                    GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
                    GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, cr);
                    chk("colorRb");
                    return;
                }
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colorTexHolder[0]); chk("texture.bind");
                if (c.gameSeq) {
                    GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, c.colorInternal, c.w, c.h, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                } else {
                    GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, c.colorInternal, c.w, c.h, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                }
                chk("glTexImage2D(color)");
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL30C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL30C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colorTexHolder[0], 0);
                chk("glFramebufferTexture2D(color)");
            };
            colorTexHolder[0] = colorTex;

            if (!c.depthFirst) attachColor.run();

            // profundidade
            if (depthTex == 0 && !c.depth.equals("NONE")) {
                rb = GL30C.glGenRenderbuffers(); chk("glGenRenderbuffers");
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, rb); chk("glBindRenderbuffer");
                int fmt = rbFmt(c.depth);
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, fmt, c.dw, c.dh); chk("glRenderbufferStorage");
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
                boolean hasStencil = c.depth.equals("RB24S8") || c.depth.equals("RB32FS8");
                if (hasStencil && c.stencilAttach.equals("COMB")) {
                    GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, 0x821A, GL30C.GL_RENDERBUFFER, rb); chk("fbRb(DEPTH_STENCIL)");
                } else {
                    GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, rb); chk("fbRb(depth)");
                    if (hasStencil) { GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, 0x8D20, GL30C.GL_RENDERBUFFER, rb); chk("fbRb(stencil)"); }
                }
            } else if (depthTex != 0) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex); // depthTexture.bind()
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
                boolean hasStencil = c.depth.equals("TEX24S8") || c.depth.equals("TEX32FS8");
                if (hasStencil && c.stencilAttach.equals("COMB")) {
                    GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, 0x821A, GL11C.GL_TEXTURE_2D, depthTex, 0); chk("fbTex(DEPTH_STENCIL)");
                } else {
                    GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depthTex, 0); chk("fbTex(depth)");
                    if (hasStencil) { GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, 0x8D20, GL11C.GL_TEXTURE_2D, depthTex, 0); chk("fbTex(stencil)"); }
                }
            }
            if (c.depthFirst) attachColor.run();

            r.status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            chk("glCheckFramebufferStatus");

            // info de formatos reais escolhidos pelo driver
            StringBuilder inf = new StringBuilder();
            if (colorTex != 0) {
                inf.append("corIF=").append(hex(texLevelParam(colorTex, 0x1003 + 0))); // GL_TEXTURE_INTERNAL_FORMAT=0x1003
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            }
            if (depthTex != 0) {
                inf.append(" depIF=").append(hex(texLevelParam(depthTex, 0x1003)));
                inf.append(" depBits=").append(texLevelParam(depthTex, 0x884A));
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            while (GL11C.glGetError() != 0) { }
            r.info = inf.toString();
        } catch (Throwable t) {
            r.status = 0;
            stepErrs.append("EXC=").append(t);
        } finally {
            // o jogo rebinda o FBO anterior (lastID)
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevBound);
            if (fbo != 0) GL30C.glDeleteFramebuffers(fbo);
            if (colorTex != 0) GL11C.glDeleteTextures(colorTex);
            if (depthTex != 0) GL11C.glDeleteTextures(depthTex);
            if (rb != 0) GL30C.glDeleteRenderbuffers(rb);
            teardownPre();
        }
        r.errs = stepErrs.toString().trim();
        synchronized (dbgMsgs) { r.dbg = String.join(" || ", dbgMsgs); }
        return r;
    }
    static final int[] colorTexHolder = new int[1];

    // ---- Modo "game": usa as classes reais do jogo (TextureFBO/Texture) no contexto da sonda, sem abrir o jogo ----
    static long WIN;
    static void frame() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GL11C.glViewport(0, 0, 1366, 768);
        GL11C.glClearColor(0.2f, 0.1f, 0.4f, 1f);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT | GL11C.GL_STENCIL_BUFFER_BIT);
        // FBO de desenho com textura, para forcar trabalho real da GPU
        int f = GL30C.glGenFramebuffers(); GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, f);
        int t = GL11C.glGenTextures(); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 0x1908, 1024, 1024, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, t, 0);
        GL11C.glViewport(0, 0, 1024, 1024);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GL11C.glViewport(0, 0, 1366, 768);
        GL11C.glEnable(GL11C.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glBegin(7);
        org.lwjgl.opengl.GL11.glTexCoord2f(0, 0); org.lwjgl.opengl.GL11.glVertex2f(-1, -1); org.lwjgl.opengl.GL11.glTexCoord2f(1, 0); org.lwjgl.opengl.GL11.glVertex2f(1, -1);
        org.lwjgl.opengl.GL11.glTexCoord2f(1, 1); org.lwjgl.opengl.GL11.glVertex2f(1, 1); org.lwjgl.opengl.GL11.glTexCoord2f(0, 1); org.lwjgl.opengl.GL11.glVertex2f(-1, 1);
        org.lwjgl.opengl.GL11.glEnd();
        GL11C.glDisable(GL11C.GL_TEXTURE_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL11C.glFinish();
        GLFW.glfwSwapBuffers(WIN);
        GL30C.glDeleteFramebuffers(f); GL11C.glDeleteTextures(t);
        while (GL11C.glGetError() != 0) { }
    }

    static void gameClasses() {
        p("");
        p("## Classes reais do jogo (zombie.core.textures.*) no contexto da sonda");
        try {
            java.lang.reflect.Field ct = zombie.core.opengl.RenderThread.class.getDeclaredField("contextThread");
            ct.setAccessible(true); ct.set(null, Thread.currentThread());
            java.lang.reflect.Field ii = zombie.core.opengl.RenderThread.class.getDeclaredField("isInitialized");
            ii.setAccessible(true); ii.setBoolean(null, true);
            zombie.core.opengl.RenderThread.renderThread = Thread.currentThread();
        } catch (Throwable t) { p("falha ao preparar RenderThread: " + t); t.printStackTrace(); return; }
        // quadros reais: desenha, troca buffers (SwapBuffers) e so entao cria o FBO, como no inicio do mundo
        for (int fr = 0; fr < 5; fr++) frame();
        p("(5 quadros desenhados e apresentados antes da criacao)");
        int[][] sizes = {{1024, 1024}, {2048, 1024}, {4096, 2048}};
        for (String pre : new String[]{"none", "fbo", "fbo-s+pbo+unit3+rowlen+enabl+drawnone+cmask+rb+tex"}) {
            for (int[] sz : sizes) {
                for (int kind = 0; kind < 3; kind++) {
                    String nome = kind == 0 ? "Atlas(cor16+depth512)" : kind == 1 ? "ModelOutlines(cor16+RB D16)" : "stencil(cor16+RB D24S8)";
                    setupPre(pre);
                    stepErrs = new StringBuilder();
                    dbgMsgs.clear();
                    String res;
                    try {
                        zombie.core.textures.Texture t = new zombie.core.textures.Texture(sz[0], sz[1], 16);
                        zombie.core.textures.TextureFBO f;
                        if (kind == 0) {
                            zombie.core.textures.Texture d = new zombie.core.textures.Texture(sz[0], sz[1], 512);
                            f = new zombie.core.textures.TextureFBO(t, d, false);
                        } else {
                            f = new zombie.core.textures.TextureFBO(t, kind == 2);
                        }
                        res = "COMPLETE (id=" + f.getBufferId() + ")";
                    } catch (Throwable ex) {
                        res = "EXCECAO: " + ex;
                        Throwable c = ex.getCause(); if (c != null) res += " / " + c;
                    }
                    synchronized (dbgMsgs) { p("| jogo | " + nome + " | " + sz[0] + "x" + sz[1] + " | pre=" + pre + " | " + res + " | glerr=" + errName(GL11C.glGetError()) + " | " + String.join(" || ", dbgMsgs)); }
                    teardownPre();
                }
            }
        }
    }


    // ---- Teste de pressao: acumula FBOs (como o jogo faz ao longo do mundo) ate o status deixar de ser COMPLETE ----
    static long freeMb() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize() / (1024 * 1024);
        } catch (Throwable t) { return -1; }
    }

    static final List<byte[]> HOG = new ArrayList<>();
    static void hog(long mb) {
        long before = freeMb();
        for (long i = 0; i < mb; i += 64) {
            byte[] b = new byte[64 * 1024 * 1024];
            java.util.Arrays.fill(b, (byte) 0x5A);
            HOG.add(b);
        }
        p("heap ocupada: " + mb + " MB (RAM livre " + before + " -> " + freeMb() + " MB)");
    }

    static void stress(String label, int w, int h, boolean depthTex, long capMb, int maxCount) {
        p("");
        p("## Pressao: " + label + " (" + w + "x" + h + ", " + (depthTex ? "cor+depth tex D16" : "cor+RB D16") + "), teto " + capMb + " MB, RAM livre inicial " + freeMb() + " MB");
        List<int[]> keep = new ArrayList<>();
        long perMb = ((long) w * h * 4 + (long) w * h * 2) / (1024 * 1024);
        if (perMb < 1) perMb = 1;
        int n = 0;
        String stopReason = "teto";
        long minFree = Long.MAX_VALUE;
        for (; n < maxCount; n++) {
            if ((long) (n + 1) * perMb > capMb) break;
            long fm = freeMb();
            if (fm >= 0) { minFree = Math.min(minFree, fm); if (fm < 500) { stopReason = "RAM livre < 500 MB"; break; } }
            Cfg c = new Cfg("S", label, w, h, depthTex ? "TEX16" : "RB16");
            int[] objs = new int[4];
            stepErrs = new StringBuilder();
            dbgMsgs.clear();
            int fbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            int ct = makeColorTex(c);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ct);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 0x1908, w, h, 0, 0x1908, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL30C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL30C.GL_CLAMP_TO_EDGE);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, ct, 0);
            int dt = 0, rb = 0;
            if (depthTex) {
                dt = makeDepthTex(c, depthTexFmt("TEX16"));
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, dt, 0);
            } else {
                rb = GL30C.glGenRenderbuffers();
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, rb);
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, 0x81A5, w, h);
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, rb);
            }
            int st = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            chk("tudo");
            if (st == 0x8CD5) {
                GL11C.glClearColor(0.1f, 0.2f, 0.3f, 1f);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                GL11C.glFinish();
                chk("clear");
            }
            objs[0] = fbo; objs[1] = ct; objs[2] = dt; objs[3] = rb;
            keep.add(objs);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            if (st != 0x8CD5 || stepErrs.length() > 0) {
                synchronized (dbgMsgs) {
                    p("PRIMEIRA FALHA no FBO #" + (n + 1) + ": status=" + statusName(st) + " erros=" + stepErrs.toString().trim()
                            + " msgs=" + String.join(" || ", dbgMsgs) + " ~" + ((long) (n + 1) * perMb) + " MB alocados, RAM livre " + freeMb() + " MB");
                }
                stopReason = "falha";
                n++;
                break;
            }
        }
        p("criados=" + n + " (~" + ((long) n * perMb) + " MB), motivo da parada=" + stopReason + ", RAM livre minima=" + (minFree == Long.MAX_VALUE ? -1 : minFree) + " MB");
        if (stopReason.equals("falha")) {
            // pequeno ainda funciona depois da falha?
            Cfg small = new Cfg("S", "pos-falha 256x256", 256, 256, "TEX16");
            Res rs = run(small);
            p("pos-falha 256x256 TEX16: " + statusName(rs.status) + " " + rs.errs + " " + rs.dbg);
            Cfg atlas = new Cfg("S", "pos-falha Atlas 1024", 1024, 1024, "TEX16");
            Res ra = run(atlas);
            p("pos-falha Atlas 1024 TEX16: " + statusName(ra.status) + " " + ra.errs + " " + ra.dbg);
        }
        for (int[] o : keep) {
            GL30C.glDeleteFramebuffers(o[0]); GL11C.glDeleteTextures(o[1]);
            if (o[2] != 0) GL11C.glDeleteTextures(o[2]);
            if (o[3] != 0) GL30C.glDeleteRenderbuffers(o[3]);
        }
        while (GL11C.glGetError() != 0) { }
        Cfg after = new Cfg("S", "apos liberar Atlas 1024", 1024, 1024, "TEX16");
        Res r2 = run(after);
        p("apos liberar tudo, Atlas 1024 TEX16: " + statusName(r2.status) + " " + r2.errs);
    }


    static void report(Cfg c, Res r) {
        String line = String.format("| %s | %s | %dx%d | dep=%s%s | pre=%s | %s%s%s | **%s** | %s | %s | %s |",
                c.group, c.name, c.w, c.h, c.depth,
                (c.depth.contains("S8") ? "/" + c.stencilAttach : ""), c.pre,
                c.depthFirst ? "depth-primeiro " : "", c.gameSeq ? "" : "texImage-unica ", c.colorInternal == 0x1908 ? "RGBA" : "RGBA8",
                statusName(r.status), r.errs.isEmpty() ? "-" : r.errs, r.info == null ? "" : r.info,
                r.dbg.isEmpty() ? "-" : r.dbg.replace("|", "/"));
        p(line);
    }

    public static void main(String[] a) throws Exception {
        out = new PrintWriter(new java.io.FileWriter(a[0]));
        int samples = a.length > 1 ? Integer.parseInt(a[1]) : 0;
        boolean debugCtx = a.length < 3 || !a[2].equals("0");
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit falhou");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, samples);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, debugCtx ? 1 : 0);
        long win = GLFW.glfwCreateWindow(1366, 768, "FboProbe", 0, 0);
        if (win == 0) throw new IllegalStateException("glfwCreateWindow falhou");
        GLFW.glfwMakeContextCurrent(win);
        WIN = win;
        GLCapabilities caps = GL.createCapabilities();

        p("# FboProbe  os.arch=" + System.getProperty("os.arch") + " java=" + System.getProperty("java.version")
                + " samples(pedido)=" + samples + " debugctx=" + debugCtx);
        p("GL_VENDOR=" + GL11C.glGetString(GL11C.GL_VENDOR));
        p("GL_RENDERER=" + GL11C.glGetString(GL11C.GL_RENDERER));
        p("GL_VERSION=" + GL11C.glGetString(GL11C.GL_VERSION));
        p("GL_MAX_TEXTURE_SIZE=" + GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE));
        p("GL_MAX_RENDERBUFFER_SIZE=" + GL11C.glGetInteger(GL30C.GL_MAX_RENDERBUFFER_SIZE));
        p("GL_MAX_COLOR_ATTACHMENTS=" + GL11C.glGetInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS));
        p("GL_MAX_SAMPLES=" + GL11C.glGetInteger(GL30C.GL_MAX_SAMPLES));
        p("GL_SAMPLES(default fb)=" + GL11C.glGetInteger(0x80A9));
        p("default fb bits: depth=" + GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER, 0x1801, 0x8216)
                + " stencil=" + GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER, 0x1802, 0x8217)
                + " alpha=" + GL11C.glGetInteger(0x0D55));
        while (GL11C.glGetError() != 0) { }
        p("GL_KHR_debug=" + caps.GL_KHR_debug + " OpenGL43=" + caps.OpenGL43 + " ARB_framebuffer_object=" + caps.GL_ARB_framebuffer_object
                + " ARB_texture_non_power_of_two=" + caps.GL_ARB_texture_non_power_of_two + " ARB_depth_texture=" + caps.GL_ARB_depth_texture);
        p("GL_CONTEXT_FLAGS=" + hex(GL11C.glGetInteger(0x821E)) + " (debug=0x2)");
        if (caps.GL_KHR_debug || caps.OpenGL43) {
            haveDbg = true;
            GLDebugMessageCallback cb = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
                String m = GLDebugMessageCallback.getMessage(length, message).trim();
                synchronized (dbgMsgs) { dbgMsgs.add("[" + hex(source) + "/" + hex(type) + "/" + hex(severity) + "] " + m); }
            });
            KHRDebug.glDebugMessageCallback(cb, 0);
            GL11C.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
            GL11C.glEnable(KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            KHRDebug.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, (IntBuffer) null, true);
            p("debug callback: ativo");
        } else {
            p("debug callback: indisponivel");
        }
        while (GL11C.glGetError() != 0) { }
        p("");
        p("| grupo | caso | tamanho HW | profundidade | estado previo | cor | status | erros GL (por passo) | formatos reais | mensagens do driver |");
        p("|---|---|---|---|---|---|---|---|---|---|");

        if (a.length > 5 && Long.parseLong(a[5]) > 0) hog(Long.parseLong(a[5]));
        List<Cfg> cases = new ArrayList<>();
        String[] pres = {"none", "fbo", "fbo-s", "fbo+rb+tex", "rb", "tex",
                "pbo", "pbosmall", "unit3", "rowlen", "enabl", "fbo+drawnone", "cmask",
                "fbo-s+pbo+unit3+rowlen+enabl+drawnone+cmask+rb+tex"};

        // A. Chamadas reais do jogo, com estado previo variado
        for (String pre : pres) {
            cases.add(new Cfg("A.Atlas", "DeadBodyAtlas$Atlas 1024x1024 cor16+depth512", 1024, 1024, "TEX16").pre(pre));
        }
        // Core.width x Core.height possiveis -> HW (potencia de dois): ModelOutlines (cor + RB D16, stencil=false)
        int[][] scr = {{1366, 768, 2048, 1024}, {1280, 720, 2048, 1024}, {1920, 1080, 2048, 2048}, {1920, 1200, 2048, 2048}, {2880, 1920, 4096, 2048}};
        for (int[] s : scr) {
            for (String pre : pres) {
                cases.add(new Cfg("A.ModelOutlines", "fbo cor16+RB D16 (Core " + s[0] + "x" + s[1] + ")", s[2], s[3], "RB16").pre(pre));
            }
        }
        // combinedFbo: Core*2.5 -> HW ; cor 16 + depth 512, stencil=false
        int[][] comb = {{1366, 768, 4096, 2048}, {1280, 720, 4096, 2048}, {1920, 1080, 8192, 4096}};
        for (int[] s : comb) {
            for (String pre : new String[]{"none", "fbo", "fbo+rb+tex", "fbo-s+pbo+unit3+rowlen+enabl+drawnone+cmask+rb+tex"}) {
                cases.add(new Cfg("A.combinedFbo", "combinedFbo cor16+depth512 (Core " + s[0] + "x" + s[1] + ")", s[2], s[3], "TEX16").pre(pre));
            }
        }

        // B. Variacoes de profundidade para os tamanhos reais
        int[][] vs = {{1024, 1024}, {2048, 1024}, {4096, 2048}};
        String[] depths = {"NONE", "TEX16", "TEX24", "TEX32F", "TEX24S8", "TEX32FS8", "RB16", "RB24", "RB32F", "RB24S8", "RB32FS8"};
        for (int[] s : vs) {
            for (String d : depths) {
                cases.add(new Cfg("B.variantes", "profundidade " + d, s[0], s[1], d));
                if (d.endsWith("S8")) cases.add(new Cfg("B.variantes", "profundidade " + d, s[0], s[1], d).sa("COMB"));
            }
            // ordem de anexacao
            cases.add(new Cfg("B.ordem", "depth antes da cor TEX16", s[0], s[1], "TEX16").df());
            cases.add(new Cfg("B.ordem", "depth antes da cor RB16", s[0], s[1], "RB16").df());
            // formato de cor
            cases.add(new Cfg("B.cor", "cor RGBA8 + TEX16", s[0], s[1], "TEX16").ci(0x8058));
            cases.add(new Cfg("B.cor", "cor RGBA8 + RB16", s[0], s[1], "RB16").ci(0x8058));
            cases.add(new Cfg("B.cor", "cor RGBA sem dupla texImage + TEX16", s[0], s[1], "TEX16").gs(false));
        }

        // C. Tamanhos da profundidade vs cor (uma delas maior/menor)
        Cfg mm = new Cfg("C.dim", "depth 512x512 com cor 1024x1024 (TEX16)", 1024, 1024, "TEX16"); mm.dw = 512; mm.dh = 512; cases.add(mm);

        int ok = 0, fail = 0;
        for (Cfg c : cases) {
            Res r = run(c);
            report(c, r);
            if (r.ok()) ok++; else fail++;
        }
        p("");
        p("RESUMO: casos=" + cases.size() + " COMPLETE=" + ok + " nao-COMPLETE=" + fail);
        if (a.length > 3 && a[3].equals("game")) {
            gameClasses();
        }
        if (a.length > 3 && a[3].equals("stress")) {
            long cap = a.length > 4 ? Long.parseLong(a[4]) : 1500;
            stress("Atlas", 1024, 1024, true, cap, 400);
            stress("ModelOutlines", 2048, 1024, false, cap, 100);
            stress("combinedFbo", 4096, 2048, true, cap, 60);
            stress("tiny", 16, 16, true, cap, 3000);
        }
        out.close();
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
        System.exit(0);
    }
}
