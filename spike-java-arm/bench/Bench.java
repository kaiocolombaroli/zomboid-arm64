import java.util.*;

public class Bench {
    static long sink;
    static double dsink;
    static final long WARM_MS = Long.getLong("warm", 5000);
    static final long MEAS_MS = Long.getLong("meas", 6000);
    static final int REPS = Integer.getInteger("reps", 3);

    interface Load { long chunk(); } // returns ops done in one chunk

    // ---- interpreter
    static final int[] CODE = new int[256];
    static {
        Random r = new Random(1);
        for (int i = 0; i < CODE.length; i++) CODE[i] = r.nextInt(1 << 20);
    }
    static long interp() {
        int[] code = CODE; long[] reg = new long[16]; long acc = 1; int pc = 0; int n = 0;
        for (int k = 0; k < 2_000_000; k++) {
            int ins = code[pc & 255]; pc++;
            int a = (ins >> 4) & 15, b = (ins >> 8) & 15;
            switch (ins & 15) {
                case 0: reg[a] = reg[b] + acc; break;
                case 1: reg[a] = reg[b] - 3; break;
                case 2: reg[a] = reg[a] * 31 + b; break;
                case 3: acc ^= reg[a]; break;
                case 4: reg[a] = ins >>> 12; break;
                case 5: if ((reg[a] & 1) == 0) pc += 2; break;
                case 6: acc += reg[b]; break;
                case 7: reg[a] = reg[b] ^ (reg[a] >>> 3); break;
                case 8: acc = acc * 6364136223846793005L + 1442695040888963407L; break;
                case 9: reg[a] = code[(int) (reg[b] & 255)]; break;
                case 10: reg[a] <<= (b & 7); break;
                case 11: if (acc < 0) pc -= 1; break;
                case 12: reg[a] = reg[a] | reg[b]; break;
                case 13: reg[a] = Math.max(reg[a], reg[b]); break;
                case 14: acc += pc; break;
                default: reg[a] = reg[b] & 0xFFFF; break;
            }
        }
        sink += acc + reg[3] + reg[7];
        return 2_000_000;
    }

    // ---- objects
    static abstract class Ent { float x, y; abstract void update(float dt); }
    static final class Zed extends Ent { int hp = 100; void update(float dt) { x += dt * 1.1f; y -= dt * 0.7f; hp--; } }
    static final class Pl extends Ent { float v = 2; void update(float dt) { x += v * dt; y += v * dt * 0.5f; } }
    static final class Item extends Ent { int age; void update(float dt) { age++; x += dt * 0.01f; } }
    static final ArrayList<Ent> ENTS = new ArrayList<>();
    static long objects() {
        ArrayList<Ent> list = ENTS;
        if (list.isEmpty()) {
            for (int i = 0; i < 5000; i++) list.add(i % 3 == 0 ? new Zed() : i % 3 == 1 ? new Pl() : new Item());
        }
        long ops = 0;
        for (int it = 0; it < 20; it++) {
            for (int i = 0, n = list.size(); i < n; i++) list.get(i).update(0.016f);
            ops += list.size();
            // churn short-lived objects
            ArrayList<Ent> tmp = new ArrayList<>();
            for (int i = 0; i < 5000; i++) { Ent e = (i & 1) == 0 ? new Zed() : new Item(); e.x = i; tmp.add(e); }
            float s = 0; for (Ent e : tmp) s += e.x;
            dsink += s; ops += 5000;
        }
        return ops;
    }

    // ---- hashmap
    static final HashMap<String, Object> MAP = new HashMap<>();
    static final String[] KEYS = new String[20000];
    static { for (int i = 0; i < KEYS.length; i++) KEYS[i] = "item_" + i + "_key"; }
    static int hmI;
    static long hashmap() {
        HashMap<String, Object> m = MAP; long hit = 0;
        for (int i = 0; i < 400_000; i++) {
            String k = KEYS[(hmI * 7919 + i * 31) % KEYS.length];
            if ((i & 3) == 0) m.put(k, k); else if (m.get(k) != null) hit++;
        }
        hmI++; sink += hit + m.size();
        return 400_000;
    }

    // ---- floatmath
    static long floatmath() {
        float px = 10.5f, py = -3.2f, acc = 0;
        for (int i = 0; i < 1_000_000; i++) {
            float x = (i & 1023) * 0.37f, y = ((i >> 3) & 1023) * 0.21f;
            float dx = x - px, dy = y - py;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float a = (float) Math.atan2(dy, dx);
            acc += d * 0.001f + a;
        }
        dsink += acc;
        return 1_000_000;
    }

    // ---- grid
    static final int X = 128, Y = 128, Z = 8;
    static final int[] TILE = new int[X * Y * Z];
    static final byte[][][] FLAGS = new byte[Z][Y][X];
    static {
        Random r = new Random(2);
        for (int i = 0; i < TILE.length; i++) TILE[i] = r.nextInt(64);
        for (int z = 0; z < Z; z++) for (int y = 0; y < Y; y++) for (int x = 0; x < X; x++) FLAGS[z][y][x] = (byte) r.nextInt(8);
    }
    static long grid() {
        long cnt = 0;
        for (int z = 0; z < Z; z++)
            for (int y = 0; y < Y; y++) {
                byte[] row = FLAGS[z][y]; int base = (z * Y + y) * X;
                for (int x = 0; x < X; x++) {
                    if ((row[x] & 2) != 0 && TILE[base + x] > 20) cnt += TILE[base + x];
                    else cnt ^= row[x];
                }
            }
        sink += cnt;
        return (long) X * Y * Z;
    }

    static void run(String name, Load l) {
        long end = System.nanoTime() + WARM_MS * 1_000_000L;
        while (System.nanoTime() < end) l.chunk();
        for (int r = 1; r <= REPS; r++) {
            long ops = 0, t0 = System.nanoTime(), lim = t0 + MEAS_MS * 1_000_000L, t;
            do { ops += l.chunk(); t = System.nanoTime(); } while (t < lim);
            double sec = (t - t0) / 1e9;
            System.out.println("RES," + name + "," + r + "," + String.format(Locale.ROOT, "%.0f", ops / sec));
        }
    }

    public static void main(String[] a) {
        run("interpreter", Bench::interp);
        run("objects", Bench::objects);
        run("hashmap", Bench::hashmap);
        run("floatmath", Bench::floatmath);
        run("grid", Bench::grid);
        System.out.println("SINK," + sink + "," + dsink);
    }
}
