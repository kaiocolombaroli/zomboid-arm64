# Project Zomboid on native ARM64 (Windows on ARM)

[Português](README.md)

An experimental port of Project Zomboid 42.21 (Build 42, single player) that runs on a native ARM64 JVM on Windows on ARM, with no x64 emulation inside the game process.

**Unofficial project.** It has no affiliation with, and no endorsement from, The Indie Stone. Project Zomboid belongs to its owners. This repository **does not contain the game or any file from it**: every user must own the game on their own Steam account and fetch the inputs themselves (see "Step by step"). It does not contain FMOD either.

## What it is

Project Zomboid for Windows ships x64 native DLLs (physics, lighting, pathfinding, sound and so on). On an ARM64 machine they only run through emulation. This project replaces that with:

- a JDK 25 ARM64 JVM (Microsoft Build of OpenJDK) and LWJGL 3.4.1 ARM64;
- the native libraries from the **macOS** build of the game (the macOS depot has `arm64` code), loaded by a custom Mach-O loader plus a layer that implements what they expect from libSystem and libc++;
- ARM64 bridge DLLs that export the `Java_*` methods the game expects;
- stub DLLs for networking, Steam, Bink and imgui, and the official ARM64 FMOD DLLs for sound;
- a Java agent (`pzarm-agent.jar`) that adjusts the game's rendering (a workaround for a Mesa D3D12 texture bug and a partial workaround for a wall-collapsing behavior).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Performance

Measured on a single device (Surface Pro X, SQ1, 8 GB, Adreno 680), one short session per version, with non-identical paths: about 3x the FPS in the world (14.5 FPS on emulated x64 versus 43.3 FPS on ARM64; [reports/m4-resultado.md](reports/m4-resultado.md), in Portuguese). Loading the world was about 1.5x faster. This is a small, uncontrolled measurement; your results may differ. The game's 60 FPS cap may have limited ARM64. The FBO mode (the launcher's current default) was not measured separately in that comparison.

## Requirements

- Windows 11 on ARM (ARM64), for example a Surface Pro X. Tested on 1 machine.
- 8 GB of RAM or more (on 8 GB free memory gets close to the limit; close other programs).
- Project Zomboid **42.21** installed from Steam (Windows version), on your account. Other versions were not tested, and the launcher refuses a `projectzomboid.jar` different from the one `dist/` was generated for (exit code 3).
- Access to your Steam account to download macOS depot `108602` (app 108600 has a macOS depot).
- A free FMOD account (fmod.com) to download FMOD Studio API 2.03.09 for Windows. FMOD is **not** redistributed here.
- Microsoft Store: "OpenCL, OpenGL, and Vulkan Compatibility Pack" (gives Windows on ARM OpenGL through D3D12).
- PowerShell 5.1 or 7, and Python 3 on PATH.
- About 3 GB of free disk space (estimate) for the JDK, toolchain and build.

## Step by step (from scratch to running the game)

1. **Clone** this repository and open PowerShell in its folder.
2. **Install the game** from Steam (Project Zomboid 42.21, Windows). It is only read; nothing is written to the game folder. If it lives elsewhere, set `$env:PZ_GAME_DIR` or pass `-GameDir` to the scripts.
3. **Download macOS depot 108602** with your Steam account (the `.dylib` files are the only macOS part used). Two options:
   - Steam console (`steam://open/console`): `download_depot 108600 108602`. Steam prints the folder when done (something like `steamapps\content\app_108600\depot_108602`).
   - [DepotDownloader](https://github.com/SteamRE/DepotDownloader): `DepotDownloader -app 108600 -depot 108602 -os macos -username YOUR_USER -dir depot108602`.
   - Note: the depot version must match game 42.21. When the game is updated the "current" depot changes too; use that era's manifest (DepotDownloader's `-manifest`) if you need to pin it. This download was not verified in this documentation.
4. **Install FMOD Studio API 2.03.09** for Windows (fmod.com/download, login required). It ships the ARM64 DLLs at `api\core\lib\arm64\fmod.dll` and `api\studio\lib\arm64\fmodstudio.dll`.
5. **Install the Compatibility Pack** from the Microsoft Store.
6. **Run setup**, pointing at what you downloaded:

   ```powershell
   .\setup.ps1 -MacDepotDir C:\path\to\depot108602 -FmodApiDir "C:\Program Files (x86)\FMOD SoundSystem\FMOD Studio API Windows"
   ```

   The script checks the environment, downloads what is free (JDK 25 ARM64, LWJGL 3.4.1 ARM64 from Maven Central, llvm-mingw/CMake/Ninja ARM64 toolchain), copies the dylibs to `game-libs\macos-42.21` and the FMOD DLLs to `third-party\fmod`, and lists anything missing. `-CheckOnly` only verifies; `-NoDownload` stays offline.
7. **Build**:

   ```powershell
   .\build.ps1
   ```

   This produces `dist\` (24 ARM64 DLLs, `manifest.json` and `pzarm-agent.jar`). With `-Test` it also runs `ctest` and the Python tests.
8. **Launch the game**:

   ```powershell
   .\launcher\run-game.ps1
   ```

   `-DryRun` only validates and prints the command line. The game's data (saves, settings, the game's own logs) live in `cache\`, not in `%USERPROFILE%\Zomboid`; on first run the launcher copies your `options.ini` and turns `doContainerOutline` off (the container outline fails on OpenGL over D3D12). Launcher logs go to `logs\`.

## Launcher options

`.\launcher\run-game.ps1 [options]`:

| Option | Effect |
|---|---|
| (default) | `fboRenderChunk=true`, `renderfix=flushtex` (texture-bug workaround) and `collapse=player` (the house in front does not open up when you are close) |
| `-NoFboChunk` | back to the old path: `fboRenderChunk=false`, no renderfix, no collapse. It feels smoother |
| `-NoCollapseFix` | disables only `collapse=player` (the house opens up when close again) |
| `-RenderFix <mode>` | picks another texture workaround (`none`, `flushtex`, `flush`, `rebind`, `uniforms`, `dirty`, `dirtyprog`, `flushchunk`, `flushtexchunk`); see [docs/RESULTS.md](docs/RESULTS.md) (Portuguese) |
| `-NoSound` | play without sound |
| `-NoAgent` | no Java agent (no rendering adjustments) |
| `-UseFboChunk` | accepted with no effect (FBO mode is already the default) |
| `-GameDir`, `-DistDir`, `-CacheDir` | paths; `-Timeout <s>`, `-DryRun`, `-Env`, `-PzSet`, `-RenderDiag`, `-DebugGl` for diagnostics |

The full description is in the header of `launcher\run-game.ps1` (Portuguese).

## Tests

```powershell
python -m unittest discover -s tests/tools -t .      # ~4 min: launcher, generators, manifest
ctest --test-dir build --output-on-failure           # native tests (needs the build)
.\build.ps1 -Test                                    # build + ctest + unittest
```

The tests in `tests/harness/` (Java and PowerShell, one `run-*.ps1` per native library plus `run-render-test.ps1` and `run-agent-test.ps1` for the agent) exercise the libraries and the agent against the game; they need `dist\` and the game, and open windows and an OpenGL context. Some compare ARM64 output with x64. Some `tests/tools` and ctest tests depend on the game or FMOD and are skipped or fail if those inputs are absent.

## Known limits

- Single player only. No multiplayer and no Steam (the game runs with `-nosteam`; no Workshop or achievements).
- `flushtex` is a **workaround** for the texture bug (sprites drawn with the wrong texture) on Mesa D3D12, not a Mesa fix. The root cause was not isolated: a standalone reproducer did not reproduce it.
- In FBO mode (the default) the game can feel less smooth than with `-NoFboChunk`, although it shows the scene correctly. The FPS comparison between the modes (median 49 versus 60) came from different sessions and is not conclusive.
- The house in front of the player may still collapse when you are close in some situations; `collapse=player` reduces this but was not validated in every case.
- Memory: on 8 GB free RAM got close to zero while loading (54 MB in the world with sound, 6 MB at the session minimum).
- Tested on 1 machine (Surface Pro X), one short session per configuration. Voice chat (VoIP) was not tested and Bink videos are disabled (stub DLL).
- The port does not modify the game; it targets version 42.21. Other versions may break the bridge DLLs.

## Troubleshooting

- `setup.ps1` prints "FALTA ..." (missing): each line says what to do. Run it again afterwards.
- `ERRO: projectzomboid.jar diverge do manifesto` (exit 3): the game was updated after `build.ps1`. Run `.\build.ps1` again (the game version must be the 42.21 compatible with the dylibs).
- `DLL ausente em dist` (exit 4): run `.\build.ps1`. `DLL nao e arm64`: do not copy x64 DLLs into `dist\`.
- Black window or immediate exit: check the Compatibility Pack and the latest `logs\game-*.log` (standard output and error are appended at the end of the file). `-DebugGl` writes FBO diagnostics to `logs\fbo-debug.log`.
- Wrong sprite textures: check that the effective `renderfix` in the log header is `flushtex`; try `-NoFboChunk`.
- No sound: check `third-party\fmod` (version 2.03.09, ARM64) and run `python -I tools\check_fmod.py`. `-NoSound` turns sound off.
- Low memory: close the browser and other programs; lower the resolution.
- Build failure: run `.\build.ps1 -Clean` and attach the output to an issue.

## License and credits

Code in this repository is under the [MIT license](LICENSE). See [THIRD_PARTY.md](THIRD_PARTY.md): the Mach-O loader and Darwin layer follow the zomdroid project (MIT), and the game's and FMOD's DLLs/dylibs are not part of the repository and carry their own licenses.

Research history and decisions: [reports/](reports/) (results per stage) and [docs/design/](docs/design/) (specs and plans), written during development, mostly in Portuguese.
