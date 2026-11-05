import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Locale;
import zombie.core.physics.Bullet;

/**
 * Harness da Bullet nativa (PZBullet64.dll x libPZBullet.dylib), 98 exports Java_zombie_core_physics_Bullet_*.
 * Uso: BulletHarness <saida.txt> [<tempos.txt>]
 *
 * Ordem real (bytecode de Bullet, WorldSimulation, VehicleScript.toBullet, CarController, IsoChunk):
 *   Bullet.init() (loadLibrary + cmdBuf direto LE de 4096 + initPZBullet)
 *   WorldSimulation.create -> Bullet.initWorld(9 ints) = initWorld(7) + activateChunkMap(0, xmin, ymin, grid)
 *   Bullet.clearPhysicsMeshes() (initPhysicsMeshes; sem malhas: nao ha assets do jogo)
 *   VehicleScript.toBullet -> defineVehicleScript(nome, float[200])
 *   IsoChunk.update -> setChunkMinMaxLevel; IsoChunk.updatePhysicsForLevel -> cmd 8 (wx,wy,min,max,nivel), updateChunk(x,y,n,bytes) x64, cmd -1 -1, CatchToBullet
 *   Bullet.updatePlayerList -> cmd 12 (n shorts, n x (id,x,y), -1 -1), CatchToBullet
 *   CarController -> addVehicle(id,x,y,altura,qx,qy,qz,qw,script); controlVehicle(id,...)
 *   WorldSimulation.update -> stepSimulation(0.01f,0,0f); getVehicleCount/getVehiclePhysics; getObjectPhysics
 * Mundo minimo inventado: 7x7 chunks (56x56 quadrados), chao em todos, um bloco solido no caminho do veiculo.
 * Cada passo = 5 chamadas de stepSimulation(0.01f,0,0f) (50 ms); 120 passos = 6 s simulados.
 * Formato: linhas "step=N ..." sao comparadas por compare_float.py (maior diferenca por passo); as demais exatas.
 */
public class BulletHarness {
    static PrintWriter out;
    static final float[] ff = new float[8192];

    static void p(String s) { out.println(s); }
    static String f(float v) { return String.format(Locale.ROOT, "%.9f", v); }

    static ByteBuffer cmd() { ByteBuffer b = Bullet.cmdBuf; b.clear(); return b; }

    static void updateChunkAll(int wx, int wy, int solidFromX, int solidToX, int solidFromY, int solidToY) {
        Bullet.setChunkMinMaxLevel(wx, wy, 0, 0);
        ByteBuffer b = cmd();
        b.put((byte) 8).putShort((short) wx).putShort((short) wy).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        byte[] shapes = new byte[4];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int gx = wx * 8 + x, gy = wy * 8 + y;
            int n = 0;
            if (gx >= solidFromX && gx <= solidToX && gy >= solidFromY && gy <= solidToY) shapes[n++] = 1;   // Solid (ordinal 0 + 1)
            shapes[n++] = 7;                                                                                    // Floor (ordinal 6 + 1)
            Bullet.updateChunk(x, y, n, shapes);
        }
        Bullet.endUpdateChunk();
    }

    static void playerList(int id, int x, int y) {
        ByteBuffer b = cmd();
        b.put((byte) 12).putShort((short) 1).putInt(id).putInt(x).putInt(y);
        b.put((byte) -1).put((byte) -1);
        Bullet.CatchToBullet(b);
    }

    static float[] vehicleScript() {
        float[] a = new float[200];
        int i = 0;
        a[i++] = 1.0f;     // modelScale
        a[i++] = 800.0f;   // massa
        a[i++] = 0.1f;     // rollInfluence
        a[i++] = 30.0f;    // suspensionStiffness
        a[i++] = 2.88f;    // compression
        a[i++] = 2.83f;    // damping
        a[i++] = 10.0f;    // maxSuspensionTravelCm
        a[i++] = 0.2f;     // suspensionRestLength
        a[i++] = 1.4f;     // wheelFriction
        a[i++] = 5.0f;     // stoppingMovementForce
        a[i++] = 4.0f;     // numero de rodas
        float[][] w = { { 1, -0.45f, -0.1f, 0.80f }, { 1, 0.45f, -0.1f, 0.80f }, { 0, -0.45f, -0.1f, -0.80f }, { 0, 0.45f, -0.1f, -0.80f } };
        for (float[] r : w) { a[i++] = r[0]; a[i++] = r[1]; a[i++] = r[2]; a[i++] = r[3]; a[i++] = 0.25f; }   // frente, x, y, z, raio
        a[i++] = 1.0f;     // numero de formas
        a[i++] = 1.0f;     // tipo caixa
        a[i++] = 0.0f; a[i++] = 0.0f; a[i++] = 0.0f;     // offset
        a[i++] = 1.0f; a[i++] = 0.6f; a[i++] = 2.2f;     // extents
        a[i++] = 0.0f; a[i++] = 0.0f; a[i++] = 0.0f;     // rotate
        return a;
    }

    /**
     * Durante stepSimulation o nativo chama de volta Bullet.updatePhysicsForLevelIfNeeded(wx,wy,nivel) (static do jogo), que usa
     * IsoWorld.instance.currentCell.getChunk. Sem mundo carregado dava NPE a cada chamada (45 por passo, nas duas arquiteturas).
     * Stub: currentCell com um unico IsoChunkMap com ignore=true, entao getChunk devolve null ("chunk nao carregado", retorno false).
     */
    static void stubCell() throws Throwable {
        java.lang.reflect.Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        Object cell = u.allocateInstance(zombie.iso.IsoCell.class);
        Object map = u.allocateInstance(zombie.iso.IsoChunkMap.class);
        java.lang.reflect.Field ig = zombie.iso.IsoChunkMap.class.getDeclaredField("ignore");
        ig.setAccessible(true); ig.setBoolean(map, true);
        java.lang.reflect.Field cm = zombie.iso.IsoCell.class.getDeclaredField("chunkMap");
        cm.setAccessible(true);
        Object arr = java.lang.reflect.Array.newInstance(zombie.iso.IsoChunkMap.class, 4);
        for (int i = 0; i < 4; i++) java.lang.reflect.Array.set(arr, i, map);
        cm.set(cell, arr);
        zombie.iso.IsoWorld.instance.currentCell = (zombie.iso.IsoCell) cell;
    }

    static void body(String timesPath) throws Throwable {
        stubCell();
        Bullet.init();
        p("init ok");
        p("version " + Bullet.getPZBulletVersion());
        p("isWorldInit antes=" + Bullet.isWorldInit());
        Bullet.defineVehicleScript("Base.HarnessCar", vehicleScript());     // VehicleScript.Loaded -> toBullet, antes do mundo
        p("script definido");
        Bullet.initWorld(0, 0, 1, 1, 0, 0, 0, 0, 13);
        p("isWorldInit depois=" + Bullet.isWorldInit());
        Bullet.clearPhysicsMeshes();                                        // Bullet.initPhysicsMeshes
        float[] cube = new float[24];
        int ci = 0;
        for (int cx = -1; cx <= 1; cx += 2) for (int cy = -1; cy <= 1; cy += 2) for (int cz = -1; cz <= 1; cz += 2) { cube[ci++] = cx * 0.5f; cube[ci++] = cy * 0.5f; cube[ci++] = cz * 0.5f; }
        Bullet.definePhysicsMesh(0, false, cube);                           // casco convexo de um cubo (faces coplanares)
        p("definePhysicsMesh ok");
        Bullet.defineVehiclePhysicsMesh("Base.HarnessCar", 1, cube);
        p("defineVehiclePhysicsMesh ok");

        for (int cy = 0; cy < 7; cy++) for (int cx = 0; cx < 7; cx++) updateChunkAll(cx, cy, 36, 37, 20, 36);
        p("chunks 49");
        playerList(0, 12, 28);

        final int VID = 1;
        Bullet.addVehicle(VID, 10.5f, 28.5f, 0.6f, 0f, 0.7071068f, 0f, 0.7071068f, "Base.HarnessCar");   // girado 90 graus: nariz para +x
        p("vehicleCount apos add=" + Bullet.getVehicleCount());
        int obj = Bullet.addPhysicsObject(20.5f, 20.5f);
        p("physicsObject id=" + obj);
        Bullet.setTireInflation(VID, 0, 0.9f);
        Bullet.setTireRemoved(VID, 3, false);
        Bullet.setVehicleVelocityMultiplier(VID, 1.0f, 1.0f);

        float[] own = new float[64];
        float[] objf = new float[64];
        long startNs = System.nanoTime();
        for (int step = 1; step <= 120; step++) {
            float engine = step < 90 ? 3000.0f : 0.0f, brake = step >= 90 ? 400.0f : 0.0f, steer = (step >= 5 && step < 15) ? 0.08f : 0.0f;
            Bullet.controlVehicle(VID, engine, brake, steer);
            if (step == 50) Bullet.applyCentralForceToVehicle(VID, 0f, 500f, 0f);
            if (step == 60) Bullet.applyTorqueToVehicle(VID, 0f, 200f, 0f);
            playerList(0, 12 + step / 6, 28);
            for (int s = 0; s < 5; s++) Bullet.stepSimulation(0.01f, 0, 0f);
            int vc = Bullet.getVehicleCount();
            int n = Bullet.getVehiclePhysics(0, ff);
            StringBuilder sb = new StringBuilder("step=" + step + " vc=" + vc + " n=" + n);
            if (n > 0) {
                sb.append(" id=").append((int) ff[0]);
                String[] nm = { "x", "y", "z", "qx", "qy", "qz", "qw", "vx", "vy", "vz" };
                for (int i = 0; i < nm.length; i++) sb.append(' ').append(nm[i]).append('=').append(f(ff[1 + i]));
                sb.append(" a=").append((int) ff[11]);
                for (int w = 0; w < 4; w++) {
                    int o = 12 + w * 4;
                    sb.append(" w").append(w).append('=').append(f(ff[o])).append(',').append(f(ff[o + 1])).append(',').append(f(ff[o + 2])).append(',').append(f(ff[o + 3]));
                }
            }
            int no = Bullet.getObjectPhysics(objf);
            sb.append(" no=").append(no);
            if (no > 0) sb.append(" oid=").append((int) objf[0]).append(" ox=").append(f(objf[1])).append(" oy=").append(f(objf[2]));
            p(sb.toString());
        }
        p("fim cenario");
        long elapsedNs = System.nanoTime() - startNs;
        System.err.println("cenario_ms=" + elapsedNs / 1e6);

        // fase posterior (linhas "post=": tolerancia como "step="): exports extras do veiculo, na ordem de BaseVehicle
        Bullet.teleportVehicle(VID, 12.5f, 28.5f, 0.6f, 0f, 0f, 0f, 1f);
        Bullet.setVehicleActive(VID, true);
        p("setVehicleStatic ret=" + Bullet.setVehicleStatic(VID, false));
        p("setVehicleMass ret=" + Bullet.setVehicleMass(VID, 900.0f));
        Bullet.createServerCell(3, 3);
        Bullet.removeServerCell(3, 3);
        for (int k = 1; k <= 10; k++) {
            Bullet.controlVehicle(VID, 2000.0f, 0.0f, 0.0f);
            for (int s = 0; s < 5; s++) Bullet.stepSimulation(0.01f, 0, 0f);
            int n = Bullet.getVehiclePhysics(0, ff);
            StringBuilder sb = new StringBuilder("post=" + k + " n=" + n);
            if (n > 0) for (int i = 0; i < 11; i++) sb.append(" f").append(i).append('=').append(f(ff[i]));
            p(sb.toString());
        }
        int ro = Bullet.getOwnVehiclePhysics(VID, own);
        StringBuilder sb2 = new StringBuilder("post=11 own_ret=" + ro);
        for (int i = 0; i < 12; i++) sb2.append(" o").append(i).append('=').append(f(own[i]));
        p(sb2.toString());

        // entradas invalidas conhecidas: veiculo inexistente (log nativo "Vehicle not found. VehicleId:%d" via vsnprintf -> DebugLog.nativeLog)
        p("setVehicleParams invalido ret=" + Bullet.setVehicleParams(9999, new float[64]));
        p("setVehicleMass invalido ret=" + Bullet.setVehicleMass(9999, 100.0f));
        try { p("getOwnVehiclePhysics invalido ret=" + Bullet.getOwnVehiclePhysics(9999, own)); }
        catch (RuntimeException e) { p("getOwnVehiclePhysics invalido excecao " + e.getClass().getName() + ": " + e.getMessage()); }
        try { Bullet.controlVehicle(9999, 1.0f, 0.0f, 0.0f); p("controlVehicle invalido sem excecao"); }
        catch (RuntimeException e) { p("controlVehicle invalido excecao " + e.getClass().getName() + ": " + e.getMessage()); }
        try { p("setOwnVehiclePhysics invalido ret=" + Bullet.setOwnVehiclePhysics(9999, own, false)); }
        catch (RuntimeException e) { p("setOwnVehiclePhysics invalido excecao " + e.getClass().getName() + ": " + e.getMessage()); }
        try { Bullet.removeVehicle(9999); p("removeVehicle invalido sem excecao"); }
        catch (RuntimeException e) { p("removeVehicle invalido excecao " + e.getClass().getName() + ": " + e.getMessage()); }

        // rotina pesada para os tempos: 20 medidas de 50 chamadas de stepSimulation(0.01f) com o veiculo dirigindo (3 de aquecimento)
        if (timesPath != null) {
            int warm = 3, runs = 20;
            long[] t = new long[runs];
            double chk = 0;
            for (int i = 0; i < warm + runs; i++) {
                long a = System.nanoTime();
                Bullet.controlVehicle(VID, 2000.0f, 0.0f, 0.0f);
                for (int s = 0; s < 50; s++) Bullet.stepSimulation(0.01f, 0, 0f);
                long d = System.nanoTime() - a;
                if (i >= warm) t[i - warm] = d;
                Bullet.getVehiclePhysics(0, ff);
                chk += ff[1] * 0.001 + ff[3] * 0.001;
            }
            long sum = 0; for (long v : t) sum += v;
            java.util.Arrays.sort(t);
            try (PrintWriter tw = new PrintWriter(timesPath)) {
                tw.println(String.format(Locale.ROOT, "step50 mean_us=%.3f median_us=%.3f min_us=%.3f max_us=%.3f n=%d warmup=%d chk=%.2f",
                    sum / (double) runs / 1000.0, (t[runs / 2 - 1] + t[runs / 2]) / 2.0 / 1000.0, t[0] / 1000.0, t[runs - 1] / 1000.0, runs, warm, chk));
            }
        }

        Bullet.removeVehicle(VID);
        p("vehicleCount apos remove=" + Bullet.getVehicleCount());
        Bullet.destroyWorld();
        p("isWorldInit apos destroy=" + Bullet.isWorldInit());
    }

    static Throwable failure;

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(args[0]);
        String times = args.length > 1 ? args[1] : null;
        Thread th = new Thread(() -> {
            try { body(times); } catch (Throwable e) { failure = e; }
        }, "BulletHarness");
        th.start();
        th.join();
        out.flush(); out.close();
        if (failure != null) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
