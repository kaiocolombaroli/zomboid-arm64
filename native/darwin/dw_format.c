/* Formato printf Apple -> ucrt. A diferenca que importa: "long" tem 64 bits na Apple e 32 no
   Windows, entao %ld/%lu/%lx/%lo/%li/%lX viram %lld/%llu/%llx/... Flags, largura (inclusive '*'),
   precisao e %% sao preservados. */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "dw_internal.h"

static void put(char* out, size_t cap, size_t* n, char c) {
  if (out && *n + 1 < cap) out[*n] = c;
  (*n)++;
}

size_t dw_fix_format(const char* in, char* out, size_t cap) {
  size_t n = 0;
  if (!in) in = "";
  while (*in) {
    if (*in != '%') { put(out, cap, &n, *in++); continue; }
    put(out, cap, &n, *in++);                       /* '%' */
    if (*in == '%') { put(out, cap, &n, *in++); continue; }
    while (*in && strchr("-+ #0", *in)) put(out, cap, &n, *in++);           /* flags */
    if (*in == '*') put(out, cap, &n, *in++);
    else while (*in >= '0' && *in <= '9') put(out, cap, &n, *in++);          /* largura */
    if (*in == '.') {                                                       /* precisao */
      put(out, cap, &n, *in++);
      if (*in == '*') put(out, cap, &n, *in++);
      else while (*in >= '0' && *in <= '9') put(out, cap, &n, *in++);
    }
    if (*in == 'l' && in[1] && strchr("diuoxX", in[1])) {                   /* %l? sozinho */
      put(out, cap, &n, 'l'); put(out, cap, &n, 'l'); in++;
    } else {
      while (*in && strchr("hlLqjzt", *in)) put(out, cap, &n, *in++);       /* demais modificadores */
    }
    if (*in) put(out, cap, &n, *in++);                                       /* conversao */
  }
  if (out && cap) out[n < cap ? n : cap - 1] = 0;
  return n;
}

/* Buffer da pilha quando o formato e curto; senao, heap da CRT. */
#define DW_FMT_STACK 512
int dw_vsnprintf(char* buf, uint64_t n, const char* fmt, char* ap) {
  char stk[DW_FMT_STACK];
  char* f = stk;
  size_t need = dw_fix_format(fmt, NULL, 0);
  if (need >= DW_FMT_STACK) {
    f = malloc(need + 1);
    if (!f) return -1;
  }
  dw_fix_format(fmt, f, need + 1);
  int r = vsnprintf(buf, (size_t)n, f, (va_list)ap);
  if (f != stk) free(f);
  return r;
}

int dw_vprintf(const char* fmt, char* ap) {
  char stk[DW_FMT_STACK];
  char* f = stk;
  size_t need = dw_fix_format(fmt, NULL, 0);
  if (need >= DW_FMT_STACK) {
    f = malloc(need + 1);
    if (!f) return -1;
  }
  dw_fix_format(fmt, f, need + 1);
  int r = vprintf(f, (va_list)ap);
  if (f != stk) free(f);
  return r;
}

int dw_vfprintf(void* f, const char* fmt, char* ap) {
  char stk[DW_FMT_STACK];
  char* g = stk;
  size_t need = dw_fix_format(fmt, NULL, 0);
  if (need >= DW_FMT_STACK) {
    g = malloc(need + 1);
    if (!g) return -1;
  }
  dw_fix_format(fmt, g, need + 1);
  int r = vfprintf((FILE*)f, g, (va_list)ap);
  if (g != stk) free(g);
  return r;
}

/* sprintf sem limite: o chamador garante o buffer (como na libc). */
int dw_vsprintf(char* buf, const char* fmt, char* ap) { return dw_vsnprintf(buf, 0x7FFFFFFFu, fmt, ap); }
