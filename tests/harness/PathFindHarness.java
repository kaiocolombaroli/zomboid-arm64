import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Locale;
import zombie.pathfind.nativeCode.PathfindNative;

/**
 * Harness deterministico da PathFind nativa (PathfindNative: 11 exports; os 2 de PathfindNativeRenderer exigem GL e ficam de fora).
 * Uso: PathFindHarness <saida.txt> [<tempos.txt>]
 *
 * Ordem real (bytecode de PathfindNative.init(IsoMetaGrid) e PathfindNativeThread.updateThread), tudo na thread "PathfindNativeThread":
 *   PathfindNative.init() (loadLibrary) -> initWorld(minX,minY,w,h,server)
 *   tarefas de chunk:   ChunkUpdateTask.execute -> updateChunk(loadId,wx,wy,bb)   [bb = int minNivel+32, int maxNivel+32, 64x(long bits, short custo), short n, n x slope]
 *   tarefas de quadrado: SquareUpdateTask.execute -> updateSquare(loadId,x,y,z+32,bits,custo,dirSlope(8=nenhum),min,max)
 *   tarefas de veiculo:  VehicleAddTask -> addVehicle(bb) / VehicleUpdateTask -> teleportVehicle(bb) / VehicleRemoveTask -> removeVehicle(id)
 *   PathfindNative.update()
 *   pedidos: PathfindNative.findPath(requestBb[47], pathBb) (bytes iguais aos de PathfindNative.findPath(PathFindRequest,...));
 *            retorno 1 => pathBb: short n, n x (float x, float y, float z+32, int)
 *   remocao: removeChunk(wx,wy), destroyWorld(), freeMemoryAtExit()
 * Todos os ByteBuffers sao allocateDirect na ordem padrao (big-endian), como no jogo.
 * Mapa: invencao fixa, 64x64 quadrados (chunks 0..7 x 0..7) em um nivel (z=0, no nativo 32).
 */
public class PathFindHarness {
    static final int LV = 32;            // nivel 0 no nativo
    static final float VEH_Z = 32.0f;     // so com z=32 (nivel 0 no nativo) o veiculo bloqueia; com 0.0 nao tem efeito (verificado no x64)
    static PrintWriter out;
    static Throwable failure;
    static int found = 0, requests = 0;

    // bits de SquareUpdateTask
    static final long SOLID = 1L, COLLIDE_W = 2L, COLLIDE_N = 4L, SOLID_FLOOR = 512L, WINDOW_W = 2048L, WINDOW_N = 4096L,
        CAN_PATH_W = 8192L, CAN_PATH_N = 16384L, THUMP_W = 32768L, THUMP_N = 65536L, THUMPABLE = 131072L,
        HOPPABLE_N = 16777216L, HOPPABLE_W = 33554432L, TALL_HOPPABLE_N = 1073741824L, FARMING_PLANT = 4294967296L;

    static void p(String s) { out.println(s); }
    static String f(float v) { return String.format(Locale.ROOT, "%.6f", v); }

    // ---- mapa ----
    static long bits(int x, int y) {
        long b = SOLID_FLOOR;
        if (x == 30 && y < 40) b |= SOLID;                                  // parede vertical com abertura em y>=40
        if (y == 30 && x >= 10 && x <= 25) {                                // cerca horizontal (borda norte do quadrado)
            b |= COLLIDE_N;
            if (x >= 14 && x <= 15) b |= THUMPABLE | THUMP_N;               // trecho "quebravel"
            if (x >= 18 && x <= 19) b |= HOPPABLE_N;                        // trecho "pulavel"
            if (x >= 22 && x <= 23) b |= TALL_HOPPABLE_N;                   // cerca alta
            if (x == 12) b |= WINDOW_N | CAN_PATH_N;                        // janela
        }
        if (x == 45 && y >= 5 && y <= 12) b |= COLLIDE_W | (y == 8 ? (THUMP_W | CAN_PATH_W) : 0L);
        if (x == 52 && y == 52) b |= FARMING_PLANT;
        return b;
    }
    static short cost(int x, int y) { return (short) ((x >= 40 && x <= 50 && y >= 14 && y <= 50) ? 20 : 0); }

    static ByteBuffer chunkBuffer(int cx, int cy) {
        ByteBuffer bb = ByteBuffer.allocateDirect(2048);
        bb.putInt(LV).putInt(LV);                                           // minLevel+32, maxLevel+32
        for (int sy = 0; sy < 8; sy++) for (int sx = 0; sx < 8; sx++) {
            int x = cx * 8 + sx, y = cy * 8 + sy;
            bb.putLong(bits(x, y)).putShort(cost(x, y));
        }
        // lista de superficies inclinadas: uma rampa no chunk (2,1) -- quadrado local (3,4)
        if (cx == 2 && cy == 1) {
            bb.putShort((short) 1);
            bb.put((byte) 3).put((byte) 4).put((byte) LV).put((byte) 0).putFloat(0.0f).putFloat(0.5f);
        } else {
            bb.putShort((short) 0);
        }
        bb.flip();
        return bb;
    }

    static void putVehicle(ByteBuffer bb, int id, float x1, float y1, float x2, float y2, float margin, float up, float[] crawl) {
        bb.clear();
        bb.putInt(id);
        for (int pass = 0; pass < 2; pass++) {
            float m = pass * margin;
            bb.putFloat(x1 - m).putFloat(y1 - m).putFloat(x2 + m).putFloat(y1 - m).putFloat(x2 + m).putFloat(y2 + m).putFloat(x1 - m).putFloat(y2 + m);
            bb.putFloat(VEH_Z);                                            // VehiclePoly.z
        }
        bb.putFloat(up);
        bb.put((byte) crawl.length);
        for (float c : crawl) bb.putFloat(c);
    }

    // ---- pedidos ----
    static final class Req {
        float sx, sy, sz, tx, ty, tz;
        int mover;            // 0 outro, 1 jogador, 2 zumbi
        boolean npc, canCrawl, crawling, ignoreCrawlCost, canThump, canClimbFences, hasTarget, canBend, doNotRelease, canClimbTall, avoidFarming;
        int minLevel = 0, maxLevel = 63;
        Req(float sx, float sy, float tx, float ty, int mover) { this.sx = sx; this.sy = sy; this.tx = tx; this.ty = ty; this.mover = mover; }
    }

    static ByteBuffer requestBb = ByteBuffer.allocateDirect(47);
    static ByteBuffer pathBb = ByteBuffer.allocateDirect(3072);

    static int doRequest(Req r) {
        ByteBuffer b = requestBb;
        b.clear();
        b.putFloat(r.sx).putFloat(r.sy).putFloat(r.sz + LV).putFloat(r.tx).putFloat(r.ty).putFloat(r.tz + LV);
        b.putInt(r.mover);
        b.put((byte) (r.npc ? 1 : 0)).put((byte) (r.canCrawl ? 1 : 0)).put((byte) (r.crawling ? 1 : 0)).put((byte) (r.ignoreCrawlCost ? 1 : 0))
         .put((byte) (r.canThump ? 1 : 0)).put((byte) (r.canClimbFences ? 1 : 0)).put((byte) (r.hasTarget ? 1 : 0)).put((byte) (r.canBend ? 1 : 0));
        b.putInt(r.minLevel).putInt(r.maxLevel);
        b.put((byte) (r.doNotRelease ? 1 : 0)).put((byte) (r.canClimbTall ? 1 : 0)).put((byte) (r.avoidFarming ? 1 : 0));
        pathBb.clear();
        b.clear();
        return PathfindNative.findPath(b, pathBb);
    }

    static String describe(Req r, int ret) {
        StringBuilder s = new StringBuilder();
        s.append("ret=").append(ret);
        requests++;
        if (ret == 1) {
            found++;
            short n = pathBb.getShort();
            s.append(" nodes=").append(n);
            for (int i = 0; i < n; i++) {
                float x = pathBb.getFloat(), y = pathBb.getFloat(), z = pathBb.getFloat();
                int fl = pathBb.getInt();
                s.append(' ').append(f(x)).append(',').append(f(y)).append(',').append(f(z - LV)).append(',').append(fl);
            }
        }
        return s.toString();
    }

    static void req(String name, Req r) {
        p("req " + name + " " + describe(r, doRequest(r)));
    }

    static void body(String timesPath) throws Throwable {
        PathfindNative.init();
        p("init ok");
        PathfindNative.initWorld(0, 0, 1, 1, false);
        p("initWorld ok");

        // tarefas de chunk (loadId unico e nao-zero por chunk)
        short loadId = 1;
        for (int cy = 0; cy < 8; cy++) for (int cx = 0; cx < 8; cx++) PathfindNative.updateChunk(loadId++, cx, cy, chunkBuffer(cx, cy));
        PathfindNative.update();
        p("chunks loaded " + (loadId - 1));

        // tarefas de quadrado: abre uma porta (remove a parede em (30,20)) e fecha um buraco da cerca
        short lid = (short) (3 + 8 * 2 + 1);    // loadId do chunk cx=3, cy=2 (contem x=30,y=20)
        PathfindNative.updateSquare(lid, 30, 20, LV, SOLID_FLOOR, (short) 0, 8, 0f, 0f);
        PathfindNative.update();
        p("squares updated");

        Req base = new Req(5.5f, 5.5f, 55.5f, 55.5f, 1);
        req("player_diag", base);
        req("player_through_gap", new Req(35.5f, 20.5f, 25.5f, 20.5f, 1));
        req("player_wall_detour", new Req(25.5f, 35.5f, 35.5f, 35.5f, 1));
        req("player_cost_patch", new Req(38.5f, 30.5f, 52.5f, 30.5f, 1));
        req("player_fence_around", new Req(12.5f, 20.5f, 12.5f, 45.5f, 1));
        req("other_fence_around", new Req(12.5f, 20.5f, 12.5f, 45.5f, 0));
        req("player_same_square", new Req(5.5f, 5.5f, 5.6f, 5.6f, 1));
        req("player_outside_world", new Req(5.5f, 5.5f, 200.5f, 200.5f, 1));
        req("player_unloaded_chunk", new Req(5.5f, 5.5f, 120.5f, 20.5f, 1));
        for (int mask = 0; mask < 32; mask++) {   // matriz de flags na rota que cruza a cerca
            Req r = new Req(16.5f, 20.5f, 16.5f, 45.5f, (mask & 1) != 0 ? 2 : 1);
            r.canThump = (mask & 2) != 0;
            r.canClimbFences = (mask & 4) != 0;
            r.canClimbTall = (mask & 8) != 0;
            r.hasTarget = (mask & 16) != 0;
            r.canBend = mask % 3 == 0;
            req("fence_flags_" + mask, r);
            Req r2 = new Req(20.5f, 20.5f, 20.5f, 45.5f, 2);
            r2.canThump = (mask & 1) != 0; r2.canClimbFences = (mask & 2) != 0; r2.canClimbTall = (mask & 4) != 0;
            r2.hasTarget = true; r2.canBend = (mask & 8) != 0; r2.avoidFarming = (mask & 16) != 0;
            req("fence_zombie_" + mask, r2);
        }
        {
            Req r = new Req(5.5f, 5.5f, 50.5f, 5.5f, 2);
            r.canCrawl = true; r.crawling = true; r.ignoreCrawlCost = false; r.canThump = true; r.hasTarget = true;
            req("zombie_crawl", r);
            r.ignoreCrawlCost = true;
            req("zombie_crawl_ignorecost", r);
            Req d = new Req(5.5f, 5.5f, 55.5f, 55.5f, 1);
            d.doNotRelease = true;
            req("player_donotrelease", d);
            Req npc = new Req(5.5f, 5.5f, 55.5f, 55.5f, 1);
            npc.npc = true; npc.avoidFarming = true;
            req("player_npc_avoidfarm", npc);
            Req lv = new Req(5.5f, 5.5f, 55.5f, 55.5f, 1);
            lv.minLevel = 31; lv.maxLevel = 33;
            req("player_levels", lv);
            req("player_slope", new Req(19.5f, 12.5f, 19.5f, 13.5f, 1));
        }

        // veiculos
        ByteBuffer vb = ByteBuffer.allocateDirect(256);
        float[] crawl = {0.0f, 1.2f, 0.0f, -1.2f};
        {
            putVehicle(vb, 7, 32.0f, 14.0f, 36.0f, 26.0f, 0.5f, 1.0f, crawl);
            PathfindNative.addVehicle(vb);
            PathfindNative.update();
            p("vehicle added id=7");
            req("veh_player_across", new Req(25.5f, 20.5f, 45.5f, 20.5f, 1));
            Req z = new Req(25.5f, 20.5f, 45.5f, 20.5f, 2); z.canCrawl = true; z.crawling = true; z.canThump = true; z.hasTarget = true;
            req("veh_zombie_crawl", z);
            putVehicle(vb, 7, 40.0f, 40.0f, 44.0f, 52.0f, 0.5f, 1.0f, crawl);
            PathfindNative.teleportVehicle(vb);
            PathfindNative.update();
            p("vehicle teleported id=7");
            req("veh_after_teleport_old_spot", new Req(25.5f, 20.5f, 45.5f, 20.5f, 1));
            req("veh_after_teleport_new_spot", new Req(36.5f, 46.5f, 48.5f, 46.5f, 1));
            PathfindNative.removeVehicle(7);
            PathfindNative.update();
            p("vehicle removed id=7");
            req("veh_after_remove", new Req(36.5f, 46.5f, 48.5f, 46.5f, 1));
            putVehicle(vb, 8, 40.0f, 40.0f, 44.0f, 52.0f, 0.5f, 1.0f, crawl);
            PathfindNative.addVehicle(vb);
            PathfindNative.update();
            p("vehicle added id=8");
            req("veh_add_second_spot", new Req(36.5f, 46.5f, 48.5f, 46.5f, 1));
            Req z2 = new Req(36.5f, 46.5f, 48.5f, 46.5f, 2); z2.canCrawl = true; z2.crawling = true; z2.hasTarget = true;
            req("veh_add_second_spot_crawl", z2);
            PathfindNative.removeVehicle(8);
            PathfindNative.update();
            p("vehicle removed id=8");
        }

        // remocao de chunk e requisicao atravessando o chunk removido
        PathfindNative.removeChunk(2, 2);
        PathfindNative.update();
        req("after_removeChunk", new Req(5.5f, 5.5f, 55.5f, 55.5f, 1));
        PathfindNative.updateChunk((short) 100, 2, 2, chunkBuffer(2, 2));
        PathfindNative.update();
        req("after_reloadChunk", new Req(5.5f, 5.5f, 55.5f, 55.5f, 1));

        p("requests " + requests + " found " + found);
        if (found < 10) throw new IllegalStateException("poucos caminhos encontrados: " + found + "/" + requests + " (nativo nao exercitado?)");

        // medicao: rotina pesada = findPath em rotas variadas (LCG fixo); aquecimento 100 + 1000 medidas
        if (timesPath != null) {
            int warm = 100, runs = 1000;
            long[] t = new long[runs];
            long seed = 99, chk = 0;
            int ok = 0;
            for (int i = 0; i < warm + runs; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float sx = 1.5f + (int) ((seed >>> 33) % 60), sy = 1.5f + (int) ((seed >>> 13) % 60);
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float tx = 1.5f + (int) ((seed >>> 33) % 60), ty = 1.5f + (int) ((seed >>> 13) % 60);
                Req r = new Req(sx, sy, tx, ty, 1);
                long a = System.nanoTime();
                int ret = doRequest(r);
                long d = System.nanoTime() - a;
                if (ret == 1) { ok++; chk += pathBb.getShort(); }
                if (i >= warm) t[i - warm] = d;
            }
            long sum = 0; for (long v : t) sum += v;
            java.util.Arrays.sort(t);
            try (PrintWriter tw = new PrintWriter(timesPath)) {
                tw.println(String.format(Locale.ROOT, "findPath1000 mean_us=%.3f median_us=%.3f min_us=%.3f max_us=%.3f n=%d warmup=%d found=%d chk=%d",
                    sum / (double) runs / 1000.0, (t[runs / 2 - 1] + t[runs / 2]) / 2.0 / 1000.0, t[0] / 1000.0, t[runs - 1] / 1000.0, runs, warm, ok, chk));
            }
        }
        PathfindNative.destroyWorld();
        PathfindNative.freeMemoryAtExit();
        p("destroyed");
    }

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(args[0]);
        String times = args.length > 1 ? args[1] : null;
        Thread th = new Thread(() -> {
            try { body(times); } catch (Throwable e) { failure = e; }
        }, "PathfindNativeThread");
        th.start();
        th.join();
        out.flush(); out.close();
        if (failure != null) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
