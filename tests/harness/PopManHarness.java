import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Harness da PopMan nativa (PZPopMan64: ZombiePopulationManager 28 + MapCollisionData 17 + MPDebugInfo 8 + DebugCommands 1;
 * ZombiePopulationRenderer (5) exige GL e fica de fora). Sem iniciar o jogo.
 * Uso: PopManHarness <saida.txt> [<tempos.txt>]
 *
 * Ordem do bytecode (MapCollisionData.init / ZombiePopulationManager.init / updateMain / updateThread / save / stop):
 *   MCD.n_setGameState x N (Core.*, GameWindow.*, SandboxOptions.*, SavefileNaming.*, World.ZombiesDisabled, PAUSED)
 *   MCD.n_initMetaGrid, n_initMetaCell, n_initMetaChunk (32x32), MCD.n_init
 *   ZPM.n_init, n_configFloat/Int (onConfigReloaded), n_setOutfitNames, n_setSpawnOrigins
 *   por quadro: ZPM.n_loadChunk / n_loadedAreas, n_updateMain, n_getAddZombieCount/Data (main);
 *               MCD.n_update + ZPM.n_updateThread (thread MCD); n_save / n_saveCell; n_stop
 * Os natives sao privados: chamados por reflexao (mesma classe/classloader do jogo).
 * Estado em build/harness/popman-<arch>/ (SaveDir); nada vai para a pasta do jogo.
 * Modos (3o argumento opcional): normal (padrao) | corrupt (arquivos de save corrompidos antes da recarga).
 */
public class PopManHarness {
    static PrintWriter out;
    static Class<?> ZPM, MCD;
    static boolean det = true;   // det: intensidade 0 (sem repovoamento aleatorio) + zumbis explicitos; rand: repovoamento com random_device
    static Path base;      // build/harness/popman-<arch>
    static String arch;

    static void p(String s) { out.println(s); }
    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    static Method find(Class<?> c, String name, Class<?>... types) throws Exception {
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }
    static Object call(Class<?> c, String name, Class<?>[] types, Object... args) throws Throwable {
        try { return find(c, name, types).invoke(null, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static final Class<?> I = int.class, F = float.class, Z = boolean.class, D = double.class, S = String.class;

    static void gs(String k, String v) throws Throwable { call(MCD, "n_setGameState", new Class<?>[]{S, S}, k, v); }
    static void gs(String k, boolean v) throws Throwable { call(MCD, "n_setGameState", new Class<?>[]{S, Z}, k, v); }
    static void gs(String k, int v) throws Throwable { call(MCD, "n_setGameState", new Class<?>[]{S, I}, k, v); }
    static void cf(String k, float v) throws Throwable { call(ZPM, "n_configFloat", new Class<?>[]{S, F}, k, v); }
    static void ci(String k, int v) throws Throwable { call(ZPM, "n_configInt", new Class<?>[]{S, I}, k, v); }

    static final ByteBuffer BB = ByteBuffer.allocateDirect(1024);   // como ZombiePopulationManager.byteBuffer (big-endian)
    static boolean summaryOnly = false;
    static int randTotal = 0;
    static List<Object[]> collected = null;
    static int addTotal = 0;
    static long addChk = 0;

    /** Lado Java de updateMain: n_updateMain + leitura dos zumbis adicionados. */
    static void updateMain(float mult, double hours, boolean dump) throws Throwable {
        call(ZPM, "n_updateMain", new Class<?>[]{F, D}, mult, hours);
        int cnt = (Integer) call(ZPM, "n_getAddZombieCount", new Class<?>[]{});
        int got = 0;
        List<String> recs = new ArrayList<>();
        for (int i = 0; i < cnt; ) {
            BB.clear();
            int n = (Integer) call(ZPM, "n_getAddZombieData", new Class<?>[]{I, ByteBuffer.class}, i, BB);
            i += n;
            if (n <= 0) throw new IllegalStateException("n_getAddZombieData devolveu " + n);
            for (int j = 0; j < n; j++) {
                float x = BB.getFloat(), y = BB.getFloat(), z = BB.getFloat();
                byte dir = BB.get();
                int outfit = BB.getInt(), flags = BB.getInt(), px = BB.getInt(), py = BB.getInt(), pid = BB.getInt();
                recs.add(String.format(Locale.ROOT, "x=%.3f y=%.3f z=%.3f dir=%d outfit=%d flags=%d px=%d py=%d pid=%d", x, y, z, dir, outfit, flags, px, py, pid));
                if (collected != null && !summaryOnly) collected.add(new Object[]{x, y, z, dir, outfit, flags, px, py, pid});
                if (!summaryOnly) addChk += (long) (x * 1000) * 31 + (long) (y * 1000) + dir * 7L + outfit + flags + pid;
            }
            got += n;
        }
        addTotal += got;
        if (summaryOnly) {
            // spawnHorde/worldSound usam random_device (posicao/direcao/quais zumbis): fase aleatoria, so contagem (linhas #rand, fora do compare.py)
            randTotal += got;
            return;
        }
        p("updateMain hours=" + String.format(Locale.ROOT, "%.3f", hours) + " addCount=" + cnt + " got=" + got);
        if (dump) {
            // a ordem pode variar entre execucoes (thread/pool): ordenar so para comparar
            Collections.sort(recs);
            for (String r : recs) p("  add " + r);
        }
    }

    static int workerIters = 0;
    /** Zumbis explicitos (como virtualizeZombie -> n_addZombie): posicoes por LCG fixo nas 4 celulas; ids unicos. */
    static void injectZombies() throws Throwable {
        long seed = 12345;
        int n = 0;
        for (int i = 0; i < 400; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float x = 1.5f + (int) ((seed >>> 33) % 590);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float y = 1.5f + (int) ((seed >>> 33) % 590);
            byte dir = (byte) ((seed >>> 20) & 7);
            int outfit = (int) ((seed >>> 11) & 3) == 0 ? 0 : (int) ((seed >>> 11) & 3);
            int flags = 4 | (int) ((seed >>> 40) & 1);
            int px = (i % 5 == 0) ? (int) x + 3 : Integer.MIN_VALUE, py = (i % 5 == 0) ? (int) y + 2 : Integer.MIN_VALUE;
            call(ZPM, "n_addZombie", new Class<?>[]{F, F, F, byte.class, I, I, I, I, I}, x, y, 0f, dir, outfit, flags, px, py, 5000 + i);
            n++;
        }
        p("injected " + n);
    }

    static void workerFrame() throws Throwable {      // MCDThread.runInner (sem n_save): repete enquanto houver trabalho (como o laco da thread)
        int n = 0;
        do {
            call(MCD, "n_update", new Class<?>[]{});
            call(ZPM, "n_updateThread", new Class<?>[]{});
            n++;
        } while (n < 200 && ((Boolean) call(ZPM, "n_hasDataForThread", new Class<?>[]{}) || !(Boolean) call(ZPM, "n_shouldWait", new Class<?>[]{}) ));
        workerIters += n;
        p((summaryOnly ? "#rand " : "") + "worker iters=" + n);
    }

    static String sha(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(b);
        return hex(d, 8);
    }

    /** Lista os arquivos do estado (nome relativo, tamanho, sha) e devolve nome->bytes. */
    static TreeMap<String, byte[]> files(String tag) throws Exception {
        TreeMap<String, byte[]> m = new TreeMap<>();
        Path root = base.resolve("Saves");
        if (Files.exists(root)) {
            try (var st = Files.walk(root)) {
                for (Path f : (Iterable<Path>) st.filter(Files::isRegularFile).sorted()::iterator)
                    m.put(root.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
            }
        }
        for (var e : m.entrySet())
            p("file " + tag + " " + e.getKey() + " size=" + e.getValue().length + " sha=" + sha(e.getValue()));
        p("files " + tag + " count=" + m.size());
        return m;
    }

    static void rmrf(Path r) throws Exception {
        if (!Files.exists(r)) return;
        try (var st = Files.walk(r)) {
            for (Path f : (Iterable<Path>) st.sorted(Comparator.reverseOrder())::iterator) Files.delete(f);
        }
    }

    static void worldInit(String mode) throws Throwable {
        // o jogo cria as pastas do save antes (SavefileNaming); o nativo so abre arquivos
        Files.createDirectories(base.resolve("Saves").resolve("Sandbox").resolve("harness").resolve("zpop"));
        Files.createDirectories(base.resolve("Saves").resolve("Sandbox").resolve("harness").resolve("chunkdata"));
        String sep = java.io.File.separator;
        String cache = base.toString();
        gs("Core.GameMode", "Sandbox");
        gs("Core.GameSaveWorld", "harness");
        gs("Core.bLastStand", false);
        gs("Core.noSave", false);
        gs("GameWindow.CacheDir", cache);
        gs("GameWindow.GameModeCacheDir", cache + sep + "Saves" + sep + "Sandbox" + sep);
        gs("GameWindow.SaveDir", cache + sep + "Saves");
        gs("SandboxOptions.Distribution", 1);
        gs("SandboxOptions.Zombies", 3);
        gs("SavefileNaming.SUBDIR_CHUNKDATA", "chunkdata");
        gs("SavefileNaming.SUBDIR_ZPOP", "zpop");
        gs("World.ZombiesDisabled", false);
        gs("PAUSED", true);
        // meta grid: 2x2 celulas, minX=0 minY=0
        call(MCD, "n_initMetaGrid", new Class<?>[]{I, I, I, I}, 0, 0, 2, 2);
        for (int cy = 0; cy < 2; cy++) for (int cx = 0; cx < 2; cx++) {
            call(MCD, "n_initMetaCell", new Class<?>[]{I, I, S}, cx, cy, cx + "_" + cy + ".lotheader");
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int inten = det ? 0 : ((x * 7 + y * 13 + cx * 5 + cy * 11) % 9);      // 0..8 (LotHeader.getZombieIntensityForChunk >= 0)
                call(MCD, "n_initMetaChunk", new Class<?>[]{I, I, I, I, I}, cx, cy, x, y, inten);
            }
        }
        call(MCD, "n_init", new Class<?>[]{I, I, I, I}, 0, 0, 2, 2);
        call(ZPM, "n_init", new Class<?>[]{Z, Z, I, I, I, I}, false, false, 0, 0, 2, 2);
        // ZombiePopulationManager.onConfigReloaded (valores padrao do Sandbox)
        cf("PopulationMultiplier", 1.0f);
        cf("PopulationStartMultiplier", 1.0f);
        cf("PopulationPeakMultiplier", 1.5f);
        ci("PopulationPeakDay", 28);
        cf("RespawnHours", 72.0f);
        cf("RespawnUnseenHours", 16.0f);
        cf("RespawnMultiplier", 0.1f);
        cf("RedistributeHours", 12.0f);
        ci("FollowSoundDistance", 100);
        cf("MinZombiesPerChunk", 0.0f);
        cf("MaxZombiesPerChunk", 255.0f);
        cf("UniformZombiesPerChunk", 0.2f);
        call(ZPM, "n_setOutfitNames", new Class<?>[]{String[].class}, (Object) new String[]{"generic", "police", "farmer", "doctor"});
        call(ZPM, "n_setSpawnOrigins", new Class<?>[]{int[].class}, (Object) new int[]{100, 100, 20, 20});
        p("init ok");
    }

    static String timeLine(String name, long[] t, int warm) {
        long sum = 0; for (long v : t) sum += v;
        long[] c = t.clone(); Arrays.sort(c);
        int n = c.length;
        return String.format(Locale.ROOT, "%s mean_us=%.3f median_us=%.3f min_us=%.3f max_us=%.3f n=%d warmup=%d%n",
            name, sum / (double) n / 1000.0, (c[n / 2 - 1] + c[n / 2]) / 2.0 / 1000.0, c[0] / 1000.0, c[n - 1] / 1000.0, n, warm);
    }
    static String timeSave = "";

    static void body(String timesPath, String mode) throws Throwable {
        ZPM = Class.forName("zombie.popman.ZombiePopulationManager");
        MCD = Class.forName("zombie.MapCollisionData");
        // MapCollisionData/ZombiePopulationManager carregam PZPopMan(64) no bloco estatico/init do jogo; aqui direto.
        System.loadLibrary("PZPopMan64");
        p("mode=" + mode);   // os.arch fica em reports/native-popman-<arch>.meta

        rmrf(base);
        worldInit(mode);

        // areas carregadas (LoadedAreas: x,y,w,h em chunks) + chunks (n_loadChunk como addChunkToWorld)
        int[] areas = {16, 16, 24, 24, 40, 40, 8, 8};
        call(ZPM, "n_loadedAreas", new Class<?>[]{I, int[].class, Z}, 2, areas, false);
        for (int cy = 16; cy < 40; cy++) for (int cx = 16; cx < 40; cx++)
            call(ZPM, "n_loadChunk", new Class<?>[]{I, I, Z}, cx, cy, true);
        for (int cy = 40; cy < 48; cy++) for (int cx = 40; cx < 48; cx++)
            call(ZPM, "n_loadChunk", new Class<?>[]{I, I, Z}, cx, cy, true);
        p("chunks loaded");
        if (det) injectZombies();

        double hours = 100.0;
        for (int frame = 0; frame < 12; frame++) {
            call(MCD, "n_setGameState", new Class<?>[]{S, Z}, "PAUSED", false);
            updateMain(1.0f, hours, frame < 3 || frame == 11);
            workerFrame();
            hours += 0.25;
        }

        // registerZombie / releaseZombie / aggro / spawnHorde / worldSound / updateRealZombies
        int id1 = (Integer) call(ZPM, "n_registerZombie", new Class<?>[]{F, F, F, byte.class, I, I, I}, 300.5f, 300.5f, 0f, (byte) 2, 1, 5, 1001);
        int id2 = (Integer) call(ZPM, "n_registerZombie", new Class<?>[]{F, F, F, byte.class, I, I, I}, 305.5f, 301.5f, 0f, (byte) 4, 2, 5, 1002);
        p("registered " + id1 + " " + id2);
        call(ZPM, "n_updateRealZombies", new Class<?>[]{I, int[].class, float[].class}, 2,
            new int[]{id1, 5, id2, 5}, new float[]{300.5f, 300.5f, 0f, 305.5f, 301.5f, 0f});
        call(ZPM, "n_aggroTarget", new Class<?>[]{I, I, I}, id1, 310, 310);
        for (int frame = 0; frame < 4; frame++) { updateMain(1.0f, hours, true); workerFrame(); hours += 0.1; }
        call(ZPM, "n_releaseZombie", new Class<?>[]{I}, id1);
        call(ZPM, "n_addZombie", new Class<?>[]{F, F, F, byte.class, I, I, I, I, I}, 400.5f, 400.5f, 0f, (byte) 3, 1, 5, -1, -1, 2001);
        for (int frame = 0; frame < 3; frame++) { updateMain(1.0f, hours, true); workerFrame(); hours += 0.1; }
        p("radar has=" + call(ZPM, "n_hasRadarData", new Class<?>[]{}));
        call(ZPM, "n_requestRadarData", new Class<?>[]{});
        workerFrame();
        float[] radar = new float[256];
        boolean has = (Boolean) call(ZPM, "n_hasRadarData", new Class<?>[]{});
        p("radar has2=" + has);
        if (has) {
            int rn = (Integer) call(ZPM, "n_getRadarZombieData", new Class<?>[]{float[].class}, (Object) radar);
            p("radar n=" + rn);
        }
        p("readyToPause=" + call(ZPM, "n_readyToPause", new Class<?>[]{}) + " hasDataForThread=" + call(ZPM, "n_hasDataForThread", new Class<?>[]{}) + " shouldWait=" + call(ZPM, "n_shouldWait", new Class<?>[]{}));
        p("total_adds=" + addTotal + " chk=" + addChk);
        if (addTotal < 5) throw new IllegalStateException("poucos zumbis gerados: " + addTotal + " (nativo nao exercitado?)");

        // ---- salvar ----
        long t0 = System.nanoTime();
        call(ZPM, "n_save", new Class<?>[]{});
        call(MCD, "n_save", new Class<?>[]{});
        long tSave = System.nanoTime() - t0;
        TreeMap<String, byte[]> afterSave = files("save1", true);
        for (int cy = 0; cy < 2; cy++) for (int cx = 0; cx < 2; cx++)
            call(ZPM, "n_saveCell", new Class<?>[]{I, I}, cx, cy);
        TreeMap<String, byte[]> afterCells = files("saveCells", true);

        // salvamento de uma segunda geracao (mesmos dados): bytes devem ser iguais ao primeiro
        call(ZPM, "n_save", new Class<?>[]{});
        TreeMap<String, byte[]> afterSave2 = files("save2", true);
        p("save2_same_as_save1=" + sameFiles(afterCells, afterSave2));

        // medicao 1: rotina pesada de salvar (n_save + 4 n_saveCell, ~400 zumbis virtuais), 5 de aquecimento + 40 medidas
                if (timesPath != null) {
            int warm = 5, runs = 40;
            long[] t = new long[runs];
            for (int i = 0; i < warm + runs; i++) {
                long a = System.nanoTime();
                call(ZPM, "n_save", new Class<?>[]{});
                for (int cy = 0; cy < 2; cy++) for (int cx = 0; cx < 2; cx++) call(ZPM, "n_saveCell", new Class<?>[]{I, I}, cx, cy);
                long d = System.nanoTime() - a;
                if (i >= warm) t[i - warm] = d;
            }
            timeSave = timeLine("saveAll45", t, warm);
            if (!sameFiles(afterSave2, files("timing_after"))) throw new IllegalStateException("save repetido mudou os bytes");
        }
        // fase aleatoria (spawnHorde/worldSound usam random_device e escolhem zumbis virtuais ao acaso): so contagem, depois de tudo que e
        // comparado byte a byte; em seguida os arquivos salvos voltam ao disco (bytes do save2) para a recarga ser do que foi comparado
        if (det) {
            double hr = 141.0;
            call(MCD, "n_setGameState", new Class<?>[]{S, Z}, "PAUSED", false);
            call(ZPM, "n_worldSound", new Class<?>[]{I, I, I, I}, 320, 320, 50, 0);
            call(ZPM, "n_spawnHorde", new Class<?>[]{I, I, I, I, F, F, I}, 330, 330, 8, 8, 0f, 0f, 20);
            summaryOnly = true;
            for (int frame = 0; frame < 4; frame++) { updateMain(1.0f, hr, true); workerFrame(); hr += 0.1; }
            summaryOnly = false;
            p("#rand horde_total=" + randTotal + " ge20=" + (randTotal >= 20));
            call(ZPM, "n_save", new Class<?>[]{});
            for (var e : afterSave2.entrySet()) Files.write(base.resolve("Saves").resolve(e.getKey()), e.getValue());
            addTotal = 0;
        }
        // ---- recarregar ----
        call(ZPM, "n_stop", new Class<?>[]{});
        call(MCD, "n_stop", new Class<?>[]{});
        p("stopped");
        if (mode.equals("corrupt")) { corruptCase(); }
        else {
            // snapshot bruto dos bytes dos saves para a comparacao externa
            dumpFiles(afterSave2);
            reloadCase(timesPath, afterSave2);
        }
    }

    static boolean sameFiles(TreeMap<String, byte[]> a, TreeMap<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (var e : a.entrySet()) if (!Arrays.equals(e.getValue(), b.get(e.getKey()))) return false;
        return true;
    }

    static TreeMap<String, byte[]> files(String tag, boolean quiet) throws Exception { return files(tag); }

    static void dumpFiles(TreeMap<String, byte[]> m) throws Exception {
        Path dest = base.resolve("snapshot");
        rmrf(dest);
        for (var e : m.entrySet()) {
            Path f = dest.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
    }

    static void reloadCase(String timesPath, TreeMap<String, byte[]> saved) throws Throwable {
        worldInit("reload");
        int[] areas = {16, 16, 24, 24, 40, 40, 8, 8};
        call(ZPM, "n_loadedAreas", new Class<?>[]{I, int[].class, Z}, 2, areas, false);
        for (int cy = 16; cy < 40; cy++) for (int cx = 16; cx < 40; cx++)
            call(ZPM, "n_loadChunk", new Class<?>[]{I, I, Z}, cx, cy, true);
        for (int cy = 40; cy < 48; cy++) for (int cx = 40; cx < 48; cx++)
            call(ZPM, "n_loadChunk", new Class<?>[]{I, I, Z}, cx, cy, true);
        addTotal = 0; addChk = 0;
        collected = new ArrayList<>();
        double hours = 140.0;
        for (int frame = 0; frame < 4; frame++) {
            call(MCD, "n_setGameState", new Class<?>[]{S, Z}, "PAUSED", false);
            updateMain(1.0f, hours, true);
            workerFrame();
            hours += 0.25;
        }
        p("reload total_adds=" + addTotal + " chk=" + addChk);
        call(ZPM, "n_save", new Class<?>[]{});
        TreeMap<String, byte[]> afterReload = files("reload");
        p("reload_same_as_saved=" + sameFiles(saved, afterReload));
        // ida e volta: o jogo devolve os zumbis reais a populacao virtual (virtualizeZombie -> n_addZombie); salvar de novo e comparar com o 1o save
        for (Object[] r : collected)
            call(ZPM, "n_addZombie", new Class<?>[]{F, F, F, byte.class, I, I, I, I, I}, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
        collected = null;
        call(ZPM, "n_save", new Class<?>[]{});
        TreeMap<String, byte[]> afterRound = files("roundtrip");
        p("roundtrip_same_as_saved=" + sameFiles(saved, afterRound));
        for (var e : saved.entrySet()) {
            byte[] o = afterRound.get(e.getKey());
            p("roundtrip " + e.getKey() + " equal=" + (o != null && Arrays.equals(o, e.getValue())) + " sizeSaved=" + e.getValue().length + " sizeRound=" + (o == null ? -1 : o.length));
        }
        if (timesPath != null) {
            // medicao 2: 200 quadros de updateMain + thread nos 2 mundos carregados (sem repovoamento), 20 de aquecimento
            call(MCD, "n_setGameState", new Class<?>[]{S, Z}, "PAUSED", false);
            int warm = 20, runs = 200;
            long[] t = new long[runs];
            double hr = 200.0;
            for (int i = 0; i < warm + runs; i++) {
                long a = System.nanoTime();
                call(ZPM, "n_updateMain", new Class<?>[]{F, D}, 1.0f, hr);
                int cnt = (Integer) call(ZPM, "n_getAddZombieCount", new Class<?>[]{});
                call(MCD, "n_update", new Class<?>[]{});
                call(ZPM, "n_updateThread", new Class<?>[]{});
                long d = System.nanoTime() - a;
                hr += 0.05;
                if (i >= warm) t[i - warm] = d;
            }
            try (PrintWriter tw = new PrintWriter(timesPath)) {
                tw.print(timeSave);
                tw.print(timeLine("frame200", t, warm));
            }
        }
        call(ZPM, "n_stop", new Class<?>[]{});
        call(MCD, "n_stop", new Class<?>[]{});
        p("destroyed");
    }

    /** Arquivos corrompidos: o nativo deve capturar o erro por dentro (throw C++ pego na dylib) e responder igual no x64. */
    static void corruptCase() throws Throwable {
        Path dir = base.resolve("Saves").resolve("Sandbox").resolve("harness").resolve("zpop");
        List<Path> bins = new ArrayList<>();
        try (var st = Files.walk(base.resolve("Saves"))) { st.filter(f -> f.toString().endsWith(".bin")).sorted().forEach(bins::add); }
        for (Path f : bins) {
            byte[] b = Files.readAllBytes(f);
            String n = f.getFileName().toString();
            p("corrupting " + n + " size=" + b.length);
            if (n.equals("zpop_virtual.bin")) {
                Files.write(f, Arrays.copyOf(b, Math.max(1, b.length / 2)));             // truncado
            } else if (n.equals("zpop_ids.bin")) {
                byte[] c = b.clone(); if (c.length >= 4) { c[0] = (byte) 0x7f; c[1] = (byte) 0xff; c[2] = (byte) 0xff; c[3] = (byte) 0xff; }
                Files.write(f, c);                                                      // versao/contagem invalida
            } else {
                Files.write(f, Arrays.copyOf(b, Math.max(1, b.length / 3)));
            }
        }
        reloadCorrupt();
    }

    static void reloadCorrupt() throws Throwable {
        Throwable caught = null;
        try {
            worldInit("corrupt");
            int[] areas = {16, 16, 24, 24};
            call(ZPM, "n_loadedAreas", new Class<?>[]{I, int[].class, Z}, 1, areas, false);
            for (int cy = 16; cy < 40; cy++) for (int cx = 16; cx < 40; cx++)
                call(ZPM, "n_loadChunk", new Class<?>[]{I, I, Z}, cx, cy, true);
            for (int frame = 0; frame < 4; frame++) { call(MCD, "n_setGameState", new Class<?>[]{S, Z}, "PAUSED", false); updateMain(1.0f, 150.0 + frame, false); workerFrame(); }
            call(ZPM, "n_save", new Class<?>[]{});
        } catch (Throwable t) { caught = t; }
        p("corrupt_reload_exception=" + (caught == null ? "none" : caught.getClass().getName() + ": " + caught.getMessage()));
        files("corrupt_after");
        p("corrupt done");
    }

    static Throwable failure;

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(args[0]);
        String times = args.length > 1 ? args[1] : null;
        String mode = args.length > 2 ? args[2] : (System.getenv("POPMAN_MODE") != null ? System.getenv("POPMAN_MODE") : "normal");
        det = !mode.equals("rand");
        Path root = Paths.get(args[0]).toAbsolutePath().getParent().getParent();
        String a = System.getProperty("os.arch");
        arch = (a.equals("aarch64") || a.equals("arm64")) ? "arm64" : "x64";
        base = root.resolve("build").resolve("harness").resolve("popman-" + arch);
        Thread th = new Thread(() -> {
            try { body(times, mode); } catch (Throwable e) { failure = e; }
        }, "MapCollisionData");
        th.start();
        th.join();
        out.flush();
        if (failure != null) {
            failure.printStackTrace();
            p("FAILURE " + failure);
            out.close();
            System.exit(1);
        }
        out.close();
        System.exit(0);
    }
}
