#include "darwin.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include "dw_internal.h"

/* ---- ___chkstk_darwin ------------------------------------------------------
   Convencao Apple arm64 (confirmada em libPZBullet.dylib): tamanho do frame em
   BYTES em w9/x9, chamada com `blr x16`, e o prologo faz `sub sp, sp, ...`
   DEPOIS. Preserva todos os registradores exceto x9/x10 (e x16/x17 do stub).
   Nao move sp. Le um byte em cada pagina de 4 KiB abaixo de sp, de cima para
   baixo, para que o guard page do Windows cresca a pilha em ordem. */
__asm__(
  ".text\n"
  ".p2align 2\n"
  ".globl dw_chkstk_darwin\n"
  "dw_chkstk_darwin:\n"
  "  mov x10, sp\n"
  "1:\n"
  "  cmp x9, #4096\n"
  "  b.ls 2f\n"
  "  sub x10, x10, #4096\n"
  "  ldrb wzr, [x10]\n"
  "  sub x9, x9, #4096\n"
  "  b 1b\n"
  "2:\n"
  "  sub x10, x10, x9\n"
  "  ldrb wzr, [x10]\n"
  "  ret\n");
extern void dw_chkstk_darwin(void);

/* ---- stack protector ---- */
uint64_t dw_stack_chk_guard = 0x2f6b9d3e5a17c481ull;
static void dw_stack_chk_fail(void) {
  fputs("dw: __stack_chk_fail\n", stderr);
  abort();
}

/* ---- mem* ---- */
static void* dw_memcpy(void* d, const void* s, uint64_t n) { return memcpy(d, s, (size_t)n); }
static void* dw_memmove(void* d, const void* s, uint64_t n) { return memmove(d, s, (size_t)n); }
static void* dw_memset(void* d, int c, uint64_t n) { return memset(d, c, (size_t)n); }
static void dw_bzero(void* d, uint64_t n) { memset(d, 0, (size_t)n); }

/* ---- operator new/delete ---- */
static void* dw_new(uint64_t n) {
  void* p = dw_malloc(n ? n : 1);
  if (!p) { fputs("dw: operator new: out of memory\n", stderr); abort(); }
  return p;
}
static void dw_delete(void* p) { dw_free(p); }

/* ---- __cxa_atexit: guarda e retorna 0 (nao executa no M1) ---- */
#define DW_ATEXIT_MAX 1024
static struct { void (*fn)(void*); void* arg; void* dso; } dw_atexit_tab[DW_ATEXIT_MAX];
static volatile LONG dw_atexit_n;
static int dw_cxa_atexit(void (*fn)(void*), void* arg, void* dso) {
  LONG i = InterlockedIncrement(&dw_atexit_n) - 1;
  if (i < DW_ATEXIT_MAX) { dw_atexit_tab[i].fn = fn; dw_atexit_tab[i].arg = arg; dw_atexit_tab[i].dso = dso; }
  return 0;
}

/* ---- armadilha restante: o stub do binder lazy nunca deve ser chamado (a carga resolve tudo) ---- */
static void trap_dyld_stub_binder(void) { fputs("dw: trap: dyld_stub_binder\n", stderr); abort(); }

const dw_entry dw_runtime_entries[] = {
  {"memcpy", (void*)dw_memcpy}, {"memmove", (void*)dw_memmove},
  {"memset", (void*)dw_memset}, {"bzero", (void*)dw_bzero},
  {"_Znwm", (void*)dw_new}, {"_Znam", (void*)dw_new},
  {"_ZdlPv", (void*)dw_delete}, {"_ZdaPv", (void*)dw_delete},
  {"__cxa_atexit", (void*)dw_cxa_atexit},
  {"_tlv_bootstrap", (void*)dw_tlv_get}, {"_tlv_atexit", (void*)dw_tlv_atexit},
  {"_Unwind_Resume", (void*)dw_unwind_resume},
  {"_ZSt9terminatev", (void*)dw_terminate},
  {"__cxa_allocate_exception", (void*)dw_cxa_allocate_exception},
  {"__cxa_begin_catch", (void*)dw_cxa_begin_catch},
  {"__cxa_end_catch", (void*)dw_cxa_end_catch},
  {"__cxa_free_exception", (void*)dw_cxa_free_exception},
  {"__cxa_rethrow", (void*)dw_cxa_rethrow},
  {"__cxa_throw", (void*)dw_cxa_throw},
  {"__gxx_personality_v0", (void*)dw_gxx_personality_stub},
  {"dyld_stub_binder", (void*)trap_dyld_stub_binder},
  /* Mach-O sem o '_' inicial: ___chkstk_darwin -> __chkstk_darwin etc. Aceita as duas grafias. */
  {"__chkstk_darwin", (void*)dw_chkstk_darwin}, {"___chkstk_darwin", (void*)dw_chkstk_darwin},
  {"_stack_chk_guard", &dw_stack_chk_guard}, {"__stack_chk_guard", &dw_stack_chk_guard},
  {"_stack_chk_fail", (void*)dw_stack_chk_fail}, {"__stack_chk_fail", (void*)dw_stack_chk_fail},
  {NULL, NULL}
};
