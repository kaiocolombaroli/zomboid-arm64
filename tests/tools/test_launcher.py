import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import make_manifest  # noqa: E402

LAUNCHER = os.path.join(ROOT, "launcher", "run-game.ps1")
BASE = os.path.join(ROOT, "build", "test-launcher")
REAL_OPTIONS = os.path.join(os.path.expanduser("~"), "Zomboid", "options.ini")
NAMES = (
    "lwjgl glfw lwjgl_opengl lwjgl_stb lwjgl_tinyfd jemalloc Lighting64 PZPopMan64 PZPathFind64 PZBullet64 "
    "PZClipper64 jassimp64 pzdarwin ZNetNoSteam64 ZNetJNI64 fmodintegration64 bink64 imgui-java64 RakNet64 "
    "steam_api64 fmod fmodstudio bink2w64"
).split()
JSON = {
    "mainClass": "zombie/gameStates/MainScreenState",
    "classpath": [".", "projectzomboid.jar"],
    "vmArgs": ["-Djava.awt.headless=true", "-Xmx3072m", "-Dzomboid.steam=1", "-Djava.library.path=win64/;."],
    "windows": {"6.1": {"vmArgs": ["-XX:+UseG1GC"]}, "10.0.17134": {"vmArgs": ["-XX:+UseZGC"]}},
}


def fake_dll(machine):
    """PE minimo (cabecalho MZ + PE\\0\\0 + Machine) so para o teste de arquitetura."""
    d = bytearray(256)
    d[0:2] = b"MZ"
    struct.pack_into("<I", d, 0x3C, 0x80)
    d[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", d, 0x84, machine)
    return bytes(d)


def sha(b):
    return hashlib.sha256(b).hexdigest()


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = os.path.join(BASE, self.id().split(".")[-1])
        shutil.rmtree(self.tmp, ignore_errors=True)
        self.game = os.path.join(self.tmp, "game")
        self.dist = os.path.join(self.tmp, "dist")
        self.cache = os.path.join(self.tmp, "cache")
        self.user_opt = os.path.join(self.tmp, "user-options.ini")
        os.makedirs(self.game)
        os.makedirs(self.dist)
        with open(os.path.join(self.game, "ProjectZomboid64.json"), "w") as f:
            json.dump(JSON, f)
        self.jar_bytes = b"fake-jar-content"
        self.jar = os.path.join(self.game, "projectzomboid.jar")
        with open(self.jar, "wb") as f:
            f.write(self.jar_bytes)
        for n in NAMES:
            with open(os.path.join(self.dist, n + ".dll"), "wb") as f:
                f.write(fake_dll(0xAA64))
        self.manifest()

    def manifest(self):
        m = make_manifest.build(self.dist, self.jar, os.path.join(self.tmp, "nao-existe.txt"))
        with open(os.path.join(self.dist, "manifest.json"), "w") as f:
            json.dump(m, f)

    def run_launcher(self, *extra, game=None, dist=None):
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", LAUNCHER, "-DryRun",
               "-GameDir", game or self.game, "-DistDir", dist or self.dist, "-CacheDir", self.cache,
               "-UserOptions", self.user_opt] + list(extra)
        return subprocess.run(cmd, capture_output=True, text=True, timeout=180)

    def cmdline(self, r):
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        m = re.search(r"^Cmd: (.*)$", r.stdout, re.M)
        self.assertTrue(m, r.stdout)
        return m.group(1)

    def test_cmdline_has_args(self):
        c = self.cmdline(self.run_launcher())
        for a in ("-nosteam", "-cachedir=", "-XX:+UseZGC", "-Xmx3072m"):
            self.assertIn(a, c)
        self.assertNotIn("-Xms", c)
        self.assertNotIn("-agentlib", c)
        self.assertIn("pzarm-agent.jar=fps,dir=", c)   # agente fps por padrao
        self.assertNotIn("UseG1GC", c)

    def test_sound_default(self):
        c = self.cmdline(self.run_launcher())
        self.assertNotIn("-nosound", c)
        self.assertIn("-nosteam", c)

    def test_nosound_flag(self):
        c = self.cmdline(self.run_launcher("-NoSound"))
        self.assertIn(" -nosound ", c)
        self.assertIn("-nosteam", c)
        self.assertIn("-cachedir=", c)

    def test_refuses_x64_fmod(self):
        for n in ("fmod.dll", "fmodstudio.dll"):
            with open(os.path.join(self.dist, n), "wb") as f:
                f.write(fake_dll(0x8664))
            self.manifest()
            r = self.run_launcher()
            self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
            self.assertIn(n, r.stderr)
            self.assertIn("x64", r.stderr)
            with open(os.path.join(self.dist, n), "wb") as f:
                f.write(fake_dll(0xAA64))

    def test_missing_fmod_message(self):
        for n in ("fmod.dll", "fmodstudio.dll"):
            os.remove(os.path.join(self.dist, n))
            r = self.run_launcher()
            self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
            self.assertIn(n, r.stderr)
            self.assertIn("third-party/fmod", r.stderr)
            with open(os.path.join(self.dist, n), "wb") as f:
                f.write(fake_dll(0xAA64))

    def test_env_and_pzset(self):
        r = self.run_launcher("-PzSet", "PerformanceSettings.fboRenderChunk=false", "-Env", "MESA_GLTHREAD=false,MESA_SHADER_CACHE_DISABLE=true")
        c = self.cmdline(r)
        self.assertIn(",pz=PerformanceSettings.fboRenderChunk=false", c)
        self.assertIn("MESA_GLTHREAD=false MESA_SHADER_CACHE_DISABLE=true", r.stdout)
        self.assertIn("PzSet (agente Java, aplicado ao fim do <clinit> de PerformanceSettings): PerformanceSettings.fboRenderChunk=false", r.stdout)

    def agent_arg(self, c):
        m = re.search(r"pzarm-agent\.jar=(\S+)", c)
        self.assertTrue(m, c)
        return m.group(1)

    def test_default_is_fbo_glsync_collapse(self):
        r = self.run_launcher()
        a = self.agent_arg(self.cmdline(r))
        self.assertIn(",pz=PerformanceSettings.fboRenderChunk=true", a)
        self.assertTrue(a.endswith(",collapse=player,vbosync=sync,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertNotIn("renderfix", a)
        self.assertIn(",vbosync=sync", a)
        self.assertIn("fboRenderChunk efetivo: true (origem: padrao)", r.stdout)
        self.assertIn("renderfix efetivo: nenhum (origem: padrao do modo FBO)", r.stdout)
        self.assertIn("collapse efetivo: player (origem: padrao do modo FBO)", r.stdout)

    def test_no_fbo_chunk_restores_old_path(self):
        r = self.run_launcher("-NoFboChunk")
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",pz=PerformanceSettings.fboRenderChunk=false,vbosync=sync,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertNotIn("renderfix", a)
        self.assertNotIn("collapse", a)
        self.assertIn("fboRenderChunk efetivo: false (origem: opcao -NoFboChunk)", r.stdout)
        self.assertIn("renderfix efetivo: nenhum", r.stdout)
        self.assertIn("collapse efetivo: nenhum", r.stdout)
        self.cmdline(self.run_launcher("-NoFboChunk", "-NoAgent"))

    def test_use_fbo_chunk_is_accepted_without_effect(self):
        base = self.agent_arg(self.cmdline(self.run_launcher()))
        r = self.run_launcher("-UseFboChunk")
        self.assertEqual(self.agent_arg(self.cmdline(r)), base)
        self.assertIn("fboRenderChunk efetivo: true (origem: padrao)", r.stdout)
        self.cmdline(self.run_launcher("-UseFboChunk", "-NoAgent"))
        self.assertEqual(self.run_launcher("-NoFboChunk", "-UseFboChunk").returncode, 2)

    def test_render_fix_override(self):
        a = self.agent_arg(self.cmdline(self.run_launcher("-RenderFix", "flush")))
        self.assertIn(",renderfix=flush,flushevery=1,collapse=player", a)

    def test_no_collapse_fix(self):
        r = self.run_launcher("-NoCollapseFix")
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",vbosync=sync,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertNotIn("collapse", a)
        self.assertIn("collapse efetivo: nenhum (origem: opcao -NoCollapseFix)", r.stdout)
        self.assertIn("renderfix efetivo: nenhum (origem: padrao do modo FBO)", r.stdout)

    def test_render_fix_none_and_override(self):
        r = self.run_launcher("-RenderFix", "none")
        a = self.agent_arg(self.cmdline(r))
        self.assertNotIn("renderfix", a)
        self.assertTrue(a.endswith(",collapse=player,vbosync=sync,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertIn("renderfix efetivo: nenhum (origem: opcao -RenderFix none)", r.stdout)
        r = self.run_launcher("-RenderFix", "flushchunk")
        a = self.agent_arg(self.cmdline(r))
        self.assertIn(",renderfix=flushchunk,collapse=player", a)
        self.assertNotIn("flushtex", a)
        self.assertIn("renderfix efetivo: flushchunk (origem: opcao -RenderFix)", r.stdout)
        a = self.agent_arg(self.cmdline(self.run_launcher("-RenderFix", "none", "-NoCollapseFix")))
        self.assertNotIn("renderfix", a)
        self.assertNotIn("collapse", a)

    def test_renderfix_and_nocollapse_conflict_with_no_fbo(self):
        for extra in (["-RenderFix", "flushtex"], ["-RenderFix", "none"], ["-NoCollapseFix"]):
            r = self.run_launcher("-NoFboChunk", *extra)
            self.assertEqual(r.returncode, 2, extra)
            self.assertIn("-NoFboChunk", r.stderr)

    def test_explicit_pzset_false_undoes_fbo_mode(self):
        r = self.run_launcher("-PzSet", "PerformanceSettings.fboRenderChunk=false")
        a = self.agent_arg(self.cmdline(r))
        self.assertEqual(a.count("fboRenderChunk"), 1)
        self.assertNotIn("renderfix", a)
        self.assertNotIn("collapse", a)
        self.assertIn("fboRenderChunk efetivo: false (origem: -PzSet explicito)", r.stdout)
        for m in ("flushchunk", "flushtexchunk"):
            self.assertEqual(self.run_launcher("-PzSet", "PerformanceSettings.fboRenderChunk=false", "-RenderFix", m).returncode, 2)
        a = self.agent_arg(self.cmdline(self.run_launcher("-PzSet", "PerformanceSettings.fboRenderChunk=false", "-RenderFix", "flush")))
        self.assertIn(",renderfix=flush,flushevery=1", a)
        self.assertNotIn("collapse", a)

    def test_explicit_pzset_true_is_the_default(self):
        r = self.run_launcher("-PzSet", "PerformanceSettings.fboRenderChunk=true")
        a = self.agent_arg(self.cmdline(r))
        self.assertEqual(a.count("fboRenderChunk"), 1)
        self.assertTrue(a.endswith(",collapse=player,vbosync=sync,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertIn("fboRenderChunk efetivo: true (origem: -PzSet explicito)", r.stdout)

    def test_noagent_header_not_applicable(self):
        r = self.run_launcher("-NoAgent")
        self.cmdline(r)
        self.assertIn("fboRenderChunk efetivo: nao aplicavel", r.stdout)
        self.assertIn("renderfix efetivo: nao aplicavel", r.stdout)
        self.assertIn("collapse efetivo: nao aplicavel", r.stdout)
        self.assertIn("vbosync efetivo: nao aplicavel (-NoAgent)", r.stdout)
        self.cmdline(self.run_launcher("-NoAgent", "-NoFboChunk"))
        self.assertEqual(self.run_launcher("-NoAgent", "-NoCollapseFix").returncode, 2)

    def test_vbosync_default_sync_in_both_modes(self):
        for extra in ([], ["-NoFboChunk"]):
            r = self.run_launcher(*extra)
            a = self.agent_arg(self.cmdline(r))
            self.assertEqual(a.count("vbosync=sync"), 1)
            self.assertIn("vbosync efetivo: sync (origem: padrao)", r.stdout)

    def test_new_defaults_glsync_swapfence_in_both_modes(self):
        for extra in ([], ["-NoFboChunk"]):
            r = self.run_launcher(*extra)
            a = self.agent_arg(self.cmdline(r))
            self.assertTrue(a.endswith(",glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
            self.assertEqual(a.count("glsync"), 3)
            self.assertNotIn("swapfinish", a)
            self.assertNotIn("renderfix", a)
            self.assertIn("glsync efetivo: swapfence,uploadflush,otherflush (origem: padrao)", r.stdout)

    def test_glsync_swapfence_accepted_and_swapfinish_fallback(self):
        r = self.run_launcher("-GlSync", "SwapFence")
        self.assertEqual(r.returncode, 0, r.stderr)
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",glsync=swapfence"), a)
        self.assertIn("glsync efetivo: swapfence (origem: opcao -GlSync)", r.stdout)
        a = self.agent_arg(self.cmdline(self.run_launcher("-GlSync", "swapfinish")))
        self.assertTrue(a.endswith(",glsync=swapfinish"), a)

    def test_glsync_none_disables(self):
        r = self.run_launcher("-GlSync", "none")
        a = self.agent_arg(self.cmdline(r))
        self.assertNotIn("glsync", a)
        self.assertIn("glsync efetivo: nenhum (origem: opcao -GlSync none)", r.stdout)
        self.assertEqual(self.run_launcher("-GlSync", "NONE,swapfinish").returncode, 2)
        self.assertEqual(self.run_launcher("-NoAgent", "-GlSync", "none").returncode, 2)

    def test_old_behavior_via_options(self):
        r = self.run_launcher("-GlSync", "none", "-RenderFix", "flushtex", "-VboSync", "sync")
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",renderfix=flushtex,collapse=player,vbosync=sync"), a)
        self.assertIn("renderfix efetivo: flushtex (origem: opcao -RenderFix)", r.stdout)
        self.assertIn("vbosync efetivo: sync (origem: opcao -VboSync)", r.stdout)

    def test_vbosync_invalidate(self):
        r = self.run_launcher("-VboSync", "invalidate")
        a = self.agent_arg(self.cmdline(r))
        self.assertIn(",vbosync=invalidate", a)
        self.assertNotIn("vbosync=sync", a)
        self.assertIn("vbosync efetivo: invalidate (origem: opcao -VboSync)", r.stdout)
        a = self.agent_arg(self.cmdline(self.run_launcher("-NoFboChunk", "-VboSync", "sync")))
        self.assertIn(",vbosync=sync", a)

    def test_vbosync_case_insensitive_is_normalized(self):
        a = self.agent_arg(self.cmdline(self.run_launcher("-VboSync", "INVALIDATE")))
        self.assertIn(",vbosync=invalidate", a)

    def test_noagent_has_no_vbosync_in_cmdline(self):
        self.assertNotIn("vbosync=", self.cmdline(self.run_launcher("-NoAgent")))

    def test_uidiag_switch(self):
        a = self.agent_arg(self.cmdline(self.run_launcher("-UiDiag")))
        self.assertTrue(a.endswith(",vbosync=sync,uidiag,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertNotIn("uidiag", self.agent_arg(self.cmdline(self.run_launcher())))
        self.assertEqual(self.run_launcher("-NoAgent", "-UiDiag").returncode, 2)

    def test_uimodeldiag_switch(self):
        a = self.agent_arg(self.cmdline(self.run_launcher("-UiModelDiag")))
        self.assertTrue(a.endswith(",vbosync=sync,uimodeldiag,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        a = self.agent_arg(self.cmdline(self.run_launcher("-UiDiag", "-UiModelDiag")))
        self.assertTrue(a.endswith(",vbosync=sync,uidiag,uimodeldiag,glsync=swapfence,glsync=uploadflush,glsync=otherflush"), a)
        self.assertNotIn("uimodeldiag", self.agent_arg(self.cmdline(self.run_launcher())))
        self.assertEqual(self.run_launcher("-NoAgent", "-UiModelDiag").returncode, 2)

    def test_glsync_list_and_header(self):
        r = self.run_launcher("-GlSync", "SwapFinish,modelfinish,swapfinish")
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",glsync=swapfinish,glsync=modelfinish"), a)
        self.assertIn("glsync efetivo: swapfinish,modelfinish (origem: opcao -GlSync)", r.stdout)
        a = self.agent_arg(self.cmdline(self.run_launcher("-GlSync", "swapflush", "-UiModelDiag")))
        self.assertTrue(a.endswith(",uimodeldiag,glsync=swapflush"), a)

    def test_glsync_upload_and_clear_values(self):
        r = self.run_launcher("-GlSync", "UploadFinish,uploadflush,uploadcount,clearfinish,clearflush,swapfinish")
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",glsync=uploadfinish,glsync=uploadflush,glsync=uploadcount,glsync=clearfinish,glsync=clearflush,glsync=swapfinish"), a)
        self.assertIn("glsync efetivo: uploadfinish,uploadflush,uploadcount,clearfinish,clearflush,swapfinish (origem: opcao -GlSync)", r.stdout)
        a = self.agent_arg(self.cmdline(self.run_launcher("-NoFboChunk", "-GlSync", "uploadcount")))
        self.assertTrue(a.endswith(",glsync=uploadcount"), a)
        self.assertNotIn("swapfinish", a)

    def test_glsync_dirty_all_and_other_values(self):
        r = self.run_launcher("-GlSync", "SwapFinish,uploadflush,otherflush")
        a = self.agent_arg(self.cmdline(r))
        self.assertTrue(a.endswith(",glsync=swapfinish,glsync=uploadflush,glsync=otherflush"), a)
        a = self.agent_arg(self.cmdline(self.run_launcher("-GlSync", "uploadflushall,uploadfinishall,otherfinish")))
        self.assertTrue(a.endswith(",glsync=uploadflushall,glsync=uploadfinishall,glsync=otherfinish"), a)

    def test_glsync_errors(self):
        r = self.run_launcher("-NoAgent", "-GlSync", "swapfinish")
        self.assertEqual(r.returncode, 2)
        self.assertIn("-GlSync", r.stderr)
        r = self.run_launcher("-GlSync", "foo")
        self.assertEqual(r.returncode, 2)
        self.assertIn("-GlSync invalido", r.stderr)
        r = self.run_launcher("-NoAgent")
        self.assertIn("glsync efetivo: nao aplicavel (-NoAgent)", r.stdout)

    def read_cache_opt(self):
        with open(os.path.join(self.cache, "options.ini"), encoding="utf-8") as f:
            return f.read()

    def test_resolution_and_dpi_default_unchanged(self):
        with open(self.user_opt, "w", encoding="utf-8", newline="") as f:
            f.write("width=1920\r\nheight=1280\r\nfullScreen=false\r\n")
        r = self.run_launcher()
        self.cmdline(r)
        self.assertIn("resolucao: 1920x1280 (origem: copia do options.ini do usuario no cache)", r.stdout)
        self.assertIn("dpi: nenhum (padrao)", r.stdout)
        self.assertNotIn("__COMPAT_LAYER", r.stdout)
        r = self.run_launcher()   # 2a execucao: o valor vem do cache
        self.assertIn("resolucao: 1920x1280 (origem: cache\\options.ini de execucao anterior)", r.stdout)
        opt = self.read_cache_opt()
        self.assertIn("width=1920", opt)
        self.assertIn("height=1280", opt)

    def test_resolution_writes_cache_only_and_sets_dpi(self):
        original = "width=2880\r\nheight=1920\r\nfullScreen=false\r\n"
        with open(self.user_opt, "w", encoding="utf-8", newline="") as f:
            f.write(original)
        r = self.run_launcher("-Resolution", "1600x900")
        self.cmdline(r)
        opt = self.read_cache_opt()
        self.assertIn("width=1600", opt)
        self.assertIn("height=900", opt)
        self.assertNotIn("width=2880", opt)
        self.assertIn("fullScreen=false", opt)
        with open(self.user_opt, encoding="utf-8", newline="") as f:
            self.assertEqual(f.read(), original)   # nunca mexe no options.ini do usuario
        self.assertIn("resolucao: 1600x900 (origem: opcao -Resolution", r.stdout)
        self.assertIn("dpi: DPIUNAWARE (origem: opcao -Resolution)", r.stdout)
        self.assertIn("__COMPAT_LAYER=DPIUNAWARE", r.stdout)

    def test_resolution_appends_missing_keys(self):
        r = self.run_launcher("-Resolution", "1920x1280")
        self.cmdline(r)
        opt = self.read_cache_opt()
        self.assertRegex(opt, r"(?m)^width=1920\r?$")
        self.assertRegex(opt, r"(?m)^height=1280\r?$")

    def test_framerate_writes_cache_only(self):
        original = "frameRate=60\r\nwidth=1920\r\n"
        with open(self.user_opt, "w", encoding="utf-8", newline="") as f:
            f.write(original)
        r = self.run_launcher("-FrameRate", "30")
        self.cmdline(r)
        opt = self.read_cache_opt()
        self.assertIn("frameRate=30", opt)
        self.assertNotIn("frameRate=60", opt)
        self.assertIn("width=1920", opt)
        with open(self.user_opt, encoding="utf-8", newline="") as f:
            self.assertEqual(f.read(), original)
        self.assertIn("framerate: 30 (origem: opcao -FrameRate", r.stdout)
        self.assertIn("resolucao: nao alterada (padrao)", r.stdout)
        self.assertIn("dpi: nenhum (padrao)", r.stdout)   # -FrameRate nao mexe no dpi

    def test_framerate_default_and_append_and_invalid(self):
        with open(self.user_opt, "w", encoding="utf-8", newline="") as f:
            f.write("frameRate=60\r\n")
        r = self.run_launcher()
        self.cmdline(r)
        self.assertIn("framerate: 60 (origem: copia do options.ini do usuario no cache)", r.stdout)
        self.assertIn("frameRate=60", self.read_cache_opt())
        r = self.run_launcher("-FrameRate", "30")
        r = self.run_launcher()
        self.assertIn("framerate: 30 (origem: cache\\options.ini de execucao anterior)", r.stdout)
        shutil.rmtree(self.cache, ignore_errors=True)
        with open(self.user_opt, "w", encoding="utf-8", newline="") as f:
            f.write("width=1920\r\n")
        self.assertIn("framerate: nao alterado (padrao)", self.run_launcher().stdout)
        shutil.rmtree(self.cache, ignore_errors=True)
        os.remove(self.user_opt)
        self.cmdline(self.run_launcher("-FrameRate", "144"))
        self.assertRegex(self.read_cache_opt(), r"(?m)^frameRate=144\r?$")
        for bad in ("9", "241", "0", "-5"):
            r = self.run_launcher("-FrameRate", bad)
            self.assertEqual(r.returncode, 2, bad)
            self.assertIn("-FrameRate invalido", r.stderr)
        self.assertNotEqual(self.run_launcher("-FrameRate", "abc").returncode, 0)

    def test_resolution_invalid(self):
        for bad in ("1920", "19x1280", "1920x12", "axb", "1920X1280", "12345x1280"):
            r = self.run_launcher("-Resolution", bad)
            self.assertEqual(r.returncode, 2, bad)
            self.assertIn("-Resolution invalido", r.stderr)

    def test_dpiunaware_only_and_env_respected(self):
        with open(self.user_opt, "w", encoding="utf-8", newline="") as f:
            f.write("width=2880\r\nheight=1920\r\n")
        r = self.run_launcher("-DpiUnaware")
        self.cmdline(r)
        self.assertIn("dpi: DPIUNAWARE (origem: opcao -DpiUnaware)", r.stdout)
        self.assertIn("resolucao: 2880x1920 (origem: copia do options.ini do usuario no cache)", r.stdout)
        self.assertIn("width=2880", self.read_cache_opt())
        r = self.run_launcher("-Resolution", "1600x900", "-Env", "__COMPAT_LAYER=HIGHDPIAWARE")
        self.cmdline(r)
        self.assertIn("__COMPAT_LAYER=HIGHDPIAWARE", r.stdout)
        self.assertNotIn("DPIUNAWARE", r.stdout)
        self.assertIn("dpi: HIGHDPIAWARE (origem: -Env explicito", r.stdout)

    def test_stutterdiag_and_gclog(self):
        a = self.agent_arg(self.cmdline(self.run_launcher("-StutterDiag")))
        self.assertTrue(a.endswith(",glsync=swapfence,glsync=uploadflush,glsync=otherflush,stutterdiag"), a)
        self.assertNotIn("stutterdiag", self.agent_arg(self.cmdline(self.run_launcher())))
        self.assertEqual(self.run_launcher("-NoAgent", "-StutterDiag").returncode, 2)
        c = self.cmdline(self.run_launcher("-GcLog"))
        self.assertRegex(c, r"-Xlog:gc\*,safepoint:file=\S*logs\\gc-\d{8}-\d{6}\.log:uptime,time,level,tags")
        self.assertNotIn("-Xlog", self.cmdline(self.run_launcher()))
        self.assertIn("-Xlog:gc*", self.cmdline(self.run_launcher("-NoAgent", "-GcLog")))

    def test_vbosync_none_omits(self):
        r = self.run_launcher("-VboSync", "none")
        a = self.agent_arg(self.cmdline(r))
        self.assertNotIn("vbosync", a)
        self.assertIn("vbosync efetivo: nenhum (origem: opcao -VboSync none)", r.stdout)

    def test_vbosync_conflicts(self):
        for v in ("sync", "invalidate", "none"):
            r = self.run_launcher("-NoAgent", "-VboSync", v)
            self.assertEqual(r.returncode, 2, v)
            self.assertIn("-VboSync", r.stderr)
        self.assertNotEqual(self.run_launcher("-VboSync", "xyz").returncode, 0)

    def test_render_diag_args(self):
        c = self.cmdline(self.run_launcher("-RenderDiag", "-RenderDiagEvery", "8"))
        self.assertIn(",renderdiag,diagevery=8,collapse=player", c)
        self.assertNotIn("renderfix", c)
        c = self.cmdline(self.run_launcher("-NoFboChunk", "-RenderDiag"))
        self.assertIn(",renderdiag,diagevery=64", c)
        self.assertNotIn("renderfix", c)

    def test_render_fix_modes(self):
        for m in ("rebind", "flush", "uniforms", "dirty", "dirtyprog", "flushtex", "flushchunk", "flushtexchunk"):
            c = self.cmdline(self.run_launcher("-RenderFix", m))
            self.assertIn(",renderfix=" + m, c)
            self.assertNotIn("renderdiag", c)
            self.assertEqual(c.count("renderfix"), 1)
        self.assertNotEqual(self.run_launcher("-RenderFix", "xyz").returncode, 0)
        self.assertEqual(self.run_launcher("-NoAgent", "-RenderFix", "flush").returncode, 2)
        self.assertEqual(self.run_launcher("-NoAgent", "-RenderDiag").returncode, 2)

    def test_render_flush_every(self):
        c = self.cmdline(self.run_launcher("-RenderFix", "flush", "-RenderFlushEvery", "64"))
        self.assertIn(",renderfix=flush,flushevery=64", c)
        c = self.cmdline(self.run_launcher("-RenderFix", "flush"))
        self.assertIn(",renderfix=flush,flushevery=1", c)
        for bad in ("0", "-3", "abc"):
            self.assertNotEqual(self.run_launcher("-RenderFix", "flush", "-RenderFlushEvery", bad).returncode, 0)
        self.assertEqual(self.run_launcher("-RenderFix", "rebind", "-RenderFlushEvery", "8").returncode, 2)
        for m in ("flushtex", "flushchunk", "flushtexchunk"):
            self.assertEqual(self.run_launcher("-RenderFix", m, "-RenderFlushEvery", "8").returncode, 2)
            self.assertNotIn("flushevery", self.cmdline(self.run_launcher("-RenderFix", m)))

    def test_pzset_true_no_duplicate_and_noagent(self):
        c = self.cmdline(self.run_launcher("-PzSet", "PerformanceSettings.fboRenderChunk=true"))
        self.assertEqual(c.count("fboRenderChunk"), 1)
        self.assertIn("fboRenderChunk=true", c)
        r = self.run_launcher("-NoAgent")
        self.assertNotIn("pzarm-agent", self.cmdline(r))
        self.assertIn("nao aplicavel", r.stdout)
        self.assertNotIn("render-diag", r.stdout)

    def test_pzset_allowlist_and_noagent(self):
        r = self.run_launcher("-PzSet", "PerformanceSettings.lockFps=1")
        self.assertEqual(r.returncode, 2)
        r = self.run_launcher("-NoAgent", "-PzSet", "PerformanceSettings.useFbos=false")
        self.assertEqual(r.returncode, 2)
        r = self.run_launcher("-Env", "1ruim=x")
        self.assertEqual(r.returncode, 2)

    def test_noagent(self):
        c = self.cmdline(self.run_launcher("-NoAgent"))
        self.assertNotIn("pzarm-agent", c)

    def test_javaagent_optional(self):
        agent = os.path.join(self.tmp, "agent.jar")
        open(agent, "wb").close()
        c = self.cmdline(self.run_launcher("-JavaAgent", agent))
        self.assertIn("-javaagent:" + agent, c)

    def test_library_path_only_dist(self):
        c = self.cmdline(self.run_launcher())
        for prop in ("java.library.path", "org.lwjgl.librarypath"):
            vals = re.findall(r"-D%s=(\S+)" % re.escape(prop), c)
            self.assertEqual(vals, [self.dist], c)
        self.assertNotIn("win64", c)

    def test_refuses_game_dir_in_path(self):
        r = self.run_launcher(dist=self.game)
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("pasta do jogo", r.stderr)
        r = self.run_launcher(dist=os.path.join(self.game, "dist"))
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)

    def test_refuses_jar_mismatch(self):
        with open(self.jar, "wb") as f:
            f.write(b"outro-jar")
        r = self.run_launcher()
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("jar", r.stderr)

    def test_refuses_x64_in_dist(self):
        with open(os.path.join(self.dist, "bink64.dll"), "wb") as f:
            f.write(fake_dll(0x8664))
        self.manifest()
        r = self.run_launcher()
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("bink64.dll", r.stderr)
        self.assertIn("x64", r.stderr)

    def test_refuses_extra_x64_in_dist(self):
        with open(os.path.join(self.dist, "extra.dll"), "wb") as f:
            f.write(fake_dll(0x8664))
        self.manifest()
        r = self.run_launcher()
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("extra.dll", r.stderr)

    def test_missing_dll(self):
        os.remove(os.path.join(self.dist, "glfw.dll"))
        r = self.run_launcher()
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("glfw.dll", r.stderr)

    def test_dll_differs_from_manifest(self):
        with open(os.path.join(self.dist, "fmod.dll"), "ab") as f:
            f.write(b"x")
        r = self.run_launcher()
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("fmod.dll", r.stderr)

    def test_missing_game_is_env_error(self):
        r = self.run_launcher(game=os.path.join(self.tmp, "nao-existe"))
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)

    def test_options_created_from_user_copy(self):
        src = b"foo=1\r\ndoContainerOutline=true\r\nbar=2\r\n"
        with open(self.user_opt, "wb") as f:
            f.write(src)
        r = self.run_launcher()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(os.path.join(self.cache, "options.ini"), "rb") as f:
            out = f.read()
        self.assertEqual(out, b"foo=1\r\ndoContainerOutline=false\r\nbar=2\r\n")
        with open(self.user_opt, "rb") as f:
            self.assertEqual(f.read(), src)

    def test_options_line_added_when_missing(self):
        os.makedirs(self.cache)
        with open(os.path.join(self.cache, "options.ini"), "wb") as f:
            f.write(b"foo=1\r\nbar=2")
        self.assertEqual(self.run_launcher().returncode, 0)
        with open(os.path.join(self.cache, "options.ini"), "rb") as f:
            self.assertEqual(f.read(), b"foo=1\r\nbar=2\r\ndoContainerOutline=false\r\n")

    def test_options_created_without_source(self):
        self.assertEqual(self.run_launcher().returncode, 0)
        with open(os.path.join(self.cache, "options.ini"), "rb") as f:
            self.assertEqual(f.read(), b"doContainerOutline=false\r\n")

    def test_real_user_options_untouched(self):
        if not os.path.exists(REAL_OPTIONS):
            self.skipTest("options.ini do usuario ausente")
        with open(REAL_OPTIONS, "rb") as fh:
            before = fh.read()
        real_user_dir = os.path.dirname(REAL_OPTIONS)
        listing = sorted(os.listdir(real_user_dir))
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", LAUNCHER, "-DryRun",
               "-GameDir", self.game, "-DistDir", self.dist, "-CacheDir", self.cache]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(os.path.join(self.cache, "options.ini"), "rb") as f:
            self.assertIn(b"doContainerOutline=false", f.read())
        with open(REAL_OPTIONS, "rb") as fh:
            self.assertEqual(fh.read(), before)
        self.assertEqual(sorted(os.listdir(real_user_dir)), listing)


class ManifestTests(unittest.TestCase):
    def test_game_version_null_without_file(self):
        self.assertIsNone(make_manifest.game_version(os.path.join(BASE, "nao-existe", "version.txt")))


if __name__ == "__main__":
    unittest.main()
