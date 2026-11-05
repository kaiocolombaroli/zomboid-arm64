import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;

/**
 * Teste do agente de diagnostico (launcher/agent): chama o TextureFBO REAL do jogo.
 * 1) caso valido (cor RGBA + profundidade D24 do jogo)      -> nao deve gerar entrada no log;
 * 2) caso artificialmente incompleto (textura RGBA como anexo de profundidade) -> excecao do jogo + entrada no log;
 * 3) mesmo caso de novo, mesma classe chamadora            -> NAO deve gerar segunda entrada.
 */
public class AgentFboTest {
    static String tryFbo(boolean bad) {
        try {
            zombie.core.textures.Texture t = new zombie.core.textures.Texture(512, 512, 16);
            zombie.core.textures.Texture d = new zombie.core.textures.Texture(512, 512, bad ? 16 : 512);
            zombie.core.textures.TextureFBO f = new zombie.core.textures.TextureFBO(t, d, false);
            return "COMPLETE id=" + f.getBufferId();
        } catch (Throwable ex) {
            return "EXCECAO: " + ex + (ex.getCause() != null ? " / " + ex.getCause() : "");
        }
    }

    public static void main(String[] a) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit falhou");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, 1);
        long win = GLFW.glfwCreateWindow(1366, 768, "AgentFboTest", 0, 0);
        if (win == 0) throw new IllegalStateException("glfwCreateWindow falhou");
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("GL_RENDERER=" + GL11C.glGetString(GL11C.GL_RENDERER));
        java.lang.reflect.Field ct = zombie.core.opengl.RenderThread.class.getDeclaredField("contextThread");
        ct.setAccessible(true); ct.set(null, Thread.currentThread());
        java.lang.reflect.Field ii = zombie.core.opengl.RenderThread.class.getDeclaredField("isInitialized");
        ii.setAccessible(true); ii.setBoolean(null, true);
        zombie.core.opengl.RenderThread.renderThread = Thread.currentThread();
        System.out.println("valido    : " + tryFbo(false));
        System.out.println("incompleto: " + tryFbo(true));
        System.out.println("incompleto: " + tryFbo(true));
        GLFW.glfwTerminate();
    }
}
