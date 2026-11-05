import fmod.fmod.FMOD_STUDIO_EVENT_CALLBACK;
import fmod.javafmod;
import fmod.javafmodJNI;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uso: FmodHarness <saida.txt> <tempos.txt>   (-Dpz.game=<pasta do jogo>, -Dpz.audible=1 para tocar de verdade)
 *
 * Segue a ordem real de zombie.core.fmod / fmod.fmod.FMODManager.init():
 *   javafmodJNI.init() (loadLibrary fmod, fmodstudio, fmodintegration64) -> FMOD_System_Create ->
 *   FMOD_System_Init(1024, 0, 131840) -> Set3DSettings(1,1,1) -> loadBank(ZomboidSound.strings, ZomboidMusic,
 *   ZomboidSound, ZomboidSoundMP, ...) -> CreateChannelGroup("InGameNonBank") -> listagem de eventos/VCAs/parametros.
 * Depois cria uma instancia de um evento dos bancos reais (media/sound/banks/Desktop/*.bank), toca, confere o
 * estado, registra um callback (que o FMOD chama de uma thread nativa), para e libera.
 *
 * Saida (sem enderecos, igual em x64 e ARM64): so o que e deterministico. Tempos e informacoes variaveis vao
 * para o arquivo de tempos.
 *
 * Nota (desvio do plano): as JNI do jogo nao expoem o ponteiro do sistema core antes de FMOD_System_Init, entao
 * FMOD_System_SetOutput(NOSOUND_NRT) nao e alcancavel daqui. O modo padrao abre o dispositivo normalmente e
 * silencia o grupo master (mute + volume 0); -Dpz.audible=1 nao silencia.
 */
public class FmodHarness {
    static PrintStream out;
    static PrintStream times;
    static boolean audible = "1".equals(System.getProperty("pz.audible"));
    static int failures;

    static void line(String k, Object v) {
        out.println(k + "=" + v);
    }

    static void check(String k, boolean ok) {
        line(k, ok ? "ok" : "FALHOU");
        if (!ok) failures++;
    }

    /** Registra o retorno de uma chamada FMOD: diferente de FMOD_OK (0) conta como falha. */
    static int rc(String k, int v) {
        line(k, v);
        if (v != 0) failures++;
        return v;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    interface Cond {
        boolean ok();
    }

    /** Roda System_Update a cada 20 ms (como o quadro do jogo) ate a condicao ou o limite. */
    static boolean pumpUntil(Cond c, long limitMs) {
        long end = System.nanoTime() + limitMs * 1_000_000L;
        while (System.nanoTime() < end) {
            javafmod.FMOD_System_Update();
            if (c.ok()) return true;
            sleep(20);
        }
        javafmod.FMOD_System_Update();
        return c.ok();
    }

    static void pump(long ms) {
        pumpUntil(() -> false, ms);
    }

    // ---- callbacks vindos de thread nativa ----
    static final AtomicInteger started = new AtomicInteger(), stopped = new AtomicInteger(), markers = new AtomicInteger();
    static final Map<Thread, Integer> cbThreads = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    static volatile Thread mainThread;

    static class Cb extends FMOD_STUDIO_EVENT_CALLBACK {
        void note() {
            cbThreads.merge(Thread.currentThread(), 1, Integer::sum);
        }

        @Override
        public void started(long instance) {
            note();
            started.incrementAndGet();
        }

        @Override
        public void stopped(long instance) {
            note();
            stopped.incrementAndGet();
        }

        @Override
        public void timelineMarker(long instance, String name, int position) {
            note();
            markers.incrementAndGet();
        }
    }

    static String bankPath(String game, String name) {
        return Paths.get(game, "media", "sound", "banks", "Desktop", name + ".bank").toString();
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.nanoTime();
        out = new PrintStream(Files.newOutputStream(Paths.get(a[0])), true, "UTF-8");
        times = new PrintStream(Files.newOutputStream(Paths.get(a[1])), true, "UTF-8");
        mainThread = Thread.currentThread();
        String game = System.getProperty("pz.game");
        line("mode", audible ? "audible" : "silent");

        // 1. carga das bibliotecas, exatamente como FMODManager (javafmodJNI.init)
        javafmodJNI.init();
        line("loadLibrary", "ok");

        // 2. criacao e inicializacao do sistema
        int rc = javafmod.FMOD_System_Create();
        line("System_Create", rc);
        if (rc != 0) fail("System_Create");
        rc = javafmod.FMOD_System_Init(1024, 0L, 131840L);
        line("System_Init", rc);
        if (rc != 0) fail("System_Init");
        rc("Set3DSettings", javafmod.FMOD_System_Set3DSettings(1f, 1f, 1f));
        long master = javafmod.FMOD_System_GetMasterChannelGroup();
        check("master_group_valid", master > 0);
        if (!audible) {
            rc("master_mute", javafmod.FMOD_ChannelGroup_SetMute(master, true));
            rc("master_volume0", javafmodJNI.FMOD_ChannelGroup_SetVolume(master, 0f));
        }

        // 3. bancos, na ordem de FMODManager.init (os que nao existem em disco falham igual nos dois lados)
        String[] banks = {"ZomboidSound.strings", "ZomboidMusic", "ZomboidSound", "ZomboidSoundMP", "Animal", "Broadcast",
                "Character", "Object", "Vehicle", "Weapon", "World", "Zombie"};
        int loaded = 0;
        for (String b : banks) {
            Path p = Paths.get(bankPath(game, b));
            long h = javafmod.FMOD_Studio_System_LoadBankFile(p.toString());
            boolean exists = Files.exists(p);
            line("loadBank." + b, exists ? (h > 0 ? "ok" : "erro " + h) : (h > 0 ? "inesperado" : "ausente"));
            if (exists && h > 0) loaded++;
            if (exists != (h > 0)) failures++;
        }
        line("banks_loaded", loaded);
        long group = javafmod.FMOD_System_CreateChannelGroup("InGameNonBank");
        check("channel_group_valid", group > 0);
        javafmodJNI.FMOD_Studio_System_FlushCommands();
        javafmod.FMOD_System_Update();

        // 4. listagem (initEvents/initVCA/initGlobalParameters)
        int nbanks = javafmodJNI.FMOD_Studio_System_GetBankCount();
        line("bank_count", nbanks);
        long[] bankList = new long[nbanks];
        int gbl = javafmodJNI.FMOD_Studio_System_GetBankList(bankList);   // devolve a quantidade, nao FMOD_OK
        line("GetBankList", gbl);
        check("GetBankList_count", gbl == nbanks);
        List<String> paths = new ArrayList<>();
        Map<String, Long> descByPath = new java.util.HashMap<>();
        int vcas = 0;
        for (long b : bankList) {
            int n = javafmodJNI.FMOD_Studio_Bank_GetEventCount(b);
            long[] ev = new long[n];
            if (n > 0) javafmodJNI.FMOD_Studio_Bank_GetEventList(b, ev);
            for (long e : ev) {
                String path = javafmodJNI.FMOD_Studio_EventDescription_GetPath(e);
                paths.add(path);
                descByPath.put(path, e);
            }
            vcas += javafmodJNI.FMOD_Studio_Bank_GetVCACount(b);
        }
        Collections.sort(paths);
        line("event_count", paths.size());
        line("vca_count", vcas);
        line("global_parameter_count", javafmodJNI.FMOD_Studio_System_GetParameterDescriptionCount());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size() && i < 5; i++) sb.append(paths.get(i)).append(';');
        line("first_events", sb);
        long sumLen = 0;
        for (String p : paths) sumLen += javafmodJNI.FMOD_Studio_EventDescription_GetLength(descByPath.get(p));
        line("sum_event_length_ms", sumLen);
        long busMaster = javafmod.FMOD_Studio_System_GetBus("bus:/");
        check("bus_master_valid", busMaster > 0);
        long busSfx = javafmod.FMOD_Studio_System_GetBus("bus:/NaoExiste");
        // ERRO ESPERADO: "bus:/NaoExiste" nao existe; a chamada tem de falhar (handle <= 0).
        check("bus_missing_is_error", busSfx <= 0);

        // 5. bool por valor e por ponteiro (setMute/getMute, setPaused/getPaused): os shims C++ -> C
        if (busMaster > 0) {
            rc("bus.volume.set", javafmod.FMOD_Studio_Bus_SetVolume(busMaster, 0.5f));
            float bv = javafmod.FMOD_Studio_Bus_GetVolume(busMaster);
            line("bus.volume.get", bv);
            check("bus.volume.get_value", Math.abs(bv - 0.5f) < 1e-6f);
            rc("bus.mute.set1", javafmod.FMOD_Studio_Bus_SetMute(busMaster, true));
            boolean m1 = javafmod.FMOD_Studio_Bus_GetMute(busMaster);
            line("bus.mute.get1", m1);
            check("bus.mute.get1_value", m1);
            rc("bus.mute.set0", javafmod.FMOD_Studio_Bus_SetMute(busMaster, false));
            boolean m0 = javafmod.FMOD_Studio_Bus_GetMute(busMaster);
            line("bus.mute.get0", m0);
            check("bus.mute.get0_value", !m0);
            rc("bus.paused.set1", javafmod.FMOD_Studio_Bus_SetPaused(busMaster, true));
            javafmodJNI.FMOD_Studio_System_FlushCommands();
            javafmod.FMOD_System_Update();             // setPaused do Studio so vale depois do update
            pump(200);
            boolean p1 = javafmod.FMOD_Studio_Bus_GetPaused(busMaster);
            line("bus.paused.get1", p1);
            // COMPORTAMENTO CONHECIDO DO JOGO (nao e falha da ponte): a JNI FMOD_Studio_Bus_GetPaused do jogo nao
            // chama Bus::getPaused (a dylib so importa Bus::setPaused; o x64 nativo tambem devolve false). Por isso o
            // valor lido nao e conferido aqui; a pausa e conferida pelo EventInstance (paused.* abaixo) e a
            // comparacao x64 x ARM64 exige o mesmo valor.
            rc("bus.paused.set0", javafmod.FMOD_Studio_Bus_SetPaused(busMaster, false));
            javafmodJNI.FMOD_Studio_System_FlushCommands();
            javafmod.FMOD_System_Update();
            boolean p0 = javafmod.FMOD_Studio_Bus_GetPaused(busMaster);
            line("bus.paused.get0", p0);
            check("bus.paused.get0_value", !p0);   // false e o valor certo apos retomar (e o unico que a JNI devolve)
            rc("bus.volume.restore", javafmod.FMOD_Studio_Bus_SetVolume(busMaster, 1f));
        } else {
            failures++;                                // bus_master_valid ja contou; o bloco nao pode passar em silencio
            line("bus_block", "PULADO (sem bus master)");
        }
        rc("SetNumListeners", javafmod.FMOD_Studio_SetNumListeners(1));
        javafmod.FMOD_Studio_Listener3D(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f);

        // 6. evento de teste: o primeiro (ordem de caminho) com duracao conhecida entre 3 e 20 s
        String chosen = null;
        long chosenLen = 0;
        String prefer = System.getProperty("pz.event");
        if (prefer != null && descByPath.containsKey(prefer)) {
            chosen = prefer;
            chosenLen = javafmodJNI.FMOD_Studio_EventDescription_GetLength(descByPath.get(prefer));
        } else {
            for (String p : paths) {
                long len = javafmodJNI.FMOD_Studio_EventDescription_GetLength(descByPath.get(p));
                if (len >= 3000 && len <= 20000 && !p.contains("Music")) {
                    chosen = p;
                    chosenLen = len;
                    break;
                }
            }
        }
        if (chosen == null) fail("nenhum evento de teste");
        line("event", chosen);
        line("event.length_ms", chosenLen);
        long desc = javafmod.FMOD_Studio_System_GetEvent(chosen);
        check("event_description_valid", desc > 0);
        line("event.instances.before", javafmodJNI.FMOD_Studio_EventDescription_GetInstanceCount(desc));
        rc("LoadEventSampleData", javafmodJNI.FMOD_Studio_LoadEventSampleData(desc));
        pump(300);

        long inst = javafmod.FMOD_Studio_System_CreateEventInstance(desc);
        check("instance_valid", inst > 0);
        line("event.instances.created", javafmodJNI.FMOD_Studio_EventDescription_GetInstanceCount(desc));
        Cb cb = new Cb();
        // STARTED (0x8) | STOPPED (0x20)
        rc("SetCallback", javafmod.FMOD_Studio_EventInstance_SetCallback(inst, cb, 0x28));
        rc("SetVolume", javafmod.FMOD_Studio_EventInstance_SetVolume(inst, 0.8f));
        float gv = javafmodJNI.FMOD_Studio_EventInstance_GetVolume(inst);
        line("GetVolume", gv);
        check("GetVolume_value", Math.abs(gv - 0.8f) < 1e-6f);
        rc("EventInstance3D", javafmod.FMOD_Studio_EventInstance3D(inst, 1f, 0f, 2f));
        check("paused.initial_false", !javafmodJNI.FMOD_Studio_EventInstance_GetPaused(inst));
        line("SetPaused1", callVoid(() -> javafmodJNI.FMOD_Studio_EventInstance_SetPaused(inst, true)));
        check("paused.set_true", javafmodJNI.FMOD_Studio_EventInstance_GetPaused(inst));
        callVoid(() -> javafmodJNI.FMOD_Studio_EventInstance_SetPaused(inst, false));
        check("paused.cleared_false", !javafmodJNI.FMOD_Studio_EventInstance_GetPaused(inst));

        javafmod.FMOD_Studio_StartEvent(inst);
        long tPlay = System.nanoTime();
        check("callback_started", pumpUntil(() -> started.get() >= 1, 3000));
        int st = javafmod.FMOD_Studio_GetPlaybackState(inst);
        // PLAYING 0, SUSTAINING 1, STOPPED 2, STARTING 3, STOPPING 4
        check("state_playing_after_start", st == 0 || st == 1 || st == 3);
        pump(audible ? 5000 : 2000);                 // toca por 2 s (5 s no modo audivel)
        long pos = javafmod.FMOD_Studio_GetTimelinePosition(inst);
        check("timeline_advanced", pos > 500);
        int st2 = javafmod.FMOD_Studio_GetPlaybackState(inst);
        check("state_playing_at_2s", st2 == 0 || st2 == 1);
        line("event.instances.playing", javafmodJNI.FMOD_Studio_EventDescription_GetInstanceCount(desc));
        times.println("play_ms=" + (System.nanoTime() - tPlay) / 1_000_000 + " timeline_pos_ms=" + pos);

        rc("Stop", javafmod.FMOD_Studio_EventInstance_Stop(inst, true));
        check("callback_stopped", pumpUntil(() -> stopped.get() >= 1, 3000));
        int st3 = javafmod.FMOD_Studio_GetPlaybackState(inst);
        line("state_after_stop", st3);
        rc("ReleaseEventInstance", javafmod.FMOD_Studio_ReleaseEventInstance(inst));
        check("instances_released", pumpUntil(() -> javafmodJNI.FMOD_Studio_EventDescription_GetInstanceCount(desc) == 0, 3000));

        // 7. varios ciclos: a thread nativa do FMOD tem de ser anexada uma unica vez (mesmo Thread Java em todos)
        for (int i = 0; i < 3; i++) {
            long in2 = javafmod.FMOD_Studio_System_CreateEventInstance(desc);
            javafmod.FMOD_Studio_EventInstance_SetCallback(in2, cb, 0x28);
            javafmod.FMOD_Studio_StartEvent(in2);
            int want = started.get() + 1;
            pumpUntil(() -> started.get() >= want, 3000);
            pump(150);
            javafmod.FMOD_Studio_EventInstance_Stop(in2, true);
            int wantS = stopped.get() + 1;
            pumpUntil(() -> stopped.get() >= wantS, 3000);
            javafmod.FMOD_Studio_ReleaseEventInstance(in2);
            pump(50);
        }
        line("callbacks.started", started.get());
        line("callbacks.stopped", stopped.get());
        check("callbacks_all_delivered", started.get() == 4 && stopped.get() == 4);
        boolean notMain = !cbThreads.containsKey(mainThread);
        check("callback_thread_not_main", notMain);
        line("callback_distinct_threads", cbThreads.size());
        check("callback_thread_attached_once", cbThreads.size() == 1);
        StringBuilder tn = new StringBuilder();
        for (Thread t : cbThreads.keySet()) tn.append(t.getName()).append(' ');
        times.println("callback_threads=" + tn);

        // 7b. marcador de timeline (como a musica do jogo): callback com (long, String, int) vindo da thread nativa
        long mdesc = javafmod.FMOD_Studio_System_GetEvent("event:/Music/Main");
        check("marker.event_found", mdesc > 0);
        if (mdesc > 0) {
            final List<String> seen = Collections.synchronizedList(new ArrayList<>());
            FMOD_STUDIO_EVENT_CALLBACK mcb = new FMOD_STUDIO_EVENT_CALLBACK() {
                @Override
                public void timelineMarker(long instance, String name, int position) {
                    seen.add(name + "@" + position);
                    cbThreads.merge(Thread.currentThread(), 1, Integer::sum);
                }
            };
            javafmod.FMOD_Studio_LoadEventSampleData(mdesc);
            pump(200);
            long mi = javafmod.FMOD_Studio_System_CreateEventInstance(mdesc);
            check("marker.instance_valid", mi > 0);
            // TIMELINE_MARKER = 0x800, a mascara que o SoundManager usa
            line("marker.SetCallback", javafmod.FMOD_Studio_EventInstance_SetCallback(mi, mcb, 0x800));
            javafmod.FMOD_Studio_StartEvent(mi);
            check("marker.delivered", pumpUntil(() -> !seen.isEmpty(), 3000));
            if (audible) pump(4000);                  // deixa a musica tocar
            line("marker.first", seen.isEmpty() ? "nenhum" : seen.get(0));
            javafmod.FMOD_Studio_EventInstance_Stop(mi, true);
            pump(100);
            javafmod.FMOD_Studio_ReleaseEventInstance(mi);
            check("callback_thread_still_one", cbThreads.size() == 1);
        }

        // 8. liberacao
        rc("StopAllEvents", busMaster > 0 ? javafmod.FMOD_Studio_Bus_StopAllEvents(busMaster, true) : -1);
        pump(100);
        rc("ChannelGroup_Release", javafmodJNI.FMOD_ChannelGroup_Release(group));

        times.println("total_ms=" + (System.nanoTime() - t0) / 1_000_000);
        line("failures", failures);
        out.flush();
        // o jogo nao chama FMOD_System_Close ao sair; o processo termina com threads do FMOD vivas
        System.out.flush();
        System.exit(failures == 0 ? 0 : 1);
    }

    static Object callVoid(Runnable r) {
        r.run();
        return "void";
    }

    static void fail(String why) {
        line("FATAL", why);
        out.flush();
        System.exit(3);
    }
}
