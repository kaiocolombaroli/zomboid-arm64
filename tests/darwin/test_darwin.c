#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include "darwin.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static int test_alloc_contract(void) {
  void* p = dw_aligned_alloc(64, 100);
  CHECK(p && ((uintptr_t)p % 64) == 0);
  CHECK(dw_malloc_size(p) >= 100);
  memset(p, 0xAB, 100);
  dw_free(p);
  void* q = dw_malloc(1);
  CHECK(q && ((uintptr_t)q % 16) == 0 && dw_malloc_size(q) >= 1);
  dw_free(q);
  dw_free(NULL);
  return 0;
}

#define NTHREADS 8
#define CYCLES 100000
#define RING 16
static volatile LONG g_thread_fail;

static DWORD WINAPI alloc_worker(LPVOID arg) {
  uint32_t s = 0x9e3779b9u * (uint32_t)(uintptr_t)(arg + 1);
  struct { unsigned char* p; uint32_t n; unsigned char pat; } ring[RING] = {0};
  for (int i = 0; i < CYCLES; i++) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    int slot = i % RING;
    if (ring[slot].p) {
      for (uint32_t k = 0; k < ring[slot].n; k++)
        if (ring[slot].p[k] != ring[slot].pat) { InterlockedIncrement(&g_thread_fail); return 1; }
      dw_free(ring[slot].p);
    }
    uint32_t n = 1 + (s % 4096);
    unsigned char* p = dw_malloc(n);
    if (!p) { InterlockedIncrement(&g_thread_fail); return 1; }
    ring[slot].p = p; ring[slot].n = n; ring[slot].pat = (unsigned char)(s >> 24);
    memset(p, ring[slot].pat, n);
  }
  for (int i = 0; i < RING; i++) {
    if (!ring[i].p) continue;
    for (uint32_t k = 0; k < ring[i].n; k++)
      if (ring[i].p[k] != ring[i].pat) { InterlockedIncrement(&g_thread_fail); return 1; }
    dw_free(ring[i].p);
  }
  return 0;
}

static int test_alloc_threads(void) {
  HANDLE h[NTHREADS];
  for (int i = 0; i < NTHREADS; i++) {
    h[i] = CreateThread(NULL, 0, alloc_worker, (LPVOID)(uintptr_t)i, 0, NULL);
    CHECK(h[i]);
  }
  WaitForMultipleObjects(NTHREADS, h, TRUE, INFINITE);
  for (int i = 0; i < NTHREADS; i++) CloseHandle(h[i]);
  CHECK(g_thread_fail == 0);
  return 0;
}

/* Emula o inicio de um prologo Apple: x9 = 1 MiB; blr fn. Devolve em x0 o
   endereco sp-1MiB (o fim do frame), ou 0 se x5/x8 foram alterados ou sp mudou.
   O C verifica depois que as paginas entre sp e esse endereco estao commitadas. */
__asm__(
  ".text\n.p2align 2\n.globl run_frame\nrun_frame:\n"
  "  stp x29, x30, [sp, #-16]!\n"
  "  mov x29, sp\n"
  "  mov x16, x0\n"
  "  movz x5, #0x234\n"
  "  movz x8, #0x678\n"
  "  mov x11, sp\n"
  "  movz x9, #0x10, lsl #16\n"        /* 1 MiB */
  "  blr x16\n"
  "  mov x0, #0\n"
  "  cmp x5, #0x234\n"
  "  b.ne 1f\n"
  "  cmp x8, #0x678\n"
  "  b.ne 1f\n"
  "  mov x12, sp\n"
  "  cmp x12, x11\n"
  "  b.ne 1f\n"
  "  sub x0, sp, #256, lsl #12\n"
  "1:\n"
  "  ldp x29, x30, [sp], #16\n"
  "  ret\n");
extern uint64_t run_frame(void* chkstk);

static DWORD WINAPI chkstk_thread(LPVOID arg) {
  void* fn = dw_resolve("__chkstk_darwin");
  if (!fn) return 2;
  uint64_t lo = run_frame(fn);
  if (!lo) return 3;
  /* todas as paginas do frame (sp-1MiB .. sp) precisam estar commitadas */
  for (uint64_t a = lo; a < lo + (1u << 20); a += 4096) {
    MEMORY_BASIC_INFORMATION mbi;
    if (!VirtualQuery((void*)a, &mbi, sizeof mbi) || mbi.State != MEM_COMMIT) { fprintf(stderr, "page %llu of 256 state=%lx prot=%lx\n", (unsigned long long)((a-lo)/4096), (unsigned long)mbi.State, (unsigned long)mbi.Protect); return 4; }
  }
  volatile unsigned char* p = (volatile unsigned char*)lo;
  p[0] = 0x77; p[(1u << 20) - 1] = 0x77;   /* ultima pagina e ultimo byte do frame */
  if (p[0] != 0x77) return 5;
  *(uint64_t*)arg = 1;
  return 0;
}

static int test_chkstk_large_frame(void) {
  uint64_t ok = 0;
  /* 4 MiB apenas reservados (commit inicial = 1 pagina) */
  HANDLE h = CreateThread(NULL, 4u << 20, chkstk_thread, &ok, STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
  CHECK(h);
  WaitForSingleObject(h, INFINITE);
  DWORD code = 99; GetExitCodeThread(h, &code);
  CloseHandle(h);
  fprintf(stderr, "thread exit code: %lu\n", (unsigned long)code);
  CHECK(code == 0);
  CHECK(ok == 1);
  return 0;
}

static int test_import_coverage(const char* path) {
  CHECK(path);
  FILE* f = fopen(path, "r");
  CHECK(f);
  char line[512]; int total = 0, missing = 0;
  while (fgets(line, sizeof line, f)) {
    size_t n = strlen(line);
    while (n && (line[n-1] == '\n' || line[n-1] == '\r' || line[n-1] == ' ')) line[--n] = 0;
    if (!n) continue;
    total++;
    if (!dw_resolve(line)) { fprintf(stderr, "missing import: %s\n", line); missing++; }
  }
  fclose(f);
  fprintf(stderr, "imports: %d, missing: %d\n", total, missing);
  CHECK(total > 0 && missing == 0);
  /* dw_install_code: instalar `ret` e chamar */
  const uint32_t ret_insn = 0xd65f03c0u;
  void (*fn)(void) = (void (*)(void))dw_install_code(&ret_insn, 4);
  CHECK(fn);
  fn();
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) { fputs("uso: test_darwin <teste> [arg]\n", stderr); return 2; }
  int r;
  if (!strcmp(argv[1], "alloc_contract")) r = test_alloc_contract();
  else if (!strcmp(argv[1], "alloc_threads")) r = test_alloc_threads();
  else if (!strcmp(argv[1], "chkstk_large_frame")) r = test_chkstk_large_frame();
  else if (!strcmp(argv[1], "import_coverage")) r = test_import_coverage(argc > 2 ? argv[2] : NULL);
  else { fprintf(stderr, "teste desconhecido: %s\n", argv[1]); return 2; }
  if (!r) printf("PASS %s\n", argv[1]);
  return r;
}
