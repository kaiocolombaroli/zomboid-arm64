import java.io.PrintWriter;
import java.util.Locale;
import zombie.iso.LightingJNI;

/**
 * Harness deterministico da Lighting nativa. Sem iniciar o jogo.
 * Uso: LightingHarness <saida.txt> [<tempos.txt>]
 * Sequencia (na thread "Lighting Thread"): init, stateBeginUpdate, playerSet, chunkBeginUpdate,
 * chunkLevelBeginUpdate, squareBeginUpdate/squareSet/squareSetLightTransmission/squareEndUpdate,
 * addLight, stateEndFrame, stateEndUpdate, DoLightingUpdateNew (tempo fixo), leituras.
 */
public class LightingHarness {
    static final int CX = 1000, CY = 800;   // chunk minimo da janela (em chunks)
    static final int GRID = 5;              // janela GRID x GRID chunks
    static final int Z = 32;                // nivel 0 + 32
    // Transmissao nao-nula torna a saida do PROPRIO x64 nao-deterministica (ver reports/m1-resultado.md); so com LH_TRANSMISSION=1.
    static final boolean NONZERO_TRANSMISSION = System.getenv("LH_TRANSMISSION") != null;
    static PrintWriter out;
    static Throwable failure;

    static void p(String s) { out.println(s); }
    static String f(float v) { return String.format(Locale.ROOT, "%.6f", v); }


    static void mainFrame(boolean withChunks) {
        LightingJNI.stateBeginUpdate(0, CX, CY, GRID, GRID);
        float px = CX * 8 + 20.5f, py = CY * 8 + 20.5f;
        LightingJNI.playerSet(px, py, Z, 0.7f, 0.7f, false, false, false, false, 0.5f, 0.0f, 0.5f);
        LightingJNI.stateEndFrame(1.0f, 1.0f, 1.0f, 0.4f, 0.3f, 30.0f, 60.0f, false, 0.0f, 10);
        if (withChunks) {
            for (int cy = 0; cy < GRID; cy++) for (int cx = 0; cx < GRID; cx++) chunk(cx, cy);
        }
        for (int cy = 0; cy < GRID; cy++) for (int cx = 0; cx < GRID; cx++) LightingJNI.chunkLightingDone(CX + cx, CY + cy);
        LightingJNI.stateEndUpdate();
    }

    static void chunk(int cx, int cy) {
        LightingJNI.chunkBeginUpdate(CX + cx, CY + cy, Z, Z + 1);
        LightingJNI.chunkLevelBeginUpdate(Z);
        for (int sy = 0; sy < 8; sy++) {
            for (int sx = 0; sx < 8; sx++) {
                int wx = (CX + cx) * 8 + sx, wy = (CY + cy) * 8 + sy;
                LightingJNI.squareBeginUpdate(sx, sy, Z);
                boolean wall = (wx % 16 == 12) && (wy % 16 != 8);
                int block = wall ? 0x0f : 0;
                int vision = (((wx + wy) % 3) == 0) ? (1 << 27) : 0;
                LightingJNI.squareSet(block, !wall, !wall, true, vision, -1L, -1L, 6, true);
                // 4 lados (W,N,E,S) x (r,g,b,distancia,0). Padrao do jogo = zeros; alguns quadrados com "cortina" no lado oeste.
                if (NONZERO_TRANSMISSION && wx % 16 == 6 && wy % 4 == 0) {
                    if ("2".equals(System.getenv("LH_SWAP_TRANSMISSION"))) // sensibilidade, variante pilha: troca float 3 (1.0) e 19 (fora dos registradores)
                        LightingJNI.squareSetLightTransmission(0.6f, 0.5f, 0.4f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 1.0f);
                    else
                    if (System.getenv("LH_SWAP_TRANSMISSION") != null) // so p/ prova de sensibilidade: troca float 0 e 3
                        LightingJNI.squareSetLightTransmission(1.0f, 0.5f, 0.4f, 0.6f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f);
                    else
                    LightingJNI.squareSetLightTransmission(0.6f, 0.5f, 0.4f, 1.0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f,  0f, 0f, 0f, 0f, 0f);
                } else {
                    LightingJNI.squareSetLightTransmission(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
                }
                LightingJNI.squareEndUpdate();
            }
        }
        LightingJNI.chunkLevelEndUpdate();
        // nivel acima: sem quadrados (como chunks reais com squares nulos)
        LightingJNI.chunkLevelBeginUpdate(Z + 1);
        for (int sy = 0; sy < 8; sy++) for (int sx = 0; sx < 8; sx++) LightingJNI.squareSetNull(sx, sy, Z + 1);
        LightingJNI.chunkLevelEndUpdate();
        LightingJNI.chunkEndUpdate();
    }

    static String bench(boolean relight) {
        long base = relight ? 5_000_000_000L : 3_000_000_000L;
        for (int i = 0; i < 200; i++) {
            LightingJNI.DoLightingUpdateNew(base + i * 16_000_000L, false);
            mainFrame(relight);
        }
        long[] t = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long a = System.nanoTime();
            LightingJNI.DoLightingUpdateNew(base + (200 + i) * 16_000_000L, false);
            t[i] = System.nanoTime() - a;
            mainFrame(relight);
        }
        long sum = 0; for (long v : t) sum += v;
        java.util.Arrays.sort(t);
        return String.format(Locale.ROOT, "mean_us=%.3f median_us=%.3f min_us=%.3f max_us=%.3f n=1000 warmup=200",
            sum / 1000.0 / 1000.0, (t[499] + t[500]) / 2.0 / 1000.0, t[0] / 1000.0, t[999] / 1000.0);
    }

    static String snapshot() {
        StringBuilder o = new StringBuilder();
        o.append("visibleRoomCount " + LightingJNI.getVisibleRoomCount(0)).append('\n');

        int[] li = new int[49];
        for (int y = 14; y <= 26; y += 2) {
            for (int x = 12; x <= 26; x += 2) {
                int wx = CX * 8 + x, wy = CY * 8 + y;
                StringBuilder sb = new StringBuilder("sq " + x + " " + y);
                sb.append(" dirty=").append(LightingJNI.getSquareDirty(0, wx, wy, Z));
                sb.append(" seen=").append(LightingJNI.getSeen(0, wx, wy, Z));
                sb.append(" canSee=").append(LightingJNI.getCanSee(0, wx, wy, Z));
                sb.append(" couldSee=").append(LightingJNI.getCouldSee(0, wx, wy, Z));
                sb.append(" dark=").append(f(LightingJNI.getDarkMulti(0, wx, wy, Z)));
                sb.append(" tdark=").append(f(LightingJNI.getTargetDarkMulti(0, wx, wy, Z)));
                o.append(sb).append('\n');
                StringBuilder v = new StringBuilder("  vert");
                for (int k = 0; k < 8; k++) v.append(' ').append(LightingJNI.getVertLight(0, wx, wy, Z, k));
                o.append(v).append('\n');
                StringBuilder l = new StringBuilder("  info");
                for (int k = 0; k < 3; k++) l.append(' ').append(f(LightingJNI.getLightInfo(0, wx, wy, Z, k)));
                o.append(l).append('\n');
                boolean ok = LightingJNI.getSquareLighting(0, wx, wy, Z, li);
                StringBuilder s = new StringBuilder("  lighting " + ok);
                for (int k = 0; k < 49; k++) s.append(' ').append(li[k]);
                o.append(s).append('\n');
            }
        }

        return o.toString();
    }

    static void body(String timesPath) throws Throwable {
        LightingJNI.init();
        p("init " + LightingJNI.init);

        // Luzes primeiro (como checkLights() no jogo)
        if (System.getenv("LH_NOLIGHT") == null) LightingJNI.addLight(1, CX * 8 + 18, CY * 8 + 18, Z, 10, 1.0f, 0.8f, 0.5f, -1, true);
        if (System.getenv("LH_NOLIGHT") == null) LightingJNI.addLight(2, CX * 8 + 26, CY * 8 + 14, Z, 8, 0.3f, 0.6f, 1.0f, -1, true);
        mainFrame(true);
        p("updateCounter " + LightingJNI.stateUpdateCounter(0));

        // Alterna Do... (thread de iluminacao) e quadros da thread principal ate convergir
        int cycles = 0;
        for (int i = 0; i < 40; i++) {
            LightingJNI.DoLightingUpdateNew(1_000_000_000L + i * 16_000_000L, i == 0);
            if (LightingJNI.WaitingForMain()) { mainFrame(false); cycles++; }
        }
        // Convergencia: a iluminacao nativa e assincrona (fade/threads); repete ate o resultado ficar estavel.
        String prev = null; int stable = 0, n = 0;
        while (stable < 50 && n < 1000) {
            LightingJNI.DoLightingUpdateNew(2_000_000_000L + n * 16_000_000L, false);
            if (LightingJNI.WaitingForMain()) mainFrame(false);
            Thread.sleep(20);
            String cur = snapshot();
            if (cur.equals(prev)) stable++; else { stable = 0; prev = cur; }
            n++;
        }
        System.err.println("convergence cycles=" + n + " stable=" + stable);
        if (stable < 50) throw new IllegalStateException("nao convergiu");
        out.print(prev);
        if (System.getenv("LH_PROBE") != null) {
            for (int r = 0; r < 4; r++) {
                mainFrame(true);
                for (int i = 0; i < 20; i++) { LightingJNI.DoLightingUpdateNew(9_000_000_000L + (r * 100 + i) * 16_000_000L, false); if (LightingJNI.WaitingForMain()) mainFrame(false); Thread.sleep(20); }
                String q = snapshot();
                int d = 0; String[] A = prev.split("\n"), B = q.split("\n");
                for (int i = 0; i < A.length; i++) if (!A[i].equals(B[i])) d++;
                System.err.println("probe rep " + r + " differing lines vs first: " + d);
            }
        }

        // Medicao: 200 aquecimento + 1000 medidas, so a chamada Do... e cronometrada.
        // "steady": quadro principal sem reenviar chunks; "relight": reenvia todos os chunks antes de cada Do...
        if (timesPath != null) {
            try (PrintWriter tw = new PrintWriter(timesPath)) {
                tw.println("steady " + bench(false));
                tw.println("relight " + bench(true));
            }
        }
        LightingJNI.destroy();
    }

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(args[0]);
        String times = args.length > 1 ? args[1] : null;
        Thread th = new Thread(() -> {
            try { body(times); } catch (Throwable e) { failure = e; }
        }, "Lighting Thread");
        th.start();
        th.join();
        out.flush(); out.close();
        if (failure != null) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
