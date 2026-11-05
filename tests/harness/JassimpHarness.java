import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import jassimp.AiAnimation;
import jassimp.AiBone;
import jassimp.AiBoneWeight;
import jassimp.AiMaterial;
import jassimp.AiMatrix4f;
import jassimp.AiMesh;
import jassimp.AiNode;
import jassimp.AiNodeAnim;
import jassimp.AiPostProcessSteps;
import jassimp.AiScene;
import jassimp.Jassimp;

/**
 * Harness deterministico do jassimp nativo (jassimp.Jassimp: aiImportFile + getErrorString).
 * Uso: JassimpHarness <saida.txt> [<tempos.txt>]   (-Dpz.game=<pasta do jogo> -Dpz.tmp=<pasta gravavel>)
 *
 * Chamada real do jogo (bytecode de FileTask_LoadMesh / FileTask_LoadAnimation / AiSceneAssetManager, sem AiIOSystem
 * nem AiProgressHandler, ou seja, aiImportFile(caminho, flags, null, null) por Jassimp.importFile(String, Set)):
 *   malha:    EnumSet.of(FIND_INSTANCES, MAKE_LEFT_HANDED, LIMIT_BONE_WEIGHTS, TRIANGULATE, OPTIMIZE_MESHES,
 *             REMOVE_REDUNDANT_MATERIALS, JOIN_IDENTICAL_VERTICES)
 *   animacao: EnumSet.of(MAKE_LEFT_HANDED, REMOVE_REDUNDANT_MATERIALS)
 * A escala (0.01) e a rotacao de +-90 graus sao aplicadas depois, em Java, nao pelo assimp.
 * Casos: modelos reais de media/ (estatico .x e .fbx, malha com esqueleto, animacao) + arquivo truncado + inexistente.
 */
public class JassimpHarness {
    static PrintWriter out;
    static final EnumSet<AiPostProcessSteps> MESH = EnumSet.of(AiPostProcessSteps.FIND_INSTANCES,
        AiPostProcessSteps.MAKE_LEFT_HANDED, AiPostProcessSteps.LIMIT_BONE_WEIGHTS, AiPostProcessSteps.TRIANGULATE,
        AiPostProcessSteps.OPTIMIZE_MESHES, AiPostProcessSteps.REMOVE_REDUNDANT_MATERIALS,
        AiPostProcessSteps.JOIN_IDENTICAL_VERTICES);
    static final EnumSet<AiPostProcessSteps> ANIM = EnumSet.of(AiPostProcessSteps.MAKE_LEFT_HANDED,
        AiPostProcessSteps.REMOVE_REDUNDANT_MATERIALS);

    static void p(String s) { out.println(s); }
    static String f(double v) { return String.format(Locale.ROOT, "%.6f", v); }

    static void dumpMatrix(String tag, AiMatrix4f m) {
        double s = 0, t = 0;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) { s += m.get(r, c); t += m.get(r, c) * (r * 4 + c + 1); }
        p(tag + " sum=" + f(s) + " wsum=" + f(t));
    }

    static void dumpNode(AiNode n, int depth) {
        StringBuilder meshes = new StringBuilder();
        for (int i : n.getMeshes()) meshes.append(i).append(',');
        p("node d=" + depth + " name=" + n.getName() + " children=" + n.getNumChildren() + " meshes=" + meshes);
        dumpMatrix("  xform", (AiMatrix4f) n.getTransform(Jassimp.getWrapperProvider()));
        for (AiNode c : n.getChildren()) dumpNode(c, depth + 1);
    }

    static void dumpScene(AiScene sc) {
        p("meshes=" + sc.getNumMeshes() + " materials=" + sc.getNumMaterials() + " animations=" + sc.getNumAnimations()
            + " lights=" + sc.getNumLights() + " cameras=" + sc.getNumCameras());
        int mi = 0;
        for (AiMesh m : sc.getMeshes()) {
            double sp = 0, sn = 0, st = 0, wp = 0;
            FloatBuffer pos = m.getPositionBuffer();
            int nv = m.getNumVertices();
            for (int i = 0; i < nv * 3; i++) { sp += pos.get(i); wp += pos.get(i) * (i % 3 + 1) * ((i / 3) % 7 + 1); }
            if (m.hasNormals()) { FloatBuffer nb = m.getNormalBuffer(); for (int i = 0; i < nv * 3; i++) sn += nb.get(i); }
            if (m.hasTexCoords(0)) { for (int i = 0; i < nv; i++) st += m.getTexCoordU(i, 0) + 2 * m.getTexCoordV(i, 0); }
            long si = 0;
            IntBuffer ib = m.getIndexBuffer();
            int ni = ib.limit();
            for (int i = 0; i < ni; i++) si += (long) ib.get(i) * (i % 5 + 1);
            p("mesh " + mi + " name=" + m.getName() + " verts=" + nv + " faces=" + m.getNumFaces() + " indices=" + ni
                + " mat=" + m.getMaterialIndex() + " pure_tri=" + m.isPureTriangle() + " normals=" + m.hasNormals()
                + " uv=" + m.hasTexCoords(0) + " bones=" + (m.hasBones() ? m.getBones().size() : 0));
            p("  psum=" + f(sp) + " pwsum=" + f(wp) + " nsum=" + f(sn) + " uvsum=" + f(st) + " idxsum=" + si);
            if (m.hasBones()) {
                double totalW = 0;
                for (AiBone b : m.getBones()) {
                    double sw = 0; long sv = 0;
                    for (AiBoneWeight w : b.getBoneWeights()) { sw += w.getWeight(); sv += w.getVertexId(); }
                    totalW += sw;
                    p("  bone " + b.getName() + " weights=" + b.getNumWeights() + " wsum=" + f(sw) + " vidsum=" + sv);
                    dumpMatrix("    offset", (AiMatrix4f) b.getOffsetMatrix(Jassimp.getWrapperProvider()));
                }
                p("  bone_weight_total=" + f(totalW));
            }
            mi++;
        }
        int k = 0;
        for (AiMaterial mat : sc.getMaterials()) {
            p("material " + k + " props=" + mat.getProperties().size() + " name=" + mat.getName());
            k++;
        }
        for (AiAnimation an : sc.getAnimations()) {
            p("anim name=" + an.getName() + " duration=" + f(an.getDuration()) + " tps=" + f(an.getTicksPerSecond())
                + " channels=" + an.getNumChannels());
            for (AiNodeAnim ch : an.getChannels()) {
                double sp = 0, sr = 0, ss = 0, tt = 0;
                for (int i = 0; i < ch.getNumPosKeys(); i++) { sp += ch.getPosKeyX(i) + 2 * ch.getPosKeyY(i) + 3 * ch.getPosKeyZ(i); tt += ch.getPosKeyTime(i); }
                for (int i = 0; i < ch.getNumRotKeys(); i++) { sr += ch.getRotKeyW(i) + 2 * ch.getRotKeyX(i) + 3 * ch.getRotKeyY(i) + 4 * ch.getRotKeyZ(i); tt += ch.getRotKeyTime(i); }
                for (int i = 0; i < ch.getNumScaleKeys(); i++) { ss += ch.getScaleKeyX(i) + 2 * ch.getScaleKeyY(i) + 3 * ch.getScaleKeyZ(i); tt += ch.getScaleKeyTime(i); }
                p("  chan " + ch.getNodeName() + " pos=" + ch.getNumPosKeys() + " rot=" + ch.getNumRotKeys() + " scl=" + ch.getNumScaleKeys()
                    + " psum=" + f(sp) + " rsum=" + f(sr) + " ssum=" + f(ss) + " tsum=" + f(tt));
            }
        }
        dumpNode((AiNode) sc.getSceneRoot(Jassimp.getWrapperProvider()), 0);
    }

    static long caseNo = 0;

    static void run(String label, String path, EnumSet<AiPostProcessSteps> steps, StringBuilder times) {
        p("== " + label + " steps=" + steps);
        long t0 = System.nanoTime();
        try {
            AiScene sc = Jassimp.importFile(path, steps);
            dumpScene(sc);
        } catch (IOException e) {
            p("IOException: " + e.getMessage());
        } catch (Throwable e) {
            p("Throwable: " + e);
            failure = e;
        }
        times.append(label).append(' ').append((System.nanoTime() - t0) / 1000000L).append(" ms\n");
        caseNo++;
    }

    static Throwable failure;

    public static void main(String[] a) throws Exception {
        String game = System.getProperty("pz.game", "C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid").replace('\\', '/');
        File tmp = new File(System.getProperty("pz.tmp", System.getProperty("java.io.tmpdir")));
        tmp.mkdirs();
        out = new PrintWriter(new File(a[0]), "UTF-8");
        StringBuilder times = new StringBuilder();
        // como o jogo: o carregador do jogo escolhe "jassimp64" no Windows (o padrao do jassimp tentaria "jassimp")
        zombie.core.skinnedmodel.model.jassimp.JAssImpImporter.Init();
        run("static_book_x_first", game + "/media/models_X/Book.x", MESH, times);   // carrega a biblioteca
        // long: 4 no x64 Windows e 8 na dylib Apple (LP64) -- diferenca esperada, so se confere que e 4 ou 8.
        p("sizes v=" + Jassimp.NATIVE_AIVEKTORKEY_SIZE + " q=" + Jassimp.NATIVE_AIQUATKEY_SIZE + " v3=" + Jassimp.NATIVE_AIVEKTOR3D_SIZE
            + " float=" + Jassimp.NATIVE_FLOAT_SIZE + " int=" + Jassimp.NATIVE_INT_SIZE + " uint=" + Jassimp.NATIVE_UINT_SIZE
            + " double=" + Jassimp.NATIVE_DOUBLE_SIZE + " long_ok=" + (Jassimp.NATIVE_LONG_SIZE == 4 || Jassimp.NATIVE_LONG_SIZE == 8));
        String m = game + "/media/";
        run("static_book_x", m + "models_X/Book.x", MESH, times);
        run("static_partyhat_x", m + "models_X/Static/Clothes/F_PartyHat.x", MESH, times);
        run("static_bookhand_fbx", m + "models_X/BookHand.fbx", MESH, times);
        run("skinned_chick_x", m + "models_X/Skinned/Chicken_Chick.x", MESH, times);
        run("anim_chick_dead_x", m + "anims_X/Chicken/Chk_Chick_Dead.x", ANIM, times);
        run("static_book_x_again", m + "models_X/Book.x", MESH, times);

        // arquivo truncado: metade do .x esqueletico (excecao real do assimp)
        byte[] full = Files.readAllBytes(new File(m + "models_X/Skinned/Chicken_Chick.x").toPath());
        File trunc = new File(tmp, "jassimp_truncated.x");
        Files.write(trunc.toPath(), java.util.Arrays.copyOf(full, full.length / 2));
        run("truncated_x", trunc.getPath().replace('\\', '/'), MESH, times);
        // conteudo que nao e nenhum formato
        File junk = new File(tmp, "jassimp_junk.x");
        Files.write(junk.toPath(), "this is not a model\n".getBytes("UTF-8"));
        run("junk_x", junk.getPath().replace('\\', '/'), MESH, times);
        run("missing_file", m + "models_X/DoesNotExist.x", MESH, times);
        run("static_book_x_after_errors", m + "models_X/Book.x", MESH, times);
        p("getErrorString=" + Jassimp.getErrorString());
        p("done cases=" + caseNo);
        out.close();
        if (a.length > 1) Files.write(new File(a[1]).toPath(), times.toString().getBytes("UTF-8"));
        if (failure != null) { failure.printStackTrace(); System.exit(1); }
    }
}
