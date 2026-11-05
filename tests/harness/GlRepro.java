import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * M7: reprodutor deterministico isolado do bug "textura errada com fboRenderChunk".
 * Uso: GlRepro <saida.txt> <rotulo> <dirImagens> [sprites=3000] [rounds=3] [shaderDir]
 * Imita FBORenderChunk (FBO cor RGBA + textura de profundidade D16 512, depthFunc LEQUAL, blend (ONE,ONE_MINUS_SRC_ALPHA,
 * ONE_MINUS_DST_ALPHA,ONE)) e os desenhos tile (tileWithDepth/opaqueWithDepth reais), sprite por sprite.
 */
public class GlRepro {
    static PrintWriter out;
    static void p(String s) { System.out.println(s); out.println(s); out.flush(); }

    static final int W = 1024, H = 1024;       // tamanho HW do FBO (ver relatorio: w,h reais vem de FBORenderLevels)
    static final int ATLAS = 256, CELL = 64, CELLS = 4;
    static int NDIFF = 64;
    static final int NDEPTH = 16;
    // ---- v3: ciclo real do jogo / texturas comprimidas / pressao de memoria ----
    static final boolean V3 = System.getProperty("v3", "0").equals("1");
    static final String FACTORS = System.getProperty("factors", "");
    static final boolean F_CYCLE = V3 && FACTORS.contains("1");   // fator 1: StartShader/EndShader + programa fixo + shader de piso
    static final boolean F_TEX = V3 && FACTORS.contains("2");     // fator 2: DXT/generico comprimido + mipmaps + paginas grandes e muitas
    static final boolean F_MEM = V3 && FACTORS.contains("3");     // fator 3: pressao de memoria de GPU
    static final int PAGES = F_TEX ? Integer.getInteger("pages", 200) : 64;
    static final int PSIZE = F_TEX ? Integer.getInteger("psize", 2048) : 256;
    static final int MEM_MB = Integer.getInteger("memmb", 1600);
    static final int FMT_COMPRESSED = 34030; // GL_COMPRESSED_RGBA_ARB, o internalFormat que TextureID usa com flag 4 e RGBA
    static long estBytes = 0;
    static boolean v3Made = false;
    static List<Sprite> lastScene;

    static String shaderDir;
    static int sprites = 3000;

    // ---- modos ----
    static class Mode {
        String name; String variant; int flush; // flush: 0 nada, 1 glFlush cada, 64 glFlush a cada 64, -1 glFinish cada
        boolean ring; int mapFlags = 38; boolean bufData = true; boolean flushMap;
        Mode(String n, String v, int f) { name = n; variant = v; flush = f; }
        Mode ring(int flags, boolean bufData, boolean flushMap) { this.ring = true; this.mapFlags = flags; this.bufData = bufData; this.flushMap = flushMap; return this; }
    }
    static final int PER_ROUND = Integer.getInteger("perRound", 16);
    static final boolean CHUNKS = System.getProperty("chunks", "0").equals("1");
    static final int RING = 128, RING_VB = 65536, RING_IB = 5460;

    static class Result { long ms; String mode; int round; long hash; byte[] color; float[] depth; int diffColor, diffDepth; String refName; }

    // ---- shaders ----
    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(shaderDir, f)), StandardCharsets.UTF_8); }

    static int compile(int type, String src, String what) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0)
            throw new RuntimeException("compile " + what + ": " + GL20C.glGetShaderInfoLog(s));
        return s;
    }

    static class Prog { int id, mvp, zDepth, zBlendZ, zBlendTo, diffuse, depth; }

    static Prog link(String vert, String frag, String name) {
        Prog pr = new Prog();
        int v = compile(GL20C.GL_VERTEX_SHADER, vert, name + ".vert");
        int f = compile(GL20C.GL_FRAGMENT_SHADER, frag, name + ".frag");
        pr.id = GL20C.glCreateProgram();
        GL20C.glAttachShader(pr.id, v);
        GL20C.glAttachShader(pr.id, f);
        GL20C.glLinkProgram(pr.id);
        if (GL20C.glGetProgrami(pr.id, GL20C.GL_LINK_STATUS) == 0)
            throw new RuntimeException("link " + name + ": " + GL20C.glGetProgramInfoLog(pr.id));
        pr.mvp = GL20C.glGetUniformLocation(pr.id, "ModelViewProjection");
        pr.zDepth = GL20C.glGetUniformLocation(pr.id, "zDepth");
        pr.zBlendZ = GL20C.glGetUniformLocation(pr.id, "zDepthBlendZ");
        pr.zBlendTo = GL20C.glGetUniformLocation(pr.id, "zDepthBlendToZ");
        pr.diffuse = GL20C.glGetUniformLocation(pr.id, "DIFFUSE");
        pr.depth = GL20C.glGetUniformLocation(pr.id, "DEPTH");
        return pr;
    }

    static String patchFrag(String frag, String variant) {
        if (variant.equals("semFragDepth")) return frag.replace("gl_FragDepth = calcDepthZ;", "");
        if (variant.equals("semDiscard")) return frag.replace("discard;", "gl_FragColor = vec4(0.0); gl_FragDepth = 1.0;");
        return frag;
    }

    // ---- texturas ----
    static int[] diffTex = new int[NDIFF], depthTex = new int[NDEPTH];
    static ByteBuffer[] diffData = new ByteBuffer[NDIFF], depthData = new ByteBuffer[NDEPTH];
    static final boolean CHURN = System.getProperty("churn", "0").equals("1");

    static void makeTextures() {
        Random r = new Random(1234);
        if (V3) makeV3Pages();
        for (int t = 0; !V3 && t < NDIFF; t++) {
            ByteBuffer b = BufferUtils.createByteBuffer(ATLAS * ATLAS * 4);
            float hue = t / (float) NDIFF;
            for (int y = 0; y < ATLAS; y++) for (int x = 0; x < ATLAS; x++) {
                int cx = x / CELL, cy = y / CELL, cell = cy * CELLS + cx;
                int rgb = java.awt.Color.HSBtoRGB((hue + cell * 0.037f) % 1f, 0.55f + 0.4f * ((cell * 7) % 5) / 4f, 0.5f + 0.5f * (((t + cell) * 3) % 4) / 3f);
                // alfa recortado tipo folhagem: buracos por ruido deterministico
                int lx = x % CELL, ly = y % CELL;
                double dx = lx - 32, dy = ly - 36;
                boolean in = dx * dx + dy * dy < 30 * 30;
                int noise = ((lx * 73856093) ^ (ly * 19349663) ^ (cell * 83492791) ^ (t * 40503)) & 0xff;
                int a = in ? (noise < 70 ? 0 : (noise < 100 ? 128 : 255)) : 0;
                if (cell % 5 == 0) a = 255; // sprites opacos (cerca/parede)
                b.put((byte) ((rgb >> 16) & 255)).put((byte) ((rgb >> 8) & 255)).put((byte) (rgb & 255)).put((byte) a);
            }
            b.flip();
            diffData[t] = b;
            diffTex[t] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, diffTex[t]);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, ATLAS, ATLAS, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, b);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        }
        for (int t = 0; t < NDEPTH; t++) {
            // formato real de TileDepthTexture: glTexImage2D(2D,0,GL_RED(6403),w,h,0,GL_RED,UNSIGNED_BYTE), NEAREST
            ByteBuffer b = BufferUtils.createByteBuffer(ATLAS * ATLAS);
            for (int y = 0; y < ATLAS; y++) for (int x = 0; x < ATLAS; x++) {
                int cx = x / CELL, cy = y / CELL, cell = cy * CELLS + cx;
                int lx = x % CELL, ly = y % CELL;
                int v;
                switch ((cell + t) % 4) {
                    case 0: v = 1 + lx * 254 / (CELL - 1); break;            // rampa horizontal
                    case 1: v = 1 + ly * 254 / (CELL - 1); break;            // rampa vertical
                    case 2: v = (lx < 20 || ly > 50) ? 0 : 60 + (lx + ly) * 2; break; // zonas zero
                    default: v = ((lx / 8 + ly / 8) & 1) == 0 ? 0 : 200; break;
                }
                b.put((byte) Math.min(255, v));
            }
            b.flip();
            depthData[t] = b;
            depthTex[t] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex[t]);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RED, ATLAS, ATLAS, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, b);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
    }

    static int[] pageRgb(int p) { return new int[]{40 + (p % 7) * 30, 40 + ((p / 7) % 7) * 30, 40 + (p / 49) * 30}; }

    static void makeV3Pages() {
        if (v3Made) return;
        v3Made = true;
        NDIFF = PAGES; diffTex = new int[PAGES]; diffData = new ByteBuffer[PAGES];
        int fmt = F_TEX ? FMT_COMPRESSED : GL11C.GL_RGBA;
        ByteBuffer buf = BufferUtils.createByteBuffer(PSIZE * PSIZE * 4);
        long t0 = System.nanoTime();
        for (int t = 0; t < PAGES; t++) {
            int[] c = pageRgb(t);
            int px = 0xFF000000 | (c[2] << 16) | (c[1] << 8) | c[0]; // memoria little-endian R,G,B,A
            buf.clear();
            IntBuffer ib = buf.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            for (int i = 0; i < PSIZE * PSIZE; i++) ib.put(i, px);
            diffTex[t] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, diffTex[t]);
            // TextureID.generateHwId: filtros ANTES do upload (min 9987 LINEAR_MIPMAP_LINEAR com mips, mag 9729), wrap REPEAT
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, F_TEX ? 9987 : GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, F_TEX ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            int levels = F_TEX ? 32 - Integer.numberOfLeadingZeros(PSIZE) : 1;
            for (int l = 0; l < levels; l++) {
                int sz = Math.max(1, PSIZE >> l);
                ByteBuffer sl = buf.duplicate(); sl.clear(); sl.limit(sz * sz * 4);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, l, fmt, sz, sz, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, sl);
                estBytes += F_TEX ? (long) Math.max(16, sz * sz) : (long) sz * sz * 4; // DXT5 = 1 byte/pixel
            }
            if (F_TEX) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, 33085 /*MAX_LEVEL*/, levels - 1);
            if (t == 0 && F_TEX) {
                int[] q = new int[1];
                GL11C.glGetTexLevelParameteriv(GL11C.GL_TEXTURE_2D, 0, 0x86A1 /*GL_TEXTURE_COMPRESSED*/, q);
                int a = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, 0x1003 /*INTERNAL_FORMAT*/);
                p("formato real pagina0: TEXTURE_COMPRESSED=" + q[0] + " INTERNAL_FORMAT=0x" + Integer.toHexString(a) + " (pedido 0x" + Integer.toHexString(fmt) + ")");
            }
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        p(String.format("paginas v3: %d x %dx%d fmt=0x%x mips=%b, estimativa em GPU (DXT5 se comprimido)=%d MB, upload %d ms", PAGES, PSIZE, PSIZE, fmt, F_TEX, estBytes >> 20, (System.nanoTime() - t0) / 1000000));
        if (GL.getCapabilities().GL_ATI_meminfo) { int[] v = new int[4]; GL11C.glGetIntegerv(0x87FC, v); p("GL_ATI_meminfo antes dos fillers kb=" + v[0]); }
        if (F_MEM) {
            long target = (long) MEM_MB << 20;
            long fixedExtra = 64L << 20; // FBOs/buffers/depth
            int n = 0;
            ByteBuffer fb = BufferUtils.createByteBuffer(2048 * 2048 * 4);
            while (estBytes + fixedExtra + (16L << 20) <= target) {
                int ft = GL11C.glGenTextures();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ft);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, 2048, 2048, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, fb);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
                fillers.add(ft); estBytes += 2048L * 2048 * 4; n++;
            }
            GL11C.glFinish();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            p(String.format("pressao de memoria: %d fillers 2048^2 RGBA, estimativa total=%d MB (alvo %d MB, limite real ~1770 MB)", n, (estBytes + fixedExtra) >> 20, MEM_MB));
        }
        if (GL.getCapabilities().GL_ATI_meminfo) {
            int[] v = new int[4]; GL11C.glGetIntegerv(0x87FC, v);
            p("GL_ATI_meminfo TEXTURE_FREE_MEMORY_ATI kb=" + v[0]);
        }
    }
    static List<Integer> fillers = new ArrayList<>();

    // ---- cena ----
    static class Sprite { int kind; int dt, dcell, pt, pcell; float x, y, w, h, z, bz, tz; boolean opaque; int col; }

    static List<Sprite> makeScene(int n) {
        Random r = new Random(987654321L), kr = new Random(555);
        List<Sprite> l = new ArrayList<>();
        int dt = 0, pt = 0;
        for (int i = 0; i < n; i++) {
            Sprite s = new Sprite();
            if (i % 3 == 0 || l.isEmpty()) { dt = r.nextInt(NDIFF); pt = r.nextInt(NDEPTH); } // pequenos lotes da mesma textura
            s.dt = dt; s.pt = pt;
            s.dcell = r.nextInt(CELLS * CELLS); s.pcell = r.nextInt(CELLS * CELLS);
            s.w = 64; s.h = 64 + 32 * r.nextInt(2);
            s.x = r.nextInt(W - 64); s.y = r.nextInt(H - 96);
            s.z = 0.15f + 0.7f * r.nextFloat();
            s.bz = s.z - 0.08f * r.nextFloat();
            s.tz = s.z + 0.08f * r.nextFloat();
            s.opaque = r.nextInt(5) == 0;
            int tint = r.nextInt(4);
            s.col = tint == 0 ? 0xFFFFFFFF : (0xFF000000 | (0x80 + r.nextInt(0x80)) << 16 | (0x80 + r.nextInt(0x80)) << 8 | (0x80 + r.nextInt(0x80)));
            if (F_CYCLE) { int k = kr.nextInt(10); s.kind = k < 6 ? 0 : (k < 8 ? 1 : 2); }
            if (V3) s.col = 0xFFFFFFFF;
            l.add(s);
        }
        return l;
    }

    static long hashBytes(byte[] b, float[] d) {
        long h = 1125899906842597L;
        for (byte x : b) h = 31 * h + x;
        for (float x : d) h = 31 * h + Float.floatToRawIntBits(x);
        return h;
    }

    static List<Integer> extraTex = new ArrayList<>();
    static int curProg = -1; // equivalente a ShaderHelper.currentlyBound

    static Result runOnce(Mode m, int round, int frames) throws Exception {
        String vert = read("tileWithDepth.vert");
        Prog tile = link(vert, patchFrag(read("tileWithDepth.frag"), m.variant), "tile");
        Prog opaq = link(read("opaqueWithDepth.vert"), patchFrag(read("opaqueWithDepth.frag"), m.variant), "opaque");
        makeTextures();

        // FBO como TextureFBO.initInternal(cor, depthTexture): cor RGBA (nao dimensionado), depth = textura D16 (512)
        int colTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colTex);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, W, H, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        int depTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depTex);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, 33189, W, H, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_SHORT, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        int fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colTex, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depTex, 0);
        int st = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (st != GL30C.GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("FBO status 0x" + Integer.toHexString(st));

        // buffers
        List<Sprite> scene = makeScene(sprites);
        if (m.ring) return runRing(m, round, frames, scene, tile, opaq, fbo, colTex, depTex);
        int vbo = GL15C.glGenBuffers(), ibo = GL15C.glGenBuffers();
        int vsize = sprites * 4 * 36, isize = sprites * 6 * 2;
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vsize, GL15C.GL_STREAM_DRAW);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, isize, GL15C.GL_STREAM_DRAW);

        FloatBuffer mvp = BufferUtils.createFloatBuffer(16);
        // x' = 2x/W-1, y' = 2y/H-1, z' = 2z-1 (zDepth em [0,1] vira profundidade zDepth)
        mvp.put(new float[]{2f / W, 0, 0, 0, 0, 2f / H, 0, 0, 0, 0, 2, 0, -1, -1, -1, 1}).flip();

        for (int frame = 0; frame < frames; frame++) {
            // --- como FBORenderChunk.startRenderThread: bind FBO, viewport, glEnable(DEPTH_TEST), glDepthFunc(ALWAYS), glDepthMask(true), clear depth 1 ---
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL11C.glViewport(0, 0, W, H);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glClearColor(0, 0, 0, 0);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);
            GL11C.glDepthMask(true);
            GL11C.glClearDepth(1.0);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT | GL11C.GL_COLOR_BUFFER_BIT);
            GL11C.glDepthFunc(GL11C.GL_LEQUAL); // 515
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE_MINUS_DST_ALPHA, GL11C.GL_ONE);

            // VBO/IBO mapeados uma vez por frame
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            ByteBuffer vb = GL30C.glMapBufferRange(GL15C.GL_ARRAY_BUFFER, 0, vsize, GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_INVALIDATE_BUFFER_BIT, null);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
            ByteBuffer ib = GL30C.glMapBufferRange(GL15C.GL_ELEMENT_ARRAY_BUFFER, 0, isize, GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_INVALIDATE_BUFFER_BIT, null);
            for (int i = 0; i < scene.size(); i++) {
                Sprite s = scene.get(i);
                float du0 = (s.dcell % CELLS) / (float) CELLS, dv0 = (s.dcell / CELLS) / (float) CELLS;
                float pu0 = (s.pcell % CELLS) / (float) CELLS, pv0 = (s.pcell / CELLS) / (float) CELLS;
                float cs = 1f / CELLS;
                float[][] corner = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                for (int c = 0; c < 4; c++) {
                    float fx = corner[c][0], fy = corner[c][1];
                    vb.putFloat(s.x + fx * s.w).putFloat(s.y + fy * s.h);
                    vb.putFloat(du0 + fx * cs).putFloat(dv0 + fy * cs);
                    vb.put((byte) (s.col >> 16)).put((byte) (s.col >> 8)).put((byte) s.col).put((byte) (s.col >>> 24)); // R G B A ubyte
                    vb.putFloat(pu0 + fx * cs).putFloat(pv0 + fy * cs);
                    vb.putFloat(0).putFloat(0);
                }
                int b = i * 4;
                ib.putShort((short) b).putShort((short) (b + 1)).putShort((short) (b + 2));
                ib.putShort((short) b).putShort((short) (b + 2)).putShort((short) (b + 3));
            }
            vb.rewind(); ib.rewind();
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL15C.glUnmapBuffer(GL15C.GL_ARRAY_BUFFER);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GL15C.glUnmapBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER);

            // restoreVbos: atributos 0..4, stride 36
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, 36, 0);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 36, 8);
            GL20C.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, 36, 16);
            GL20C.glVertexAttribPointer(3, 2, GL11C.GL_FLOAT, false, 36, 20);
            GL20C.glVertexAttribPointer(4, 2, GL11C.GL_FLOAT, false, 36, 28);
            for (int a = 0; a < 5; a++) GL20C.glEnableVertexAttribArray(a);

            int lastDiffuse = -1, lastDepth = -1;
            curProg = -1;
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            for (int i = 0; i < scene.size(); i++) {
                Sprite s = scene.get(i);
                if (CHURN && i > 0 && i % 25 == 0) {
                    // fidelidade: uploads no meio do lote (TileDepthTexture.updateGPUTexture / TextureID.generateHwId), unidade ativa 0, sem restaurar o bind
                    Sprite q = scene.get(i - 1);
                    if ((i / 25) % 2 == 0) {
                        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex[q.pt]);
                        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                        depthData[q.pt].rewind();
                        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
                        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RED, ATLAS, ATLAS, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, depthData[q.pt]);
                        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
                    } else {
                        int nt = GL11C.glGenTextures();
                        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, nt);
                        diffData[q.dt].rewind();
                        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, ATLAS, ATLAS, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, diffData[q.dt]);
                        extraTex.add(nt);
                    }
                    lastDiffuse = -1;
                }
                // textura 0 (DIFFUSE): so se mudou (cache lastTextureID); assignFilteringFlags = bind + glTexParameteri
                if (diffTex[s.dt] != lastDiffuse) {
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, diffTex[s.dt]);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
                    lastDiffuse = diffTex[s.dt];
                }
                // textura 1 (DEPTH): glActiveTexture(1) + bind direto + volta a 0
                if (depthTex[s.pt] != lastDepth) {
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex[s.pt]);
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                    lastDepth = depthTex[s.pt];
                }
                // StartShader: glUseProgram (cache do ShaderHelper), samplers, MVP, uniforms do setter
                Prog pr = s.opaque ? opaq : tile;
                if (curProg != pr.id) { GL20C.glUseProgram(pr.id); curProg = pr.id; }
                GL20C.glUniform1i(pr.diffuse, 0);
                GL20C.glUniform1i(pr.depth, 1);
                GL20C.glUniformMatrix4fv(pr.mvp, false, mvp);
                GL20C.glUniform1f(pr.zDepth, s.z);
                GL20C.glUniform1f(pr.zBlendZ, s.bz);
                GL20C.glUniform1f(pr.zBlendTo, s.tz);
                GL12C.glDrawRangeElements(GL11C.GL_TRIANGLES, i * 4, i * 4 + 3, 6, GL11C.GL_UNSIGNED_SHORT, (long) i * 6 * 2);
                if (m.flush == 1) GL11C.glFlush();
                else if (m.flush == 64) { if ((i & 63) == 63) GL11C.glFlush(); }
                else if (m.flush == -1) GL11C.glFinish();
            }
            GL20C.glUseProgram(0); curProg = 0;
            // endRenderThread: generateMipmap na textura de cor (opcao mipMaps do jogo)
            if (System.getProperty("mip", "0").equals("1")) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colTex);
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
        }

        return finishRun(m, round, fbo, colTex, depTex, vbo, ibo, tile, opaq, 0);
    }

    static Result finishRun(Mode m, int round, int fbo, int colTex, int depTex, int vbo, int ibo, Prog tile, Prog opaq, long t0) {
        // leitura
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL11C.glFinish();
        long ms = t0 == 0 ? 0 : (System.nanoTime() - t0) / 1000000;
        ByteBuffer cb = BufferUtils.createByteBuffer(W * H * 4);
        GL11C.glReadPixels(0, 0, W, H, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, cb);
        FloatBuffer db = BufferUtils.createFloatBuffer(W * H);
        GL11C.glReadPixels(0, 0, W, H, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, db);
        Result res = new Result();
        res.mode = m.name; res.round = round; res.ms = ms;
        res.color = new byte[W * H * 4]; cb.get(res.color);
        res.depth = new float[W * H]; db.get(res.depth);
        res.hash = hashBytes(res.color, res.depth);

        // limpeza (do zero a cada rodada)
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GL30C.glDeleteFramebuffers(fbo);
        GL11C.glDeleteTextures(colTex); GL11C.glDeleteTextures(depTex);
        for (int t : extraTex) GL11C.glDeleteTextures(t);
        extraTex.clear();
        if (!V3) for (int t : diffTex) GL11C.glDeleteTextures(t);
        for (int t : depthTex) GL11C.glDeleteTextures(t);
        if (vbo != 0) { GL15C.glDeleteBuffers(vbo); GL15C.glDeleteBuffers(ibo); }
        GL20C.glDeleteProgram(tile.id); GL20C.glDeleteProgram(opaq.id);
        int e = GL11C.glGetError();
        if (e != 0) p("  [aviso] glGetError=0x" + Integer.toHexString(e) + " em " + m.name + "#" + round);
        return res;
    }

    static int[] rvbo = new int[RING], ribo = new int[RING];
    static int scratchFbo, scratchTex;
    static int rLastDiffuse, rLastDepth;

    static void writeSprite(ByteBuffer vb, ByteBuffer ib, Sprite s, int local) {
        float du0 = (s.dcell % CELLS) / (float) CELLS, dv0 = (s.dcell / CELLS) / (float) CELLS;
        float pu0 = (s.pcell % CELLS) / (float) CELLS, pv0 = (s.pcell / CELLS) / (float) CELLS;
        float cs = 1f / CELLS;
        float[][] corner = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int c = 0; c < 4; c++) {
            float fx = corner[c][0], fy = corner[c][1];
            vb.putFloat(s.x + fx * s.w).putFloat(s.y + fy * s.h);
            vb.putFloat(du0 + fx * cs).putFloat(dv0 + fy * cs);
            vb.put((byte) (s.col >> 16)).put((byte) (s.col >> 8)).put((byte) s.col).put((byte) (s.col >>> 24));
            vb.putFloat(pu0 + fx * cs).putFloat(pv0 + fy * cs);
            vb.putFloat(0).putFloat(0);
        }
        int b = local * 4;
        ib.putShort((short) b).putShort((short) (b + 1)).putShort((short) (b + 2));
        ib.putShort((short) b).putShort((short) (b + 2)).putShort((short) (b + 3));
    }

    // Padrao SpriteRenderer$RingBuffer: anel de 128 pares VBO(65536)/IBO(5460) STREAM_DRAW; por rodada:
    // [glBufferData(NULL)] + glMapBufferRange(0,size,flags) + escrita + unmap + attribs + desenhos, sem flush.
    static Result runRing(Mode m, int round, int frames, List<Sprite> scene, Prog tile, Prog opaq, int fbo, int colTex, int depTex) {
        lastScene = scene;
        if (F_CYCLE) makeFloorProg();
        FloatBuffer mvp = BufferUtils.createFloatBuffer(16);
        mvp.put(new float[]{2f / W, 0, 0, 0, 0, 2f / H, 0, 0, 0, 0, 2, 0, -1, -1, -1, 1}).flip();
        for (int i = 0; i < RING; i++) {
            rvbo[i] = GL15C.glGenBuffers(); ribo[i] = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, rvbo[i]);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, RING_VB, GL15C.GL_STREAM_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ribo[i]);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, RING_IB, GL15C.GL_STREAM_DRAW);
        }
        if (CHUNKS && scratchFbo == 0) {
            scratchTex = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scratchTex);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, 256, 256, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            scratchFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, scratchFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, scratchTex, 0);
        }
        long t0 = System.nanoTime();
        int idx = -1;
        for (int frame = 0; frame < frames; frame++) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL11C.glViewport(0, 0, W, H);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glClearColor(0, 0, 0, 0);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);
            GL11C.glDepthMask(true);
            GL11C.glClearDepth(1.0);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT | GL11C.GL_COLOR_BUFFER_BIT);
            GL11C.glDepthFunc(GL11C.GL_LEQUAL);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE_MINUS_DST_ALPHA, GL11C.GL_ONE);
            rLastDiffuse = -1; rLastDepth = -1; curProg = -1;
            for (int a = 0; a < 5; a++) GL20C.glEnableVertexAttribArray(a);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            for (int base = 0; base < scene.size(); base += PER_ROUND) {
                int n = Math.min(PER_ROUND, scene.size() - base);
                idx = (idx + 1) % RING;
                if (m.flushMap) GL11C.glFlush();
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, rvbo[idx]);
                if (m.bufData) GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, RING_VB, GL15C.GL_STREAM_DRAW);
                ByteBuffer vb = GL30C.glMapBufferRange(GL15C.GL_ARRAY_BUFFER, 0, RING_VB, m.mapFlags, null);
                GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ribo[idx]);
                if (m.bufData) GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, RING_IB, GL15C.GL_STREAM_DRAW);
                ByteBuffer ib = GL30C.glMapBufferRange(GL15C.GL_ELEMENT_ARRAY_BUFFER, 0, RING_IB, m.mapFlags, null);
                if (vb == null || ib == null) throw new RuntimeException("map null");
                for (int k = 0; k < n; k++) writeSprite(vb, ib, scene.get(base + k), k);
                GL15C.glUnmapBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, rvbo[idx]);
                GL15C.glUnmapBuffer(GL15C.GL_ARRAY_BUFFER);
                GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ribo[idx]);
                GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, 36, 0);
                GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 36, 8);
                GL20C.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, 36, 16);
                GL20C.glVertexAttribPointer(3, 2, GL11C.GL_FLOAT, false, 36, 20);
                GL20C.glVertexAttribPointer(4, 2, GL11C.GL_FLOAT, false, 36, 28);
                for (int k = 0; k < n; k++) {
                    Sprite s = scene.get(base + k);
                    if (diffTex[s.dt] != rLastDiffuse) {
                        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, diffTex[s.dt]);
                        if (!V3) {
                            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
                            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
                        }
                        rLastDiffuse = diffTex[s.dt];
                    }
                    if (s.kind == 0) {
                        if (depthTex[s.pt] != rLastDepth) {
                            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
                            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex[s.pt]);
                            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                            rLastDepth = depthTex[s.pt];
                        }
                        Prog pr = s.opaque ? opaq : tile;
                        if (curProg != pr.id) { GL20C.glUseProgram(pr.id); curProg = pr.id; }
                        GL20C.glUniform1i(pr.diffuse, 0);
                        GL20C.glUniform1i(pr.depth, 1);
                        GL20C.glUniformMatrix4fv(pr.mvp, false, mvp);
                        GL20C.glUniform1f(pr.zDepth, s.z);
                        GL20C.glUniform1f(pr.zBlendZ, s.bz);
                        GL20C.glUniform1f(pr.zBlendTo, s.tz);
                        GL12C.glDrawRangeElements(GL11C.GL_TRIANGLES, k * 4, k * 4 + 3, 6, GL11C.GL_UNSIGNED_SHORT, (long) k * 6 * 2);
                        if (F_CYCLE) { GL20C.glUseProgram(0); curProg = 0; } // EndShader
                    } else {
                        // texture1 == null: StateRun.render faz glActiveTexture(1); glDisable(TEXTURE_2D); glActiveTexture(0)
                        if (rLastDepth != -2) {
                            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
                            GL11C.glDisable(GL11C.GL_TEXTURE_2D);
                            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                            rLastDepth = -2;
                        }
                        if (s.kind == 1) { // segundo shader de um sampler so (piso)
                            if (curProg != floorProg.id) { GL20C.glUseProgram(floorProg.id); curProg = floorProg.id; }
                            GL20C.glUniform1i(floorProg.diffuse, 0);
                            GL20C.glUniformMatrix4fv(floorProg.mvp, false, mvp);
                            GL20C.glUniform1f(floorProg.zDepth, s.z);
                            GL12C.glDrawRangeElements(GL11C.GL_TRIANGLES, k * 4, k * 4 + 3, 6, GL11C.GL_UNSIGNED_SHORT, (long) k * 6 * 2);
                            GL20C.glUseProgram(0); curProg = 0;
                        } else { // programa fixo
                            if (curProg != 0) { GL20C.glUseProgram(0); curProg = 0; }
                            ffDraw(s, k, mvp);
                            for (int a = 0; a < 5; a++) GL20C.glEnableVertexAttribArray(a);
                            GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, 36, 0);
                            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 36, 8);
                            GL20C.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, 36, 16);
                            GL20C.glVertexAttribPointer(3, 2, GL11C.GL_FLOAT, false, 36, 20);
                            GL20C.glVertexAttribPointer(4, 2, GL11C.GL_FLOAT, false, 36, 28);
                        }
                    }
                    if (m.flush == 1) GL11C.glFlush();
                    else if (m.flush == 64) { if ((k & 63) == 63) GL11C.glFlush(); }
                    else if (m.flush == -1) GL11C.glFinish();
                }
                if (CHUNKS) { // troca de FBO entre rodadas, sem glFlush
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, scratchFbo);
                    GL11C.glViewport(0, 0, 256, 256);
                    GL11C.glClearColor(0.1f, 0.2f, 0.3f, 1f);
                    GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
                    GL11C.glViewport(0, 0, W, H);
                    GL11C.glClearColor(0, 0, 0, 0);
                }
            }
            GL20C.glUseProgram(0); curProg = 0;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        Result r = finishRun(m, round, fbo, colTex, depTex, 0, 0, tile, opaq, t0);
        for (int i = 0; i < RING; i++) { GL15C.glDeleteBuffers(rvbo[i]); GL15C.glDeleteBuffers(ribo[i]); }
        return r;
    }

    static Prog floorProg;
    static void makeFloorProg() {
        if (floorProg != null) return;
        String v = "#version 120\nuniform mat4 ModelViewProjection; uniform float zDepth;\nattribute vec2 aPos; attribute vec2 aUv; attribute vec4 aCol;\nvarying vec2 uv; varying vec4 col;\nvoid main(){ uv=aUv; col=aCol; gl_Position = ModelViewProjection*vec4(aPos, zDepth, 1.0); }\n";
        String f = "#version 120\nuniform sampler2D DIFFUSE; varying vec2 uv; varying vec4 col;\nvoid main(){ gl_FragColor = texture2D(DIFFUSE, uv) * col; }\n";
        Prog pr = new Prog();
        int vs = compile(GL20C.GL_VERTEX_SHADER, v, "floor.vert"), fs = compile(GL20C.GL_FRAGMENT_SHADER, f, "floor.frag");
        pr.id = GL20C.glCreateProgram();
        GL20C.glAttachShader(pr.id, vs); GL20C.glAttachShader(pr.id, fs);
        GL20C.glBindAttribLocation(pr.id, 0, "aPos"); GL20C.glBindAttribLocation(pr.id, 1, "aUv"); GL20C.glBindAttribLocation(pr.id, 2, "aCol");
        GL20C.glLinkProgram(pr.id);
        if (GL20C.glGetProgrami(pr.id, GL20C.GL_LINK_STATUS) == 0) throw new RuntimeException("link floor: " + GL20C.glGetProgramInfoLog(pr.id));
        pr.mvp = GL20C.glGetUniformLocation(pr.id, "ModelViewProjection");
        pr.zDepth = GL20C.glGetUniformLocation(pr.id, "zDepth");
        pr.diffuse = GL20C.glGetUniformLocation(pr.id, "DIFFUSE");
        floorProg = pr;
    }

    static void ffDraw(Sprite s, int k, FloatBuffer mvp) {
        for (int a = 0; a < 5; a++) GL20C.glDisableVertexAttribArray(a);
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        org.lwjgl.opengl.GL11.glLoadMatrixf(mvp);
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        org.lwjgl.opengl.GL11.glLoadIdentity();
        org.lwjgl.opengl.GL11.glTranslatef(0, 0, s.z);
        GL11C.glEnable(GL11C.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL13.glClientActiveTexture(GL13C.GL_TEXTURE0);
        org.lwjgl.opengl.GL11.glEnableClientState(org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY);
        org.lwjgl.opengl.GL11.glEnableClientState(org.lwjgl.opengl.GL11.GL_TEXTURE_COORD_ARRAY);
        org.lwjgl.opengl.GL11.glEnableClientState(org.lwjgl.opengl.GL11.GL_COLOR_ARRAY);
        org.lwjgl.opengl.GL11.glVertexPointer(2, GL11C.GL_FLOAT, 36, 0L);
        org.lwjgl.opengl.GL11.glTexCoordPointer(2, GL11C.GL_FLOAT, 36, 8L);
        org.lwjgl.opengl.GL11.glColorPointer(4, GL11C.GL_UNSIGNED_BYTE, 36, 16L);
        GL12C.glDrawRangeElements(GL11C.GL_TRIANGLES, k * 4, k * 4 + 3, 6, GL11C.GL_UNSIGNED_SHORT, (long) k * 6 * 2);
        org.lwjgl.opengl.GL11.glDisableClientState(org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY);
        org.lwjgl.opengl.GL11.glDisableClientState(org.lwjgl.opengl.GL11.GL_TEXTURE_COORD_ARRAY);
        org.lwjgl.opengl.GL11.glDisableClientState(org.lwjgl.opengl.GL11.GL_COLOR_ARRAY);
    }

    // ---- analise de pagina errada (v3): decodifica a cor de pagina de cada pixel ----
    static int decodePage(Result r, int i) {
        int a = r.color[i * 4 + 3] & 255;
        if (a == 0) return -1;
        int R = r.color[i * 4] & 255, G = r.color[i * 4 + 1] & 255, B = r.color[i * 4 + 2] & 255;
        int ri = Math.round((R - 40) / 30f), gi = Math.round((G - 40) / 30f), bi = Math.round((B - 40) / 30f);
        if (ri < 0 || ri > 6 || gi < 0 || gi > 6 || bi < 0) return -2;
        if (Math.abs(R - (40 + ri * 30)) > 12 || Math.abs(G - (40 + gi * 30)) > 12 || Math.abs(B - (40 + bi * 30)) > 12) return -2;
        int p = ri + 7 * gi + 49 * bi;
        return p < NDIFF ? p : -2;
    }

    /** retorna {pixelsDiferentesDaRef, paginaErrada(ambos decodificados e diferentes), foraDePaleta, daPaginaDoDesenhoAnterior} */
    static int[] pageAnalysis(Result r, Result ref, List<Sprite> sc) {
        boolean[] wrong = new boolean[W * H], prev = new boolean[W * H];
        int dif = 0, wrongPage = 0, off = 0, prevHit = 0;
        int[] dec = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            boolean c = r.color[i * 4] != ref.color[i * 4] || r.color[i * 4 + 1] != ref.color[i * 4 + 1]
                    || r.color[i * 4 + 2] != ref.color[i * 4 + 2] || r.color[i * 4 + 3] != ref.color[i * 4 + 3];
            if (!c) continue;
            dif++;
            int g = decodePage(r, i), e = decodePage(ref, i);
            dec[i] = g;
            if (g == -2) { off++; wrong[i] = true; }
            else if (g != e) { wrongPage++; wrong[i] = true; }
        }
        for (int j = 1; j < sc.size(); j++) {
            Sprite s = sc.get(j);
            int pp = sc.get(j - 1).dt;
            int x0 = Math.max(0, (int) s.x), x1 = Math.min(W, (int) (s.x + s.w)), y0 = Math.max(0, (int) s.y), y1 = Math.min(H, (int) (s.y + s.h));
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                int i = y * W + x;
                if (wrong[i] && !prev[i] && dec[i] == pp) { prev[i] = true; prevHit++; }
            }
        }
        return new int[]{dif, wrongPage, off, prevHit};
    }

    static void diff(Result a, Result ref) {
        int dc = 0, dd = 0;
        for (int i = 0; i < W * H; i++) {
            boolean c = a.color[i * 4] != ref.color[i * 4] || a.color[i * 4 + 1] != ref.color[i * 4 + 1]
                    || a.color[i * 4 + 2] != ref.color[i * 4 + 2] || a.color[i * 4 + 3] != ref.color[i * 4 + 3];
            if (c) dc++;
            if (a.depth[i] != ref.depth[i]) dd++;
        }
        a.diffColor = dc; a.diffDepth = dd;
    }

    static int diffAny(Result a, Result b) {
        int n = 0;
        for (int i = 0; i < W * H; i++) {
            if (a.color[i * 4] != b.color[i * 4] || a.color[i * 4 + 1] != b.color[i * 4 + 1] || a.color[i * 4 + 2] != b.color[i * 4 + 2]
                    || a.color[i * 4 + 3] != b.color[i * 4 + 3] || a.depth[i] != b.depth[i]) n++;
        }
        return n;
    }

    static void savePng(Result r, String path, Result diffRef) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = (y * W + x);
            int rr = r.color[i * 4] & 255, g = r.color[i * 4 + 1] & 255, b = r.color[i * 4 + 2] & 255, a = r.color[i * 4 + 3] & 255;
            int px;
            if (diffRef == null) {
                // composicao sobre cinza para ver alfa
                int bg = 90;
                px = 0xFF000000 | (Math.min(255, rr + bg * (255 - a) / 255) << 16) | (Math.min(255, g + bg * (255 - a) / 255) << 8) | Math.min(255, b + bg * (255 - a) / 255);
            } else {
                boolean c = r.color[i * 4] != diffRef.color[i * 4] || r.color[i * 4 + 1] != diffRef.color[i * 4 + 1] || r.color[i * 4 + 2] != diffRef.color[i * 4 + 2] || r.color[i * 4 + 3] != diffRef.color[i * 4 + 3];
                boolean d = r.depth[i] != diffRef.depth[i];
                px = 0xFF000000 | (c ? 0xFF0000 : 0) | (d ? 0x0000FF : 0) | ((c || d) ? 0 : 0x202020);
            }
            img.setRGB(x, H - 1 - y, px); // GL origem embaixo
        }
        File f = new File(path);
        f.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", f);
    }

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(new java.io.FileWriter(args[0], false));
        String label = args.length > 1 ? args[1] : "run";
        String imgDir = args.length > 2 ? args[2] : "reports/repro";
        if (args.length > 3) sprites = Integer.parseInt(args[3]);
        int rounds = args.length > 4 ? Integer.parseInt(args[4]) : 3;
        shaderDir = args.length > 5 ? args[5] : "C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid\\media\\shaders";
        int frames = Integer.getInteger("frames", 1);

        p("# GlRepro " + label + " os.arch=" + System.getProperty("os.arch") + " MESA_GLTHREAD=" + System.getenv("MESA_GLTHREAD")
                + " sprites=" + sprites + " rounds=" + rounds + " frames=" + frames + " fbo=" + W + "x" + H + " mip=" + System.getProperty("mip", "0") + " churn=" + System.getProperty("churn", "0"));
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) throw new RuntimeException("glfwInit");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 0);
        long win = GLFW.glfwCreateWindow(1366, 768, "GlRepro", MemoryUtil.NULL, MemoryUtil.NULL);
        if (win == 0) throw new RuntimeException("glfwCreateWindow");
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        String renderer = GL11C.glGetString(GL11C.GL_RENDERER);
        p("GL_VENDOR=" + GL11C.glGetString(GL11C.GL_VENDOR));
        p("GL_RENDERER=" + renderer);
        p("GL_VERSION=" + GL11C.glGetString(GL11C.GL_VERSION));
        if (renderer == null || !renderer.contains("D3D12")) p("!!! GL_RENDERER nao contem D3D12");
        if (System.getProperty("probe", "0").equals("1")) { out.close(); System.exit(0); }
        if (V3) p("v3 factors=" + FACTORS + " cycle=" + F_CYCLE + " tex(comp+mips)=" + F_TEX + " pages=" + PAGES + " psize=" + PSIZE + " mem=" + F_MEM + " memmb=" + MEM_MB);

        boolean ringSet = System.getProperty("buf", "old").equals("ring");
        Mode[] ringModes = {
                new Mode("ring_base", "orig", 0).ring(38, true, false),
                new Mode("ring_flush1", "orig", 1).ring(38, true, false),
                new Mode("ring_flush64", "orig", 64).ring(38, true, false),
                new Mode("ring_finish1", "orig", -1).ring(38, true, false),
                new Mode("ring_map10", "orig", 0).ring(10, false, false),
                new Mode("ring_map6", "orig", 0).ring(6, true, false),
                new Mode("ring_nobufdata", "orig", 0).ring(38, false, false),
                new Mode("ring_flushmap", "orig", 0).ring(38, true, true),
        };
        Mode[] oldModes = {
                new Mode("base", "orig", 0), new Mode("flush1", "orig", 1), new Mode("flush64", "orig", 64), new Mode("finish1", "orig", -1),
                new Mode("semFragDepth", "semFragDepth", 0), new Mode("semFragDepth_finish1", "semFragDepth", -1),
                new Mode("semDiscard", "semDiscard", 0), new Mode("semDiscard_finish1", "semDiscard", -1),
        };
        Mode[] v3Modes = {ringModes[0], ringModes[1], ringModes[2], ringModes[3]};
        Mode[] modes = V3 ? v3Modes : (ringSet ? ringModes : oldModes);
        String pf = ringSet ? "ring_" : "";
        java.util.Map<String, List<Result>> all = new java.util.LinkedHashMap<>();
        for (Mode m : modes) {
            List<Result> l = new ArrayList<>();
            for (int r = 1; r <= rounds; r++) {
                try { l.add(runOnce(m, r, frames)); }
                catch (Throwable t) { p("ERRO " + m.name + "#" + r + ": " + t); t.printStackTrace(); }
            }
            all.put(m.name, l);
            p("modo " + m.name + " feito (" + l.size() + " rodadas)");
        }
        Result ref = all.get(pf + "finish1").isEmpty() ? null : all.get(pf + "finish1").get(0);
        p("");
        p(String.format("%-22s %-3s %-18s %-14s %-12s %-14s %-7s %s", "modo", "rod", "hash", "difCor vs ref", "difDepth", "dif vs rod1", "ms", "ref"));
        for (Mode m : modes) {
            List<Result> l = all.get(m.name);
            Result r0 = ref;
            String refName = pf + "finish1#1";
            if (m.variant.equals("semFragDepth")) { List<Result> rl = all.get("semFragDepth_finish1"); r0 = rl.isEmpty() ? null : rl.get(0); refName = "semFragDepth_finish1#1"; }
            if (m.variant.equals("semDiscard")) { List<Result> rl = all.get("semDiscard_finish1"); r0 = rl.isEmpty() ? null : rl.get(0); refName = "semDiscard_finish1#1"; }
            for (Result r : l) {
                if (r0 != null) diff(r, r0);
                int inter = r == l.get(0) ? 0 : diffAny(r, l.get(0));
                String extra = "";
                if (V3 && r0 != null && lastScene != null) {
                    int[] pa = pageAnalysis(r, r0, lastScene);
                    extra = String.format("  pixDif=%d paginaErrada=%d foraPaleta=%d daPaginaAnterior=%d", pa[0], pa[1], pa[2], pa[3]);
                }
                p(String.format("%-22s %-3d %-18x %-14d %-12d %-14d %-7d %s%s", m.name, r.round, r.hash, r.diffColor, r.diffDepth, inter, r.ms, refName, extra));
            }
        }
        p("");
        p("tempo medio por modo (ms, desenho+glFinish, " + rounds + " rodadas):");
        for (Mode m : modes) {
            long sum = 0; int dsum = 0; for (Result r : all.get(m.name)) { sum += r.ms; dsum += r.diffColor + r.diffDepth; }
            int c = Math.max(1, all.get(m.name).size());
            p(String.format("  %-22s %6d ms   soma pixels diferentes vs ref=%d", m.name, sum / c, dsum));
        }
        // imagens
        try {
            if (!all.get(pf + "base").isEmpty()) savePng(all.get(pf + "base").get(0), imgDir + "/" + label + "-" + pf + "base.png", null);
            if (ref != null) savePng(ref, imgDir + "/" + label + "-" + pf + "finish1.png", null);
            if (ref != null && !all.get(pf + "base").isEmpty()) savePng(all.get(pf + "base").get(0), imgDir + "/" + label + "-" + pf + "diff-base-vs-finish1.png", ref);
            if (ringSet) for (Mode m : modes) if (!m.name.equals("ring_finish1") && !m.name.equals("ring_base") && !m.name.equals("ring_flush1") && ref != null && !all.get(m.name).isEmpty())
                savePng(all.get(m.name).get(0), imgDir + "/" + label + "-diff-" + m.name + "-vs-finish1.png", ref);
            if (ref != null && !all.get(pf + "flush1").isEmpty()) savePng(all.get(pf + "flush1").get(0), imgDir + "/" + label + "-" + pf + "diff-flush1-vs-finish1.png", ref);
            p("imagens em " + imgDir);
        } catch (Throwable t) { p("falha ao salvar imagens: " + t); }

        boolean baseDiff = false, flushDiff = false;
        for (Result r : all.get(pf + "base")) if (r.diffColor + r.diffDepth > 0) baseDiff = true;
        for (Result r : all.get(pf + "flush1")) if (r.diffColor + r.diffDepth > 0) flushDiff = true;
        p("");
        p("VEREDITO[" + label + "]: base difere de finish1=" + baseDiff + "; flush1 difere de finish1=" + flushDiff
                + " => bug " + (baseDiff && !flushDiff ? "REPRODUZIDO" : "NAO reproduzido (criterio base!=finish1 e flush1==finish1)"));
        out.close();
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
        System.exit(0);
    }
}
