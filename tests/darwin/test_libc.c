/* Testes da libc com semantica Apple em pzdarwin.dll. Tudo e chamado por dw_resolve (a tabela real);
   dw_fix_format e a unica funcao chamada direto. Uso: test_libc <teste> <pasta-de-trabalho> */
#include <errno.h>
#include <io.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <windows.h>
#include <winioctl.h>
#include "darwin.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static const char* g_dir;
static char g_path[1024];
static const char* path_in(const char* name) {
  snprintf(g_path, sizeof g_path, "%s/%s", g_dir, name);
  return g_path;
}

typedef void* (*fn_fopen)(const char*, const char*);
typedef int (*fn_fclose)(void*);
typedef uint64_t (*fn_fread)(void*, uint64_t, uint64_t, void*);
typedef uint64_t (*fn_fwrite)(const void*, uint64_t, uint64_t, void*);
typedef int (*fn_fseeko)(void*, int64_t, int);
typedef int64_t (*fn_ftello)(void*);
typedef int (*fn_rename)(const char*, const char*);
typedef int (*fn_rand)(void);
typedef int (*fn_vsnprintf)(char*, uint64_t, const char*, char*);
typedef struct { double s, c; } sincos_t;
typedef sincos_t (*fn_sincos)(double);
typedef int (*fn_gtod)(void*, void*);

#define R(T, name) T name = (T)dw_resolve(#name); if (!name) { fprintf(stderr, "sem %s\n", #name); return 1; }

static int test_fopen_binary(void) {
  R(fn_fopen, fopen) R(fn_fclose, fclose) R(fn_fread, fread) R(fn_fwrite, fwrite)
  const char data[] = "a\r\nb\x1A" " c";   /* 7 bytes */
  const char* p = path_in("libc_bin.txt");
  void* f = fopen(p, "w");
  CHECK(f);
  CHECK(fwrite(data, 1, 7, f) == 7);
  CHECK(fclose(f) == 0);
  f = fopen(p, "r");
  CHECK(f);
  char buf[32] = {0};
  CHECK(fread(buf, 1, sizeof buf, f) == 7);
  CHECK(memcmp(buf, data, 7) == 0);
  fclose(f);
  remove(p);
  return 0;
}

static int test_fseeko_64bit(void) {
  R(fn_fopen, fopen) R(fn_fclose, fclose) R(fn_fwrite, fwrite) R(fn_fread, fread)
  R(fn_fseeko, fseeko) R(fn_ftello, ftello)
  const char* p = path_in("libc_sparse.bin");
  void* f = fopen(p, "w+");
  CHECK(f);
  DWORD got;
  DeviceIoControl((HANDLE)_get_osfhandle(_fileno((FILE*)f)), FSCTL_SET_SPARSE, NULL, 0, NULL, 0, &got, NULL);
  const int64_t off = 5LL * 1024 * 1024 * 1024 + 123;
  CHECK(fseeko(f, off, SEEK_SET) == 0);
  CHECK(ftello(f) == off);
  CHECK(fwrite("Z", 1, 1, f) == 1);
  CHECK(ftello(f) == off + 1);
  CHECK(fseeko(f, -1, SEEK_END) == 0);
  CHECK(ftello(f) == off);
  char c = 0;
  CHECK(fread(&c, 1, 1, f) == 1 && c == 'Z');
  fclose(f);
  remove(p);
  return 0;
}

static int test_fopen_unicode(void) {
  R(fn_fopen, fopen) R(fn_fclose, fclose) R(fn_fwrite, fwrite)
  const char* p = path_in("libc_a\xC3\xA7\xC3\xA3o \xC3\xA9.txt");   /* "ação é" em UTF-8 */
  void* f = fopen(p, "w");
  CHECK(f);
  CHECK(fwrite("x", 1, 1, f) == 1);
  fclose(f);
  wchar_t w[1024];
  CHECK(MultiByteToWideChar(CP_UTF8, 0, p, -1, w, 1024) > 0);
  CHECK(GetFileAttributesW(w) != INVALID_FILE_ATTRIBUTES);
  DeleteFileW(w);
  return 0;
}

static int write_file(const char* p, const char* s) {
  FILE* f = fopen(p, "wb");
  if (!f) return 0;
  fputs(s, f);
  fclose(f);
  return 1;
}

static int test_rename_overwrites(void) {
  R(fn_rename, rename)
  char a[1024], b[1024];
  snprintf(a, sizeof a, "%s", path_in("libc_ren_a.txt"));
  snprintf(b, sizeof b, "%s", path_in("libc_ren_b.txt"));
  CHECK(write_file(a, "novo"));
  CHECK(write_file(b, "velho"));
  CHECK(rename(a, b) == 0);
  FILE* f = fopen(b, "rb");
  CHECK(f);
  char buf[16] = {0};
  CHECK(fread(buf, 1, 15, f) == 4 && strcmp(buf, "novo") == 0);
  fclose(f);
  CHECK(GetFileAttributesA(a) == INVALID_FILE_ATTRIBUTES);
  remove(b);
  CHECK(rename(a, b) == -1);   /* origem inexistente */
  return 0;
}

static int test_rand_range(void) {
  R(fn_rand, rand)
  int mx = 0;
  for (int i = 0; i < 1000000; i++) {
    int v = rand();
    CHECK(v >= 0);
    if (v > mx) mx = v;
  }
  fprintf(stderr, "rand max = %d\n", mx);
  CHECK((unsigned)mx > 0x7FFF0000u);
  return 0;
}

static int test_fix_format(void) {
  static const char* const cases[][2] = {
    {"%ld", "%lld"}, {"%lu", "%llu"}, {"%lx", "%llx"}, {"%zu", "%zu"}, {"%%ld", "%%ld"},
    {"%-8.3ld", "%-8.3lld"}, {"%*ld", "%*lld"}, {"%lld", "%lld"}, {"%5.*lu|%lf", "%5.*llu|%lf"},
    {"a%+08ldb%%c%lxd", "a%+08lldb%%c%llxd"}, {"%s %d %f", "%s %d %f"},
  };
  for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
    char out[128];
    size_t n = dw_fix_format(cases[i][0], out, sizeof out);
    if (strcmp(out, cases[i][1]) != 0 || n != strlen(cases[i][1])) {
      fprintf(stderr, "fix_format(\"%s\") = \"%s\", esperado \"%s\"\n", cases[i][0], out, cases[i][1]);
      return 1;
    }
  }
  char small[4];
  CHECK(dw_fix_format("%ld", small, sizeof small) == 4 && strcmp(small, "%ll") == 0);
  CHECK(dw_fix_format("%ld", NULL, 0) == 4);
  return 0;
}

/* Chama fn(fmt, ...) com os args variadicos na pilha, em slots de 8 bytes, como a Apple faz. */
extern int call_apple_variadic(void* fn, const char* fmt, const uint64_t* slots, uint64_t n);
__asm__(
  ".text\n.p2align 2\n.globl call_apple_variadic\ncall_apple_variadic:\n"
  "  stp x29, x30, [sp, #-16]!\n"
  "  mov x29, sp\n"
  "  mov x16, x0\n"
  "  mov x0, x1\n"
  "  add x4, x3, #1\n"
  "  bic x4, x4, #1\n"          /* slots arredondados para par: sp fica alinhado em 16 */
  "  sub sp, sp, x4, lsl #3\n"
  "  mov x5, #0\n"
  "1:\n"
  "  cmp x5, x3\n"
  "  b.hs 2f\n"
  "  ldr x6, [x2, x5, lsl #3]\n"
  "  str x6, [sp, x5, lsl #3]\n"
  "  add x5, x5, #1\n"
  "  b 1b\n"
  "2:\n"
  "  blr x16\n"
  "  mov sp, x29\n"
  "  ldp x29, x30, [sp], #16\n"
  "  ret\n");

static uint64_t dbits(double d) { uint64_t u; memcpy(&u, &d, 8); return u; }

static int test_printf_apple_variadic(void) {
  void* pf = dw_resolve("printf");
  CHECK(pf);
  const char* p = path_in("libc_printf.txt");
  const char* str = "fim";
  /* %ld com valor > 32 bits, double, %lu, %s, e largura vinda de '*' */
  uint64_t slots[] = { (uint64_t)-5000000000LL, dbits(3.5), 7ull, (uint64_t)(uintptr_t)str, 6ull, 42ull, 0xFFFFFFFFFFull };
  fflush(stdout);
  int saved = _dup(1);
  int fd = _open(p, _O_WRONLY | _O_CREAT | _O_TRUNC | _O_BINARY, 0600);
  CHECK(saved >= 0 && fd >= 0);
  _dup2(fd, 1);
  _close(fd);
  int r = call_apple_variadic(pf, "%ld|%.2f|%lu|%s|%*ld|%lx\n", slots, 7);
  fflush(stdout);
  _dup2(saved, 1);
  _close(saved);
  const char* want = "-5000000000|3.50|7|fim|    42|ffffffffff\n";
  FILE* f = fopen(p, "rb");
  CHECK(f);
  char buf[256] = {0};
  size_t n = fread(buf, 1, sizeof buf - 1, f);
  fclose(f);
  remove(p);
  if (strcmp(buf, want) != 0) fprintf(stderr, "obtido \"%s\"\n", buf);
  CHECK(n == strlen(want) && strcmp(buf, want) == 0);
  CHECK(r == (int)strlen(want));
  return 0;
}

static int vs(fn_vsnprintf f, char* buf, uint64_t n, const char* fmt, ...) {
  __builtin_va_list ap;
  __builtin_va_start(ap, fmt);
  int r = f(buf, n, fmt, (char*)ap);
  __builtin_va_end(ap);
  return r;
}

static int test_vsnprintf_truncates(void) {
  R(fn_vsnprintf, vsnprintf)
  char buf[8];
  memset(buf, 'X', sizeof buf);
  int r = vs(vsnprintf, buf, sizeof buf, "%ld-%s", 1234567890123L, "abcdef");
  CHECK(r == 13 + 1 + 6 - 0);          /* "1234567890123-abcdef" = 20 */
  CHECK(strcmp(buf, "1234567") == 0 && buf[7] == 0);
  char big[32];
  r = vs(vsnprintf, big, sizeof big, "%-5d|", 42);
  CHECK(r == 6 && strcmp(big, "42   |") == 0);
  return 0;
}

static int test_sincos_stret(void) {
  fn_sincos f = (fn_sincos)dw_resolve("__sincos_stret");
  CHECK(f);
  sincos_t r = f(1.0);
  CHECK(r.s == sin(1.0) && r.c == cos(1.0));
  return 0;
}

static int test_gettimeofday(void) {
  fn_gtod f = (fn_gtod)dw_resolve("gettimeofday");
  CHECK(f);
  struct { int64_t sec; int32_t usec; int32_t pad; } tv = {0};
  CHECK(f(&tv, NULL) == 0);
  CHECK(sizeof tv == 16);
  CHECK(tv.usec >= 0 && tv.usec < 1000000);
  int64_t d = (int64_t)time(NULL) - tv.sec;
  CHECK(d >= -5 && d <= 5);
  return 0;
}

/* Extras: math, strings, alocacao. */
static int test_misc(void) {
  typedef double (*d1)(double);
  typedef double (*d2)(double, double);
  typedef float (*f2)(float, float);
  CHECK(((d1)dw_resolve("sin"))(0.5) == sin(0.5));
  CHECK(((d1)dw_resolve("cos"))(0.5) == cos(0.5));
  CHECK(((d1)dw_resolve("tan"))(0.5) == tan(0.5));
  CHECK(((d1)dw_resolve("acos"))(0.5) == acos(0.5));
  CHECK(((d1)dw_resolve("asin"))(0.5) == asin(0.5));
  CHECK(((d2)dw_resolve("atan2"))(1, 2) == atan2(1, 2));
  CHECK(((d2)dw_resolve("pow"))(2, 10) == 1024.0);
  CHECK(((f2)dw_resolve("powf"))(2.0f, 3.0f) == 8.0f);
  CHECK(((d2)dw_resolve("fmod"))(7.5, 2) == 1.5);
  uint64_t (*sl)(const char*) = dw_resolve("strlen");
  char* (*rch)(const char*, int) = dw_resolve("strrchr");
  int (*cmp)(const void*, const void*, uint64_t) = dw_resolve("memcmp");
  void (*pat)(void*, const void*, uint64_t) = dw_resolve("memset_pattern16");
  void* (*cal)(uint64_t, uint64_t) = dw_resolve("calloc");
  CHECK(sl && rch && cmp && pat && cal);
  CHECK(sl("abcd") == 4);
  const char* s = "a/b/c";
  CHECK(rch(s, '/') == s + 3 && rch(s, 'z') == NULL);
  CHECK(cmp("abc", "abd", 3) < 0 && cmp("abc", "abc", 3) == 0);
  unsigned char pt[16]; for (int i = 0; i < 16; i++) pt[i] = (unsigned char)i;
  unsigned char b[40]; memset(b, 0xEE, sizeof b);
  pat(b, pt, 35);
  CHECK(b[0] == 0 && b[15] == 15 && b[16] == 0 && b[34] == 2 && b[35] == 0xEE);
  unsigned char* z = cal(10, 10);
  CHECK(z);
  for (int i = 0; i < 100; i++) CHECK(z[i] == 0);
  dw_free(z);
  CHECK(cal(UINT64_MAX / 2, 4) == NULL);
  return 0;
}

/* ---- M3 Tarefa 2: superficie da libjassimp.dylib ---- */

/* snprintf(buf, n, fmt, ...) com os variadicos na pilha, em slots de 8 bytes (convencao Apple). */
extern int call_apple_snprintf(void* fn, char* buf, uint64_t n, const char* fmt, const uint64_t* slots, uint64_t count);
__asm__(
  ".text\n.p2align 2\n.globl call_apple_snprintf\ncall_apple_snprintf:\n"
  "  stp x29, x30, [sp, #-16]!\n"
  "  mov x29, sp\n"
  "  mov x16, x0\n"
  "  mov x0, x1\n"
  "  mov x1, x2\n"
  "  mov x2, x3\n"
  "  add x7, x5, #1\n"
  "  bic x7, x7, #1\n"
  "  sub sp, sp, x7, lsl #3\n"
  "  mov x6, #0\n"
  "1:\n"
  "  cmp x6, x5\n"
  "  b.hs 2f\n"
  "  ldr x7, [x4, x6, lsl #3]\n"
  "  str x7, [sp, x6, lsl #3]\n"
  "  add x6, x6, #1\n"
  "  b 1b\n"
  "2:\n"
  "  blr x16\n"
  "  mov sp, x29\n"
  "  ldp x29, x30, [sp], #16\n"
  "  ret\n");

static int test_snprintf_apple(void) {
  void* sp = dw_resolve("snprintf");
  CHECK(sp);
  char buf[128];
  const char* str = "fim";
  uint64_t slots[] = { (uint64_t)-5000000000LL, dbits(3.5), 7ull, (uint64_t)(uintptr_t)str, 0xFFFFFFFFFFull };
  int r = call_apple_snprintf(sp, buf, sizeof buf, "%ld|%.2f|%lu|%s|%lx", slots, 5);
  const char* want = "-5000000000|3.50|7|fim|ffffffffff";
  if (strcmp(buf, want) != 0) fprintf(stderr, "obtido \"%s\"\n", buf);
  CHECK(strcmp(buf, want) == 0 && r == (int)strlen(want));
  char small[8];
  r = call_apple_snprintf(sp, small, sizeof small, "%s", (uint64_t[]){ (uint64_t)(uintptr_t)"abcdefghijkl" }, 1);
  CHECK(r == 12 && strcmp(small, "abcdefg") == 0);   /* trunca com NUL, devolve o tamanho completo */
  return 0;
}

static int test_ctype_table(void) {
  const unsigned char* rl = dw_resolve("_DefaultRuneLocale");
  int (*mask)(int, uint64_t) = dw_resolve("__maskrune");
  int (*lower)(int) = dw_resolve("__tolower");
  CHECK(rl && mask && lower);
  /* __runetype[256] em +60 (offset lido no desmonte de libjassimp.dylib: ldr w8, [x20, x0, lsl #2 + 0x3c]) */
  const uint32_t* rt = (const uint32_t*)(rl + 60);
  CHECK(rt['a'] & 0x1000 && rt['a'] & 0x100);          /* lower, alpha */
  CHECK(!(rt['a'] & 0x8000));
  CHECK(rt['Z'] & 0x8000 && rt['Z'] & 0x100);          /* upper, alpha */
  CHECK(rt['0'] & 0x400 && !(rt['0'] & 0x100));        /* digit */
  CHECK(rt[' '] & 0x4000 && rt[' '] & 0x20000);        /* space, blank */
  CHECK(!(rt[' '] & 0x800) && (rt[' '] & 0x40000));    /* print mas nao graph */
  CHECK(rt['\n'] & 0x4000 && rt['\n'] & 0x200);        /* space, control */
  CHECK(!(rt['\n'] & 0x20000));                        /* nao e blank */
  CHECK(rt['!'] & 0x2000 && rt['!'] & 0x800);          /* punct, graph */
  CHECK(rt['f'] & 0x10000 && !(rt['g'] & 0x10000));    /* xdigit */
  CHECK(rt[0x80] == 0 && rt[0xff] == 0);               /* locale C: acima de ASCII nao classifica */
  CHECK(memcmp(rl, "RuneMagi", 8) == 0);
  /* __maskrune(c, mask) == runetype[c] & mask, tambem para c >= 128 */
  CHECK(mask('a', 0x1000) != 0 && mask('A', 0x1000) == 0 && mask('A', 0x8000) != 0);
  CHECK(mask('5', 0x400) != 0 && mask(' ', 0x4000) != 0 && mask('\n', 0x4000) != 0 && mask('x', 0x4000) == 0);
  CHECK(mask(0xE9, 0x100) == 0 && mask(-1, 0x4000) == 0 && mask(0x10000, 0x100) == 0);
  CHECK(lower('A') == 'a' && lower('Z') == 'z' && lower('a') == 'a' && lower('0') == '0' && lower(-1) == -1 && lower(0xC9) == 0xC9);
  return 0;
}

static int test_string_funcs(void) {
  char* (*strchr_)(const char*, int) = dw_resolve("strchr");
  int (*strcmp_)(const char*, const char*) = dw_resolve("strcmp");
  char* (*strcpy_)(char*, const char*) = dw_resolve("strcpy");
  char* (*strncpy_)(char*, const char*, uint64_t) = dw_resolve("strncpy");
  int (*strncmp_)(const char*, const char*, uint64_t) = dw_resolve("strncmp");
  char* (*strstr_)(const char*, const char*) = dw_resolve("strstr");
  int (*strncasecmp_)(const char*, const char*, uint64_t) = dw_resolve("strncasecmp");
  void* (*memchr_)(const void*, int, uint64_t) = dw_resolve("memchr");
  int32_t* (*wmemchr_)(const int32_t*, int32_t, uint64_t) = dw_resolve("wmemchr");
  void* (*realloc_)(void*, uint64_t) = dw_resolve("realloc");
  int (*atoi_)(const char*) = dw_resolve("atoi");
  CHECK(strchr_ && strcmp_ && strcpy_ && strncpy_ && strncmp_ && strstr_ && strncasecmp_ && memchr_ && wmemchr_ && realloc_ && atoi_);
  const char* s = "hello world";
  CHECK(strchr_(s, 'o') == s + 4 && strchr_(s, 'z') == NULL && strchr_(s, 0) == s + 11);
  CHECK(strcmp_("a", "b") < 0 && strcmp_("b", "a") > 0 && strcmp_("abc", "abc") == 0);
  char b[16];
  CHECK(strcpy_(b, "abc") == b && strcmp_(b, "abc") == 0);
  memset(b, 'x', sizeof b);
  strncpy_(b, "ab", 5);
  CHECK(b[0] == 'a' && b[1] == 'b' && b[2] == 0 && b[4] == 0 && b[5] == 'x');   /* completa com NUL */
  CHECK(strncmp_("abcX", "abcY", 3) == 0 && strncmp_("abcX", "abcY", 4) < 0);
  CHECK(strstr_(s, "wor") == s + 6 && strstr_(s, "xyz") == NULL && strstr_(s, "") == s);
  CHECK(strncasecmp_("HeLLo", "hello", 5) == 0 && strncasecmp_("HeLLo", "hellp", 5) < 0 && strncasecmp_("abcZ", "ABCz", 3) == 0);
  CHECK(memchr_(s, 'w', 11) == s + 6 && memchr_(s, 'w', 6) == NULL);
  int32_t w[6] = { 'a', 0x1F600, 0x10000, 'z', 0x10001, 0 };     /* wchar_t Apple = 32 bits */
  CHECK(wmemchr_(w, 0x1F600, 6) == w + 1 && wmemchr_(w, 0x10001, 6) == w + 4 && wmemchr_(w, 0x10001, 4) == NULL);
  CHECK(wmemchr_(w, 0x1F601, 6) == NULL);                        /* nao compara so os 16 bits baixos */
  CHECK(atoi_("123") == 123 && atoi_("-45x") == -45 && atoi_("  7") == 7 && atoi_("zzz") == 0);
  unsigned char* p = realloc_(NULL, 10);
  CHECK(p);
  for (int i = 0; i < 10; i++) p[i] = (unsigned char)(i + 1);
  p = realloc_(p, 5000);
  CHECK(p && dw_malloc_size(p) == 5000);
  for (int i = 0; i < 10; i++) CHECK(p[i] == i + 1);
  p = realloc_(p, 4);
  CHECK(p && p[3] == 4 && dw_malloc_size(p) == 4);
  dw_free(p);
  return 0;
}

static int test_math_float(void) {
  typedef float (*f1)(float);
  typedef float (*f2)(float, float);
  typedef struct { float s, c; } sc_t;
  typedef sc_t (*fsc)(float);
  const float xs[] = { -0.9f, -0.25f, 0.0f, 0.3f, 0.77f, 1.0f };
  for (size_t i = 0; i < sizeof xs / sizeof xs[0]; i++) {
    float x = xs[i];
    CHECK(((f1)dw_resolve("acosf"))(x) == acosf(x));
    CHECK(((f1)dw_resolve("asinf"))(x) == asinf(x));
    CHECK(((f1)dw_resolve("atanf"))(x) == atanf(x));
    CHECK(((f1)dw_resolve("cosf"))(x) == cosf(x));
    CHECK(((f1)dw_resolve("tanf"))(x) == tanf(x));
    CHECK(((f2)dw_resolve("atan2f"))(x, 0.5f) == atan2f(x, 0.5f));
    sc_t r = ((fsc)dw_resolve("__sincosf_stret"))(x);
    CHECK(r.s == sinf(x) && r.c == cosf(x));
  }
  f1 e = (f1)dw_resolve("__exp10f");
  CHECK(e(0.0f) == 1.0f && e(2.0f) == 100.0f && e(-1.0f) == 0.1f);
  CHECK(fabsf(e(0.5f) - 3.1622777f) < 3e-7f);
  return 0;
}

static int test_fseek_ftell(void) {
  void* (*fopen_)(const char*, const char*) = dw_resolve("fopen");
  int (*fclose_)(void*) = dw_resolve("fclose");
  int (*fseek_)(void*, int64_t, int) = dw_resolve("fseek");
  int64_t (*ftell_)(void*) = dw_resolve("ftell");
  uint64_t (*fwrite_)(const void*, uint64_t, uint64_t, void*) = dw_resolve("fwrite");
  CHECK(fopen_ && fclose_ && fseek_ && ftell_ && fwrite_);
  void* f = fopen_(path_in("libc_seek.bin"), "w+");
  CHECK(f);
  DWORD got;
  DeviceIoControl((HANDLE)_get_osfhandle(_fileno((FILE*)f)), FSCTL_SET_SPARSE, NULL, 0, NULL, 0, &got, NULL);
  CHECK(fseek_(f, 5000000000LL, 0) == 0);                 /* long Apple = 64 bits: mais de 4 GiB (arquivo esparso) */
  CHECK(ftell_(f) == 5000000000LL);
  CHECK(fwrite_("Z", 1, 1, f) == 1 && ftell_(f) == 5000000001LL);
  CHECK(fseek_(f, -1, 2) == 0 && ftell_(f) == 5000000000LL);   /* SEEK_END */
  CHECK(fseek_(f, 0, 0) == 0 && ftell_(f) == 0);
  fclose_(f);
  remove(path_in("libc_seek.bin"));
  return 0;
}

static int test_fs(void) {
  int (*stat_)(const char*, void*) = dw_resolve("stat");
  int (*mkdir_)(const char*, int) = dw_resolve("mkdir");
  int (*chdir_)(const char*) = dw_resolve("chdir");
  int (*remove_)(const char*) = dw_resolve("remove");
  char* (*realpath_)(const char*, char*) = dw_resolve("realpath$DARWIN_EXTSN");
  CHECK(stat_ && mkdir_ && chdir_ && remove_ && realpath_);
  char file[1024], dir[1024], nested[1024];
  snprintf(file, sizeof file, "%s", path_in("libc_fs_a\xC3\xA7\xC3\xA3o.txt"));   /* UTF-8 */
  snprintf(dir, sizeof dir, "%s/libc_fs_d\xC3\xADr", g_dir);
  snprintf(nested, sizeof nested, "%s/sub", dir);
  remove_(file); remove_(nested); remove_(dir);
  {   /* fopen da tabela: aceita UTF-8 (o fopen da CRT de teste, nao) */
    void* (*fopen_)(const char*, const char*) = dw_resolve("fopen");
    int (*fclose_)(void*) = dw_resolve("fclose");
    uint64_t (*fwrite_)(const void*, uint64_t, uint64_t, void*) = dw_resolve("fwrite");
    void* f = fopen_(file, "w");
    CHECK(f && fwrite_("0123456789", 1, 10, f) == 10 && fclose_(f) == 0);
  }

  /* struct stat Apple arm64: st_mode @4 (u16), st_size @96 (i64), 144 bytes */
  unsigned char st[144];
  memset(st, 0xCC, sizeof st);
  CHECK(stat_(file, st) == 0);
  uint16_t mode; int64_t size;
  memcpy(&mode, st + 4, 2); memcpy(&size, st + 96, 8);
  CHECK(size == 10 && (mode & 0170000) == 0100000 && (mode & 0400));
  int64_t mtime; memcpy(&mtime, st + 48, 8);
  CHECK(mtime > 1700000000LL);                          /* st_mtimespec.tv_sec @48 */
  CHECK(stat_(g_dir, st) == 0);
  memcpy(&mode, st + 4, 2);
  CHECK((mode & 0170000) == 0040000);
  errno = 0;
  CHECK(stat_(path_in("libc_nao_existe.bin"), st) == -1 && errno == ENOENT);

  CHECK(mkdir_(dir, 0777) == 0);                         /* modo ignorado */
  errno = 0;
  CHECK(mkdir_(dir, 0777) == -1 && errno == EEXIST);
  CHECK(mkdir_(nested, 0) == 0);
  CHECK(stat_(nested, st) == 0);

  /* realpath com buffer NULL: aloca com dw_malloc (a dylib libera com free), caminho absoluto com '/' */
  char rel[1024];
  snprintf(rel, sizeof rel, "%s/./sub/../sub", dir);
  char* rp = realpath_(rel, NULL);
  CHECK(rp);
  CHECK(dw_malloc_size(rp) == strlen(rp) + 1);
  CHECK(strchr(rp, '\\') == NULL && strstr(rp, "/sub") && strstr(rp, "d\xC3\xADr") && !strstr(rp, "/./") && !strstr(rp, ".."));
  dw_free(rp);
  char buf[1024];
  CHECK(realpath_(file, buf) == buf && strstr(buf, "a\xC3\xA7\xC3\xA3o.txt"));
  errno = 0;
  CHECK(realpath_(path_in("libc_nao_existe.bin"), NULL) == NULL && errno == ENOENT);

  /* buffer do chamador: saida >= 1024 bytes (com NUL) -> ENAMETOOLONG; buffer NULL aloca o necessario */
  {
    char lp[2048];
    int n = snprintf(lp, sizeof lp, "//?/%s", dir);
    wchar_t wl[2048];
    int created = 0, ok = 0, got_small = 0, got_big = 0;
    while (n < 1030) {
      int room = 1030 - n - 1;                    /* '/' + nome */
      int len = room > 40 ? 40 : room;
      if (len < 1) break;
      n += snprintf(lp + n, sizeof lp - n, "/%.*s", len, "0123456789abcdef0123456789abcdef0123456789abcdef");
      MultiByteToWideChar(CP_UTF8, 0, lp, -1, wl, 2048);
      for (wchar_t* c = wl; *c; c++) if (*c == L'/') *c = L'\\';
      if (!CreateDirectoryW(wl, NULL) && GetLastError() != ERROR_ALREADY_EXISTS) break;
      created++;
    }
    if (n == 1030 && created) {
      char small[1024];
      errno = 0;
      ok = 1;
      char lpb[2048];                               /* prefixo \\?\ exige barras invertidas */
      strcpy(lpb, lp);
      for (char* c = lpb; *c; c++) if (*c == '/') *c = '\\';
      got_small = (realpath_(lpb, small) == NULL && errno == ENAMETOOLONG);
      char* big = realpath_(lpb, NULL);
      got_big = (big && strlen(big) >= 1024);
      if (big) dw_free(big);
    }
    for (int k = created; k > 0; k--) {          /* remove a cadeia, do fundo para a raiz */
      MultiByteToWideChar(CP_UTF8, 0, lp, -1, wl, 2048);
      for (wchar_t* c = wl; *c; c++) if (*c == L'/') *c = L'\\';
      RemoveDirectoryW(wl);
      char* sl = strrchr(lp, '/');
      if (sl) *sl = 0;
    }
    if (ok) { CHECK(got_small); CHECK(got_big); }
    else printf("realpath longo: sem suporte a caminho longo neste ambiente (pulado)\n");
  }

  /* chdir muda o diretorio do processo (caminho UTF-8) */
  char saved[1024];
  GetCurrentDirectoryA(sizeof saved, saved);
  CHECK(chdir_(dir) == 0);
  wchar_t cwd[1024];
  GetCurrentDirectoryW(1024, cwd);
  CHECK(wcsstr(cwd, L"dír") != NULL);
  CHECK(chdir_(saved) == 0);
  CHECK(chdir_(path_in("libc_nao_existe_dir")) == -1);

  /* remove apaga arquivo e diretorio vazio, falha com diretorio nao vazio e com inexistente */
  CHECK(remove_(dir) == -1);                             /* nao vazio */
  CHECK(remove_(nested) == 0);
  CHECK(remove_(dir) == 0);
  CHECK(remove_(file) == 0);
  errno = 0;
  CHECK(remove_(file) == -1 && errno == ENOENT);
  return 0;
}

/* exit(): descarrega os streams abertos e termina o processo com o codigo dado. */
static int test_exit_child(void) {
  void* (*fopen_)(const char*, const char*) = dw_resolve("fopen");
  uint64_t (*fwrite_)(const void*, uint64_t, uint64_t, void*) = dw_resolve("fwrite");
  void (*exit_)(int) = dw_resolve("exit");
  void* f = fopen_(path_in("libc_exit.txt"), "w");
  if (!f) return 90;
  fwrite_("flushed", 1, 7, f);        /* sem fclose nem fflush */
  exit_(23);
  return 91;
}
static int test_exit(void) {
  char cmd[2048];
  char exe[1024];
  GetModuleFileNameA(NULL, exe, sizeof exe);
  remove(path_in("libc_exit.txt"));
  snprintf(cmd, sizeof cmd, "\"%s\" exit_child \"%s\"", exe, g_dir);
  STARTUPINFOA si; memset(&si, 0, sizeof si); si.cb = sizeof si;
  PROCESS_INFORMATION pi;
  CHECK(CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi));
  WaitForSingleObject(pi.hProcess, 20000);
  DWORD code = 0;
  GetExitCodeProcess(pi.hProcess, &code);
  CloseHandle(pi.hProcess); CloseHandle(pi.hThread);
  CHECK(code == 23);
  FILE* f = fopen(path_in("libc_exit.txt"), "rb");
  CHECK(f);
  char b[16] = {0};
  CHECK(fread(b, 1, 15, f) == 7 && strcmp(b, "flushed") == 0);
  fclose(f);
  remove(path_in("libc_exit.txt"));
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 3) { fputs("uso: test_libc <teste> <pasta>\n", stderr); return 2; }
  g_dir = argv[2];
  static const struct { const char* n; int (*f)(void); } T[] = {
    {"fopen_binary", test_fopen_binary}, {"fseeko_64bit", test_fseeko_64bit},
    {"fopen_unicode", test_fopen_unicode}, {"rename_overwrites", test_rename_overwrites},
    {"rand_range", test_rand_range}, {"fix_format", test_fix_format},
    {"printf_apple_variadic", test_printf_apple_variadic},
    {"vsnprintf_truncates", test_vsnprintf_truncates}, {"sincos_stret", test_sincos_stret},
    {"gettimeofday", test_gettimeofday}, {"misc", test_misc},
    {"snprintf_apple", test_snprintf_apple}, {"ctype_table", test_ctype_table}, {"string_funcs", test_string_funcs},
    {"math_float", test_math_float}, {"fseek_ftell", test_fseek_ftell}, {"fs", test_fs}, {"exit", test_exit},
    {"exit_child", test_exit_child},
  };
  for (size_t i = 0; i < sizeof T / sizeof T[0]; i++)
    if (!strcmp(argv[1], T[i].n)) {
      int r = T[i].f();
      if (!r) printf("PASS %s\n", argv[1]);
      return r;
    }
  fprintf(stderr, "teste desconhecido: %s\n", argv[1]);
  return 2;
}
