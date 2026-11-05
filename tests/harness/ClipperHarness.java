import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Locale;
import zombie.vehicles.Clipper;
import zombie.vehicles.ClipperOffset;

/**
 * Harness deterministico da Clipper nativa (19 exports: Clipper 13 + ClipperOffset 6). Sem iniciar o jogo.
 * Uso: ClipperHarness <saida.txt> [<tempos.txt>]
 * As sequencias seguem o bytecode do jogo (ByteBuffer.allocateDirect na ordem padrao, big-endian, como Zone/IsoMetaGrid):
 *  - Zone.difference:           clear, addPath(n,buf,false), clipAABB, generatePolygons(), getPolygon(i)
 *  - VisibilityGraph:           clear, addAABB*N, generatePolygons(), getPolygon(i) (anel externo + buracos)
 *  - CollideWithObstaclesPoly:  clear, addPolygon*N, generatePolygons(), getPolygon(i)
 *  - Zone.getPolygonTriangles:  clear, addPath, generatePolygons(), triangulate(0,buf)
 *  - WorldMapGeometry:          clear, addPath*, clipAABB*, generatePolygons(D), triangulate2(i,buf)
 *  - HLChunkRegion/FBORenderOcclusion: generatePolygons(D,I) / generatePolygons(I,D,I)
 *  - Zone.checkPolylineOutline: ClipperOffset.clear, addPath(n,buf.flip(),join,end), execute(w), getPolygonCount, getPolygon
 * Fora do bytecode do jogo (cobertura dos exports): addLine, generateTriangulatePolygons, addAABBBevel.
 */
public class ClipperHarness {
    static PrintWriter out;
    static final ByteBuffer BUF = ByteBuffer.allocateDirect(3072);
    static int polygonsTotal = 0;

    static void p(String s) { out.println(s); }
    static String f(float v) { return String.format(Locale.ROOT, "%.6f", v); }

    static void putPts(ByteBuffer b, float... xy) { b.clear(); for (float v : xy) b.putFloat(v); }

    /** Le um anel (short n + n pares de float) a partir da posicao atual do buffer. */
    static String ring(ByteBuffer b) {
        short n = b.getShort();
        StringBuilder s = new StringBuilder("n=" + n);
        for (int j = 0; j < n; j++) s.append(' ').append(f(b.getFloat())).append(',').append(f(b.getFloat()));
        return s.toString();
    }

    /** getPolygon como em VisibilityGraph: anel externo, short de buracos, cada buraco. */
    static void dumpPolygon(String tag, Clipper c, int i) {
        BUF.clear();
        int r = c.getPolygon(i, BUF);
        p(tag + " poly " + i + " ret=" + r);
        p("  outer " + ring(BUF));
        short holes = BUF.getShort();
        p("  holes " + holes);
        for (int h = 0; h < holes; h++) p("  hole " + h + " " + ring(BUF));
    }

    static void dumpAll(String tag, Clipper c, int n) {
        p(tag + " count " + n);
        if (n < 0) throw new IllegalStateException(tag + ": contagem negativa " + n);
        for (int i = 0; i < n; i++) dumpPolygon(tag, c, i);
        polygonsTotal += n;
    }

    static void dumpTriangles(String tag, Clipper c, int idx) {
        BUF.clear();
        int n = c.triangulate(idx, BUF);
        StringBuilder s = new StringBuilder(tag + " triangulate " + idx + " verts=" + n);
        for (int i = 0; i < n; i++) s.append(' ').append(f(BUF.getFloat())).append(',').append(f(BUF.getFloat()));
        p(s.toString());
    }

    static void dumpTriangles2(String tag, Clipper c, int idx) {
        BUF.clear();
        int ni = c.triangulate2(idx, BUF);
        short nv = BUF.getShort();
        StringBuilder s = new StringBuilder(tag + " triangulate2 " + idx + " indices=" + ni + " verts=" + nv);
        for (int i = 0; i < nv; i++) s.append(' ').append(f(BUF.getFloat())).append(',').append(f(BUF.getFloat()));
        s.append(" idx");
        for (int i = 0; i < ni; i++) s.append(' ').append(BUF.getShort());
        p(s.toString());
    }

    static final float[] L_SHAPE = {0,0, 40,0, 40,16, 16,16, 16,40, 0,40};
    static final float[] SQUARE_A = {0,0, 24,0, 24,24, 0,24};
    static final float[] SQUARE_B = {12,12, 36,12, 36,36, 12,36};
    static final float[] HOLE_C = {6,6, 6,18, 18,18, 18,6};
    static final float[] STAR = {20,0, 26,14, 40,14, 29,23, 33,37, 20,29, 7,37, 11,23, 0,14, 14,14};

    static void scenarios() {
        Clipper c = new Clipper();

        // 1. Zone.difference
        putPts(BUF, L_SHAPE);
        c.clear(); c.addPath(6, BUF, false);
        c.clipAABB(4, 4, 30, 30);
        dumpAll("zoneDiff", c, c.generatePolygons());
        putPts(BUF, STAR);
        c.clear(); c.addPath(10, BUF, false);
        c.clipAABB(10, 5, 30, 35);
        dumpAll("zoneDiffStar", c, c.generatePolygons());
        // addPath de 4 argumentos (WorldMapStreet): as 4 combinacoes
        for (int fl = 0; fl < 4; fl++) {
            putPts(BUF, SQUARE_A);
            c.clear(); c.addPath(4, BUF, (fl & 1) != 0, (fl & 2) != 0);
            putPts(BUF, SQUARE_B);
            c.addPath(4, BUF, true, true);
            dumpAll("addPath4_" + fl, c, c.generatePolygons());
        }

        // 2. VisibilityGraph: anel de AABBs (gera buraco) + um bloco separado
        c.clear();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) {
            boolean ring = (x == 0 || x == 4 || y == 0 || y == 4);
            if (ring) c.addAABB(x * 1.0f - 0.3f, y * 1.0f - 0.3f, x + 1.0f + 0.3f, y + 1.0f + 0.3f);
        }
        c.addAABB(10.0f, 10.0f, 12.5f, 11.5f);
        dumpAll("visGraph", c, c.generatePolygons());
        c.addAABBBevel(20, 20, 24, 23, 0.5f);
        dumpAll("visGraphBevel", c, c.generatePolygons());

        // 3. CollideWithObstaclesPoly: quads rotados
        c.clear();
        c.addPolygon(0f, 0f, 10f, 2f, 9f, 8f, -1f, 6f);
        c.addPolygon(8f, 1f, 18f, 3f, 17f, 9f, 7f, 7f);
        c.addPolygon(30f, 30f, 34f, 30f, 34f, 34f, 30f, 34f);
        dumpAll("obstacles", c, c.generatePolygons());

        // 4. Zone.getPolygonTriangles
        putPts(BUF, L_SHAPE);
        c.clear(); c.addPath(6, BUF, false);
        int n = c.generatePolygons();
        p("triZone count " + n);
        dumpTriangles("triZone", c, 0);
        putPts(BUF, STAR);
        c.clear(); c.addPath(10, BUF, false);
        n = c.generatePolygons();
        p("triStar count " + n);
        dumpTriangles("triStar", c, 0);

        // 5. WorldMapGeometry: poligono com buraco, clip, varias tolerancias, triangulate2
        putPts(BUF, SQUARE_A);
        c.clear(); c.addPath(4, BUF, false);
        putPts(BUF, HOLE_C);
        c.addPath(4, BUF, false);
        c.clipAABB(-5, -5, 20, 30);
        double[] deltas = {0.0, 0.5, 2.0};
        for (double d : deltas) {
            n = c.generatePolygons(d);
            p("wm gen delta=" + d + " count " + n);
            polygonsTotal += n;
            for (int i = 0; i < n; i++) dumpTriangles2("wm", c, i);
        }
        n = c.generatePolygons(0.0, 2);
        p("wm genDI count " + n); polygonsTotal += n;
        for (int i = 0; i < n; i++) dumpTriangles2("wmDI", c, i);

        // 6. HLChunkRegion / FBORenderOcclusion (argumentos inteiros inventados)
        c.clear();
        for (int i = 0; i < 6; i++) c.addAABB(i * 3.0f, (i % 2) * 2.0f, i * 3.0f + 4.0f, (i % 2) * 2.0f + 4.0f);
        n = c.generatePolygons(0.0, 0);
        p("hl genDI count " + n); polygonsTotal += n;
        for (int i = 0; i < n; i++) dumpPolygon("hl", c, i);
        n = c.generatePolygons(3, 0.0, 0);
        p("fbo genIDI count " + n); polygonsTotal += n;
        n = c.generatePolygons(3, 0.5, 1);
        p("fbo genIDI2 count " + n);

        // 7. cobertura: addLine, generateTriangulatePolygons (nao chamadas pelo bytecode do jogo)
        c.clear();
        c.addLine(0f, 0f, 30f, 0f);
        c.addLine(30f, 0f, 30f, 20f);
        n = c.generatePolygons();
        p("lines count " + n);
        for (int i = 0; i < n; i++) dumpPolygon("lines", c, i);
        putPts(BUF, STAR);
        c.clear(); c.addPath(10, BUF, false);
        n = c.generateTriangulatePolygons(3, 0);
        p("genTri count " + n);
        for (int i = 0; i < Math.max(n, 0); i++) { dumpTriangles("genTri", c, i); dumpTriangles2("genTri", c, i); }

        // 9. entradas degeneradas (nao devem lancar excecao)
        putPts(BUF, 0f, 0f, 5f, 5f);
        c.clear(); c.addPath(2, BUF, false);
        p("degenerate2 count " + c.generatePolygons());
        c.clear();
        p("empty count " + c.generatePolygons());
    }

    static void offsets() {
        ClipperOffset o = new ClipperOffset();
        // Zone.checkPolylineOutline: largura par/impar, Miter + Butt, flip antes de addPath
        for (int width = 3; width <= 4; width++) {
            float h = (width % 2 == 0) ? 0f : 0.5f;
            float[] pts = {0, 0, 20, 0, 20, 12, 40, 12};
            o.clear();
            BUF.clear();
            for (int i = 0; i < pts.length; i += 2) { BUF.putFloat(pts[i] + h); BUF.putFloat(pts[i + 1] + h); }
            BUF.flip();
            o.addPath(pts.length / 2, BUF, 3, 2);   // JoinType.Miter.ordinal()=3, EndType.Butt.ordinal()=2
            o.execute(width / 2.0f);
            int n = o.getPolygonCount();
            p("polyline width=" + width + " count " + n);
            if (n < 1) throw new IllegalStateException("ClipperOffset sem poligono");
            for (int i = 0; i < n; i++) { BUF.clear(); int r = o.getPolygon(i, BUF); p("  ret=" + r + " " + ring(BUF)); }
            polygonsTotal += n;
        }
        // todas as combinacoes JoinType x EndType
        float[] pts = {0, 0, 16, 4, 30, 0, 30, 20};
        for (int join = 0; join < 4; join++) for (int end = 0; end < 5; end++) {
            o.clear();
            BUF.clear();
            for (float v : pts) BUF.putFloat(v);
            BUF.flip();
            o.addPath(4, BUF, join, end);
            o.execute(2.5);
            int n = o.getPolygonCount();
            StringBuilder s = new StringBuilder("offset join=" + join + " end=" + end + " count " + n);
            for (int i = 0; i < n; i++) { BUF.clear(); int r = o.getPolygon(i, BUF); s.append("\n  ret=").append(r).append(' ').append(ring(BUF)); }
            p(s.toString());
            polygonsTotal += n;
        }
        // delta negativo em poligono fechado
        o.clear();
        BUF.clear();
        for (float v : SQUARE_A) BUF.putFloat(v);
        BUF.flip();
        o.addPath(4, BUF, 3, 0);   // EndType.Polygon
        o.execute(-3.0);
        int n = o.getPolygonCount();
        p("offsetNeg count " + n);
        for (int i = 0; i < n; i++) { BUF.clear(); o.getPolygon(i, BUF); p("  " + ring(BUF)); }
        polygonsTotal += n;
    }

    /** Rotina pesada: 400 AABB pseudo-aleatorias (LCG fixo), uniao, leitura de todos os poligonos. */
    static String bench() {
        Clipper c = new Clipper();
        int warm = 50, runs = 300;
        long[] t = new long[runs];
        long chk = 0;
        for (int it = 0; it < warm + runs; it++) {
            long seed = 12345 + it * 7919L;
            long a = System.nanoTime();
            c.clear();
            for (int k = 0; k < 400; k++) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L);
                int x = (int) ((seed >>> 33) % 120), y = (int) ((seed >>> 13) % 120);
                c.addAABB(x, y, x + 3 + (k % 4), y + 3 + (k % 3));
            }
            int n = c.generatePolygons();
            for (int i = 0; i < n; i++) { BUF.clear(); c.getPolygon(i, BUF); chk += BUF.getShort(); }
            long d = System.nanoTime() - a;
            if (it >= warm) t[it - warm] = d;
        }
        long sum = 0; for (long v : t) sum += v;
        java.util.Arrays.sort(t);
        return String.format(Locale.ROOT, "mean_us=%.3f median_us=%.3f min_us=%.3f max_us=%.3f n=%d warmup=%d chk=%d",
            sum / (double) runs / 1000.0, (t[runs / 2 - 1] + t[runs / 2]) / 2.0 / 1000.0, t[0] / 1000.0, t[runs - 1] / 1000.0, runs, warm, chk);
    }

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(args[0]);
        String times = args.length > 1 ? args[1] : null;
        try {
            Clipper.init();
            p("init ok");
            scenarios();
            offsets();
            p("polygonsTotal " + polygonsTotal);
            if (polygonsTotal < 20) throw new IllegalStateException("poucos poligonos: " + polygonsTotal + " (nativo nao exercitado?)");
            if (times != null) {
                try (PrintWriter tw = new PrintWriter(times)) { tw.println("unionAABB400 " + bench()); }
            }
            out.flush(); out.close();
        } catch (Throwable e) {
            out.flush(); out.close();
            e.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }
}
