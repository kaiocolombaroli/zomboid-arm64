/* Testes de pthread/sched/dispatch e das demais importacoes da libSystem de libfmodintegration.dylib.
   Tudo e chamado por dw_resolve (a tabela real). Uso: test_pthread <teste> [args] */
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <windows.h>
#include "darwin.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)
#define R(T, name) T name = (T)dw_resolve(#name); if (!name) { fprintf(stderr, "sem %s\n", #name); return 1; }

#define A_ETIMEDOUT 60
#define A_EBUSY 16
#define A_EINVAL 22

typedef uint64_t mutex_t[8];    /* pthread_mutex_t Apple: 64 bytes */
typedef uint64_t cond_t[6];     /* pthread_cond_t Apple: 48 bytes */
typedef uint64_t once_t[2];     /* pthread_once_t Apple: 16 bytes */
typedef uint64_t attr_t[8];     /* pthread_attr_t Apple: 64 bytes */
typedef struct { int64_t sec; int64_t nsec; } ts_t;
typedef void* (*start_t)(void*);

typedef int (*f_minit)(void*, const void*);
typedef int (*f_m1)(void*);
typedef int (*f_cwait)(void*, void*);
typedef int (*f_ctwait)(void*, void*, const void*);
typedef int (*f_create)(void**, const void*, start_t, void*);
typedef int (*f_join)(void*, void**);
typedef int (*f_once)(void*, void (*)(void));

/* inicializadores estaticos da Apple, como o codigo da dylib os grava inline */
static void apple_mutex(mutex_t m) { memset(m, 0, sizeof(mutex_t)); m[0] = 0x32AAABA7; }
static void apple_cond(cond_t c) { memset(c, 0, sizeof(cond_t)); c[0] = 0x3CB0B1BB; }
static void apple_once(once_t o) { memset(o, 0, sizeof(once_t)); o[0] = 0x30B1BCBA; }

static void now_ts(ts_t* t, int64_t add_ns) {
  FILETIME ft; GetSystemTimePreciseAsFileTime(&ft);
  uint64_t v = (((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) - 116444736000000000ull;
  int64_t ns = (int64_t)(v % 10000000ull) * 100 + add_ns;
  int64_t sec = (int64_t)(v / 10000000ull);
  sec += ns / 1000000000; ns %= 1000000000;
  if (ns < 0) { ns += 1000000000; sec--; }
  t->sec = sec; t->nsec = ns;
}
static double ms_now(void) {
  LARGE_INTEGER f, c; QueryPerformanceFrequency(&f); QueryPerformanceCounter(&c);
  return 1000.0 * (double)c.QuadPart / (double)f.QuadPart;
}

/* ---- mutex ---- */
static struct { mutex_t m; long long counter; } g_c;
static f_m1 g_lock, g_unlock;
static void* contention_worker(void* arg) {
  (void)arg;
  for (int i = 0; i < 125000; i++) { g_lock(g_c.m); g_c.counter++; g_unlock(g_c.m); }
  return (void*)(uintptr_t)7;
}
static int test_mutex_contention(void) {
  R(f_create, pthread_create) R(f_join, pthread_join) R(f_minit, pthread_mutex_init)
  g_lock = dw_resolve("pthread_mutex_lock"); g_unlock = dw_resolve("pthread_mutex_unlock");
  CHECK(g_lock && g_unlock);
  CHECK(pthread_mutex_init(g_c.m, NULL) == 0);
  void* th[8];
  for (int i = 0; i < 8; i++) CHECK(pthread_create(&th[i], NULL, contention_worker, NULL) == 0);
  for (int i = 0; i < 8; i++) { void* r = NULL; CHECK(pthread_join(th[i], &r) == 0 && r == (void*)7); }
  CHECK(g_c.counter == 1000000);
  /* mesmo teste com o inicializador estatico Apple (lazy) */
  apple_mutex(g_c.m); g_c.counter = 0;
  for (int i = 0; i < 8; i++) CHECK(pthread_create(&th[i], NULL, contention_worker, NULL) == 0);
  for (int i = 0; i < 8; i++) CHECK(pthread_join(th[i], NULL) == 0);
  CHECK(g_c.counter == 1000000);
  return 0;
}

static int test_mutex_reinit(void) {
  R(f_minit, pthread_mutex_init) R(f_m1, pthread_mutex_destroy) R(f_m1, pthread_mutex_lock) R(f_m1, pthread_mutex_unlock)
  mutex_t m;
  CHECK(pthread_mutex_init(m, NULL) == 0);
  CHECK(pthread_mutex_lock(m) == 0);
  CHECK(pthread_mutex_destroy(m) == A_EBUSY);          /* travado */
  CHECK(pthread_mutex_unlock(m) == 0);
  CHECK(pthread_mutex_destroy(m) == 0);
  CHECK(pthread_mutex_lock(m) == A_EINVAL);            /* destruido */
  CHECK(pthread_mutex_init(m, NULL) == 0);             /* recriado no mesmo buffer */
  CHECK(pthread_mutex_lock(m) == 0);                   /* nao herda o estado antigo (nao trava) */
  CHECK(pthread_mutex_unlock(m) == 0);
  /* buffer travado e depois reaproveitado como mutex novo (construtor de std::mutex zera os 64 bytes) */
  CHECK(pthread_mutex_lock(m) == 0);
  apple_mutex(m);
  CHECK(pthread_mutex_lock(m) == 0);
  CHECK(pthread_mutex_unlock(m) == 0);
  CHECK(pthread_mutex_destroy(m) == 0);
  /* destruir e recriar via inicializador estatico */
  apple_mutex(m);
  CHECK(pthread_mutex_destroy(m) == 0);
  apple_mutex(m);
  CHECK(pthread_mutex_lock(m) == 0 && pthread_mutex_unlock(m) == 0);
  return 0;
}

/* std::mutex da dylib: lock/unlock/~mutex sobre o buffer inicializado inline */
static int test_mutex_std(void) {
  f_m1 _ZNSt3__15mutex4lockEv = dw_resolve("_ZNSt3__15mutex4lockEv");
  f_m1 _ZNSt3__15mutex6unlockEv = dw_resolve("_ZNSt3__15mutex6unlockEv");
  f_m1 _ZNSt3__15mutexD1Ev = dw_resolve("_ZNSt3__15mutexD1Ev");
  CHECK(_ZNSt3__15mutex4lockEv && _ZNSt3__15mutex6unlockEv && _ZNSt3__15mutexD1Ev);
  mutex_t m;
  apple_mutex(m);
  _ZNSt3__15mutex4lockEv(m);
  _ZNSt3__15mutex6unlockEv(m);
  _ZNSt3__15mutex4lockEv(m);
  _ZNSt3__15mutex6unlockEv(m);
  _ZNSt3__15mutexD1Ev(m);
  return 0;
}

/* ---- cond ---- */
static struct { mutex_t m; cond_t c; int flag; } g_s;
static f_m1 g_cbcast;
static f_cwait g_cwait;
static void* signal_worker(void* arg) {
  (void)arg;
  Sleep(30);
  g_lock(g_s.m); g_s.flag = 1; g_cbcast(g_s.c); g_unlock(g_s.m);
  return NULL;
}
static int test_cond_signal(void) {
  R(f_create, pthread_create) R(f_join, pthread_join)
  R(f_minit, pthread_mutex_init) R(f_minit, pthread_cond_init) R(f_m1, pthread_cond_destroy)
  g_lock = dw_resolve("pthread_mutex_lock"); g_unlock = dw_resolve("pthread_mutex_unlock");
  g_cbcast = dw_resolve("pthread_cond_broadcast"); g_cwait = dw_resolve("pthread_cond_wait");
  CHECK(g_lock && g_unlock && g_cbcast && g_cwait);
  for (int variant = 0; variant < 2; variant++) {
    if (variant == 0) { pthread_mutex_init(g_s.m, NULL); pthread_cond_init(g_s.c, NULL); }
    else { apple_mutex(g_s.m); apple_cond(g_s.c); }            /* inicializadores Apple (lazy) */
    g_s.flag = 0;
    void* th;
    CHECK(pthread_create(&th, NULL, signal_worker, NULL) == 0);
    g_lock(g_s.m);
    while (!g_s.flag) CHECK(g_cwait(g_s.c, g_s.m) == 0);
    g_unlock(g_s.m);
    CHECK(pthread_join(th, NULL) == 0);
    CHECK(pthread_cond_destroy(g_s.c) == 0);
  }
  return 0;
}

static int test_cond_timeout_past(void) {
  R(f_minit, pthread_mutex_init) R(f_m1, pthread_mutex_lock) R(f_m1, pthread_mutex_unlock)
  R(f_minit, pthread_cond_init) R(f_ctwait, pthread_cond_timedwait)
  mutex_t m; cond_t c; ts_t t;
  pthread_mutex_init(m, NULL); pthread_cond_init(c, NULL);
  pthread_mutex_lock(m);
  now_ts(&t, -1000000000LL);                               /* 1 s no passado */
  double t0 = ms_now();
  CHECK(pthread_cond_timedwait(c, m, &t) == A_ETIMEDOUT);
  CHECK(ms_now() - t0 < 100.0);
  now_ts(&t, 0);
  CHECK(pthread_cond_timedwait(c, m, &t) == A_ETIMEDOUT);  /* agora */
  now_ts(&t, 60 * 1000000LL);                              /* 60 ms no futuro */
  t0 = ms_now();
  CHECK(pthread_cond_timedwait(c, m, &t) == A_ETIMEDOUT);
  double dt = ms_now() - t0;
  CHECK(dt >= 40.0 && dt < 500.0);
  pthread_mutex_unlock(m);                                 /* o mutex voltou travado pela espera */
  ts_t bad = { 0, 2000000000LL };
  pthread_mutex_lock(m);
  CHECK(pthread_cond_timedwait(c, m, &bad) == A_EINVAL);
  pthread_mutex_unlock(m);
  return 0;
}

/* ---- once ---- */
static volatile LONG g_once_n;
static void once_fn(void) { Sleep(10); InterlockedIncrement(&g_once_n); }
static once_t g_once;
static f_once g_ponce;
static void* once_worker(void* a) { (void)a; g_ponce(g_once, once_fn); return NULL; }
static int test_once_single(void) {
  R(f_create, pthread_create) R(f_join, pthread_join)
  g_ponce = dw_resolve("pthread_once"); CHECK(g_ponce);
  apple_once(g_once);
  void* th[6];
  for (int i = 0; i < 6; i++) CHECK(pthread_create(&th[i], NULL, once_worker, NULL) == 0);
  for (int i = 0; i < 6; i++) CHECK(pthread_join(th[i], NULL) == 0);
  CHECK(g_once_n == 1);
  CHECK(g_ponce(g_once, once_fn) == 0 && g_once_n == 1);
  once_t bad = {0, 0};
  CHECK(g_ponce(bad, once_fn) == A_EINVAL);
  return 0;
}

/* ---- threads ---- */
static void* retval_fn(void* a) { return (void*)((uintptr_t)a * 3 + 1); }
static int test_create_join_retval(void) {
  R(f_create, pthread_create) R(f_join, pthread_join)
  void* th; void* ret = NULL;
  CHECK(pthread_create(&th, NULL, retval_fn, (void*)14) == 0);
  CHECK(pthread_join(th, &ret) == 0 && ret == (void*)43);
  return 0;
}

static void* stack_fn(void* a) {
  (void)a;
  MEMORY_BASIC_INFORMATION mbi;
  char probe = 0;
  VirtualQuery(&probe, &mbi, sizeof mbi);              /* AllocationBase = inicio da reserva da pilha */
  return (void*)((uintptr_t)((NT_TIB*)NtCurrentTeb())->StackBase - (uintptr_t)mbi.AllocationBase);
}
static int test_stack_size(void) {
  R(f_create, pthread_create) R(f_join, pthread_join)
  R(f_m1, pthread_attr_init) R(f_m1, pthread_attr_destroy)
  int (*setss)(void*, uint64_t) = dw_resolve("pthread_attr_setstacksize");
  CHECK(setss);
  attr_t a;
  CHECK(pthread_attr_init(a) == 0);
  CHECK(setss(a, 100) == A_EINVAL);                         /* menor que o minimo da Apple */
  CHECK(setss(a, 16384 + 1) == A_EINVAL);                   /* nao multiplo de pagina */
  CHECK(setss(a, 4u << 20) == 0);
  void* th; void* ret = NULL;
  CHECK(pthread_create(&th, a, stack_fn, NULL) == 0);
  CHECK(pthread_join(th, &ret) == 0);
  CHECK((uintptr_t)ret >= (4u << 20) - 65536 && (uintptr_t)ret <= (5u << 20));
  CHECK(pthread_attr_destroy(a) == 0);
  return 0;
}

static void* self_fn(void* out) {
  void* (*self)(void) = dw_resolve("pthread_self");
  void** o = out;
  o[0] = self(); o[1] = self();
  return NULL;
}
static void* foreign_fn(void* out) {          /* thread criada fora da camada pthread */
  void* (*self)(void) = dw_resolve("pthread_self");
  uint32_t (*mach)(void*) = dw_resolve("pthread_mach_thread_np");
  void** o = out;
  o[0] = self(); o[1] = self();
  o[2] = (void*)(uintptr_t)mach(o[0]);
  return NULL;
}
static DWORD WINAPI foreign_main(LPVOID p) { foreign_fn(p); return 0; }
static int test_self_equal(void) {
  R(f_create, pthread_create) R(f_join, pthread_join)
  void* (*self)(void) = dw_resolve("pthread_self");
  int (*equal)(void*, void*) = dw_resolve("pthread_equal");
  uint32_t (*mach)(void*) = dw_resolve("pthread_mach_thread_np");
  CHECK(self && equal && mach);
  void* me = self();
  CHECK(me && equal(me, self()) && mach(me) == GetCurrentThreadId());
  void* o[3] = {0}; void* th;
  CHECK(pthread_create(&th, NULL, self_fn, o) == 0);
  CHECK(pthread_join(th, NULL) == 0);
  CHECK(o[0] && o[0] == o[1] && !equal(o[0], me));
  void* f[3] = {0};
  HANDLE h = CreateThread(NULL, 0, foreign_main, f, 0, NULL);
  CHECK(h);
  WaitForSingleObject(h, INFINITE); CloseHandle(h);
  CHECK(f[0] && f[0] == f[1] && !equal(f[0], me) && f[2] != 0);
  return 0;
}

static int test_setname_noop(void) {
  int (*setname)(const char*) = dw_resolve("pthread_setname_np");
  int (*setsched)(void*, int, const void*) = dw_resolve("pthread_setschedparam");
  int (*pmin)(int) = dw_resolve("sched_get_priority_min");
  int (*pmax)(int) = dw_resolve("sched_get_priority_max");
  int (*yield)(void) = dw_resolve("sched_yield");
  void* (*self)(void) = dw_resolve("pthread_self");
  CHECK(setname && setsched && pmin && pmax && yield && self);
  CHECK(setname("Jarvis-FMOD-test") == 0);
  int sp[2] = {20, 0};
  CHECK(setsched(self(), 1, sp) == 0);
  CHECK(pmin(1) <= pmax(1) && pmin(1) >= 0 && pmin(2) <= pmax(2) && pmin(4) <= pmax(4));
  CHECK(pmin(99) == -1);
  CHECK(yield() == 0);
  return 0;
}

/* ---- dispatch_semaphore ---- */
static void* g_sema;
static void* sema_worker(void* a) {
  (void)a;
  int64_t (*sig)(void*) = dw_resolve("dispatch_semaphore_signal");
  Sleep(40);
  sig(g_sema);
  return NULL;
}
static uint64_t mach_ns(void) {
  LARGE_INTEGER f, c; QueryPerformanceFrequency(&f); QueryPerformanceCounter(&c);
  return (uint64_t)((__int128)c.QuadPart * 1000000000 / f.QuadPart);
}
static int test_dispatch_semaphore(void) {
  R(f_create, pthread_create) R(f_join, pthread_join)
  void* (*create)(int64_t) = dw_resolve("dispatch_semaphore_create");
  int64_t (*wait)(void*, uint64_t) = dw_resolve("dispatch_semaphore_wait");
  int64_t (*sig)(void*) = dw_resolve("dispatch_semaphore_signal");
  void (*rel)(void*) = dw_resolve("dispatch_release");
  CHECK(create && wait && sig && rel);
  CHECK(create(-1) == NULL);
  void* s = create(1);
  CHECK(s);
  CHECK(wait(s, 0) == 0);                                   /* valor inicial 1 */
  CHECK(wait(s, 0) != 0);                                   /* DISPATCH_TIME_NOW: sem espera */
  double t0 = ms_now();
  CHECK(wait(s, mach_ns() + 50000000ull) != 0);             /* 50 ms de timeout */
  double dt = ms_now() - t0;
  CHECK(dt >= 35.0 && dt < 500.0);
  CHECK(wait(s, mach_ns() - 1000000ull) != 0);              /* prazo no passado */
  g_sema = s;
  void* th;
  CHECK(pthread_create(&th, NULL, sema_worker, NULL) == 0);
  CHECK(wait(s, ~0ull) == 0);                               /* FOREVER, acordada pelo signal */
  CHECK(pthread_join(th, NULL) == 0);
  sig(s);
  CHECK(wait(s, mach_ns() + 1000000000ull) == 0);
  rel(s);
  return 0;
}

/* ---- demais importacoes da libSystem ---- */
/* sprintf(buf, fmt, ...) com os variadicos na pilha, em slots de 8 bytes (convencao Apple). */
extern int call_apple_sprintf(void* fn, char* buf, const char* fmt, const uint64_t* slots, uint64_t count);
__asm__(
  ".text\n.p2align 2\n.globl call_apple_sprintf\ncall_apple_sprintf:\n"
  "  stp x29, x30, [sp, #-16]!\n"
  "  mov x29, sp\n"
  "  mov x16, x0\n"
  "  mov x0, x1\n"
  "  mov x1, x2\n"
  "  add x7, x4, #1\n"
  "  bic x7, x7, #1\n"
  "  sub sp, sp, x7, lsl #3\n"
  "  mov x6, #0\n"
  "1:\n"
  "  cmp x6, x4\n"
  "  b.hs 2f\n"
  "  ldr x7, [x3, x6, lsl #3]\n"
  "  str x7, [sp, x6, lsl #3]\n"
  "  add x6, x6, #1\n"
  "  b 1b\n"
  "2:\n"
  "  blr x16\n"
  "  mov sp, x29\n"
  "  ldp x29, x30, [sp], #16\n"
  "  ret\n");

static uint64_t dbits(double d) { uint64_t u; memcpy(&u, &d, 8); return u; }
static int vf(int (*fn)(void*, const char*, char*), void* f, const char* fmt, ...) {
  __builtin_va_list ap;
  __builtin_va_start(ap, fmt);
  int r = fn(f, fmt, (char*)ap);
  __builtin_va_end(ap);
  return r;
}
static int cmp_int(const void* a, const void* b) { return *(const int*)a - *(const int*)b; }
typedef struct {
  int32_t sec, min, hour, mday, mon, year, wday, yday, isdst;
  int64_t gmtoff;
  const char* zone;
} atm;

static int test_libc_misc(void) {
  char buf[128];
  /* sprintf Apple: %ld de 64 bits, double e string */
  void* sp = dw_resolve("sprintf"); CHECK(sp);
  uint64_t slots[] = { (uint64_t)-5000000000LL, dbits(2.5), (uint64_t)(uintptr_t)"ok" };
  int r = call_apple_sprintf(sp, buf, "%ld|%.1f|%s", slots, 3);
  CHECK(r == (int)strlen("-5000000000|2.5|ok") && strcmp(buf, "-5000000000|2.5|ok") == 0);

  /* vfprintf + feof + rewind em arquivo temporario; __stderrp/__stdoutp sao os FILE* da ucrt */
  int (*vfp)(void*, const char*, char*) = dw_resolve("vfprintf");
  int (*feof_)(void*) = dw_resolve("feof");
  void (*rewind_)(void*) = dw_resolve("rewind");
  void** perr = dw_resolve("__stderrp");
  void** pout = dw_resolve("__stdoutp");
  CHECK(vfp && feof_ && rewind_ && perr && pout && *perr == (void*)stderr && *pout == (void*)stdout);
  char tmp[MAX_PATH];
  GetTempPathA(sizeof tmp, tmp);
  strcat(tmp, "dw_pthread_vfprintf.tmp");
  FILE* f = fopen(tmp, "w+b");
  CHECK(f);
  CHECK(vf(vfp, f, "%ld-%s", 4000000000LL, "x") == 12);
  rewind_(f);
  CHECK(!feof_(f));
  char rb[32] = {0};
  CHECK(fread(rb, 1, 31, f) == 12 && strcmp(rb, "4000000000-x") == 0);
  CHECK(feof_(f));
  rewind_(f);
  CHECK(!feof_(f) && ftell(f) == 0);
  fclose(f);
  remove(tmp);

  /* qsort com callback Apple */
  void (*qs)(void*, uint64_t, uint64_t, int (*)(const void*, const void*)) = dw_resolve("qsort");
  CHECK(qs);
  int v[] = {5, 3, 9, -1, 7, 0};
  qs(v, 6, sizeof(int), cmp_int);
  CHECK(v[0] == -1 && v[1] == 0 && v[2] == 3 && v[3] == 5 && v[4] == 7 && v[5] == 9);

  /* math */
  float (*lf)(float) = dw_resolve("logf");
  float (*l10)(float) = dw_resolve("log10f");
  CHECK(lf && l10 && fabsf(lf(2.718281828f) - 1.0f) < 1e-5f && fabsf(l10(1000.0f) - 3.0f) < 1e-5f);

  /* errno */
  int* (*err)(void) = dw_resolve("__error");
  CHECK(err);
  errno = 2;
  CHECK(*err() == 2);
  *err() = 13;
  CHECK(errno == 13);

  /* time / ctime_r / localtime_r: time_t de 64 bits e struct tm Apple (56 bytes) */
  int64_t (*tm_)(int64_t*) = dw_resolve("time");
  char* (*ct)(const int64_t*, char*) = dw_resolve("ctime_r");
  atm* (*lt)(const int64_t*, atm*) = dw_resolve("localtime_r");
  CHECK(tm_ && ct && lt);
  int64_t t1 = 0;
  int64_t t2 = tm_(&t1);
  CHECK(t1 == t2 && llabs(t2 - (int64_t)_time64(NULL)) <= 2);
  int64_t when = 1700000000;                       /* 14 nov 2023 */
  atm a; memset(&a, 0xEE, sizeof a);
  CHECK(lt(&when, &a) == &a);
  struct tm w; __time64_t wv = when; _localtime64_s(&w, &wv);
  CHECK(a.sec == w.tm_sec && a.min == w.tm_min && a.hour == w.tm_hour && a.mday == w.tm_mday &&
        a.mon == w.tm_mon && a.year == 123 && a.wday == w.tm_wday && a.yday == w.tm_yday);
  struct tm g = w; g.tm_isdst = 0;
  CHECK(a.zone && a.zone[0]);
  CHECK(_mkgmtime64(&g) - when == a.gmtoff);       /* hora local - UTC = gmtoff */
  char cb[64] = {0};
  CHECK(ct(&when, cb) == cb && strlen(cb) == 25 && cb[24] == '\n' && strstr(cb, "2023"));
  return 0;
}

static int test_import_coverage_fmod(const char* path) {
  CHECK(path);
  FILE* f = fopen(path, "r");
  CHECK(f);
  char line[1024]; int total = 0, missing = 0;
  while (fgets(line, sizeof line, f)) {
    size_t n = strlen(line);
    while (n && (line[n-1] == '\n' || line[n-1] == '\r' || line[n-1] == ' ')) line[--n] = 0;
    if (!n) continue;
    if (!strncmp(line, "FMOD_", 5) || strstr(line, "4FMOD")) { fprintf(stderr, "FMOD na lista: %s\n", line); missing++; continue; }
    total++;
    if (!dw_resolve(line)) { fprintf(stderr, "missing import: %s\n", line); missing++; }
  }
  fclose(f);
  fprintf(stderr, "imports: %d, missing: %d\n", total, missing);
  CHECK(total > 100 && missing == 0);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) { fputs("uso: test_pthread <teste> [arg]\n", stderr); return 2; }
  static const struct { const char* n; int (*f)(void); } T[] = {
    {"mutex_contention", test_mutex_contention}, {"mutex_reinit", test_mutex_reinit},
    {"mutex_std", test_mutex_std}, {"cond_signal", test_cond_signal},
    {"cond_timeout_past", test_cond_timeout_past}, {"once_single", test_once_single},
    {"create_join_retval", test_create_join_retval}, {"stack_size", test_stack_size},
    {"self_equal", test_self_equal}, {"setname_noop", test_setname_noop},
    {"dispatch_semaphore", test_dispatch_semaphore}, {"libc_misc", test_libc_misc},
  };
  int r = 2;
  if (!strcmp(argv[1], "import_coverage_fmod")) {
    r = test_import_coverage_fmod(argc > 2 ? argv[2] : NULL);
  } else {
    int found = 0;
    for (size_t i = 0; i < sizeof T / sizeof T[0]; i++)
      if (!strcmp(argv[1], T[i].n)) { r = T[i].f(); found = 1; break; }
    if (!found) { fprintf(stderr, "teste desconhecido: %s\n", argv[1]); return 2; }
  }
  if (!r) printf("PASS %s\n", argv[1]);
  return r;
}
