public class JniEnvTest {
    // Pesos distintos: qualquer troca de argumento muda o resultado.
    public static int m(int a, long b, float c, double d) {
        return a + (int) b * 10 + (int) (c * 2) * 100 + (int) (d * 4) * 1000;
    }

    public int inst(int a, long b, float c, double d) {
        return m(a, b, c, d);
    }
}
