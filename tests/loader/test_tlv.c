/* Testes de TLS de Mach-O. Uso: test_tlv <teste> <pasta de fixtures> */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include "darwin.h"
#include "macho_loader.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static char g_dir[MAX_PATH * 2];

typedef void (*snap_fn)(int64_t*);
typedef void (*set_fn)(int64_t, int64_t);
typedef int* (*addr_fn)(void);
static snap_fn g_snap;
static set_fn g_set;
static addr_fn g_addr;

static int load_fixture(void) {
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/tlv_test.dylib", g_dir);
  macho_lib_t* lib = macho_load(path);
  if (!lib) return 0;
  g_snap = (snap_fn)macho_dlsym(lib, "tlv_snapshot");
  g_set = (set_fn)macho_dlsym(lib, "tlv_set");
  g_addr = (addr_fn)macho_dlsym(lib, "tlv_addr_init");
  return g_snap && g_set && g_addr;
}

static int is_initial(const int64_t* v) {
  return v[0] == 42 && v[1] == 0 && v[2] == 11 && v[3] == 22 && v[4] == 33 && v[5] == 44 && v[6] == 0 && v[7] == 0;
}

typedef struct {
  int id, ok;
  int* addr;
} worker_t;

static DWORD WINAPI worker(LPVOID p) {
  worker_t* w = p;
  int64_t v[8];
  w->ok = 0;
  g_snap(v);
  if (!is_initial(v)) return 1;            /* cada thread comeca com os valores iniciais */
  w->addr = g_addr();
  for (int i = 0; i < 2000; i++) {
    int64_t a = w->id * 1000 + i % 100, b = w->id * 10 + 1;
    g_set(a, b);
    if (i % 50 == 0) Sleep(0);
    g_snap(v);
    if (v[0] != a || v[1] != b || v[5] != a + b || v[2] != 11) return 2;   /* nao ve valores de outra thread */
  }
  w->ok = 1;
  return 0;
}

static int test_per_thread(void) {
  CHECK(load_fixture());
  int64_t v[8];
  g_snap(v);
  CHECK(is_initial(v));
  g_set(1000, 2000);
  int* main_addr = g_addr();
  enum { N = 8 };
  worker_t w[N];
  HANDLE h[N];
  for (int i = 0; i < N; i++) {
    w[i].id = i + 1;
    w[i].ok = 0;
    w[i].addr = NULL;
    h[i] = CreateThread(NULL, 0, worker, &w[i], 0, NULL);
    CHECK(h[i]);
  }
  CHECK(WaitForMultipleObjects(N, h, TRUE, 60000) == WAIT_OBJECT_0);
  for (int i = 0; i < N; i++) {
    DWORD code = 99;
    GetExitCodeThread(h[i], &code);
    CloseHandle(h[i]);
    CHECK(code == 0 && w[i].ok);
    CHECK(w[i].addr != main_addr);
  }
  g_snap(v);                                 /* a thread principal nao viu nada das outras */
  CHECK(v[0] == 1000 && v[1] == 2000 && v[5] == 3000 && v[2] == 11);
  CHECK(g_addr() == main_addr);
  return 0;
}

/* ---- preserva registradores --------------------------------------------------------------- */

typedef struct {
  uint64_t x[32];
  uint8_t q[32][16];
} regs_t;

extern uint64_t tlv_regs_probe(void* desc, const regs_t* in, regs_t* out);

static void fill(regs_t* r, uint64_t seed) {
  memset(r, 0, sizeof *r);
  for (int i = 0; i < 32; i++) r->x[i] = (seed + (uint64_t)i) * 0x9E3779B97F4A7C15ull ^ 0xABCD0000ull;
  for (int i = 0; i < 32; i++)
    for (int b = 0; b < 16; b++) r->q[i][b] = (uint8_t)(seed * 31 + (uint64_t)i * 17 + (uint64_t)b * 5 + 1);
}

/* x1-x15, x19-x28 e q0-q31 (todos os 128 bits) iguais antes e depois. */
static int regs_same(const regs_t* in, const regs_t* out) {
  for (int i = 1; i <= 15; i++) if (in->x[i] != out->x[i]) { fprintf(stderr, "x%d mudou\n", i); return 0; }
  for (int i = 19; i <= 28; i++) if (in->x[i] != out->x[i]) { fprintf(stderr, "x%d mudou\n", i); return 0; }
  for (int i = 0; i < 32; i++) if (memcmp(in->q[i], out->q[i], 16)) { fprintf(stderr, "q%d mudou\n", i); return 0; }
  return 1;
}

typedef struct { uint64_t thunk, key, offset; } desc_t;

static desc_t g_desc[3];
static uint8_t g_tdata[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

typedef struct {
  uint64_t first, second;
  int ok_slow, ok_fast;
} probe_result_t;

static int probe_once(desc_t* d, uint64_t seed, uint64_t* addr_out) {
  regs_t in, out;
  fill(&in, seed);
  memset(&out, 0xEE, sizeof out);
  uint64_t r = tlv_regs_probe(d, &in, &out);
  *addr_out = r;
  return r == out.x[0] && regs_same(&in, &out);
}

static DWORD WINAPI probe_thread(LPVOID p) {
  uint64_t* res = p;       /* res[0], res[1]: enderecos; res[2]: ok */
  desc_t* d = &g_desc[1];
  int ok = probe_once(d, 7, &res[0]) && probe_once(d, 8, &res[1]);   /* lento, depois rapido */
  res[2] = ok && res[0] == res[1];
  return 0;
}

static int run_probe_case(const char* label) {
  uint64_t a1, a2, a3;
  CHECK(probe_once(&g_desc[0], 1, &a1));          /* primeiro acesso desta thread: caminho lento */
  CHECK(probe_once(&g_desc[0], 2, &a2));          /* depois: caminho rapido */
  CHECK(a1 == a2);
  CHECK(probe_once(&g_desc[2], 3, &a3));
  CHECK(a3 == a1 + 16);                           /* offsets 0 e 16 dentro do mesmo bloco */
  CHECK(memcmp((void*)(uintptr_t)a1, g_tdata, 16) == 0);   /* conteudo inicial copiado */
  CHECK(memcmp((void*)(uintptr_t)(a1 + 16), (const uint8_t[8]){0}, 8) == 0);   /* tbss zerado */
  uint64_t res[3] = {0, 0, 0};
  HANDLE h = CreateThread(NULL, 0, probe_thread, res, 0, NULL);
  CHECK(h);
  CHECK(WaitForSingleObject(h, 30000) == WAIT_OBJECT_0);
  CloseHandle(h);
  CHECK(res[2] == 1);
  CHECK(res[0] - 8 != a1);                        /* outra thread, outro bloco */
  printf("probe %s: ok\n", label);
  return 0;
}

static int test_preserves_registers(void) {
  /* descritores sinteticos: offsets 0, 8, 16; template = 16 bytes de tdata + 8 de tbss */
  for (int i = 0; i < 3; i++) { g_desc[i].thunk = 0; g_desc[i].key = 0; g_desc[i].offset = (uint64_t)i * 8; }
  g_desc[1].offset = 8;
  g_desc[2].offset = 16;
  dw_tlv_register(g_desc, g_desc + 3, g_tdata, sizeof g_tdata, 8);
  CHECK(g_desc[0].thunk == (uint64_t)(uintptr_t)dw_resolve("_tlv_bootstrap"));
  if (run_probe_case("key < 64 (TEB direto)")) return 1;

  /* esgota os 64 slots do TEB: a proxima imagem recebe key >= 64 e sempre usa o caminho lento */
  DWORD slots[70];
  int n = 0;
  for (; n < 70; n++) { slots[n] = TlsAlloc(); if (slots[n] == TLS_OUT_OF_INDEXES) break; if (slots[n] >= 64) { n++; break; } }
  static desc_t big[3];
  static uint8_t td2[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
  for (int i = 0; i < 3; i++) { big[i].offset = (uint64_t)i * 8; }
  big[2].offset = 16;
  dw_tlv_register(big, big + 3, td2, sizeof td2, 8);
  CHECK(big[0].key >= 64);
  memcpy(g_desc, big, sizeof big);
  memcpy(g_tdata, td2, sizeof td2);
  if (run_probe_case("key >= 64 (caminho lento sempre)")) return 1;
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 3) { fputs("uso: test_tlv <teste> <pasta de fixtures>\n", stderr); return 2; }
  snprintf(g_dir, sizeof g_dir, "%s", argv[2]);
  int r;
  if (!strcmp(argv[1], "per_thread")) r = test_per_thread();
  else if (!strcmp(argv[1], "preserves_registers")) r = test_preserves_registers();
  else { fprintf(stderr, "teste desconhecido: %s\n", argv[1]); return 2; }
  if (!r) printf("PASS %s\n", argv[1]);
  return r;
}
