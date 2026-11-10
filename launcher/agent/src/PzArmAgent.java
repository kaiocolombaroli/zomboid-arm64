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
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.time.format.DateTimeFormatter;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.ARBMapBufferRange;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
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
 * Modo vbosync: ...,vbosync=sync|invalidate (outro valor = fatal()). Hipotese (em teste, nao validada em jogo): zombie.core.VBO.GLVertexBufferObject
 *   mapeia os buffers com glMapBufferRange(alvo, 0, tamanho, 0x26) = WRITE(0x2)|INVALIDATE_RANGE(0x4)|UNSYNCHRONIZED(0x20); o anel do SpriteRenderer
 *   orfana com glBufferData(NULL) e mapeia sem sincronizar, e map(int) pula o orfanamento se o tamanho nao mudou. No Mesa D3D12 a CPU
 *   reescreve vertices que a GPU ainda nao consumiu (sprites com textura/UV errados, piscando). O agente troca, em GLVertexBufferObject,
 *   as 2 chamadas a GL30.glMapBufferRange e as 2 a ARBMapBufferRange.glMapBufferRange (exatamente 2+2, senao fatal()) por
 *   PzArmAgent.mapRange30/mapRangeArb (mesma assinatura), que chamam o original com o acesso ajustado por vboAccess:
 *   sync = remove UNSYNCHRONIZED (0x20); invalidate = como sync e, com offset 0, troca INVALIDATE_RANGE (0x4) por INVALIDATE_BUFFER (0x8).
 *   Na 1a chamada imprime em stderr "[pzarm-agent] vbosync=<modo>: glMapBufferRange access 0x26 -> 0x6".
 * Modo uidiag: ...,uidiag (diagnostico, sem alterar estado) descobre se o preview do personagem no menu some porque o jogo PULA o desenho em alguns
 *   quadros. Instrumenta zombie.ui.UI3DModel$Drawer: render()V chama uiDrawRender() no inicio; postRender()V chama uiDrawPost(rendered) no inicio;
 *   init(II)V chama uiRenderMainRet(int) logo depois de AnimatedModel.renderMain()I (dup do retorno antes do pop) e uiDrawInit(rendered) antes do
 *   return (metodos ou campo ausentes = fatal()). Uma vez por segundo grava <dir>/uidiag.log:
 *   "t=HH:mm:ss init=N initRendered=N initNotRendered=N render=N postTrue=N postFalse=N renderMainRet={v:n,...}[ swaps=N]" (contagens do ultimo segundo;
 *   swaps = glfwSwapBuffers no segundo, so se o modo fps estiver ativo).
 * Modo uimodeldiag: ...,uimodeldiag (diagnostico) troca em UI3DModel$Drawer.render()V a chamada AnimatedModel.DoRender(IIIIFF)V por
 *   PzArmAgent.uiDoRender(AnimatedModel,IIIIFF)V (exatamente 1 troca, senao fatal()). Os 4 ints sao x, y, largura, altura do boneco ja em coordenadas
 *   GL (render() passa absX, Core.height - absY - altura, largura, altura; y para cima) e viram o glViewport de UIModelCamera.Begin; DoRender comeca com
 *   glClear(GL_DEPTH_BUFFER_BIT). uiDoRender captura o estado GL (framebuffers, viewport, scissor, stencil, depth, blend, cull, alpha test, programa,
 *   bits de depth), le por glReadPixels uma grade de ate 64x64 pontos do retangulo antes e depois do DoRender original (chamado exatamente 1 vez) e
 *   conta pixels que mudaram. Grava <dir>/uimodel.log (limite ~20 MB, depois so linhas SEC):
 *   "SIG id=N drawfb=.. readfb=.. readbuf=.. vp=x,y,w,h scissor=on|off box=.. stencil=.. depthTest=.. depthFunc=.. depthMask=.. depthRange=.. colorMask=..
 *   blend=.. cull=.. alphaTest=.. prog=.. depthBits=..[ glerr=..]" (estado novo), "f=N t=HH:mm:ss.SSS mx=.. my=.. sig=N changed=K of=M rect=x,y,w,h" (por
 *   quadro; mx/my = zombie.input.Mouse.getX/getY) e "SEC t=.. frames=.. mouseMoved=.. fail=.. sigs={id:n}" (por segundo; fail = quadros com changed
 *   abaixo de 20% do maior changed visto).
 * Modo glsync: ...,glsync=swapfinish|swapflush|swapfence|modelfinish|modelflush (repetivel; outro valor = fatal()). Contorno para um hazard de sincronizacao CPU-GPU no
 *   Mesa GLon12 (o boneco do menu parou de sumir quando o uimodeldiag forcou glReadPixels). swapfinish/swapflush: glFinish()/glFlush() imediatamente antes de
 *   cada glfwSwapBuffers de org.lwjglx.opengl.Display (a chamada vira PzArmAgent.swapSync, que depois chama FpsCounter.swap se o modo fps estiver ativo, senao
 *   GLFW.glfwSwapBuffers; pelo menos 1 troca, senao fatal()). modelfinish/modelflush: glFinish()/glFlush() logo DEPOIS de AnimatedModel.DoRender em
 *   UI3DModel$Drawer.render (depois de uiDoRender, se o uimodeldiag estiver ativo; exatamente 1 ponto, senao fatal()). Uma vez por segundo grava
 *   <dir>/glsync.log: "t=HH:mm:ss swapFinish=N swapFlush=N modelFinish=N modelFlush=N" (contagens do ultimo segundo; com upload/clear/other ativos acrescenta upFinish upFlush clearFinish clearFlush calls skipped otherFlush otherFinish others; com swapfence acrescenta no fim swapFence=N fenceWaitMs=X) e imprime
 *   "[pzarm-agent] modo glsync ativo: ..." em stderr.
 *   swapfence (padrao do launcher, com uploadflush,otherflush e vbosync=sync): em vez de glFinish, cada troca de buffers cria um fence (glFenceSync) e espera
 *   (glClientWaitSync) o fence do quadro anterior, atrasado de 1 quadro; CPU e GPU seguem em paralelo. fenceWaitMs = tempo gasto nessa espera no ultimo segundo.
 *   Valores extras (combinaveis): uploadfinish/uploadflush = glFinish/glFlush ANTES de cada upload/copia de textura ou buffer (glTexSubImage2D/3D, glTexImage2D/3D,
 *   glCompressedTex*Image2D, glCopyTex*Image2D, glBufferSubData, glBufferData, glMapBuffer, glMapBufferRange, glUnmapBuffer, glGenerateMipmap(EXT),
 *   glBlitFramebuffer(EXT) e os wrappers zombie/core/VBO/IGLBufferObject.glBufferData/glBufferSubData/glMapBuffer/glUnmapBuffer); clearfinish/clearflush = o mesmo
 *   antes de glClear (contado a parte); uploadcount = so conta. Um transformer insere invokestatic PzArmAgent.preUpload(id) antes de cada chamada, em toda
 *   classe zombie/, org/lwjglx/ e se/krka/ (filtro rapido por bytes; falha numa classe = aviso em stderr e a classe fica sem alteracao, nunca fatal). preUpload so age
 *   na thread de render capturada no 1o swap (antes disso nao faz nada). stderr: "[pzarm-agent] uploadsync: classes=N pontos={nome:n}" a cada 1000 classes
 *   transformadas e no 1o swap. glsync.log ganha: upFinish=N upFlush=N clearFinish=N clearFlush=N calls={nome:n,...} (so ids nao zero, por segundo).
 * Modo stutterdiag: ...,stutterdiag[,stutterms=N] (N padrao 45) mede por quadro (de um swapSync ao proximo, na thread de render) o tempo total (frameMs), o tempo
 *   gasto nos glFinish/glFlush do swapSync (finishMs) e do preUpload (uploadSyncMs), o numero de preUpload (uploads), as chamadas a
 *   FBORenderChunk.beginRenderThread (chunks) e o tempo somado ate endRenderThread (chunkMs; chunkEnter/chunkExit sao instalados mesmo sem renderfix),
 *   GC desde o quadro anterior (gcCount/gcMs: soma de ManagementFactory.getGarbageCollectorMXBeans, 1 leitura por quadro) e mainWaitMs
 *   (RenderThread.getWaitTime(): o proprio jogo mede quanto a thread de render esperou o estado da thread principal em acquireStateForRendering). Implica o
 *   transformer de upload em modo uploadcount (so conta) e o hook do swap. Grava <dir>/stutter.log so com os quadros com frameMs >= stutterms:
 *   "t=HH:mm:ss.SSS frameMs=.. finishMs=.. uploadSyncMs=.. uploads=N chunks=N chunkMs=.. gcCount=N gcMs=N mainWaitMs=.." e a cada 10 s
 *   "SUM t=.. frames=.. slow=.. avgSlowMs=.. slowWithChunks=.. slowWithGc=.. slowFinishDominant=.. slowMainWaitDominant=.." (dominante = mais de 50% do frameMs).
 *   Flush sem redundancia: uploadflush/uploadfinish so sincronizam se a thread de render fez trabalho de GPU desde o ultimo flush/finish/swap (PzArmAgent.gpuWork()V
 *   inserido antes de glDrawArrays/Elements/RangeElements/ArraysInstanced/ElementsInstanced, glMultiDraw*, glClear, glBlitFramebuffer, glCopyTexSubImage2D,
 *   glCopyTexImage2D e glBegin; o hook draw() do renderfix tambem marca); uploadflushall/uploadfinishall = comportamento antigo (sempre). otherflush/otherfinish =
 *   glFlush/glFinish DEPOIS de cada upload da lista (PzArmAgent.postUpload(I)V) em threads que nao sao a de render e tem contexto GL atual. O glsync.log ganha
 *   skipped=N otherFlush=N otherFinish=N others={thread:n(idMaisFrequente),...} (preUpload por thread que nao e a de render).
 * Tamanho do framebuffer: a cada swap (modo fps, via FpsCounter.swap, ou glsync, via swapSync) o agente le glfwGetFramebufferSize/glfwGetWindowSize do handle do
 *   swap e, na 1a vez e sempre que mudar, imprime em stderr "[pzarm-agent] framebuffer=WxH janela=WxH" e grava "# t=.. framebuffer=.. janela=.." no glsync.log (se existir)
 *   ou como comentario no csv de fps.
 * Sem "diag", "fps", "pz", "renderdiag", "renderfix", "collapse", "vbosync", "uidiag", "uimodeldiag" nem "glsync" o agente nao faz nada.
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
        boolean diag = false, fps = false, uidiag = false, uimodeldiag = false, glsync = false, stutterdiag = false;
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
            else if (a.startsWith("vbosync=")) {
                String v = a.substring(8);
                if (v.equals("sync")) vboMode = VBO_SYNC;
                else if (v.equals("invalidate")) vboMode = VBO_INVALIDATE;
                else fatal("vbosync invalido (sync|invalidate): " + a, new IllegalArgumentException(a));
            }
            else if (a.startsWith("glsync=")) { parseGlSync(a.substring(7)); glsync = true; }
            else if (a.equals("stutterdiag")) stutterdiag = true;
            else if (a.startsWith("stutterms=")) {
                try { stutterNs = Long.parseLong(a.substring(10)) * 1_000_000L; if (stutterNs < 1_000_000L) throw new NumberFormatException(a); }
                catch (NumberFormatException e) { fatal("stutterms invalido (inteiro >= 1): " + a, e); }
            }
            else if (a.equals("uidiag")) uidiag = true;
            else if (a.equals("uimodeldiag")) uimodeldiag = true;
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
        if (fps) { fpsOn = true; FpsCounter.install(inst, dir); }
        if (collapse) installCollapse(inst);
        if (vboMode != 0) installVboSync(inst);
        if (uidiag) installUiDiag(inst, dir);
        if (uimodeldiag) installUiModel(inst, dir);
        if (stutterdiag && !uploadActive()) upCountOn = true;   // o stutterdiag conta os uploads (preUpload) sem sincronizar
        if (glsync || stutterdiag) installGlSync(inst, dir);
        if (stutterdiag) installStutter(inst, dir);
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

    // ------------------------------------------------------------------ vbosync=sync|invalidate
    static final String VBO_CLASS = "zombie/core/VBO/GLVertexBufferObject";
    static final int VBO_SYNC = 1, VBO_INVALIDATE = 2;
    static final int MAP_INVALIDATE_RANGE = 0x4, MAP_INVALIDATE_BUFFER = 0x8, MAP_UNSYNCHRONIZED = 0x20;
    static int vboMode;               // 0 = desligado
    static final AtomicBoolean vboLogged = new AtomicBoolean();

    static void installVboSync(Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!VBO_CLASS.equals(name)) return null;
                try { return instrumentVbo(buf); }
                catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
            }
        });
        System.err.println("[pzarm-agent] modo vbosync ativo: vbosync=" + vboName(vboMode));
    }

    static String vboName(int mode) { return mode == VBO_INVALIDATE ? "invalidate" : "sync"; }

    /** GLVertexBufferObject: as 2 chamadas a GL30.glMapBufferRange e as 2 a ARBMapBufferRange.glMapBufferRange (IJJILjava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;
     *  viram invokestatic PzArmAgent.mapRange30 / mapRangeArb (mesma assinatura). */
    static byte[] instrumentVbo(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0, 0};
        ClassDesc me = ClassDesc.of(ME);
        String desc = "(IJJILjava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;";
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKESTATIC && ii.name().equalsString("glMapBufferRange") && ii.type().equalsString(desc)) {
                String owner = ii.owner().asInternalName();
                if (owner.equals("org/lwjgl/opengl/GL30")) { b.invokestatic(me, "mapRange30", MethodTypeDesc.ofDescriptor(desc)); n[0]++; return; }
                if (owner.equals("org/lwjgl/opengl/ARBMapBufferRange")) { b.invokestatic(me, "mapRangeArb", MethodTypeDesc.ofDescriptor(desc)); n[1]++; return; }
            }
            b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(mm -> true, ct));
        if (n[0] != 2 || n[1] != 2)
            throw new IllegalStateException("esperava 2 GL30.glMapBufferRange e 2 ARBMapBufferRange.glMapBufferRange em GLVertexBufferObject, achei " + n[0] + " e " + n[1]);
        return out;
    }

    /** Decisao pura: acesso de glMapBufferRange ajustado. sync: sem UNSYNCHRONIZED; invalidate: como sync e, com offset 0, INVALIDATE_RANGE vira INVALIDATE_BUFFER. */
    static int vboAccess(int access, long offset, int mode) {
        int a = access & ~MAP_UNSYNCHRONIZED;
        if (mode == VBO_INVALIDATE && offset == 0) a = (a & ~MAP_INVALIDATE_RANGE) | MAP_INVALIDATE_BUFFER;
        return a;
    }

    static int vboAdjust(int access, long offset) {
        int a = vboAccess(access, offset, vboMode);
        if (vboLogged.compareAndSet(false, true)) {
            System.err.println("[pzarm-agent] vbosync=" + vboName(vboMode) + ": glMapBufferRange access " + h(access) + " -> " + h(a));
        }
        return a;
    }

    /** No lugar de GL30.glMapBufferRange (chamado de GLVertexBufferObject). */
    public static ByteBuffer mapRange30(int target, long offset, long length, int access, ByteBuffer old) {
        return GL30.glMapBufferRange(target, offset, length, vboAdjust(access, offset), old);
    }

    /** No lugar de ARBMapBufferRange.glMapBufferRange (chamado de GLVertexBufferObject). */
    public static ByteBuffer mapRangeArb(int target, long offset, long length, int access, ByteBuffer old) {
        return ARBMapBufferRange.glMapBufferRange(target, offset, length, vboAdjust(access, offset), old);
    }

    // ------------------------------------------------------------------ uidiag
    static final String UI_CLASS = "zombie/ui/UI3DModel$Drawer";
    static final AtomicLong uiInit = new AtomicLong(), uiInitRendered = new AtomicLong(), uiRender = new AtomicLong(), uiPostTrue = new AtomicLong(), uiPostFalse = new AtomicLong();
    static final ConcurrentSkipListMap<Integer, AtomicLong> uiRet = new ConcurrentSkipListMap<>();

    static void installUiDiag(Instrumentation inst, Path dir) {
        Path log = dir.toAbsolutePath().resolve("uidiag.log");
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, "# pzarm-agent uidiag " + LocalDateTime.now() + "\n# t init initRendered initNotRendered render postTrue postFalse renderMainRet swaps (contagens do ultimo segundo)\n");
        } catch (IOException e) { fatal("nao foi possivel criar " + log, e); }
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!UI_CLASS.equals(name)) return null;
                try { return instrumentUiDrawer(buf); }
                catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
            }
        });
        Thread t = new Thread(() -> uiWriter(log), "pzarm-uidiag");
        t.setDaemon(true);
        t.start();
        System.err.println("[pzarm-agent] modo uidiag ativo, log: " + log);
    }

    static void uiWriter(Path log) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        long[] prev = new long[6];
        Map<Integer, Long> prevRet = new java.util.TreeMap<>();
        while (true) {
            try {
                Thread.sleep(1000);
                long[] cur = {uiInit.get(), uiInitRendered.get(), uiRender.get(), uiPostTrue.get(), uiPostFalse.get(), FpsCounter.frames};
                StringBuilder ret = new StringBuilder("{");
                for (Map.Entry<Integer, AtomicLong> en : uiRet.entrySet()) {
                    long v = en.getValue().get(), d = v - prevRet.getOrDefault(en.getKey(), 0L);
                    prevRet.put(en.getKey(), v);
                    if (d != 0) { if (ret.length() > 1) ret.append(','); ret.append(en.getKey()).append(':').append(d); }
                }
                ret.append('}');
                String line = uiLine(LocalDateTime.now().format(fmt), cur[0] - prev[0], cur[1] - prev[1], cur[2] - prev[2], cur[3] - prev[3], cur[4] - prev[4],
                        ret.toString(), FpsCounter.started ? cur[5] - prev[5] : -1);
                prev = cur;
                Files.writeString(log, line + "\n", StandardOpenOption.APPEND);
            } catch (Throwable ignored) { }
        }
    }

    /** Formato de uma linha do uidiag.log. swaps < 0 = omitido. */
    static String uiLine(String t, long init, long initRendered, long render, long postTrue, long postFalse, String ret, long swaps) {
        return "t=" + t + " init=" + init + " initRendered=" + initRendered + " initNotRendered=" + (init - initRendered) + " render=" + render
                + " postTrue=" + postTrue + " postFalse=" + postFalse + " renderMainRet=" + ret + (swaps >= 0 ? " swaps=" + swaps : "");
    }

    public static void uiDrawRender() { uiRender.incrementAndGet(); }
    public static void uiDrawPost(boolean rendered) { (rendered ? uiPostTrue : uiPostFalse).incrementAndGet(); }
    public static void uiDrawInit(boolean rendered) { uiInit.incrementAndGet(); if (rendered) uiInitRendered.incrementAndGet(); }
    public static void uiRenderMainRet(int v) { try { uiRet.computeIfAbsent(v, k -> new AtomicLong()).incrementAndGet(); } catch (Throwable ignored) { } }

    /** UI3DModel$Drawer: render()V -> uiDrawRender() no inicio; postRender()V -> uiDrawPost(this.rendered) no inicio; init(II)V -> uiRenderMainRet(int)
     *  apos AnimatedModel.renderMain()I e uiDrawInit(this.rendered) antes de cada return. Tudo exatamente 1 vez, senao IllegalStateException. */
    static byte[] instrumentUiDrawer(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(bytes);
        ClassDesc me = ClassDesc.of(ME), self = ClassDesc.ofInternalName(UI_CLASS);
        if (cm.fields().stream().noneMatch(f -> f.fieldName().equalsString("rendered") && f.fieldType().equalsString("Z")))
            throw new IllegalStateException("campo boolean rendered ausente em " + UI_CLASS);
        int[] n = {0, 0, 0, 0};   // render, postRender, returns de init, renderMain
        boolean[] first = new boolean[2];
        CodeTransform ctRender = (CodeBuilder b, CodeElement e) -> {
            if (!first[0]) { first[0] = true; b.invokestatic(me, "uiDrawRender", MethodTypeDesc.ofDescriptor("()V")); n[0]++; }
            b.with(e);
        };
        CodeTransform ctPost = (CodeBuilder b, CodeElement e) -> {
            if (!first[1]) {
                first[1] = true;
                b.aload(0).getfield(self, "rendered", ConstantDescs.CD_boolean);
                b.invokestatic(me, "uiDrawPost", MethodTypeDesc.ofDescriptor("(Z)V"));
                n[1]++;
            }
            b.with(e);
        };
        CodeTransform ctInit = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof ReturnInstruction) {
                b.aload(0).getfield(self, "rendered", ConstantDescs.CD_boolean);
                b.invokestatic(me, "uiDrawInit", MethodTypeDesc.ofDescriptor("(Z)V"));
                n[2]++;
            }
            b.with(e);
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKEVIRTUAL && ii.name().equalsString("renderMain") && ii.type().equalsString("()I")
                    && ii.owner().asInternalName().equals("zombie/core/skinnedmodel/advancedanimation/AnimatedModel")) {
                b.dup();
                b.invokestatic(me, "uiRenderMainRet", MethodTypeDesc.ofDescriptor("(I)V"));
                n[3]++;
            }
        };
        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("render") && mm.methodType().equalsString("()V"), ctRender));
        out = cf.transformClass(cf.parse(out), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("postRender") && mm.methodType().equalsString("()V"), ctPost));
        out = cf.transformClass(cf.parse(out), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("init") && mm.methodType().equalsString("(II)V"), ctInit));
        if (n[0] != 1) throw new IllegalStateException("esperava 1 metodo Drawer.render()V, achei " + n[0]);
        if (n[1] != 1) throw new IllegalStateException("esperava 1 metodo Drawer.postRender()V, achei " + n[1]);
        if (n[2] != 1) throw new IllegalStateException("esperava 1 return em Drawer.init(II)V, achei " + n[2]);
        if (n[3] != 1) throw new IllegalStateException("esperava 1 chamada a AnimatedModel.renderMain()I em Drawer.init, achei " + n[3]);
        return out;
    }

    // ------------------------------------------------------------------ uimodeldiag
    static final String AM_CLASS = "zombie/core/skinnedmodel/advancedanimation/AnimatedModel";
    static final String UIM_DESC = "(L" + AM_CLASS + ";IIIIFF)V";
    static final long UIM_MAX_BYTES = 20L * 1024 * 1024;
    static Path uiModelLogPath;
    static BufferedWriter uiModelLog;
    static long uiModelBytes;
    static long uiFrame, uiErr, uiMaxChanged, uiSecStart, uiSecFrames, uiSecMoved, uiSecFail;
    static int uiPrevMx = Integer.MIN_VALUE, uiPrevMy = Integer.MIN_VALUE;
    static final Map<String, Integer> uiSigs = new LinkedHashMap<>();
    static final Map<Integer, Long> uiSecSigs = new java.util.TreeMap<>();
    static ByteBuffer uiPix;
    static final DateTimeFormatter UI_MS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    static void installUiModel(Instrumentation inst, Path dir) {
        uiModelLogPath = dir.toAbsolutePath().resolve("uimodel.log");
        try {
            Files.createDirectories(uiModelLogPath.getParent());
            uiModelLog = Files.newBufferedWriter(uiModelLogPath, StandardCharsets.UTF_8);
            umlog("# pzarm-agent uimodeldiag " + LocalDateTime.now());
            umlog("# SIG id=N <estado GL> | f=N t=HH:mm:ss.SSS mx=X my=Y sig=N changed=K of=M rect=x,y,w,h (rect em coordenadas GL, y para cima) | SEC t=.. frames=.. mouseMoved=.. fail=.. sigs={id:n}");
        } catch (IOException e) { fatal("nao foi possivel criar " + uiModelLogPath, e); }
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                if (!UI_CLASS.equals(name)) return null;
                try { return instrumentUiModel(buf); }
                catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
            }
        });
        System.err.println("[pzarm-agent] modo uimodeldiag ativo, log: " + uiModelLogPath);
    }

    /** UI3DModel$Drawer.render()V: troca a unica chamada AnimatedModel.DoRender(IIIIFF)V por PzArmAgent.uiDoRender(AnimatedModel,IIIIFF)V (o receptor ja esta
     *  na pilha). No bytecode do 42.21, render() passa (absX, Core.height - absY - height, width, height, sizeV, animPlayerAngle): x e y ja em coordenadas GL (origem
     *  embaixo, y para cima) do canto inferior esquerdo do boneco. AnimatedModel.DoRender faz glClear(GL_DEPTH_BUFFER_BIT), copia os 4 ints para
     *  UIModelCamera.viewportX/Y/W/H e DoRender(IModelCamera); UIModelCamera.Begin chama glViewport(x,y,w,h). */
    static byte[] instrumentUiModel(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        ClassDesc me = ClassDesc.of(ME);
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKEVIRTUAL && ii.owner().asInternalName().equals(AM_CLASS)
                    && ii.name().equalsString("DoRender") && ii.type().equalsString("(IIIIFF)V")) {
                b.invokestatic(me, "uiDoRender", MethodTypeDesc.ofDescriptor(UIM_DESC));
                n[0]++;
            } else b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("render") && mm.methodType().equalsString("()V"), ct));
        if (n[0] != 1) throw new IllegalStateException("esperava 1 chamada a AnimatedModel.DoRender(IIIIFF)V em UI3DModel$Drawer.render, achei " + n[0]);
        return out;
    }

    static synchronized void umlog(String s) {
        try {
            if (uiModelLog == null) return;
            if (uiModelBytes > UIM_MAX_BYTES && !s.startsWith("SEC")) return;
            uiModelLog.write(s); uiModelLog.newLine();
            uiModelBytes += s.length() + 2;
        } catch (Throwable ignored) { }
    }

    static int gi(int p) { return GL11.glGetInteger(p); }
    static String onoff(int cap) { return GL11.glIsEnabled(cap) ? "on" : "off"; }

    /** Estado GL relevante para o desenho do boneco (so leitura). A string e a assinatura. */
    static String uiState() {
        StringBuilder sb = new StringBuilder();
        int fb = gi(0x8CA6);
        int[] vp = new int[4], sc = new int[4];
        GL11.glGetIntegerv(0xBA2, vp);
        GL11.glGetIntegerv(0xC10, sc);
        double[] dr = new double[2];
        GL11.glGetDoublev(0xB70, dr);
        ByteBuffer cm = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        GL11.glGetBooleanv(0xC23, cm);
        sb.append("drawfb=").append(fb).append(" readfb=").append(gi(0x8CAA)).append(" readbuf=").append(h(gi(0xC02)))
          .append(" vp=").append(vp[0]).append(',').append(vp[1]).append(',').append(vp[2]).append(',').append(vp[3])
          .append(" scissor=").append(onoff(0xC11)).append(" box=").append(sc[0]).append(',').append(sc[1]).append(',').append(sc[2]).append(',').append(sc[3])
          .append(" stencil=").append(onoff(0xB90)).append(" depthTest=").append(onoff(0xB71)).append(" depthFunc=").append(h(gi(0xB74)))
          .append(" depthMask=").append(GL11.glGetBoolean(0xB72)).append(" depthRange=").append(dr[0]).append(',').append(dr[1])
          .append(" colorMask=").append(cm.get(0)).append(',').append(cm.get(1)).append(',').append(cm.get(2)).append(',').append(cm.get(3))
          .append(" blend=").append(onoff(0xBE2)).append(" cull=").append(onoff(0xB44)).append(" alphaTest=").append(onoff(0xBC0))
          .append(" prog=").append(gi(0x8B8D));
        int bits = fb == 0 ? gi(0xD56) : GL30.glGetFramebufferAttachmentParameteri(0x8CA9, 0x8D00, 0x8216);
        sb.append(" depthBits=").append(bits);
        int err = GL11.glGetError();
        if (err != 0) sb.append(" glerr=").append(h(err));
        return sb.toString();
    }

    /** Amostra uma grade nx*ny de pixels RGBA (empacotados em int) de um buffer cw*ch (linhas de baixo para cima, como o glReadPixels devolve). */
    static int[] gridSample(ByteBuffer buf, int cw, int ch, int nx, int ny) {
        int[] r = new int[nx * ny];
        for (int j = 0; j < ny; j++) {
            int row = (int) ((long) j * ch / ny);
            for (int i = 0; i < nx; i++) {
                int col = (int) ((long) i * cw / nx);
                r[j * nx + i] = buf.getInt((row * cw + col) * 4);
            }
        }
        return r;
    }

    static int diffCount(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }

    /** Le o retangulo (x,y,w,h) ja recortado do framebuffer de desenho atual e devolve a grade amostrada (preserva pack alignment, PBO de pack e read fb). */
    static int[] uiRead(int x, int y, int w, int h, int nx, int ny) {
        int need = w * h * 4;
        if (uiPix == null || uiPix.capacity() < need) uiPix = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder());
        int drawFb = gi(0x8CA6), readFb = gi(0x8CAA), pbo = gi(0x88ED), align = gi(0x0D05);
        try {
            if (readFb != drawFb) GL30.glBindFramebuffer(0x8CA8, drawFb);
            if (pbo != 0) GL15.glBindBuffer(0x88EB, 0);
            GL11.glPixelStorei(0x0D05, 1);
            uiPix.clear();
            GL11.glReadPixels(x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uiPix);
            return gridSample(uiPix, w, h, nx, ny);
        } finally {
            GL11.glPixelStorei(0x0D05, align);
            if (pbo != 0) GL15.glBindBuffer(0x88EB, pbo);
            if (readFb != drawFb) GL30.glBindFramebuffer(0x8CA8, readFb);
        }
    }

    public static String uiFrameLine(long f, String t, int mx, int my, int sig, int changed, int of, int x, int y, int w, int h) {
        return "f=" + f + " t=" + t + " mx=" + mx + " my=" + my + " sig=" + sig + " changed=" + changed + " of=" + of + " rect=" + x + "," + y + "," + w + "," + h;
    }

    static String uiSecLine(String t, long frames, long moved, long fail, Map<Integer, Long> sigs) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<Integer, Long> en : sigs.entrySet()) { if (sb.length() > 1) sb.append(','); sb.append(en.getKey()).append(':').append(en.getValue()); }
        return "SEC t=" + t + " frames=" + frames + " mouseMoved=" + moved + " fail=" + fail + " sigs=" + sb.append('}');
    }

    /** No lugar de AnimatedModel.DoRender(IIIIFF)V (chamado de UI3DModel$Drawer.render). Chama o original exatamente 1 vez, aconteca o que acontecer. */
    public static void uiDoRender(zombie.core.skinnedmodel.advancedanimation.AnimatedModel am, int x, int y, int w, int h, float size, float angle) {
        int sig = -1, nx = 0, ny = 0, cx = 0, cy = 0, cw = 0, ch = 0;
        int[] before = null;
        try {
            String st = uiState();
            synchronized (uiSigs) {
                Integer id = uiSigs.get(st);
                if (id == null) { id = uiSigs.size() + 1; uiSigs.put(st, id); umlog("SIG id=" + id + " " + st); }
                sig = id;
            }
            String[] p = st.substring(st.indexOf(" vp=") + 4).split(" ")[0].split(",");
            int vx = Integer.parseInt(p[0]), vy = Integer.parseInt(p[1]), vw = Integer.parseInt(p[2]), vh = Integer.parseInt(p[3]);
            int x0 = Math.max(x, vw > 0 ? vx : 0), y0 = Math.max(y, vh > 0 ? vy : 0);
            int x1 = Math.min(x + w, vw > 0 ? vx + vw : Integer.MAX_VALUE), y1 = Math.min(y + h, vh > 0 ? vy + vh : Integer.MAX_VALUE);
            cx = x0; cy = y0; cw = x1 - x0; ch = y1 - y0;
            if (cw > 0 && ch > 0 && (long) cw * ch <= 16_000_000L) {
                nx = Math.min(64, cw); ny = Math.min(64, ch);
                before = uiRead(cx, cy, cw, ch, nx, ny);
            }
        } catch (Throwable t) { uiErr++; before = null; }
        am.DoRender(x, y, w, h, size, angle);
        try {
            int changed = 0, of = 0;
            if (before != null) { int[] after = uiRead(cx, cy, cw, ch, nx, ny); changed = diffCount(before, after); of = before.length; }
            int mx = -1, my = -1;
            try { mx = zombie.input.Mouse.getX(); my = zombie.input.Mouse.getY(); } catch (Throwable ignored) { }
            long now = System.nanoTime();
            if (uiSecStart == 0) uiSecStart = now;
            uiFrame++; uiSecFrames++;
            if (mx != uiPrevMx || my != uiPrevMy) { if (uiPrevMx != Integer.MIN_VALUE) uiSecMoved++; uiPrevMx = mx; uiPrevMy = my; }
            if (changed > uiMaxChanged) uiMaxChanged = changed;
            if (changed * 5L < uiMaxChanged) uiSecFail++;
            uiSecSigs.merge(sig, 1L, Long::sum);
            String t = LocalDateTime.now().format(UI_MS);
            umlog(uiFrameLine(uiFrame, t, mx, my, sig, changed, of, x, y, w, h));
            if (now - uiSecStart >= 1_000_000_000L) {
                umlog(uiSecLine(t, uiSecFrames, uiSecMoved, uiSecFail, uiSecSigs));
                try { uiModelLog.flush(); } catch (Throwable ignored) { }
                uiSecStart = now; uiSecFrames = uiSecMoved = uiSecFail = 0; uiSecSigs.clear();
            }
        } catch (Throwable t) { uiErr++; }
    }

    // ------------------------------------------------------------------ glsync
    static final String SWAP_CLASS = "org/lwjglx/opengl/Display";
    static boolean syncSwapFinish, syncSwapFlush, syncModelFinish, syncModelFlush, syncSwapFence, fpsOn;
    // swapfence: fence atrasado de 1 quadro (so a thread de render usa fenceHandle)
    static long fenceHandle;
    static final AtomicLong gsSwapFence = new AtomicLong(), gsFenceWaitNs = new AtomicLong();
    static final AtomicLong gsSwapFinish = new AtomicLong(), gsSwapFlush = new AtomicLong(), gsModelFinish = new AtomicLong(), gsModelFlush = new AtomicLong();

    static void parseGlSync(String v) {
        switch (v) {
            case "swapfinish": syncSwapFinish = true; break;
            case "swapflush": syncSwapFlush = true; break;
            case "swapfence": syncSwapFence = true; break;
            case "modelfinish": syncModelFinish = true; break;
            case "modelflush": syncModelFlush = true; break;
            case "uploadfinish": upFinishOn = true; break;
            case "uploadflush": upFlushOn = true; break;
            case "uploadcount": upCountOn = true; break;
            case "uploadflushall": upFlushAllOn = true; break;
            case "uploadfinishall": upFinishAllOn = true; break;
            case "otherflush": otherFlushOn = true; break;
            case "otherfinish": otherFinishOn = true; break;
            case "clearfinish": clearFinishOn = true; break;
            case "clearflush": clearFlushOn = true; break;
            default: fatal("glsync invalido (swapfinish|swapflush|swapfence|modelfinish|modelflush|uploadfinish|uploadflush|uploadfinishall|uploadflushall|uploadcount|otherflush|otherfinish|clearfinish|clearflush): " + v, new IllegalArgumentException(v));
        }
    }

    static String glSyncDesc() {
        List<String> l = new ArrayList<>();
        if (syncSwapFinish) l.add("swapfinish");
        if (syncSwapFlush) l.add("swapflush");
        if (syncSwapFence) l.add("swapfence");
        if (syncModelFinish) l.add("modelfinish");
        if (syncModelFlush) l.add("modelflush");
        if (upFinishOn) l.add("uploadfinish");
        if (upFlushOn) l.add("uploadflush");
        if (upFinishAllOn) l.add("uploadfinishall");
        if (upFlushAllOn) l.add("uploadflushall");
        if (upCountOn) l.add("uploadcount");
        if (otherFlushOn) l.add("otherflush");
        if (otherFinishOn) l.add("otherfinish");
        if (clearFinishOn) l.add("clearfinish");
        if (clearFlushOn) l.add("clearflush");
        return String.join(",", l);
    }

    // tamanho do framebuffer/janela do handle do swap (so a thread de render usa)
    static final int[] fbW = new int[1], fbH = new int[1], wW = new int[1], wH = new int[1];
    static int lastFbW = -1, lastFbH = -1, lastWW = -1, lastWH = -1;
    static volatile Path glSyncLogPath;

    static String fbLine(int fw, int fh, int ww, int wh) { return "framebuffer=" + fw + "x" + fh + " janela=" + ww + "x" + wh; }

    /** Chamado a cada swap (de FpsCounter.swap ou de swapSync): na 1a vez e sempre que framebuffer/janela mudam, imprime em stderr
     *  "[pzarm-agent] framebuffer=WxH janela=WxH" e grava "# t=... framebuffer=.. janela=.." no glsync.log (se existir) ou no csv de fps. Nunca lanca. */
    public static void fbInfo(long window) {
        try {
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fbW, fbH);
            org.lwjgl.glfw.GLFW.glfwGetWindowSize(window, wW, wH);
            if (fbW[0] == lastFbW && fbH[0] == lastFbH && wW[0] == lastWW && wH[0] == lastWH) return;
            lastFbW = fbW[0]; lastFbH = fbH[0]; lastWW = wW[0]; lastWH = wH[0];
            String l = fbLine(lastFbW, lastFbH, lastWW, lastWH);
            System.err.println("[pzarm-agent] " + l);
            String c = "# t=" + LocalDateTime.now().format(UI_MS) + " " + l;
            Path p = glSyncLogPath;
            if (p != null) Files.writeString(p, c + "\n", StandardOpenOption.APPEND);
            else FpsCounter.comment(c);
        } catch (Throwable ignored) { }
    }

    static void installGlSync(Instrumentation inst, Path dir) {
        Path log = dir.toAbsolutePath().resolve("glsync.log");
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, "# pzarm-agent glsync " + LocalDateTime.now() + " " + glSyncDesc() + "\n# t swapFinish swapFlush modelFinish modelFlush [upFinish upFlush clearFinish clearFlush calls skipped otherFlush otherFinish others] [swapFence fenceWaitMs] (contagens do ultimo segundo; colchetes so com upload/clear/other ou swapfence ativos)\n");
        } catch (IOException e) { fatal("nao foi possivel criar " + log, e); }
        glSyncLogPath = log;
        final boolean swap = true, model = syncModelFinish || syncModelFlush;   // o hook do swap sempre entra: captura a thread de render
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                try {
                    if (swap && SWAP_CLASS.equals(name)) return instrumentSwap(buf);
                    if (model && UI_CLASS.equals(name)) return instrumentModelSync(buf);
                } catch (Throwable t) { fatal("falha ao transformar " + name, t); }
                return null;
            }
        });
        if (uploadActive()) {
            inst.addTransformer(new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                    if (name == null || !upPrefix(name)) return null;
                    try {
                        long[] sites = new long[SITES_N];
                        byte[] out = instrumentUpload(buf, sites);
                        if (out == null) return null;
                        for (int i = 0; i < SITES_N; i++) upSites.addAndGet(i, sites[i]);
                        if (upClasses.incrementAndGet() % 1000 == 0) upReport();
                        return out;
                    } catch (Throwable t) {
                        System.err.println("[pzarm-agent] aviso: uploadsync nao transformou " + name + ": " + t);
                        return null;
                    }
                }
            });
        }
        Thread t = new Thread(() -> glSyncWriter(log), "pzarm-glsync");
        t.setDaemon(true);
        t.start();
        System.err.println("[pzarm-agent] modo glsync ativo: " + glSyncDesc());
    }

    static void glSyncWriter(Path log) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        long[] prev = new long[11], prevCalls = new long[UP_N];
        long prevFence = 0, prevFenceNs = 0;
        while (true) {
            try {
                Thread.sleep(1000);
                long[] cur = {gsSwapFinish.get(), gsSwapFlush.get(), gsModelFinish.get(), gsModelFlush.get(), gsUpFinish.get(), gsUpFlush.get(), gsClearFinish.get(), gsClearFlush.get(), gsSkipped.get(), gsOtherFlush.get(), gsOtherFinish.get()};
                String t = LocalDateTime.now().format(fmt);
                String line;
                if (uploadActive()) {
                    long[] calls = new long[UP_N];
                    for (int i = 0; i < UP_N; i++) calls[i] = upCalls.get(i);
                    line = glSyncLine(t, cur[0] - prev[0], cur[1] - prev[1], cur[2] - prev[2], cur[3] - prev[3], cur[4] - prev[4], cur[5] - prev[5], cur[6] - prev[6], cur[7] - prev[7],
                            upCallsDesc(calls, prevCalls), cur[8] - prev[8], cur[9] - prev[9], cur[10] - prev[10], othersDesc());
                    prevCalls = calls;
                } else line = glSyncLine(t, cur[0] - prev[0], cur[1] - prev[1], cur[2] - prev[2], cur[3] - prev[3]);
                prev = cur;
                if (syncSwapFence) {
                    long fc = gsSwapFence.get(), fw = gsFenceWaitNs.get();
                    line += " swapFence=" + (fc - prevFence) + " fenceWaitMs=" + String.format(java.util.Locale.ROOT, "%.1f", (fw - prevFenceNs) / 1e6);
                    prevFence = fc; prevFenceNs = fw;
                }
                Files.writeString(log, line + "\n", StandardOpenOption.APPEND);
            } catch (Throwable ignored) { }
        }
    }

    static String glSyncLine(String t, long swapFinish, long swapFlush, long modelFinish, long modelFlush) {
        return "t=" + t + " swapFinish=" + swapFinish + " swapFlush=" + swapFlush + " modelFinish=" + modelFinish + " modelFlush=" + modelFlush;
    }

    /** org.lwjglx.opengl.Display: cada chamada a GLFW.glfwSwapBuffers(J)V (ou, com o modo fps ja instalado antes, a FpsCounter.swap(J)V que a substituiu)
     *  vira PzArmAgent.swapSync(J)V, que faz glFlush/glFinish e depois chama FpsCounter.swap (fps ativo) ou GLFW.glfwSwapBuffers. Pelo menos 1 troca, senao fatal(). */
    static byte[] instrumentSwap(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        ClassDesc me = ClassDesc.of(ME);
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            if (e instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKESTATIC && ii.type().equalsString("(J)V")
                    && ((ii.owner().asInternalName().equals("org/lwjgl/glfw/GLFW") && ii.name().equalsString("glfwSwapBuffers"))
                        || (ii.owner().asInternalName().equals("FpsCounter") && ii.name().equalsString("swap")))) {
                b.invokestatic(me, "swapSync", MethodTypeDesc.ofDescriptor("(J)V"));
                n[0]++;
            } else b.with(e);
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(ct));
        if (n[0] < 1) throw new IllegalStateException("nenhuma chamada a glfwSwapBuffers em " + SWAP_CLASS);
        return out;
    }

    /** UI3DModel$Drawer.render()V: logo depois da unica chamada a AnimatedModel.DoRender(IIIIFF)V (ou a PzArmAgent.uiDoRender, se o uimodeldiag a trocou antes)
     *  insere PzArmAgent.modelSync()V. Exatamente 1, senao fatal(). */
    static byte[] instrumentModelSync(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        ClassDesc me = ClassDesc.of(ME);
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            b.with(e);
            if (e instanceof InvokeInstruction ii && ((ii.opcode() == Opcode.INVOKEVIRTUAL && ii.owner().asInternalName().equals(AM_CLASS)
                        && ii.name().equalsString("DoRender") && ii.type().equalsString("(IIIIFF)V"))
                    || (ii.opcode() == Opcode.INVOKESTATIC && ii.owner().asInternalName().equals(ME) && ii.name().equalsString("uiDoRender")))) {
                b.invokestatic(me, "modelSync", MethodTypeDesc.ofDescriptor("()V"));
                n[0]++;
            }
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(
                mm -> mm.methodName().equalsString("render") && mm.methodType().equalsString("()V"), ct));
        if (n[0] != 1) throw new IllegalStateException("esperava 1 chamada a AnimatedModel.DoRender em UI3DModel$Drawer.render, achei " + n[0]);
        return out;
    }

    /** No lugar de glfwSwapBuffers (via Display). */
    public static void swapSync(long window) {
        if (renderThread == null) { renderThread = Thread.currentThread(); if (uploadActive()) upReport(); }
        if (stutterOn) stutterFrame();
        long fa = stutterOn ? System.nanoTime() : 0;
        try {
            if (syncSwapFlush) { GL11.glFlush(); gsSwapFlush.incrementAndGet(); }
            if (syncSwapFinish) { GL11.glFinish(); gsSwapFinish.incrementAndGet(); }
            if (syncSwapFence && fenceHandle != 0) {   // fence do quadro anterior: so bloqueia se a GPU atrasou mais de 1 quadro
                long w0 = System.nanoTime();
                GL32.glClientWaitSync(fenceHandle, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                gsFenceWaitNs.addAndGet(System.nanoTime() - w0);
                GL32.glDeleteSync(fenceHandle);
                fenceHandle = 0;
            }
        } catch (Throwable ignored) { fenceHandle = 0; }
        if (fa != 0) stFinishNs = System.nanoTime() - fa;
        if (fpsOn) FpsCounter.swap(window); else { fbInfo(window); org.lwjgl.glfw.GLFW.glfwSwapBuffers(window); }
        if (syncSwapFence) {
            try { fenceHandle = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); gsSwapFence.incrementAndGet(); } catch (Throwable ignored) { fenceHandle = 0; }
        }
        gpuDirty = false;   // a troca de buffers ja envia o que estava pendente
    }

    /** Logo depois do desenho do boneco do menu. */
    public static void modelSync() {
        try {
            if (syncModelFlush) { GL11.glFlush(); gsModelFlush.incrementAndGet(); }
            if (syncModelFinish) { GL11.glFinish(); gsModelFinish.incrementAndGet(); }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ glsync: upload*/clear*
    static final String[] UP_NAMES = {"glTexSubImage2D", "glTexSubImage3D", "glTexImage2D", "glTexImage3D", "glCompressedTexSubImage2D", "glCompressedTexImage2D",
        "glCopyTexSubImage2D", "glCopyTexImage2D", "glBufferSubData", "glBufferData", "glMapBuffer", "glMapBufferRange", "glUnmapBuffer", "glGenerateMipmap",
        "glGenerateMipmapEXT", "glBlitFramebuffer", "glBlitFramebufferEXT", "glClear",
        "IGLBufferObject.glBufferData", "IGLBufferObject.glBufferSubData", "IGLBufferObject.glMapBuffer", "IGLBufferObject.glUnmapBuffer"};
    static final int UP_N = UP_NAMES.length, UP_CLEAR = 17;
    static final String[] GW_NAMES = {"glDrawArrays", "glDrawElements", "glDrawRangeElements", "glDrawArraysInstanced", "glDrawElementsInstanced", "glMultiDrawArrays",
        "glMultiDrawElements", "glClear", "glBlitFramebuffer", "glCopyTexSubImage2D", "glCopyTexImage2D", "glBegin"};
    static final int GW_N = GW_NAMES.length, SITES_N = UP_N + GW_N;
    static final String[] SITE_NAMES = new String[SITES_N];
    static final Map<String, Integer> GW_IDS = new java.util.HashMap<>();
    static {
        for (int i = 0; i < UP_N; i++) SITE_NAMES[i] = UP_NAMES[i];
        for (int i = 0; i < GW_N; i++) { SITE_NAMES[UP_N + i] = "gpuWork." + GW_NAMES[i]; GW_IDS.put(GW_NAMES[i], i); }
    }
    static final Map<String, Integer> UP_GL_IDS = new java.util.HashMap<>(), UP_WRAP_IDS = new java.util.HashMap<>();
    static {
        for (int i = 0; i < UP_NAMES.length; i++) {
            if (UP_NAMES[i].startsWith("IGLBufferObject.")) UP_WRAP_IDS.put(UP_NAMES[i].substring(16), i); else UP_GL_IDS.put(UP_NAMES[i], i);
        }
    }
    static final String WRAP_OWNER = "zombie/core/VBO/IGLBufferObject";
    static final byte[] UP_NEEDLE_GL = "org/lwjgl/opengl/".getBytes(StandardCharsets.US_ASCII), UP_NEEDLE_WRAP = "IGLBufferObject".getBytes(StandardCharsets.US_ASCII),
            UP_NEEDLE_ME = "PzArmAgent".getBytes(StandardCharsets.US_ASCII);
    static boolean upFinishOn, upFlushOn, upCountOn, clearFinishOn, clearFlushOn, upFlushAllOn, upFinishAllOn, otherFlushOn, otherFinishOn;
    static boolean gpuDirty;   // so a thread de render: houve desenho/clear/blit/copia desde o ultimo flush/finish/swap
    static volatile Thread renderThread;
    static final java.util.concurrent.atomic.AtomicLongArray upCalls = new java.util.concurrent.atomic.AtomicLongArray(UP_N), upSites = new java.util.concurrent.atomic.AtomicLongArray(SITES_N);
    static final AtomicLong gsUpFinish = new AtomicLong(), gsUpFlush = new AtomicLong(), gsClearFinish = new AtomicLong(), gsClearFlush = new AtomicLong(), upClasses = new AtomicLong(),
            gsSkipped = new AtomicLong(), gsOtherUploads = new AtomicLong(), gsOtherFlush = new AtomicLong(), gsOtherFinish = new AtomicLong();

    static boolean uploadActive() { return upFinishOn || upFlushOn || upCountOn || clearFinishOn || clearFlushOn || upFlushAllOn || upFinishAllOn || otherFlushOn || otherFinishOn; }

    static boolean upPrefix(String name) { return name.startsWith("zombie/") || name.startsWith("org/lwjglx/") || name.startsWith("se/krka/"); }

    static boolean containsBytes(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            if (hay[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Id (indice em UP_NAMES) da chamada, ou -1. Vale: invokestatic org/lwjgl/opengl/* com nome da lista; invokeinterface/invokevirtual de IGLBufferObject
     *  (glBufferData, glBufferSubData, glMapBuffer, glUnmapBuffer); e PzArmAgent.mapRange30/mapRangeArb (o vbosync ja trocou glMapBufferRange). */
    static int upId(InvokeInstruction ii) {
        String owner = ii.owner().asInternalName(), name = ii.name().stringValue();
        Integer id = null;
        if (ii.opcode() == Opcode.INVOKESTATIC) {
            if (owner.startsWith("org/lwjgl/opengl/")) id = UP_GL_IDS.get(name);
            else if (owner.equals(ME) && (name.equals("mapRange30") || name.equals("mapRangeArb"))) id = UP_GL_IDS.get("glMapBufferRange");
        } else if ((ii.opcode() == Opcode.INVOKEINTERFACE || ii.opcode() == Opcode.INVOKEVIRTUAL) && owner.equals(WRAP_OWNER)) id = UP_WRAP_IDS.get(name);
        return id == null ? -1 : id;
    }

    static int gwId(InvokeInstruction ii) {
        if (ii.opcode() != Opcode.INVOKESTATIC || !ii.owner().asInternalName().startsWith("org/lwjgl/opengl/")) return -1;
        Integer g = GW_IDS.get(ii.name().stringValue());
        return g == null ? -1 : g;
    }

    /** Insere, em cada chamada de upload/copia/clear do GL, invokestatic PzArmAgent.preUpload(I)V (constante do id) ANTES e postUpload(I)V DEPOIS (nao mexem na pilha),
     *  e invokestatic PzArmAgent.gpuWork()V ANTES de cada chamada de desenho/clear/blit/copia/glBegin (GW_NAMES). sites[id] = pontos de preUpload (== postUpload);
     *  sites[UP_N + g] = pontos de gpuWork do nome g. Devolve null se a classe nao tem pontos. */
    static byte[] instrumentUpload(byte[] bytes, long[] sites) {
        if (!containsBytes(bytes, UP_NEEDLE_GL) && !containsBytes(bytes, UP_NEEDLE_WRAP) && !containsBytes(bytes, UP_NEEDLE_ME)) return null;
        ClassFile cf = ClassFile.of();
        int[] n = {0};
        ClassDesc me = ClassDesc.of(ME);
        MethodTypeDesc d = MethodTypeDesc.ofDescriptor("(I)V"), v = MethodTypeDesc.ofDescriptor("()V");
        CodeTransform ct = (CodeBuilder b, CodeElement e) -> {
            int id = -1;
            if (e instanceof InvokeInstruction ii) {
                id = upId(ii);
                if (id >= 0) { b.loadConstant(id); b.invokestatic(me, "preUpload", d); sites[id]++; n[0]++; }
                int gw = gwId(ii);
                if (gw >= 0) { b.invokestatic(me, "gpuWork", v); sites[UP_N + gw]++; n[0]++; }
            }
            b.with(e);
            if (id >= 0) { b.loadConstant(id); b.invokestatic(me, "postUpload", d); }
        };
        byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(ct));
        return n[0] == 0 ? null : out;
    }

    static String upSitesDesc() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < SITES_N; i++) if (upSites.get(i) != 0) { if (sb.length() > 1) sb.append(','); sb.append(SITE_NAMES[i]).append(':').append(upSites.get(i)); }
        return sb.append('}').toString();
    }

    static void upReport() { System.err.println("[pzarm-agent] uploadsync: classes=" + upClasses.get() + " pontos=" + upSitesDesc()); }

    /** Chamado (bytecode injetado) antes de cada upload/copia/clear. Na thread de render capturada no 1o swap: conta e sincroniza (uploadflush/uploadfinish so se houve
     *  trabalho de GPU desde o ultimo flush/finish/swap, ver gpuWork; as variantes *all sempre). Em outras threads so conta por thread (otherUpload). Nunca lanca. */
    public static void preUpload(int id) {
        try {
            Thread rt = renderThread;
            if (rt == null || Thread.currentThread() != rt) { otherUpload(id); return; }
            upCalls.incrementAndGet(id);
            boolean fin, fl, flAll, finAll;
            if (id == UP_CLEAR) { fin = clearFinishOn; fl = clearFlushOn; flAll = finAll = true; }
            else { fin = upFinishOn || upFinishAllOn; fl = upFlushOn || upFlushAllOn; flAll = upFlushAllOn; finAll = upFinishAllOn; }
            long ta = stutterOn && (fl || fin) ? System.nanoTime() : 0;
            if (stutterOn) stUploads++;
            if (fl) {
                if (flAll || gpuDirty) { GL11.glFlush(); gpuDirty = false; (id == UP_CLEAR ? gsClearFlush : gsUpFlush).incrementAndGet(); if (id != UP_CLEAR) stFlushDone++; }
                else { gsSkipped.incrementAndGet(); stFlushSkipped++; }
            }
            if (fin) {
                if (finAll || gpuDirty) { GL11.glFinish(); gpuDirty = false; (id == UP_CLEAR ? gsClearFinish : gsUpFinish).incrementAndGet(); if (id != UP_CLEAR) stFlushDone++; }
                else { gsSkipped.incrementAndGet(); stFlushSkipped++; }
            }
            if (ta != 0) stUpNs += System.nanoTime() - ta;
        } catch (Throwable ignored) { }
    }

    /** Chamado (bytecode injetado) antes de cada chamada de desenho/clear/blit/copia/glBegin: marca que a thread de render tem trabalho de GPU ainda nao enviado. */
    public static void gpuWork() { if (Thread.currentThread() == renderThread) gpuDirty = true; }

    /** Estatistica por thread que nao e a de render (so o dono escreve; o writer do glsync.log le sem trava). */
    static final class ThreadStat {
        final Thread thread = Thread.currentThread();
        final String name = thread.getName();
        final long[] perId = new long[UP_N], prevPerId = new long[UP_N];
        long total, prevTotal, flush;
        int ctx, since;   // ctx: 0 desconhecido, 1 tem contexto GL atual, 2 nao tem
    }
    static final java.util.concurrent.CopyOnWriteArrayList<ThreadStat> TSTATS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final ThreadLocal<ThreadStat> TSTAT = ThreadLocal.withInitial(() -> { ThreadStat s = new ThreadStat(); TSTATS.add(s); return s; });

    static void otherUpload(int id) {
        ThreadStat s = TSTAT.get();
        s.perId[id]++; s.total++;
        gsOtherUploads.incrementAndGet();
    }

    /** Chamado (bytecode injetado) DEPOIS de cada chamada de upload da lista (nao mexe na pilha). So age em thread que nao e a de render e que tem contexto GL atual
     *  (glfwGetCurrentContext, em cache por thread e reavaliado a cada 2048 chamadas): otherflush/otherfinish. */
    public static void postUpload(int id) {
        try {
            if (!otherFlushOn && !otherFinishOn) return;
            Thread rt = renderThread;
            if (rt == null || Thread.currentThread() == rt) return;
            ThreadStat s = TSTAT.get();
            if (s.ctx == 0 || ++s.since >= 2048) { s.ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext() != 0 ? 1 : 2; s.since = 0; }
            if (s.ctx != 1) return;
            if (otherFlushOn) { GL11.glFlush(); gsOtherFlush.incrementAndGet(); s.flush++; }
            if (otherFinishOn) { GL11.glFinish(); gsOtherFinish.incrementAndGet(); }
        } catch (Throwable ignored) { }
    }

    /** Por segundo: threads != render que chamaram preUpload: {nome:n(idMaisFrequente),...}. Remove da lista as threads mortas sem atividade. */
    static String othersDesc() {
        StringBuilder sb = new StringBuilder("{");
        for (ThreadStat s : TSTATS) {
            long d = s.total - s.prevTotal;
            if (d != 0) {
                int top = -1; long topN = 0;
                for (int i = 0; i < UP_N; i++) { long di = s.perId[i] - s.prevPerId[i]; if (di > topN) { topN = di; top = i; } s.prevPerId[i] = s.perId[i]; }
                s.prevTotal = s.total;
                if (sb.length() > 1) sb.append(',');
                sb.append(s.name).append(':').append(d).append('(').append(top >= 0 ? UP_NAMES[top] : "?").append(')');
            } else if (!s.thread.isAlive()) TSTATS.remove(s);
        }
        return sb.append('}').toString();
    }

    static String upCallsDesc(long[] cur, long[] prev) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < UP_N; i++) { long d = cur[i] - prev[i]; if (d != 0) { if (sb.length() > 1) sb.append(','); sb.append(UP_NAMES[i]).append(':').append(d); } }
        return sb.append('}').toString();
    }

    static String glSyncLine(String t, long swapFinish, long swapFlush, long modelFinish, long modelFlush, long upFinish, long upFlush, long clearFinish, long clearFlush, String calls,
            long skipped, long otherFlush, long otherFinish, String others) {
        return glSyncLine(t, swapFinish, swapFlush, modelFinish, modelFlush) + " upFinish=" + upFinish + " upFlush=" + upFlush
                + " clearFinish=" + clearFinish + " clearFlush=" + clearFlush + " calls=" + calls + " skipped=" + skipped
                + " otherFlush=" + otherFlush + " otherFinish=" + otherFinish + " others=" + others;
    }

    // ------------------------------------------------------------------ stutterdiag
    static boolean stutterOn;
    static long stutterNs = 45_000_000L;
    static BufferedWriter stutterLog;
    // so a thread de render escreve/le (swapSync, preUpload, chunkEnter/chunkExit)
    static long stLastEntry, stFinishNs, stUpNs, stUploads, stChunks, stChunkNs, stChunkStart, stSumStart;
    static long stFrames, stSlow, stSlowSumNs, stSlowChunks, stSlowGc, stSlowFinish, stSlowWait;
    static long stPrevGcCount, stPrevGcMs, stFlushDone, stFlushSkipped, stPrevOtherUp, stPrevOtherFl;
    static java.util.List<java.lang.management.GarbageCollectorMXBean> stGcBeans;

    static void installStutter(Instrumentation inst, Path dir) {
        Path log = dir.toAbsolutePath().resolve("stutter.log");
        try {
            Files.createDirectories(log.getParent());
            stutterLog = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
            stlog("# pzarm-agent stutterdiag " + LocalDateTime.now() + " stutterms=" + stutterNs / 1_000_000L);
            stlog("# t frameMs finishMs uploadSyncMs uploads chunks chunkMs gcCount gcMs mainWaitMs flushDone flushSkipped otherUploads otherFlush | SUM a cada 10 s");
            stutterLog.flush();
            stGcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        } catch (IOException e) { fatal("nao foi possivel criar " + log, e); }
        stutterOn = true;
        if (!fxChunk) {   // com renderfix *chunk o instrumentChunk ja foi instalado (evita inserir chunkEnter/chunkExit duas vezes)
            inst.addTransformer(new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] buf) {
                    if (!FC_CLASS.equals(name)) return null;
                    try { return instrumentChunk(buf); }
                    catch (Throwable t) { fatal("falha ao transformar " + name, t); return null; }
                }
            });
        }
        System.err.println("[pzarm-agent] modo stutterdiag ativo: stutterms=" + stutterNs / 1_000_000L + " log: " + log);
    }

    static void stlog(String s) { try { stutterLog.write(s); stutterLog.newLine(); } catch (Throwable ignored) { } }

    static String ms1(long ns) { return String.format(java.util.Locale.ROOT, "%.1f", ns / 1e6); }

    static String stutterLine(String t, long frameNs, long finishNs, long upNs, long uploads, long chunks, long chunkNs, long gcCount, long gcMs, long waitNs,
            long flushDone, long flushSkipped, long otherUploads, long otherFlush) {
        return "t=" + t + " frameMs=" + ms1(frameNs) + " finishMs=" + ms1(finishNs) + " uploadSyncMs=" + ms1(upNs) + " uploads=" + uploads + " chunks=" + chunks
                + " chunkMs=" + ms1(chunkNs) + " gcCount=" + gcCount + " gcMs=" + gcMs + " mainWaitMs=" + ms1(waitNs)
                + " flushDone=" + flushDone + " flushSkipped=" + flushSkipped + " otherUploads=" + otherUploads + " otherFlush=" + otherFlush;
    }

    static String stutterSum(String t, long frames, long slow, long slowSumNs, long withChunks, long withGc, long finishDom, long waitDom) {
        return "SUM t=" + t + " frames=" + frames + " slow=" + slow + " avgSlowMs=" + ms1(slow > 0 ? slowSumNs / slow : 0) + " slowWithChunks=" + withChunks
                + " slowWithGc=" + withGc + " slowFinishDominant=" + finishDom + " slowMainWaitDominant=" + waitDom;
    }

    /** Chamado no inicio de cada swapSync (thread de render): fecha o intervalo desde o swap anterior, registra se lento e zera os acumuladores. */
    static void stutterFrame() {
        try {
            long now = System.nanoTime();
            if (stSumStart == 0) stSumStart = now;
            if (stLastEntry != 0) {
                long dt = now - stLastEntry;
                stFrames++;
                // GC: 1 leitura por quadro (soma de contagem e tempo de todos os coletores; barato, sao 2 beans no ZGC)
                long gcc = 0, gcm = 0;
                for (java.lang.management.GarbageCollectorMXBean b : stGcBeans) { long c = b.getCollectionCount(), m = b.getCollectionTime(); if (c > 0) gcc += c; if (m > 0) gcm += m; }
                long dGc = gcc - stPrevGcCount, dGcMs = gcm - stPrevGcMs;
                stPrevGcCount = gcc; stPrevGcMs = gcm;
                long ou = gsOtherUploads.get(), ofl = gsOtherFlush.get() + gsOtherFinish.get();
                long dOu = ou - stPrevOtherUp, dOfl = ofl - stPrevOtherFl;
                stPrevOtherUp = ou; stPrevOtherFl = ofl;
                if (dt >= stutterNs) {
                    long wait = 0;
                    try { wait = zombie.core.opengl.RenderThread.getWaitTime(); } catch (Throwable ignored) { }
                    stSlow++; stSlowSumNs += dt;
                    if (stChunks > 0) stSlowChunks++;
                    if (dGc > 0) stSlowGc++;
                    if (stFinishNs * 2 > dt) stSlowFinish++;
                    if (wait * 2 > dt) stSlowWait++;
                    stlog(stutterLine(LocalDateTime.now().format(UI_MS), dt, stFinishNs, stUpNs, stUploads, stChunks, stChunkNs, dGc, dGcMs, wait, stFlushDone, stFlushSkipped, dOu, dOfl));
                    stutterLog.flush();
                }
            }
            stFinishNs = stUpNs = stUploads = stChunks = stChunkNs = stFlushDone = stFlushSkipped = 0; stChunkStart = 0;
            stLastEntry = now;
            if (now - stSumStart >= 10_000_000_000L) {
                stlog(stutterSum(LocalDateTime.now().format(UI_MS), stFrames, stSlow, stSlowSumNs, stSlowChunks, stSlowGc, stSlowFinish, stSlowWait));
                stutterLog.flush();
                stSumStart = now; stFrames = stSlow = stSlowSumNs = stSlowChunks = stSlowGc = stSlowFinish = stSlowWait = 0;
            }
        } catch (Throwable ignored) { }
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
    public static void chunkEnter() {
        inChunk = true; lastT0 = lastT1 = -1;
        if (stutterOn && Thread.currentThread() == renderThread) { stChunks++; stChunkStart = System.nanoTime(); }
    }

    /** Chamado (bytecode injetado) quando a thread de render termina o chunk. */
    public static void chunkExit() {
        inChunk = false;
        if (stutterOn && stChunkStart != 0 && Thread.currentThread() == renderThread) { stChunkNs += System.nanoTime() - stChunkStart; stChunkStart = 0; }
    }

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
        gpuWork();
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
