/* Testes do carregador Mach-O. Uso: test_loader <teste> <caminho da libLighting.dylib> <pasta de trabalho> */
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

#define JNI_SYM "Java_zombie_iso_LightingJNI_squareSetLightTransmission"

static char g_dylib[MAX_PATH * 2];
static char g_work[MAX_PATH * 2];

/* Captura o que macho_load escreve em stdout, num arquivo temporario. */
static int g_saved = -1;
static char g_cap_path[MAX_PATH * 2];

static void capture_begin(void) {
  snprintf(g_cap_path, sizeof g_cap_path, "%s/loader_capture.txt", g_work);
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
  static char buf[8192];
  FILE* f = fopen(g_cap_path, "rb");
  size_t n = f ? fread(buf, 1, sizeof buf - 1, f) : 0;
  if (f) fclose(f);
  buf[n] = 0;
  fputs(buf, stdout);  /* deixa o log visivel no CTest */
  return buf;
}

static int test_load_lighting(void) {
  macho_lib_t* lib = macho_load(g_dylib);
  CHECK(lib != NULL);
  CHECK(macho_dlsym(lib, JNI_SYM) != NULL);
  CHECK(strcmp(macho_lib_name(lib), "libLighting.dylib") == 0);
  /* O relatorio do Zomdroid afirma que a Lighting nao usa JNI variadica. */
  printf("macho_lib_wraps_env(Lighting) = %d\n", macho_lib_wraps_env(lib));
  CHECK(macho_lib_wraps_env(lib) == 0);
  return 0;
}

static int utf8_of(const wchar_t* w, char* out, int cap) {
  return WideCharToMultiByte(CP_UTF8, 0, w, -1, out, cap, NULL, NULL) > 0;
}

static int test_unicode_path(void) {
  wchar_t wwork[MAX_PATH * 2], wdir[MAX_PATH * 2], wsrc[MAX_PATH * 2], wdst[MAX_PATH * 2];
  CHECK(MultiByteToWideChar(CP_UTF8, 0, g_work, -1, wwork, MAX_PATH * 2) > 0);
  CHECK(MultiByteToWideChar(CP_UTF8, 0, g_dylib, -1, wsrc, MAX_PATH * 2) > 0);
  /* "test pasta ação" com a, c-cedilha, a-til e o-til reais (U+00E7 U+00E3) */
  swprintf(wdir, MAX_PATH * 2, L"%ls/test pasta ação", wwork);
  CHECK(CreateDirectoryW(wdir, NULL) || GetLastError() == ERROR_ALREADY_EXISTS);
  swprintf(wdst, MAX_PATH * 2, L"%ls/libLighting.dylib", wdir);
  CHECK(CopyFileW(wsrc, wdst, FALSE));
  char u8[MAX_PATH * 4];
  CHECK(utf8_of(wdst, u8, sizeof u8));
  CHECK(strstr(u8, "\xC3\xA7\xC3\xA3") != NULL);  /* acentos em UTF-8 no caminho */
  macho_lib_t* lib = macho_load(u8);
  CHECK(lib != NULL);
  CHECK(macho_dlsym(lib, JNI_SYM) != NULL);
  return 0;
}

static int test_rejects_garbage(void) {
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/garbage.bin", g_work);
  FILE* f = fopen(path, "wb");
  CHECK(f);
  static unsigned char zeros[4096];
  CHECK(fwrite(zeros, 1, sizeof zeros, f) == sizeof zeros);
  fclose(f);
  capture_begin();
  macho_lib_t* lib = macho_load(path);
  char* out = capture_end();
  CHECK(lib == NULL);
  CHECK(strstr(out, "[macho] garbage.bin rejected: ") != NULL);
  return 0;
}

static int test_rejects_unknown_import(void) {
  dw_test_fail_import("memcpy");
  CHECK(dw_resolve("memcpy") == NULL);
  capture_begin();
  macho_lib_t* lib = macho_load(g_dylib);
  char* out = capture_end();
  dw_test_fail_import(NULL);
  CHECK(lib == NULL);
  CHECK(strstr(out, "rejected: ") != NULL);
  CHECK(strstr(out, "memcpy") != NULL);
  CHECK(dw_resolve("memcpy") != NULL);
  return 0;
}

/* Mach-O sintetico minimo: cabecalho, segmentos e um LC_DYLD_INFO_ONLY vazio; arquivo de 4 KB. */
typedef struct { const char* name; uint64_t vmaddr, vmsize, fileoff, filesize; int32_t initprot; } synth_seg_t;

static void put32(unsigned char* b, size_t o, uint32_t v) { memcpy(b + o, &v, 4); }
static void put64(unsigned char* b, size_t o, uint64_t v) { memcpy(b + o, &v, 8); }

static const unsigned char* g_synth_rebase = NULL;
static size_t g_synth_rebase_len = 0;

static int write_synth(const char* file, const synth_seg_t* segs, int n) {
  static unsigned char buf[4096];
  memset(buf, 0, sizeof buf);
  size_t o = 32, cmds = 0;
  put32(buf, 0, 0xFEEDFACFu); put32(buf, 4, 0x0100000Cu); put32(buf, 12, 6u);   /* arm64 dylib */
  for (int i = 0; i < n; i++, o += 72) {
    put32(buf, o, 0x19u); put32(buf, o + 4, 72u);
    strncpy((char*)buf + o + 8, segs[i].name, 16);
    put64(buf, o + 24, segs[i].vmaddr); put64(buf, o + 32, segs[i].vmsize);
    put64(buf, o + 40, segs[i].fileoff); put64(buf, o + 48, segs[i].filesize);
    put32(buf, o + 56, 7u); put32(buf, o + 60, (uint32_t)segs[i].initprot);
    cmds += 72;
  }
  put32(buf, o, 0x80000022u); put32(buf, o + 4, 48u);   /* LC_DYLD_INFO_ONLY, tudo zero */
  cmds += 48;
  if (g_synth_rebase) {                                  /* fluxo de rebase em 0x800 */
    memcpy(buf + 0x800, g_synth_rebase, g_synth_rebase_len);
    put32(buf, o + 8, 0x800u); put32(buf, o + 12, (uint32_t)g_synth_rebase_len);
  }
  put32(buf, 16, (uint32_t)n + 1); put32(buf, 20, (uint32_t)cmds);
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/%s", g_work, file);
  FILE* f = fopen(path, "wb");
  if (!f) return 0;
  size_t w = fwrite(buf, 1, sizeof buf, f);
  fclose(f);
  return w == sizeof buf;
}

static int reject_synth(const char* file, const synth_seg_t* segs, int n, const char* reason) {
  CHECK(write_synth(file, segs, n));
  char path[MAX_PATH * 2], want[128];
  snprintf(path, sizeof path, "%s/%s", g_work, file);
  snprintf(want, sizeof want, "[macho] %s rejected: %s", file, reason);
  capture_begin();
  macho_lib_t* lib = macho_load(path);
  char* out = capture_end();
  CHECK(lib == NULL);
  CHECK(strstr(out, want) != NULL);
  return 0;
}

static int test_rejects_segment_overflow(void) {
  synth_seg_t segs[] = {
    { "__TEXT", 0, 0x1000, 0, 0x1000, 5 },
    { "__DATA", 0xFFFFFFFFFFFFF000ull, 0x2000, 0, 0, 3 },   /* vmaddr + vmsize da a volta em 2^64 */
  };
  return reject_synth("synth_overflow.dylib", segs, 2, "segment vm range overflows");
}

static int test_rejects_wx_segment(void) {
  synth_seg_t segs[] = { { "__TEXT", 0, 0x1000, 0, 0x1000, 7 } };
  return reject_synth("synth_wx.dylib", segs, 1, "segment is both writable and executable");
}

/* Rebase com deslocamento 2^64-8 no segmento __TEXT (vmaddr 0): nao pode escrever antes de base. */
static int test_rejects_bind_offset_overflow(void) {
  static const unsigned char stream[] = {
    0x11,                                                     /* SET_TYPE_IMM pointer */
    0x20, 0xF8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01,  /* SET_SEGMENT_AND_OFFSET_ULEB seg 0, off 2^64-8 */
    0x51,                                                     /* DO_REBASE_IMM_TIMES 1 */
    0x00
  };
  synth_seg_t segs[] = { { "__TEXT", 0, 0x1000, 0, 0x1000, 5 } };
  g_synth_rebase = stream; g_synth_rebase_len = sizeof stream;
  int rc = reject_synth("synth_offset.dylib", segs, 1, "rebase target outside its segment");
  g_synth_rebase = NULL;
  return rc;
}

/* --- dependencias: so @rpath/libfmod[studio].dylib passa, e quem resolve os simbolos e o resolvedor externo --- */
static int fake_add(int a, int b) { return a + b + 2; }       /* +2 prova que o endereco entregue foi usado */
static void* add_resolver(const char* name) {
  return strcmp(name, "FMOD_Test_Add") == 0 ? (void*)fake_add : NULL;
}

static int test_fmod_dependency_resolved(void) {
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/fixtures/fmoddep_test.dylib", g_work);
  macho_set_extern_resolver(add_resolver);
  macho_lib_t* lib = macho_load(path);
  macho_set_extern_resolver(NULL);
  CHECK(lib != NULL);
  int (*f)(int) = (int (*)(int))macho_dlsym(lib, "fmoddep_call");
  CHECK(f != NULL);
  CHECK(f(100) == 142);          /* fake_add(100, 40) = 142 */
  return 0;
}

static int test_fmod_dependency_needs_resolver(void) {
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/fixtures/fmoddep_test.dylib", g_work);
  macho_set_extern_resolver(NULL);
  capture_begin();
  macho_lib_t* lib = macho_load(path);
  char* out = capture_end();
  CHECK(lib == NULL);
  CHECK(strstr(out, "rejected: ") != NULL);
  CHECK(strstr(out, "FMOD_Test_Add") != NULL);
  return 0;
}

static int test_other_dependency_rejected(void) {
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/fixtures/otherdep_test.dylib", g_work);
  macho_set_extern_resolver(add_resolver);      /* mesmo com resolvedor, outra dependencia continua recusada */
  capture_begin();
  macho_lib_t* lib = macho_load(path);
  char* out = capture_end();
  macho_set_extern_resolver(NULL);
  CHECK(lib == NULL);
  CHECK(strstr(out, "depends on @rpath/libother.dylib") != NULL);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 4) { fprintf(stderr, "uso: test_loader <teste> <dylib> <pasta>\n"); return 2; }
  snprintf(g_dylib, sizeof g_dylib, "%s", argv[2]);
  snprintf(g_work, sizeof g_work, "%s", argv[3]);
  if (!strcmp(argv[1], "load_lighting")) return test_load_lighting();
  if (!strcmp(argv[1], "unicode_path")) return test_unicode_path();
  if (!strcmp(argv[1], "rejects_garbage")) return test_rejects_garbage();
  if (!strcmp(argv[1], "rejects_unknown_import")) return test_rejects_unknown_import();
  if (!strcmp(argv[1], "rejects_segment_overflow")) return test_rejects_segment_overflow();
  if (!strcmp(argv[1], "rejects_wx_segment")) return test_rejects_wx_segment();
  if (!strcmp(argv[1], "rejects_bind_offset_overflow")) return test_rejects_bind_offset_overflow();
  if (!strcmp(argv[1], "fmod_dependency_resolved")) return test_fmod_dependency_resolved();
  if (!strcmp(argv[1], "fmod_dependency_needs_resolver")) return test_fmod_dependency_needs_resolver();
  if (!strcmp(argv[1], "other_dependency_rejected")) return test_other_dependency_rejected();
  fprintf(stderr, "teste desconhecido: %s\n", argv[1]);
  return 2;
}
