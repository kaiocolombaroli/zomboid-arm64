/* Carga das 5 dylibs do jogo. Uso: test_all_libs load_each_lib <pasta das dylibs> <pasta de trabalho> */
#include <io.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include "darwin.h"
#include "macho_loader.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static const struct { const char* file; const char* probe_sym; int expect_tls; } kLibs[] = {
  {"libLighting.dylib",   "Java_zombie_iso_LightingJNI_squareSetLightTransmission", 0},
  {"libPZPopMan.dylib",   "Java_zombie_MapCollisionData_n_1chunkUpdateTask", 0},
  {"libPZPathFind.dylib", "Java_zombie_pathfind_nativeCode_PathfindNativeRenderer_renderNative", 0},
  {"libPZBullet.dylib",   "Java_zombie_core_physics_Bullet_ToBullet", 0},
  {"libPZClipper.dylib",  "Java_zombie_vehicles_ClipperOffset_addPath", 1},    /* a unica com __thread */
};

static char g_cap_path[MAX_PATH * 2];
static int g_saved = -1;

static void capture_begin(const char* work) {
  snprintf(g_cap_path, sizeof g_cap_path, "%s/all_libs_capture.txt", work);
  fflush(stdout);
  g_saved = _dup(1);
  int fd = _open(g_cap_path, _O_WRONLY | _O_CREAT | _O_TRUNC | _O_BINARY, 0600);
  _dup2(fd, 1);
  _close(fd);
}

static char* capture_end(void) {
  fflush(stdout);
  _dup2(g_saved, 1);
  _close(g_saved);
  static char buf[1 << 16];
  FILE* f = fopen(g_cap_path, "rb");
  size_t n = f ? fread(buf, 1, sizeof buf - 1, f) : 0;
  if (f) fclose(f);
  buf[n] = 0;
  fputs(buf, stdout);
  return buf;
}

static int test_load_each_lib(const char* dir, const char* work) {
  _putenv_s("PZARM_NATIVE_VERBOSE", "1");   /* o detalhe de TLS entra no log */
  for (size_t i = 0; i < sizeof kLibs / sizeof kLibs[0]; i++) {
    char path[MAX_PATH * 2];
    snprintf(path, sizeof path, "%s/%s", dir, kLibs[i].file);
    capture_begin(work);
    macho_lib_t* lib = macho_load(path);
    char* log = capture_end();
    if (!lib) { fprintf(stderr, "FAIL: %s nao carregou\n", kLibs[i].file); return 1; }
    CHECK(strcmp(macho_lib_name(lib), kLibs[i].file) == 0);
    CHECK(strstr(log, "loaded natively") != NULL);
    CHECK(strstr(log, "static initializer(s)") != NULL);       /* __mod_init_func rodou */
    CHECK(strstr(log, "rejected") == NULL);
    if (kLibs[i].expect_tls) CHECK(strstr(log, "TLS 2 variable(s)") != NULL);
    else CHECK(strstr(log, "TLS ") == NULL);
    printf("%s: wraps_env=%d\n", kLibs[i].file, macho_lib_wraps_env(lib));
    /* a imagem esta registrada para o PC de um export, com __unwind_info */
    void* sym = macho_dlsym(lib, kLibs[i].probe_sym);
    CHECK(sym != NULL);
    {
      const macho_image_t* im = macho_image_for_pc((uintptr_t)sym);
      CHECK(im != NULL);
      CHECK(strcmp(im->name, kLibs[i].file) == 0);
      CHECK(im->unwind_info != 0 && im->unwind_info_size > 0);
      printf("%s: image base=%p size=%zu unwind_info=+0x%zx(%zu) eh_frame=%zu\n", im->name, (void*)im->base,
             im->size, (size_t)(im->unwind_info - im->base), im->unwind_info_size, im->eh_frame_size);
    }
  }
  CHECK(macho_image_for_pc(0) == NULL);
  CHECK(macho_image_for_pc((uintptr_t)&test_load_each_lib) == NULL);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 4 || strcmp(argv[1], "load_each_lib") != 0) {
    fputs("uso: test_all_libs load_each_lib <dylibs> <trabalho>\n", stderr);
    return 2;
  }
  int r = test_load_each_lib(argv[2], argv[3]);
  if (!r) printf("PASS load_each_lib\n");
  return r;
}
