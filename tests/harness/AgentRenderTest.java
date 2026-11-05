import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Teste dos hooks renderdiag/renderfix do agente (launcher/agent) num contexto GLFW oculto.
 * Chama o RingBuffer.drawElements REAL do jogo (via reflexao; transformado pelo agente: glDrawRangeElements -> PzArmAgent.draw) com um
 * programa GLSL proprio (uniforms DIFFUSE e DEPTH), marcado como "tile" por PzArmAgent.testPrograms, e com o estado propositalmente errado
 * (unidades 0/1 com outras texturas, DIFFUSE=1, DEPTH=0). Verifica o efeito de cada modo (-Dtest.fix=none|rebind|uniforms|dirty|dirtyprog|flush|flush64|flushtex|flushchunk|flushtexchunk).
 * Tambem forca o carregamento de StateRun (transformado) para que o verificador de bytecode valide a insercao de intent().
 * Modo -Dtest.fix=collapse (agente com collapse=player, sem GL): transformacao REAL de zombie.iso.fboRenderChunk.FBORenderCutaways
 * (bytes do jar -> PzArmAgent.instrumentCutaways): 2 trocas dentro de CalculateBuildingsToCollapse, nenhum outro metodo alterado,
 * caminho fatal (classe sem o alvo -> IllegalStateException), a classe viva (transformada pelo agente) carrega e passa no verificador, e a
 * logica de collapseNear (ponto do jogador/mouse chamam o original; vizinho zera lista e occludedByOrphanStructureFlag).
 * Saida: linhas "CHECK nome: OK|FALHOU"; exit 0 se tudo OK, 1 se algum falhou.
 */
public class AgentRenderTest {
    static int fails;
    static void check(String name, boolean ok) { System.out.println("CHECK " + name + ": " + (ok ? "OK" : "FALHOU")); if (!ok) fails++; }

    static int tex() {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return t;
    }

    static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        return s;
    }

    static int bound(int unit) {
        GL13.glActiveTexture(0x84C0 + unit);
        return GL11.glGetInteger(0x8069);
    }

    /** Um desenho tile com a intencao (a, b); devolve quantos flushes o agente fez nele. */
    static long d(Method draw, Object ring, zombie.core.textures.Texture a, zombie.core.textures.Texture b) throws Exception {
        PzArmAgent.intent(a, b);
        long f = PzArmAgent.renderStats()[6];
        draw.invoke(ring, 0, 0, 0, 0);
        return PzArmAgent.renderStats()[6] - f;
    }

    static final String CUT = "zombie.iso.fboRenderChunk.FBORenderCutaways";

    static byte[] bytesOf(String cls) throws Exception {
        try (InputStream in = AgentRenderTest.class.getClassLoader().getResourceAsStream(cls.replace('.', '/') + ".class")) { return in.readAllBytes(); }
    }

    static int count(ClassModel cm, String method, String callee) {
        int n = 0;
        for (MethodModel mm : cm.methods()) {
            if (method != null && !mm.methodName().equalsString(method)) continue;
            if (mm.code().isEmpty()) continue;
            for (CodeElement e : mm.code().get())
                if (e instanceof InvokeInstruction ii && ii.name().equalsString(callee)) n++;
        }
        return n;
    }

    static List<String> shape(ClassModel cm, String method) {
        List<String> l = new ArrayList<>();
        for (MethodModel mm : cm.methods())
            if (mm.methodName().equalsString(method) && mm.code().isPresent()) for (CodeElement e : mm.code().get()) {
                if (e instanceof java.lang.classfile.Instruction ins) {
                    String d = ins.opcode().name();
                    if (e instanceof InvokeInstruction ii) d += " " + ii.owner().asInternalName() + "." + ii.name().stringValue() + ii.type().stringValue();
                    else if (e instanceof java.lang.classfile.instruction.FieldInstruction fi) d += " " + fi.owner().asInternalName() + "." + fi.name().stringValue();
                    else if (e instanceof java.lang.classfile.instruction.LoadInstruction li) d += " " + li.slot();
                    else if (e instanceof java.lang.classfile.instruction.StoreInstruction si) d += " " + si.slot();
                    l.add(d);
                }
            }
        return l;
    }

    static void collapseTests() throws Exception {
        byte[] orig = bytesOf(CUT);
        ClassModel cmO = ClassFile.of().parse(orig);
        check("original tem 2 chamadas a GetBuildingsInFrontOfCharacter em CalculateBuildingsToCollapse", count(cmO, "CalculateBuildingsToCollapse", "GetBuildingsInFrontOfCharacter") == 2);
        check("original: nenhuma outra chamada a GetBuildingsInFrontOfCharacter na classe", count(cmO, null, "GetBuildingsInFrontOfCharacter") == 2);
        byte[] out = PzArmAgent.instrumentCutaways(orig);
        ClassModel cmT = ClassFile.of().parse(out);
        check("transformado: 0 chamadas a GetBuildingsInFrontOfCharacter na classe", count(cmT, null, "GetBuildingsInFrontOfCharacter") == 0);
        check("transformado: 2 chamadas a collapseNear em CalculateBuildingsToCollapse", count(cmT, "CalculateBuildingsToCollapse", "collapseNear") == 2);
        check("transformado: collapseNear so em CalculateBuildingsToCollapse", count(cmT, null, "collapseNear") == 2);
        boolean same = true;
        for (MethodModel mm : cmO.methods()) {
            String n = mm.methodName().stringValue();
            if (n.equals("CalculateBuildingsToCollapse")) continue;
            if (!shape(cmO, n).equals(shape(cmT, n))) { same = false; System.out.println("  metodo alterado: " + n); }
        }
        check("transformado: todos os outros metodos (CalculatePointsOfInterest inclusive) identicos", same && cmO.methods().size() == cmT.methods().size());
        boolean fatal = false;
        try { PzArmAgent.instrumentCutaways(bytesOf("zombie.iso.IsoCamera")); } catch (IllegalStateException e) { fatal = true; }
        check("classe sem os 2 pontos de troca -> IllegalStateException (o agente chama fatal)", fatal);
        // classe viva: o agente (collapse=player) transforma ao carregar; carregar e inicializar passa no verificador
        Class<?> live = Class.forName(CUT, true, AgentRenderTest.class.getClassLoader());
        check("FBORenderCutaways transformado carrega e verifica", live != null);

        zombie.iso.fboRenderChunk.FBORenderCutaways.PointOfInterest poi = new zombie.iso.fboRenderChunk.FBORenderCutaways.PointOfInterest();
        poi.x = 10; poi.y = 20; poi.z = 0; poi.mousePointer = false;
        check("keepPoint: ponto do jogador mantem", PzArmAgent.keepPoint(poi, 10, 20, 0));
        check("keepPoint: vizinho em x descarta", !PzArmAgent.keepPoint(poi, 11, 20, 0));
        check("keepPoint: vizinho em y descarta", !PzArmAgent.keepPoint(poi, 10, 21, 0));
        check("keepPoint: outro andar descarta", !PzArmAgent.keepPoint(poi, 10, 20, 1));
        poi.mousePointer = true; poi.x = 99;
        check("keepPoint: mousePointer mantem", PzArmAgent.keepPoint(poi, 10, 20, 0));
        poi.mousePointer = false;

        // collapseNear com jogador, celula e quadrado sem construtor (Unsafe.allocateInstance)
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        zombie.characters.IsoPlayer pl = (zombie.characters.IsoPlayer) u.allocateInstance(zombie.characters.IsoPlayer.class);
        for (String f : new String[] {"x", "y", "z"}) {
            Field fl = zombie.iso.IsoMovingObject.class.getDeclaredField(f);
            fl.setAccessible(true);
            fl.setFloat(pl, f.equals("x") ? 10.7f : f.equals("y") ? 20.2f : 0.0f);
        }
        zombie.characters.IsoPlayer.players[zombie.iso.IsoCamera.frameState.playerIndex] = pl;
        zombie.iso.IsoCell cell = (zombie.iso.IsoCell) u.allocateInstance(zombie.iso.IsoCell.class);
        zombie.iso.IsoGridSquare sq = (zombie.iso.IsoGridSquare) u.allocateInstance(zombie.iso.IsoGridSquare.class);
        ArrayList<zombie.iso.areas.IsoBuilding> lista = new ArrayList<>();
        lista.add(null);
        cell.occludedByOrphanStructureFlag = true;
        poi.x = 11; poi.y = 20; poi.z = 0;   // vizinho (jogador em 10,20,0)
        for (boolean b : new boolean[] {false, true}) {
            PzArmAgent.collapseNear(cell, lista, sq, b, poi);
            check("collapseNear vizinho (boolean " + b + "): nao chama o original, lista vazia e flag false", lista.isEmpty() && !cell.occludedByOrphanStructureFlag);
            lista.add(null); cell.occludedByOrphanStructureFlag = true;
        }
        poi.x = 10;   // ponto do jogador: chama o original (IsoCell sem estado lanca com quadrado nao nulo)
        boolean called = false;
        try { PzArmAgent.collapseNear(cell, lista, sq, false, poi); } catch (Throwable t) { called = true; }
        check("collapseNear ponto do jogador: chama o IsoCell.GetBuildingsInFrontOfCharacter original", called);
        poi.mousePointer = true; poi.x = 99; called = false;
        try { PzArmAgent.collapseNear(cell, lista, sq, true, poi); } catch (Throwable t) { called = true; }
        check("collapseNear ponto do mouse: chama o original", called);
        // quadrado nulo: o original limpa lista e flag e retorna
        poi.mousePointer = false; poi.x = 10; lista.add(null); cell.occludedByOrphanStructureFlag = true;
        PzArmAgent.collapseNear(cell, lista, null, false, poi);
        check("collapseNear jogador com quadrado nulo: original limpa lista e flag", lista.isEmpty() && !cell.occludedByOrphanStructureFlag);
        System.out.println(fails == 0 ? "TUDO OK" : ("FALHAS=" + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    public static void main(String[] a) throws Exception {
        String fix = System.getProperty("test.fix", "none");
        if (fix.equals("collapse")) { collapseTests(); return; }
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit falhou");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long win = GLFW.glfwCreateWindow(256, 256, "AgentRenderTest", 0, 0);
        if (win == 0) throw new IllegalStateException("glfwCreateWindow falhou");
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("GL_RENDERER=" + GL11C.glGetString(GL11C.GL_RENDERER) + " fix=" + fix);
        Field ct = zombie.core.opengl.RenderThread.class.getDeclaredField("contextThread");
        ct.setAccessible(true); ct.set(null, Thread.currentThread());
        Field ii = zombie.core.opengl.RenderThread.class.getDeclaredField("isInitialized");
        ii.setAccessible(true); ii.setBoolean(null, true);
        zombie.core.opengl.RenderThread.renderThread = Thread.currentThread();

        // 0. StateRun transformado precisa passar no verificador (carrega e inicializa a classe)
        Class<?> sr = Class.forName("zombie.core.SpriteRenderer$RingBuffer$StateRun", true, AgentRenderTest.class.getClassLoader());
        check("StateRun transformado carrega e verifica", sr != null);
        if (fix.endsWith("chunk")) {
            Class<?> fc = Class.forName("zombie.iso.fboRenderChunk.FBORenderChunk", true, AgentRenderTest.class.getClassLoader());
            check("FBORenderChunk transformado carrega e verifica", fc != null);
        }
        Class<?> rb = Class.forName("zombie.core.SpriteRenderer$RingBuffer", true, AgentRenderTest.class.getClassLoader());
        Constructor<?> ctor = rb.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object ring = ctor.newInstance();
        Method draw = rb.getDeclaredMethod("drawElements", int.class, int.class, int.class, int.class);
        draw.setAccessible(true);

        int vs = shader(GL20.GL_VERTEX_SHADER, "#version 120\nvoid main(){gl_Position=vec4(0.0);gl_TexCoord[0]=vec4(0.0);}");
        int fs = shader(GL20.GL_FRAGMENT_SHADER, "#version 120\nuniform sampler2D DIFFUSE;uniform sampler2D DEPTH;void main(){gl_FragColor=texture2D(DIFFUSE,gl_TexCoord[0].st)+texture2D(DEPTH,gl_TexCoord[0].st);}");
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs); GL20.glAttachShader(prog, fs); GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(prog));
        int locD = GL20.glGetUniformLocation(prog, "DIFFUSE"), locP = GL20.glGetUniformLocation(prog, "DEPTH");
        System.out.println("prog=" + prog + " locDIFFUSE=" + locD + " locDEPTH=" + locP);
        PzArmAgent.testPrograms(prog, locD, locP);

        // Texturas do jogo (id GL real) para o "pretendido"; texturas erradas ligadas de verdade
        zombie.core.textures.Texture t0 = new zombie.core.textures.Texture(2, 2, 16), t1 = new zombie.core.textures.Texture(2, 2, 16);
        System.out.println("pretendido0 id=" + t0.getID() + " ready=" + t0.isReady() + " pretendido1 id=" + t1.getID() + " ready=" + t1.isReady());
        int x0 = tex(), x1 = tex();
        Field fb = Class.forName("zombie.core.ShaderHelper").getDeclaredField("currentlyBound");
        fb.setAccessible(true);

        // estado errado de proposito
        GL20.glUseProgram(prog); fb.setInt(null, prog);
        GL20.glUniform1i(locD, 1); GL20.glUniform1i(locP, 0);
        bound(1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, x1);
        bound(0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, x0);
        PzArmAgent.intent(t0, t1);
        long[] before = PzArmAgent.renderStats();
        draw.invoke(ring, 0, 0, 0, 0);
        long[] s = PzArmAgent.renderStats();   // {draws, tile, amostras, div, rebinds, uniformes, flushes}
        int act = GL11.glGetInteger(0x84E0);
        int b0 = bound(0), b1 = bound(1);
        GL13.glActiveTexture(0x84C0);
        int dU = GL20.glGetUniformi(prog, locD), pU = GL20.glGetUniformi(prog, locP);
        System.out.println("stats=" + java.util.Arrays.toString(s) + " ativaAntes=" + Integer.toHexString(act) + " bind0=" + b0 + " bind1=" + b1 + " DIFFUSE=" + dU + " DEPTH=" + pU);

        check("hook draw foi chamado (glDrawRangeElements -> PzArmAgent.draw)", s[0] - before[0] == 1);
        check("programa tile reconhecido", s[1] - before[1] == 1);
        check("diag amostrou e viu divergencia", s[2] == 1 && s[3] == 1);
        check("GL_ACTIVE_TEXTURE termina em TEXTURE0", act == 0x84C0);
        boolean rebind = fix.equals("rebind") || fix.startsWith("dirty"), uni = fix.equals("uniforms");
        check("rebind " + (rebind ? "religou unidades 0/1" : "nao mexeu nas texturas"),
                rebind ? (b0 == t0.getID() && b1 == t1.getID() && s[4] == (fix.startsWith("dirty") ? 0 : 1)) : (b0 == x0 && b1 == x1 && s[4] == 0));
        boolean dirty = fix.startsWith("dirty");
        check("dirty " + (dirty ? "contou 1 e restaurou programa/uniforms" : "nao atuou"),
                dirty ? (s[7] == 1 && GL11.glGetInteger(0x8B8D) == prog && dU == 1 && pU == 0) : s[7] == 0);
        check("uniforms " + (uni ? "reenviou DIFFUSE=0 DEPTH=1" : "nao mexeu nos uniforms"),
                uni ? (dU == 0 && pU == 1 && s[5] == 1) : (dU == 1 && pU == 0 && s[5] == 0));
        // 1o desenho tile, fora de chunk: flush e flushtex (par de ids novo) fazem 1 flush; os demais, nenhum
        boolean f1 = fix.equals("flush") || fix.equals("flushtex");
        check("flush " + (f1 ? "chamou glFlush no 1o desenho" : "nao chamou"), f1 ? s[6] == 1 : s[6] == 0);
        check("chunktile fora de chunk = 0", s[8] == 0);

        // programa que nao e tile: nada acontece
        GL20.glUseProgram(0); fb.setInt(null, 0);
        draw.invoke(ring, 0, 0, 0, 0);
        long[] s2 = PzArmAgent.renderStats();
        check("desenho fora de tile conta draw mas nao tile/amostra/fix",
                s2[0] == s[0] + 1 && s2[1] == s[1] && s2[2] == s[2] && s2[4] == s[4] && s2[5] == s[5] && s2[6] == s[6] && s2[7] == s[7]);

        if (fix.equals("flushtex") || fix.equals("flushchunk") || fix.equals("flushtexchunk")) {
            zombie.core.textures.Texture t2 = new zombie.core.textures.Texture(2, 2, 16);
            check("ids de textura distintos", t0.getID() > 0 && t1.getID() > 0 && t2.getID() > 0 && t0.getID() != t1.getID() && t0.getID() != t2.getID() && t1.getID() != t2.getID());
            GL20.glUseProgram(prog); fb.setInt(null, prog);
            boolean chunkOnly = fix.endsWith("chunk");
            // fora de chunk
            d(draw, ring, t0, t1);
            check(fix + " fora do chunk: mesmo par -> 0 flush", d(draw, ring, t0, t1) == 0);
            check(fix + " fora do chunk: textura1 mudou -> " + (fix.equals("flushtex") ? 1 : 0), d(draw, ring, t0, t2) == (fix.equals("flushtex") ? 1 : 0));
            check(fix + " fora do chunk: repetiu -> 0", d(draw, ring, t0, t2) == 0);
            long ct0 = PzArmAgent.renderStats()[8];
            // dentro de chunk
            PzArmAgent.chunkEnter();
            long c1 = d(draw, ring, t0, t1), c2 = d(draw, ring, t0, t1), c3 = d(draw, ring, t2, t1), c4 = d(draw, ring, null, t1), c5 = d(draw, ring, null, t1);
            long c6 = d(draw, ring, t2, null);
            PzArmAgent.chunkExit();
            if (fix.equals("flushchunk")) check("flushchunk dentro do chunk: 1 flush por desenho (6)", c1 == 1 && c2 == 1 && c3 == 1 && c4 == 1 && c5 == 1 && c6 == 1);
            else check(fix + " dentro do chunk: flush so na mudanca (1o=1,igual=0,mudou t0=1,t0 null=1,igual=0,t1 null=1)", c1 == 1 && c2 == 0 && c3 == 1 && c4 == 1 && c5 == 0 && c6 == 1);
            check(fix + ": 6 desenhos tile contados dentro do chunk", PzArmAgent.renderStats()[8] - ct0 == 6);
            // depois de sair do chunk
            long y1 = d(draw, ring, t0, t1), y2 = d(draw, ring, t0, t1), y3 = d(draw, ring, t2, t1);
            if (chunkOnly) check(fix + " apos chunkExit: 0 flush", y1 == 0 && y2 == 0 && y3 == 0);
            else check("flushtex apos chunkExit: flush so na mudanca", y1 == 1 && y2 == 0 && y3 == 1);
            check(fix + ": desenhos fora do chunk nao contam chunktile", PzArmAgent.renderStats()[8] - ct0 == 6);
            // programa que nao e tile dentro de chunk: nao conta nem faz flush
            GL20.glUseProgram(0); fb.setInt(null, 0);
            PzArmAgent.chunkEnter();
            long[] n0 = PzArmAgent.renderStats();
            draw.invoke(ring, 0, 0, 0, 0);
            long[] n1 = PzArmAgent.renderStats();
            PzArmAgent.chunkExit();
            check(fix + ": desenho nao-tile dentro do chunk nao conta nem faz flush", n1[8] == n0[8] && n1[6] == n0[6]);
            GL20.glUseProgram(prog); fb.setInt(null, prog);
        }
        if (fix.equals("flush64")) {
            GL20.glUseProgram(prog); fb.setInt(null, prog);
            long f0 = PzArmAgent.renderStats()[6];   // 1 desenho tile ate aqui, 0 flushes
            for (int i = 0; i < 62; i++) draw.invoke(ring, 0, 0, 0, 0);
            check("flush64: 63 desenhos tile, 0 flushes", PzArmAgent.renderStats()[6] - f0 == 0);
            draw.invoke(ring, 0, 0, 0, 0);
            check("flush64: 64o desenho tile faz o 1o flush", PzArmAgent.renderStats()[6] - f0 == 1);
            for (int i = 0; i < 63; i++) draw.invoke(ring, 0, 0, 0, 0);
            check("flush64: 127 desenhos, ainda 1 flush", PzArmAgent.renderStats()[6] - f0 == 1);
            draw.invoke(ring, 0, 0, 0, 0);
            check("flush64: 128o desenho faz o 2o flush", PzArmAgent.renderStats()[6] - f0 == 2);
        }
        Thread.sleep(1200);
        for (int i = 0; i < 300; i++) draw.invoke(ring, 0, 0, 0, 0);   // o tick de 1 s so roda a cada 256 desenhos
        Path log = Path.of(System.getProperty("test.logdir", "logs"), "render-diag.log");
        String txt = Files.readString(log);
        check("render-diag.log tem linha DIV", txt.contains("DIV#1 "));
        check("render-diag.log tem linha por segundo", txt.contains(" draws=") && txt.contains("amostras="));
        if (fix.startsWith("flush")) {
            check("render-diag.log tem flush/s e flushevery", txt.contains(" flush/s=") && txt.contains("flushevery=" + (fix.equals("flush64") ? 64 : 1)));
            check("render-diag.log tem chunktile", txt.contains(" chunktile=") && txt.contains(" chunktile/s="));
        }
        GLFW.glfwTerminate();
        System.out.println(fails == 0 ? "TUDO OK" : ("FALHAS=" + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
