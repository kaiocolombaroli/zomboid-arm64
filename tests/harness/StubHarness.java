import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uso: StubHarness <lib> <methods.tsv> <saida.txt>
 * Carrega a DLL stub (java.library.path = dist\) e chama, por reflexao nos metodos native reais do jar,
 * um metodo de cada retorno (int, long, boolean, float, double, objeto, void, ...), duas vezes cada.
 * Exige 0 / false / 0.0 / null. Grava "OK <ret> <export>" por metodo; sai com 1 se algo falhar.
 */
public class StubHarness {
    static Class<?>[] params(String desc, ClassLoader cl) throws Exception {
        List<Class<?>> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int j = i;
            while (desc.charAt(j) == '[') j++;
            if (desc.charAt(j) == 'L') j = desc.indexOf(';', j);
            out.add(one(desc.substring(i, j + 1), cl));
            i = j + 1;
        }
        return out.toArray(new Class<?>[0]);
    }

    static Class<?> one(String d, ClassLoader cl) throws Exception {
        switch (d.charAt(0)) {
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'S': return short.class;
            case 'C': return char.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
            case '[': return java.lang.reflect.Array.newInstance(one(d.substring(1), cl), 0).getClass();
            default: return Class.forName(d.substring(1, d.length() - 1).replace('/', '.'), false, cl);
        }
    }

    static Object zero(Class<?> c) {
        if (c == boolean.class) return false;
        if (c == byte.class) return (byte) 0;
        if (c == short.class) return (short) 0;
        if (c == char.class) return (char) 0;
        if (c == int.class) return 0;
        if (c == long.class) return 0L;
        if (c == float.class) return 0f;
        if (c == double.class) return 0d;
        return null;
    }

    static boolean isZero(Object r, Class<?> t) {
        if (t == void.class || !t.isPrimitive()) return r == null;
        if (r instanceof Boolean) return !((Boolean) r);
        if (r instanceof Character) return ((Character) r) == 0;
        if (r instanceof Float) return ((Float) r) == 0f;
        if (r instanceof Double) return ((Double) r) == 0d;
        return ((Number) r).longValue() == 0L;
    }

    public static void main(String[] a) throws Exception {
        System.loadLibrary(a[0]);
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        ClassLoader cl = StubHarness.class.getClassLoader();
        Map<String, List<String[]>> byRet = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            String[] f = line.split("\t", -1);
            if (f.length < 5 || f[1].isEmpty()) continue;
            String r = f[3].substring(f[3].indexOf(')') + 1);
            String key = r.startsWith("L") || r.startsWith("[") ? "L" : r;
            byRet.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        List<String> out = new ArrayList<>();
        int fails = 0;
        for (Map.Entry<String, List<String[]>> e : byRet.entrySet()) {
            List<String[]> c = e.getValue();
            c.sort((x, y) -> x[4].equals(y[4]) ? 0 : x[4].equals("1") ? -1 : 1);
            boolean done = false;
            String lastErr = "";
            for (String[] f : c) {
                try {
                    Class<?> k = Class.forName(f[1], false, cl);
                    Class<?>[] pt = params(f[3], cl);
                    Method m = k.getDeclaredMethod(f[2], pt);
                    m.setAccessible(true);
                    Object[] args = new Object[pt.length];
                    for (int i = 0; i < pt.length; i++) args[i] = zero(pt[i]);
                    Object self = f[4].equals("1") ? null : unsafe.allocateInstance(k);
                    for (int n = 0; n < 2; n++) {
                        Object r = m.invoke(self, args);
                        if (!isZero(r, m.getReturnType())) throw new AssertionError("retorno nao zero: " + r);
                    }
                    out.add("OK " + e.getKey() + " " + f[0]);
                    done = true;
                    break;
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    Throwable t = ex.getCause();
                    lastErr = String.valueOf(t);
                    if (t instanceof UnsatisfiedLinkError) {
                        out.add("FAIL " + e.getKey() + " " + f[0] + " " + lastErr);
                        fails++;
                        done = true;
                        break;
                    }
                } catch (AssertionError ex) {
                    out.add("FAIL " + e.getKey() + " " + f[0] + " " + ex.getMessage());
                    fails++;
                    done = true;
                    break;
                } catch (Throwable t) {
                    lastErr = String.valueOf(t); // reflexao/init de classe: tenta o proximo candidato
                }
            }
            if (!done) out.add("SKIP " + e.getKey() + " " + lastErr);
        }
        Files.write(Paths.get(a[2]), out);
        for (String s : out) System.out.println(s);
        System.exit(fails == 0 ? 0 : 1);
    }
}
