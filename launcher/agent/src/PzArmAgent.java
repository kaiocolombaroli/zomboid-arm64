import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.KHRDebug;
import zombie.characters.IsoPlayer;
import zombie.core.SceneShaderStore;
import zombie.core.math.PZMath;
import zombie.core.textures.IGLFramebufferObject;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/**
 * Agente de diagnostico do port ARM64 de Project Zomboid. Sem dependencias alem do proprio jogo/LWJGL.
 * Uso: -javaagent:pzarm-agent.jar=diag[,log=arquivo]
 * Modo diag: troca, dentro de zombie.core.textures.TextureFBO.initInternal(ITexture,ITexture,boolean), a chamada
 * IGLFramebufferObject.glCheckFramebufferStatus(I)I por PzArmAgent.check(funcs, target), que devolve o mesmo valor
 * e, se != GL_FRAMEBUFFER_COMPLETE, grava (uma vez por classe chamadora) o estado GL em fbo-debug.log.
 * Modo fps: -javaagent:pzarm-agent.jar=fps[,dir=pasta-de-logs] conta glfwSwapBuffers (ver FpsCounter); combina com diag (fps,diag).
 * Modo pz: ...,pz=PerformanceSettings.campo=valor (repetivel). Define o campo estatico logo ao fim do <clinit> de
 * zombie.core.PerformanceSettings (nada o reatribui depois: verificado no bytecode do 42.21). Lista de permissao: fboRenderChunk, useFbos
 * (ambos boolean). Cada valor aplicado e impresso em stderr: "[pzarm-agent] pz-set aplicado: ...".
 * Modo renderdiag: ...,renderdiag[,diagevery=N] le (sem alterar estado) o estado de textura/uniform antes de cada glDrawRangeElements do
 * SpriteRenderer$RingBuffer.drawElements enquanto o programa for tileWithDepth/opaqueWithDepth; grava <dir>/render-diag.log.
 * Modo renderfix: ...,renderfix=rebind|flush|uniforms|dirty|dirtyprog|flushtex|flushchunk|flushtexchunk contorna o bug de textura nesses mesmos desenhos (um modo por vez). Ver docs/RESULTS.md.
 *   flushtex: glFlush ANTES do desenho tile/opaque quando o id de texture0 ou de texture1 (null = 0) mudou em relacao ao desenho tile/opaque anterior.
 *   flushchunk: como flush (glFlush apos cada desenho tile/opaque), mas so enquanto um chunk e renderizado para o proprio FBO. O agente injeta
 *     chunkEnter() no fim de FBORenderChunk.beginRenderThread(Z)V e chunkExit() no inicio de FBORenderChunk.endRenderThread()V (exatamente 1 de cada, senao fatal()).
 *   flushtexchunk: regra do flushtex, valendo so dentro do chunk (chunkEnter zera a memoria do ultimo par de ids, entao o 1o desenho do chunk faz flush).
 *   O render-diag.log registra flush, flush/s e chunktile (desenhos tile/opaque dentro de chunk) tambem nos modos novos.
 * Modo collapse: ...,collapse=player (so vale o valor "player"; outro valor = fatal()). Corrige a casa que "abre de perto" com fboRenderChunk=true:
 *   FBORenderCutaways.CalculatePointsOfInterest tambem acrescenta como pontos de interesse os quadrados em volta do jogador
 *   (IsoCell.GetSquaresAroundPlayerSquare + isCouldSee) e CalculateBuildingsToCollapse chama IsoCell.GetBuildingsInFrontOfCharacter para cada
 *   ponto; os predios a frente desses quadrados vizinhos recolhiam paredes e telhado de uma casa que nao esconde o jogador. O agente troca,
 *   APENAS dentro de CalculateBuildingsToCollapse()Z, as DUAS chamadas (boolean false e true) a GetBuildingsInFrontOfCharacter por
 *   PzArmAgent.collapseNear(cell, lista, quadrado, boolean, ponto) (exatamente 2 pontos de troca, senao fatal()); o ponto e o local do laco
 *   (astore logo apos o checkcast PointOfInterest). collapseNear so chama o original para o ponto do proprio jogador (x/y/z iguais a
 *   floor(getX/getY/getZ) de IsoPlayer.players[playerIndex], a mesma conta de AddPointOfInterest) e para pontos mousePointer (o laco original ja
 *   pula esses antes da chamada; mantido por seguranca). Para os vizinhos faz o que o original faria sem predio: limpa a lista e zera
 *   IsoCell.occludedByOrphanStructureFlag, que o laco acumula com OR (nao fica defasado). O predio em que o jogador esta continua sendo
 *   tratado depois, pelo room id (playerWindowPeekingRoomId), fora das chamadas trocadas. CalculatePointsOfInterest e os demais usos dos
 *   pontos (corte de paredes) nao mudam. Custo por frame: 3 comparacoes de int por ponto.
 * Sem "diag", "fps", "pz", "renderdiag", "renderfix" nem "collapse" o agente nao faz nada.
 */
public final class PzArmAgent {
    static final String TARGET = "zombie/core/textures/TextureFBO";
    static final String IFACE = "zombie/core/textures/IGLFramebufferObject";
    static final String ME = "PzArmAgent";
    static volatile Path logPath;
    static final Set<String> seen = new HashSet<>();
    static boolean dbgPrepared;
    static final String PS_CLASS = "zombie/core/PerformanceSettings";
    /** Lista de permissao: campo -> descritor ("Z" boolean, "I" int). */
    static final Map<String, String> PZ_ALLOWED = Map.of("fboRenderChunk", "Z", "useFbos", "Z");
    static final Map<String, Object> pzSets = new LinkedHashMap<>();

    /** Interpreta "PerformanceSettings.campo=valor"; lanca IllegalArgumentException se fora da lista ou valor invalido. */
    static void parsePz(String spec) {
        int eq = spec.indexOf('=');
        String lhs = eq < 0 ? spec : spec.substring(0, eq), val = eq < 0 ? "" : spec.substring(eq + 1);
        if (!lhs.startsWith("PerformanceSettings.")) throw new IllegalArgumentException("pz: so PerformanceSettings.<campo>=<valor>: " + spec);
        String field = lhs.substring("PerformanceSettings.".length());
        String desc = PZ_ALLOWED.get(field);
        if (desc == null) throw new IllegalArgumentException("pz: campo fora da lista de permissao " + PZ_ALLOWED.keySet() + ": " + field);
        if (desc.equals("Z")) {
            if (val.equalsIgnoreCase("true")) pzSets.put(field, Boolean.TRUE);
            else if (val.equalsIgnoreCase("false")) pzSets.put(field, Boolean.FALSE);
            else throw new IllegalArgumentException("pz: " + field + " e boolean (true/false): " + val);
        } else pzSets.put(field, Integer.valueOf(val));
    }

    /** Chamado (do bytecode injetado) depois de cada putstatic do modo pz. */
    public static void applied(String s) { System.err.println("[pzarm-agent] pz-set aplicado: " + s); }

    static byte[] instrumentPs(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        ClassDesc ps = ClassDesc.ofInternalName(PS_CLASS);
        ClassDesc me = ClassDesc.of(ME);
        int[] done = {0};
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof ReturnInstruction) {
                for (Map.Entry<String, Object> en : pzSets.entrySet()) {
                    Object v = en.getValue();
                    boolean bool = v instanceof Boolean;
                    b.loadConstant(bool ? ((Boolean) v ? 1 : 0) : (Integer) v);
                    b.putstatic(ps, en.getKey(), bool ? ConstantDescs.CD_boolean : ConstantDescs.CD_int);
                    b.ldc("PerformanceSettings." + en.getKey() + "=" + v);
                    b.invokestatic(me, "applied", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"));
                }
                done[0]++;
            }
            b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(mm -> mm.methodName().equalsString("<clinit>"), ct));
        if (done[0] != 1) throw new IllegalStateException("esperava 1 return no <clinit> de PerformanceSettings, achei " + done[0]);
        return out;
    }

    public static void premain(String args, Instrumentation inst) {
        if (args == null) return;
        Path log = Path.of("logs", "fbo-debug.log");
        Path dir = Path.of("logs");
        boolean diag = false, fps = false;
        List<String> pz = new ArrayList<>();
        for (String a : args.split(",")) {
            if (a.equals("renderdiag")) renderDiag = true;
            else if (a.startsWith("diagevery=")) {
                try { diagEvery = Integer.parseInt(a.substring(10)); if (diagEvery < 1) throw new NumberFormatException(a); }
                catch (NumberFormatException e) { fatal("diagevery invalido (inteiro >= 1): " + a, e); }
            }
            else if (a.startsWith("renderfix=")) {
                renderFix = a.substring(10);
                if (!renderFix.equals("rebind") && !renderFix.equals("flush") && !renderFix.equals("uniforms")
                        && !renderFix.equals("dirty") && !renderFix.equals("dirtyprog") && !renderFix.equals("flushtex")
                        && !renderFix.equals("flushchunk") && !renderFix.equals("flushtexchunk"))
                    fatal("renderfix invalido (rebind|flush|uniforms|dirty|dirtyprog|flushtex|flushchunk|flushtexchunk): " + renderFix, new IllegalArgumentException(renderFix));
            }
            else if (a.startsWith("flushevery=")) {
                try { flushEvery = Integer.parseInt(a.substring(11)); if (flushEvery < 1) throw new NumberFormatException(a); }
                catch (NumberFormatException e) { fatal("flushevery invalido (inteiro >= 1): " + a, e); }
            }
            else if (a.startsWith("collapse=")) {
                if (!a.substring(9).equals("player")) fatal("collapse invalido (player): " + a, new IllegalArgumentException(a));
                collapse = true;
            }
            else if (a.equals("diag")) diag = true;
            else if (a.equals("fps")) fps = true;
            else if (a.startsWith("log=")) log = Path.of(a.substring(4));
            else if (a.startsWith("dir=")) dir = Path.of(a.substring(4));
            else if (a.startsWith("pz=")) pz.add(a.substring(3));
            else fatal("argumento desconhecido do agente: " + a, new IllegalArgumentException(a));
        }
        flushMode = renderFix.equals("flush") || renderFix.equals("flushtex") || renderFix.equals("flushchunk") || renderFix.equals("flushtexchunk");
        fxTex = renderFix.equals("flushtex") || renderFix.equals("flushtexchunk");
        fxChunk = renderFix.equals("flushchunk") || renderFix.equals("flushtexchunk");
        if (fps) FpsCounter.install(inst, dir);
        if (collapse) installCollapse(inst);
        if (renderDiag || !renderFix.isEmpty()) installRender(inst, dir);
        if (!pz.isEmpty()) {
            try { for (String p : pz) parsePz(p); }
            catch (RuntimeException e) { fatal("argumento pz invalido", e); }
            inst.addTransformer(new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                    if (!PS_CLASS.equals(name)) return null;
                    try { return instrumentPs(buf); }
                    catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
                }
            });
            System.err.println("[pzarm-agent] modo pz ativo: " + pzSets);
        }
        if (!diag) return;
        logPath = log.toAbsolutePath();
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, "# pzarm-agent diag " + LocalDateTime.now() + "\n");
        } catch (IOException e) { fatal("nao foi possivel criar o log " + logPath, e); }
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!TARGET.equals(name)) return null;
                try { return instrument(buf); }
                catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
            }
        });
        System.err.println("[pzarm-agent] modo diag ativo, log: " + logPath);
    }

    // ------------------------------------------------------------------ collapse=player
    static final String CUT_CLASS = "zombie/iso/fboRenderChunk/FBORenderCutaways";
    static final String POI_CLASS = "zombie/iso/fboRenderChunk/FBORenderCutaways$PointOfInterest";
    static boolean collapse;

    static void installCollapse(Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!CUT_CLASS.equals(name)) return null;
                try { return instrumentCutaways(buf); }
                catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
            }
        });
        System.err.println("[pzarm-agent] modo collapse ativo: collapse=player");
    }

    /** FBORenderCutaways.CalculateBuildingsToCollapse()Z: as 2 chamadas a IsoCell.GetBuildingsInFrontOfCharacter(ArrayList,IsoGridSquare,Z)V viram
     *  invokestatic PzArmAgent.collapseNear(IsoCell,ArrayList,IsoGridSquare,Z,PointOfInterest)V com o ponto do laco (local do astore apos checkcast). */
    static byte[] instrumentCutaways(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        int[] poiSlot = {-1};
        boolean[] afterCast = {false};
        ClassDesc me = ClassDesc.of(ME);
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof TypeCheckInstruction tc && tc.opcode() == Opcode.CHECKCAST && tc.type().asInternalName().equals(POI_CLASS)) afterCast[0] = true;
            else if (afterCast[0]) {
                afterCast[0] = false;
                if (e instanceof StoreInstruction st && st.opcode() == Opcode.ASTORE) {
                    if (poiSlot[0] >= 0 && poiSlot[0] != st.slot()) throw new IllegalStateException("PointOfInterest em mais de um local em CalculateBuildingsToCollapse");
                    poiSlot[0] = st.slot();
                }
            }
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKEVIRTUAL && ii.owner().asInternalName().equals("zombie/iso/IsoCell")
                    && ii.name().equalsString("GetBuildingsInFrontOfCharacter") && ii.type().equalsString("(Ljava/util/ArrayList;Lzombie/iso/IsoGridSquare;Z)V")) {
                if (poiSlot[0] < 0) throw new IllegalStateException("local do PointOfInterest nao encontrado antes de GetBuildingsInFrontOfCharacter");
                b.aload(poiSlot[0]);
                b.invokestatic(me, "collapseNear", MethodTypeDesc.ofDescriptor(
                        "(Lzombie/iso/IsoCell;Ljava/util/ArrayList;Lzombie/iso/IsoGridSquare;ZL" + POI_CLASS + ";)V"));
                n[0]++;
            } else b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("CalculateBuildingsToCollapse") && mm.methodType().equalsString("()Z"), ct));
        if (n[0] != 2) throw new IllegalStateException("esperava 2 chamadas a GetBuildingsInFrontOfCharacter em FBORenderCutaways.CalculateBuildingsToCollapse, achei " + n[0]);
        return out;
    }

    /** Decisao pura: o ponto e o do proprio jogador (mesma conta de AddPointOfInterest: floor de x/y/z) ou do mouse? */
    static boolean keepPoint(FBORenderCutaways.PointOfInterest poi, int px, int py, int pz) {
        return poi.mousePointer || (poi.x == px && poi.y == py && poi.z == pz);
    }

    /** Substitui IsoCell.GetBuildingsInFrontOfCharacter dentro de CalculateBuildingsToCollapse. Pontos vizinhos: resultado "sem predio" do original. */
    public static void collapseNear(IsoCell cell, ArrayList<IsoBuilding> out, IsoGridSquare sq, boolean b, FBORenderCutaways.PointOfInterest poi) {
        IsoPlayer p = IsoPlayer.players[IsoCamera.frameState.playerIndex];
        if (p == null || keepPoint(poi, PZMath.fastfloor(p.getX()), PZMath.fastfloor(p.getY()), PZMath.fastfloor(p.getZ()))) {
            cell.GetBuildingsInFrontOfCharacter(out, sq, b);
            return;
        }
        out.clear();
        cell.occludedByOrphanStructureFlag = false;
    }

    // ------------------------------------------------------------------ renderdiag / renderfix
    static final String RB_CLASS = "zombie/core/SpriteRenderer$RingBuffer";
    static final String FC_CLASS = "zombie/iso/fboRenderChunk/FBORenderChunk";
    static final String SR_CLASS = "zombie/core/SpriteRenderer$RingBuffer$StateRun";
    static final int GL_TEXTURE0 = 0x84C0, GL_TEXTURE1 = 0x84C1, GL_ACTIVE_TEXTURE = 0x84E0, GL_TEX_BIND_2D = 0x8069, GL_CURRENT_PROGRAM = 0x8B8D;
    static boolean renderDiag;
    static int diagEvery = 64;
    static String renderFix = "";
    static int flushEvery = 1;       // glFlush a cada N desenhos tile/opaque (modo flush)
    static boolean flushMode;        // qualquer modo de flush (loga flush/s)
    static boolean fxTex, fxChunk;   // fxTex: flush so na mudanca de texturas; fxChunk: so dentro de chunk
    static boolean inChunk;          // so a thread de render o usa (chunkEnter/chunkExit)
    static int lastT0 = -1, lastT1 = -1;
    static long nChunkTile, secChunkTile;
    static int flushCnt;             // so a thread de render o usa
    static BufferedWriter renderLog;
    static Field fBound, fDiffuse, fDepth;
    // estado do hook (so a thread de render o usa)
    static Texture inT0, inT1;
    static int pTile = -1, pOpaque = -1, locTileD = -1, locTileP = -1, locOpD = -1, locOpP = -1;
    static boolean testPrograms;
    static long nDraws, nTile, nOpaque, nSampled, nDiv, nRebind, nUniform, nFlush, nDirty, nErr;
    static long sec0, secDraws, secTile, secSampled, secDiv, secFlush;
    static final long[] divKind = new long[8];
    static final String[] KINDS = {"prog", "diffuseU", "depthU", "bind0", "bind1", "t0null", "t1null", "locNeg"};
    static int divLogged, okLogged;

    static void installRender(Instrumentation inst, Path dir) {
        try {
            Files.createDirectories(dir);
            renderLog = Files.newBufferedWriter(dir.toAbsolutePath().resolve("render-diag.log"), StandardCharsets.UTF_8);
            rlog("# pzarm-agent renderdiag " + LocalDateTime.now() + " renderdiag=" + renderDiag + " diagevery=" + diagEvery
                    + " renderfix=" + (renderFix.isEmpty() ? "(nenhum)" : renderFix) + " flushevery=" + flushEvery);
            rlog("# linha por segundo: t draws tile amostras div | totais e contagem por tipo. Divergencias: ate 200 linhas DIV; OK = primeiras 8 amostras sem divergencia");
        } catch (IOException e) { fatal("nao foi possivel criar render-diag.log em " + dir, e); }
        try {
            fBound = Class.forName("zombie.core.ShaderHelper").getDeclaredField("currentlyBound");
            fBound.setAccessible(true);
            fDiffuse = Class.forName("zombie.tileDepth.TileDepthShader").getDeclaredField("diffuse");
            fDiffuse.setAccessible(true);
            fDepth = fDiffuse.getDeclaringClass().getDeclaredField("depth");
            fDepth.setAccessible(true);
        } catch (Throwable t) { fatal("campos do jogo para renderdiag/renderfix nao encontrados (ShaderHelper.currentlyBound, TileDepthShader.diffuse/depth)", t); }
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                try {
                    if (RB_CLASS.equals(name)) return instrumentRingBuffer(buf);
                    if (SR_CLASS.equals(name)) return instrumentStateRun(buf);
                    if (fxChunk && FC_CLASS.equals(name)) return instrumentChunk(buf);
                } catch (Throwable t) { fatal("falha ao transformar " + name, t); }
                return null;
            }
        });
        System.err.println("[pzarm-agent] modo render ativo: renderdiag=" + renderDiag + " diagevery=" + diagEvery + " renderfix="
                + (renderFix.isEmpty() ? "(nenhum)" : renderFix) + " flushevery=" + flushEvery);
    }

    /** RingBuffer.drawElements(IIII)V: troca GL12.glDrawRangeElements por PzArmAgent.draw (mesma assinatura). */
    static byte[] instrumentRingBuffer(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        ClassDesc me = ClassDesc.of(ME);
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKESTATIC && ii.owner().asInternalName().equals("org/lwjgl/opengl/GL12")
                    && ii.name().equalsString("glDrawRangeElements") && ii.type().equalsString("(IIIIIJ)V")) {
                b.invokestatic(me, "draw", MethodTypeDesc.ofDescriptor("(IIIIIJ)V"));
                n[0]++;
            } else b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("drawElements") && mm.methodType().equalsString("(IIII)V"), ct));
        if (n[0] != 1) throw new IllegalStateException("esperava 1 chamada a glDrawRangeElements em RingBuffer.drawElements, achei " + n[0]);
        return out;
    }

    /** StateRun.render()V: antes de RingBuffer.drawElements(IIII)V insere PzArmAgent.intent(texture0, texture1) (nao mexe na pilha). */
    static byte[] instrumentStateRun(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        ClassDesc me = ClassDesc.of(ME), sr = ClassDesc.ofInternalName(SR_CLASS), tex = ClassDesc.ofInternalName("zombie/core/textures/Texture");
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKEVIRTUAL && ii.owner().asInternalName().equals(RB_CLASS)
                    && ii.name().equalsString("drawElements") && ii.type().equalsString("(IIII)V")) {
                b.aload(0).getfield(sr, "texture0", tex).aload(0).getfield(sr, "texture1", tex);
                b.invokestatic(me, "intent", MethodTypeDesc.ofDescriptor("(Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;)V"));
                n[0]++;
            }
            b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("render") && mm.methodType().equalsString("()V"), ct));
        if (n[0] != 1) throw new IllegalStateException("esperava 1 chamada a drawElements em StateRun.render, achei " + n[0]);
        return out;
    }

    /** FBORenderChunk: chunkEnter() antes do unico return de beginRenderThread(Z)V (FBO do chunk ja ligado) e chunkExit() antes do primeiro
     *  elemento de endRenderThread()V (antes de endDrawing). O manager so chama end quando begin foi chamado (renderThreadCurrent != null). */
    static byte[] instrumentChunk(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] enter = {0}, exit = {0};
        ClassDesc me = ClassDesc.of(ME);
        MethodTypeDesc v = MethodTypeDesc.ofDescriptor("()V");
        CodeTransform ctEnter = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof ReturnInstruction) { b.invokestatic(me, "chunkEnter", v); enter[0]++; }
            b.with(e);
        };
        boolean[] first = {true};
        CodeTransform ctExit = (CodeBuilder b, CodeElement e) -> {
            if (first[0]) { first[0] = false; b.invokestatic(me, "chunkExit", v); exit[0]++; }
            b.with(e);
        };
        ClassModel cm = cf.parse(bytes);
        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("beginRenderThread") && mm.methodType().equalsString("(Z)V"), ctEnter));
        out = cf.transformClass(cf.parse(out), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("endRenderThread") && mm.methodType().equalsString("()V"), ctExit));
        if (enter[0] != 1) throw new IllegalStateException("esperava 1 return em FBORenderChunk.beginRenderThread(Z)V, achei " + enter[0]);
        if (exit[0] != 1) throw new IllegalStateException("esperava 1 metodo FBORenderChunk.endRenderThread()V, achei " + exit[0]);
        return out;
    }

    /** Chamado (bytecode injetado) quando a thread de render comeca a desenhar um chunk no proprio FBO. */
    public static void chunkEnter() { inChunk = true; lastT0 = lastT1 = -1; }

    /** Chamado (bytecode injetado) quando a thread de render termina o chunk. */
    public static void chunkExit() { inChunk = false; }

    static synchronized void rlog(String s) {
        try { renderLog.write(s); renderLog.newLine(); renderLog.flush(); } catch (Throwable ignored) { }
    }

    /** Chamado (do bytecode injetado em StateRun.render) logo antes do desenho: as texturas que o jogo pretende usar nas unidades 0 e 1. */
    public static void intent(Texture t0, Texture t1) { inT0 = t0; inT1 = t1; }

    /** Para teste (harness): define o programa "tile" e as localizacoes dos uniforms sem depender do SceneShaderStore. */
    public static void testPrograms(int tileProg, int locDiffuse, int locDepth) {
        pTile = tileProg; locTileD = locDiffuse; locTileP = locDepth; pOpaque = -1; testPrograms = true;
    }

    /** {draws, tile+opaque, amostras, divergencias, rebinds, uniformes reenviados, flushes, dirty, desenhos tile dentro de chunk}. */
    public static long[] renderStats() { return new long[] {nDraws, nTile + nOpaque, nSampled, nDiv, nRebind, nUniform, nFlush, nDirty, nChunkTile}; }

    static void refreshPrograms() {
        if (testPrograms) return;
        try {
            var t = SceneShaderStore.tileDepthShader;
            if (t != null && t.isCompiled()) { pTile = t.getID(); locTileD = fDiffuse.getInt(t); locTileP = fDepth.getInt(t); }
            var o = SceneShaderStore.opaqueDepthShader;
            if (o != null && o.isCompiled()) { pOpaque = o.getID(); locOpD = fDiffuse.getInt(o); locOpP = fDepth.getInt(o); }
        } catch (Throwable ignored) { }
    }

    static int gameBound() {
        try { return fBound.getInt(null); } catch (Throwable t) { return GL11.glGetInteger(GL_CURRENT_PROGRAM); }
    }

    static boolean isTile(int p) { return p > 0 && (p == pTile || p == pOpaque); }

    /** No lugar de GL12.glDrawRangeElements (chamado de RingBuffer.drawElements). Le/ajusta estado so para tileWithDepth/opaqueWithDepth. */
    public static void draw(int mode, int start, int end, int count, int type, long offset) {
        boolean tile = false, flushAfter = false;
        try {
            nDraws++; secDraws++;
            if ((nDraws & 255) == 0 || sec0 == 0) tick();
            int cur = gameBound();
            tile = isTile(cur);
            if (tile) { if (cur == pTile) nTile++; else nOpaque++; secTile++; }
            if (renderDiag && nDraws % diagEvery == 0) sample(cur);
            if (tile) {
                boolean in = inChunk;
                if (in) { nChunkTile++; secChunkTile++; }
                if (fxTex) {
                    Texture a = inT0, b = inT1;
                    int i0 = a == null ? 0 : a.getID(), i1 = b == null ? 0 : b.getID();
                    boolean changed = i0 != lastT0 || i1 != lastT1;
                    lastT0 = i0; lastT1 = i1;
                    if (changed && (!fxChunk || in)) { GL11.glFlush(); nFlush++; secFlush++; }
                } else if (fxChunk) flushAfter = in;
                if (renderFix.equals("rebind")) fixRebind();
                else if (renderFix.equals("uniforms")) fixUniforms(cur);
                else if (renderFix.equals("dirty")) fixDirty(0);
                else if (renderFix.equals("dirtyprog")) fixDirty(cur);
            }
        } catch (Throwable t) { if (nErr++ < 3) rlog("ERRO no hook draw: " + t); }
        GL12.glDrawRangeElements(mode, start, end, count, type, offset);
        if (flushAfter) { GL11.glFlush(); nFlush++; secFlush++; }
        else if (flushMode && tile && !fxTex && !fxChunk && ++flushCnt >= flushEvery) { flushCnt = 0; GL11.glFlush(); nFlush++; secFlush++; }
    }

    static void tick() {
        long now = System.nanoTime();
        if (sec0 == 0) { sec0 = now; refreshPrograms(); return; }
        if (now - sec0 < 1_000_000_000L) return;
        refreshPrograms();
        if ((renderDiag || flushMode) && secDraws > 0) {
            StringBuilder sb = new StringBuilder("t=").append(LocalDateTime.now().toLocalTime().withNano(0)).append(" draws=").append(secDraws)
                    .append(" tile=").append(secTile).append(" amostras=").append(secSampled).append(" div=").append(secDiv).append(" | total draws=")
                    .append(nDraws).append(" tile=").append(nTile).append(" opaque=").append(nOpaque).append(" amostras=").append(nSampled)
                    .append(" div=").append(nDiv).append(" |");
            for (int i = 0; i < KINDS.length; i++) if (divKind[i] > 0) sb.append(' ').append(KINDS[i]).append('=').append(divKind[i]);
            if (!renderFix.isEmpty()) sb.append(" | fix ").append(renderFix).append(": rebind=").append(nRebind).append(" uniforms=").append(nUniform).append(" dirty=").append(nDirty).append(" flush=").append(nFlush).append(" flush/s=").append(secFlush).append(" flushevery=").append(flushEvery).append(" chunktile=").append(nChunkTile).append(" chunktile/s=").append(secChunkTile);
            rlog(sb.toString());
        }
        sec0 = now; secDraws = secTile = secSampled = secDiv = secFlush = secChunkTile = 0;
    }

    /** Textura que o proprio StateRun.render ligaria direto pelo id (pronta, valida, com id GL). */
    static boolean usable(Texture t) {
        return t != null && t.getID() > 0 && !t.isDestroyed() && t.isValid() && t.isReady();
    }

    static void fixRebind() {
        Texture a = inT0, b = inT1;
        if (usable(a)) { GL13.glActiveTexture(GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.getID()); Texture.lastTextureID = a.getID(); }
        if (usable(b)) { GL13.glActiveTexture(GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, b.getID()); GL13.glActiveTexture(GL_TEXTURE0); }
        nRebind++;
    }

    /** Suja o estado de textura: bind 0 e depois bind id nas unidades 0/1 (e, com prog>0, glUseProgram(0) e de volta ao programa atual). Sem glFlush.
     *  Uniforms sao estado do programa e sobrevivem a glUseProgram(0), por isso nao sao reenviados. */
    static void fixDirty(int prog) {
        Texture a = inT0, b = inT1;
        int act = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
        if (usable(a)) { GL13.glActiveTexture(GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.getID()); Texture.lastTextureID = a.getID(); }
        if (usable(b)) { GL13.glActiveTexture(GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, b.getID()); }
        GL13.glActiveTexture(act);
        if (prog > 0) { GL20.glUseProgram(0); GL20.glUseProgram(prog); }
        nDirty++;
    }

    static void fixUniforms(int prog) {
        int d = prog == pTile ? locTileD : locOpD, p = prog == pTile ? locTileP : locOpP;
        if (d >= 0) GL20.glUniform1i(d, 0);
        if (p >= 0) GL20.glUniform1i(p, 1);
        nUniform++;
    }

    static String tdesc(Texture t) {
        if (t == null) return "null";
        String n; try { n = t.getName(); } catch (Throwable e) { n = "?"; }
        return "id=" + t.getID() + " nome=" + n + " " + t.getWidth() + "x" + t.getHeight() + (t.isReady() ? "" : " NAO-PRONTA") + (t.isDestroyed() ? " DESTRUIDA" : "");
    }

    /** Leituras sem alterar estado (GL_ACTIVE_TEXTURE e restaurado). Caro com glthread (glGet* sincroniza): so a cada diagevery desenhos. */
    static void sample(int cached) {
        int real = GL11.glGetInteger(GL_CURRENT_PROGRAM);
        boolean tReal = isTile(real);
        if (!tReal && !isTile(cached)) return;
        nSampled++; secSampled++;
        int prog = tReal ? real : cached;
        boolean isTileProg = prog == pTile;
        int ld = isTileProg ? locTileD : locOpD, lp = isTileProg ? locTileP : locOpP;
        int dU = ld >= 0 ? GL20.glGetUniformi(prog, ld) : -99, pU = lp >= 0 ? GL20.glGetUniformi(prog, lp) : -99;
        int act = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL_TEXTURE0); int b0 = GL11.glGetInteger(GL_TEX_BIND_2D);
        GL13.glActiveTexture(GL_TEXTURE1); int b1 = GL11.glGetInteger(GL_TEX_BIND_2D);
        GL13.glActiveTexture(act);
        Texture t0 = inT0, t1 = inT1;
        int mask = 0;
        if (real != cached) mask |= 1;
        if (dU != 0) mask |= 2;
        if (pU != 1) mask |= 4;
        if (usable(t0) && b0 != t0.getID()) mask |= 8;
        if (usable(t1) && b1 != t1.getID()) mask |= 16;
        if (t0 == null) mask |= 32;
        if (t1 == null) mask |= 64;
        if (ld < 0 || lp < 0) mask |= 128;
        String line = "prog real=" + real + " jogo=" + cached + (isTileProg ? " (tileWithDepth)" : " (opaqueWithDepth)") + " DIFFUSE=" + dU + " DEPTH=" + pU
                + " ativa=" + h(act) + " bind0=" + b0 + " bind1=" + b1 + " | pretendido0 {" + tdesc(t0) + "} pretendido1 {" + tdesc(t1) + "}"
                + " lastTextureID=" + Texture.lastTextureID + " thread=" + Thread.currentThread().getName();
        if (mask != 0) {
            nDiv++; secDiv++;
            for (int i = 0; i < 8; i++) if ((mask & (1 << i)) != 0) divKind[i]++;
            if (divLogged++ < 200) rlog("DIV#" + divLogged + " n=" + nDraws + " tipos=" + Integer.toBinaryString(mask) + " " + line);
        } else if (okLogged++ < 8) rlog("OK n=" + nDraws + " " + line);
    }

    static void fatal(String msg, Throwable t) {
        System.err.println("[pzarm-agent] ERRO FATAL: " + msg + ": " + t);
        t.printStackTrace();
        System.err.flush();
        Runtime.getRuntime().halt(5);
    }

    static byte[] instrument(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(bytes);
        int[] replaced = {0};
        ClassDesc me = ClassDesc.of(ME);
        MethodTypeDesc hookDesc = MethodTypeDesc.ofDescriptor("(L" + IFACE + ";I)I");
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKEINTERFACE
                    && ii.owner().asInternalName().equals(IFACE)
                    && ii.name().equalsString("glCheckFramebufferStatus")
                    && ii.type().equalsString("(I)I")) {
                b.invokestatic(me, "check", hookDesc);
                replaced[0]++;
            } else b.with(e);
        };
        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("initInternal")
                        && mm.methodType().equalsString("(Lzombie/interfaces/ITexture;Lzombie/interfaces/ITexture;Z)V"), ct));
        if (replaced[0] != 1) throw new IllegalStateException("esperava 1 chamada a glCheckFramebufferStatus em initInternal, achei " + replaced[0]);
        return out;
    }

    // ------------------------------------------------------------------ hook
    /** Chamado no lugar de funcs.glCheckFramebufferStatus(target). Nunca lanca por conta propria; devolve o status original. */
    public static int check(IGLFramebufferObject funcs, int target) {
        try { prepareDebug(); } catch (Throwable ignored) { }
        int status = funcs.glCheckFramebufferStatus(target);
        if (status != 0x8CD5) {
            try { report(funcs, target, status); } catch (Throwable t) { log("falha no relatorio: " + t); }
        }
        return status;
    }

    static void prepareDebug() {
        if (dbgPrepared) return;
        dbgPrepared = true;
        var caps = GL.getCapabilities();
        if ((caps.OpenGL43 || caps.GL_KHR_debug) && !GL11.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT)) {
            GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
            log("(agente habilitou GL_DEBUG_OUTPUT para poder ler glGetDebugMessageLog)");
        }
    }

    static synchronized void log(String s) {
        try { Files.writeString(logPath, s + "\n", StandardOpenOption.APPEND); } catch (IOException ignored) { }
    }

    static String sname(int s) {
        switch (s) {
            case 0x8CD5: return "COMPLETE"; case 0x8219: return "UNDEFINED"; case 0x8CD6: return "INCOMPLETE_ATTACHMENT";
            case 0x8CD7: return "INCOMPLETE_MISSING_ATTACHMENT"; case 0x8CD9: return "INCOMPLETE_DIMENSIONS";
            case 0x8CDA: return "INCOMPLETE_FORMATS"; case 0x8CDB: return "INCOMPLETE_DRAW_BUFFER";
            case 0x8CDC: return "INCOMPLETE_READ_BUFFER"; case 0x8CDD: return "UNSUPPORTED";
            case 0x8D56: return "INCOMPLETE_MULTISAMPLE"; default: return "0x" + Integer.toHexString(s);
        }
    }

    static String h(int v) { return "0x" + Integer.toHexString(v).toUpperCase(); }

    static IntBuffer ib(int n) { return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer(); }

    static void report(IGLFramebufferObject funcs, int target, int status) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        String caller = "?";
        StringBuilder stack = new StringBuilder();
        int n = 0;
        for (StackTraceElement f : st) {
            String cn = f.getClassName();
            if (cn.equals("java.lang.Thread") || cn.equals(ME)) continue;
            if (caller.equals("?") && !cn.equals("zombie.core.textures.TextureFBO") && !cn.startsWith("zombie.util.lambda.")
                    && !cn.equals("zombie.util.Lambda") && !cn.startsWith("zombie.core.opengl.Render")
                    && !cn.startsWith("java.") && !cn.startsWith("jdk.")) caller = cn;
            if (n++ < 24) stack.append("    at ").append(f).append('\n');
        }
        synchronized (seen) { if (!seen.add(caller)) return; }
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== FBO incompleto ").append(LocalDateTime.now()).append(" thread=").append(Thread.currentThread().getName()).append(" ===\n");
        sb.append("status=").append(sname(status)).append(" (").append(h(status)).append(") classe chamadora=").append(caller).append('\n');
        sb.append("pilha:\n").append(stack);
        sb.append("glGetError=").append(h(GL11.glGetError())).append('\n');
        sb.append("GL_VENDOR/RENDERER/VERSION=").append(GL11.glGetString(GL11.GL_VENDOR)).append(" / ")
          .append(GL11.glGetString(GL11.GL_RENDERER)).append(" / ").append(GL11.glGetString(GL11.GL_VERSION)).append('\n');
        sb.append("estado GL:\n");
        sb.append("  GL_FRAMEBUFFER_BINDING=").append(GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)).append('\n');
        sb.append("  GL_DRAW_BUFFER=").append(h(GL11.glGetInteger(GL11.GL_DRAW_BUFFER))).append('\n');
        sb.append("  GL_READ_BUFFER=").append(h(GL11.glGetInteger(GL11.GL_READ_BUFFER))).append('\n');
        sb.append("  GL_RENDERBUFFER_BINDING=").append(GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING)).append('\n');
        sb.append("  GL_ACTIVE_TEXTURE=").append(h(GL11.glGetInteger(0x84E0))).append('\n');
        sb.append("  GL_TEXTURE_BINDING_2D=").append(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)).append('\n');
        sb.append("  GL_PIXEL_UNPACK_BUFFER_BINDING=").append(GL11.glGetInteger(0x88EF)).append('\n');
        sb.append("  GL_FRAMEBUFFER_SRGB=").append(GL11.glIsEnabled(0x8DB9)).append('\n');
        sb.append("  GL_SAMPLES=").append(GL11.glGetInteger(0x80A9)).append(" GL_SAMPLE_BUFFERS=").append(GL11.glGetInteger(0x80A8)).append('\n');
        ByteBuffer mask = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
        sb.append("  GL_COLOR_WRITEMASK=").append(mask.get(0)).append(',').append(mask.get(1)).append(',').append(mask.get(2)).append(',').append(mask.get(3)).append('\n');
        sb.append("anexos do FBO ligado:\n");
        attachment(sb, "COLOR_ATTACHMENT0", target, 0x8CE0);
        attachment(sb, "DEPTH_ATTACHMENT", target, 0x8D00);
        attachment(sb, "STENCIL_ATTACHMENT", target, 0x8D20);
        int s2 = funcs.glCheckFramebufferStatus(target);
        GL11.glFinish();
        int s3 = funcs.glCheckFramebufferStatus(target);
        sb.append("reexecucao: logo depois=").append(sname(s2)).append(", depois de glFinish=").append(sname(s3)).append('\n');
        debugLog(sb);
        sb.append("(motivo exato do Mesa: stderr do jogo com MESA_DEBUG=incomplete_fbo, linha 'FBO incomplete: ...')\n");
        log(sb.toString());
    }

    static void attachment(StringBuilder sb, String nome, int target, int att) {
        int type = GL30.glGetFramebufferAttachmentParameteri(target, att, 0x8CD0);
        int name = type == 0 ? 0 : GL30.glGetFramebufferAttachmentParameteri(target, att, 0x8CD1);
        sb.append("  ").append(nome).append(": tipo=").append(type == 0 ? "NONE" : type == 0x1702 ? "TEXTURE" : type == 0x8D41 ? "RENDERBUFFER" : h(type))
          .append(" nome=").append(name);
        if (type == 0x1702) {
            int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            sb.append(" w=").append(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, 0x1000))
              .append(" h=").append(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, 0x1001))
              .append(" GL_TEXTURE_INTERNAL_FORMAT=").append(h(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, 0x1003)))
              .append(" nivel=").append(GL30.glGetFramebufferAttachmentParameteri(target, att, 0x8CD2));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        } else if (type == 0x8D41) {
            int prev = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
            sb.append(" w=").append(GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, 0x8D42))
              .append(" h=").append(GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, 0x8D43))
              .append(" internal=").append(h(GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, 0x8D44)))
              .append(" samples=").append(GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, 0x8CAB));
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prev);
        }
        sb.append('\n');
    }

    static void debugLog(StringBuilder sb) {
        var caps = GL.getCapabilities();
        if (!(caps.OpenGL43 || caps.GL_KHR_debug)) { sb.append("glGetDebugMessageLog: indisponivel (sem KHR_debug)\n"); return; }
        sb.append("GL_DEBUG_OUTPUT=").append(GL11.glIsEnabled(KHRDebug.GL_DEBUG_OUTPUT)).append(" (se o jogo instalou callback, ele consome as mensagens)\n");
        int max = 64;
        IntBuffer src = ib(max), typ = ib(max), id = ib(max), sev = ib(max), len = ib(max);
        ByteBuffer msg = ByteBuffer.allocateDirect(64 * 1024);
        int got = KHRDebug.glGetDebugMessageLog(max, src, typ, id, sev, len, msg);
        sb.append("glGetDebugMessageLog: ").append(got).append(" mensagem(ns)\n");
        int off = 0;
        for (int i = 0; i < got; i++) {
            byte[] b = new byte[Math.max(0, len.get(i) - 1)];
            msg.position(off); msg.get(b); off += len.get(i);
            sb.append("  [src=").append(h(src.get(i))).append(" type=").append(h(typ.get(i))).append(" id=").append(id.get(i))
              .append(" sev=").append(h(sev.get(i))).append("] ").append(new String(b).strip()).append('\n');
        }
    }
}
