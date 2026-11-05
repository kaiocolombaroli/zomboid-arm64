/* pthread, sched e dispatch_semaphore com os layouts da libSystem da Apple (arm64).
   Os tipos Apple sao buffers de tamanho fixo, muitas vezes inicializados INLINE pelo codigo da dylib
   (std::mutex grava PTHREAD_MUTEX_INITIALIZER no construtor; confirmado no desmonte de
   libfmodintegration.dylib: `mov w8,#0xaba7; movk w8,#0x32aa,lsl #16; str x8,[obj]` + 56 bytes zero).
   Por isso toda primitiva aceita tanto o inicializador estatico Apple quanto o criado por *_init:

     pthread_mutex_t  64 B  off 0: assinatura (8)   off 8: SRWLOCK        (zero = livre)
     pthread_cond_t   48 B  off 0: assinatura (8)   off 8: CONDITION_VARIABLE (zero = vazia)
     pthread_once_t   16 B  off 0: assinatura (8)   off 8: estado (0 livre, 1 rodando, 2 feito)
     pthread_attr_t   64 B  off 0: assinatura (8)   off 8: tamanho da pilha (8)

   Inicializacao lazy: a assinatura Apple vira a "pronta" por CAS no primeiro uso; como o estado Win32
   (SRWLOCK/CONDITION_VARIABLE) e valido quando zerado, nao ha mais nada a construir. Destruir zera o
   buffer inteiro e invalida a assinatura; recriar no mesmo endereco comeca limpo. pthread_mutexattr_*
   nao sao importados por nenhuma dylib: o tipo do mutex e sempre o padrao (nao recursivo). */
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include "dw_internal.h"

/* errno da Apple (sys/errno.h) devolvido pelas funcoes pthread (elas retornam o codigo, nao usam errno). */
#define A_EDEADLK 11
#define A_EBUSY 16
#define A_EINVAL 22
#define A_EAGAIN 35
#define A_ETIMEDOUT 60

#define SIG_MUTEX_APPLE ((LONG64)0x32AAABA7)
#define SIG_COND_APPLE  ((LONG64)0x3CB0B1BB)
#define SIG_ONCE_APPLE  ((LONG64)0x30B1BCBA)
#define SIG_ATTR_APPLE  ((LONG64)0x54484441)   /* 'THDA' */
#define SIG_MUTEX_READY ((LONG64)0x4457000100000001LL)
#define SIG_COND_READY  ((LONG64)0x4457000100000002LL)
#define SIG_ATTR_READY  SIG_ATTR_APPLE

typedef struct { volatile LONG64 sig; SRWLOCK lock; } dw_mutex;
typedef struct { volatile LONG64 sig; CONDITION_VARIABLE cv; } dw_cond;
typedef struct { volatile LONG64 sig; volatile LONG state; } dw_once;
typedef struct { LONG64 sig; uint64_t stack; } dw_attr;
_Static_assert(sizeof(dw_mutex) <= 64 && sizeof(dw_cond) <= 48 && sizeof(dw_once) <= 16 &&
               sizeof(dw_attr) <= 64, "cabem nos buffers Apple");
/* struct timespec Apple: dois int64. */
typedef struct { int64_t tv_sec; int64_t tv_nsec; } dw_timespec;

/* ---- mutex ---- */
static int mtx_check(dw_mutex* m) {
  if (!m) return A_EINVAL;
  LONG64 s = m->sig;
  if (s == SIG_MUTEX_READY) return 0;
  if (s == SIG_MUTEX_APPLE) {                 /* PTHREAD_MUTEX_INITIALIZER: lazy */
    InterlockedCompareExchange64(&m->sig, SIG_MUTEX_READY, SIG_MUTEX_APPLE);
    return 0;
  }
  return A_EINVAL;
}
static int dw_pthread_mutex_init(void* mp, const void* attr) {
  (void)attr;
  if (!mp) return A_EINVAL;
  memset(mp, 0, 64);
  ((dw_mutex*)mp)->sig = SIG_MUTEX_READY;
  return 0;
}
static int dw_pthread_mutex_destroy(void* mp) {
  dw_mutex* m = mp;
  int e = mtx_check(m);
  if (e) return e;
  if (!TryAcquireSRWLockExclusive(&m->lock)) return A_EBUSY;
  memset(mp, 0, 64);                           /* sig = 0: invalido ate novo init/inicializador */
  return 0;
}
static int dw_pthread_mutex_lock(void* mp) {
  dw_mutex* m = mp;
  int e = mtx_check(m);
  if (e) return e;
  AcquireSRWLockExclusive(&m->lock);
  return 0;
}
static int dw_pthread_mutex_unlock(void* mp) {
  dw_mutex* m = mp;
  int e = mtx_check(m);
  if (e) return e;
  ReleaseSRWLockExclusive(&m->lock);
  return 0;
}

/* ---- cond ---- */
static int cnd_check(dw_cond* c) {
  if (!c) return A_EINVAL;
  LONG64 s = c->sig;
  if (s == SIG_COND_READY) return 0;
  if (s == SIG_COND_APPLE) {
    InterlockedCompareExchange64(&c->sig, SIG_COND_READY, SIG_COND_APPLE);
    return 0;
  }
  return A_EINVAL;
}
static int dw_pthread_cond_init(void* cp, const void* attr) {
  (void)attr;
  if (!cp) return A_EINVAL;
  memset(cp, 0, 48);
  ((dw_cond*)cp)->sig = SIG_COND_READY;
  return 0;
}
static int dw_pthread_cond_destroy(void* cp) {
  int e = cnd_check(cp);
  if (e) return e;
  memset(cp, 0, 48);
  return 0;
}
static int dw_pthread_cond_signal(void* cp) {
  int e = cnd_check(cp);
  if (e) return e;
  WakeConditionVariable(&((dw_cond*)cp)->cv);
  return 0;
}
static int dw_pthread_cond_broadcast(void* cp) {
  int e = cnd_check(cp);
  if (e) return e;
  WakeAllConditionVariable(&((dw_cond*)cp)->cv);
  return 0;
}
static int cond_wait_ms(void* cp, void* mp, DWORD ms) {
  dw_cond* c = cp;
  dw_mutex* m = mp;
  int e = cnd_check(c);
  if (e) return e;
  e = mtx_check(m);
  if (e) return e;
  if (SleepConditionVariableSRW(&c->cv, &m->lock, ms, 0)) return 0;
  return GetLastError() == ERROR_TIMEOUT ? A_ETIMEDOUT : A_EINVAL;
}
static int dw_pthread_cond_wait(void* cp, void* mp) { return cond_wait_ms(cp, mp, INFINITE); }

/* Tempo absoluto no relogio de gettimeofday (CLOCK_REALTIME). Tempo no passado: timeout 0, que ainda
   solta e retoma o mutex e volta ETIMEDOUT sem bloquear. */
static int dw_pthread_cond_timedwait(void* cp, void* mp, const void* abstime) {
  const dw_timespec* ts = abstime;
  if (!ts || ts->tv_nsec < 0 || ts->tv_nsec >= 1000000000LL) return A_EINVAL;
  struct { int64_t sec; int32_t usec; } now;
  dw_gettimeofday(&now, NULL);
  __int128 diff = ((__int128)ts->tv_sec - now.sec) * 1000000000 + (ts->tv_nsec - (int64_t)now.usec * 1000);
  DWORD ms = 0;
  if (diff > 0) {
    __int128 m = (diff + 999999) / 1000000;
    ms = m > 0x7FFFFFFE ? 0x7FFFFFFE : (DWORD)m;     /* espera longa demais: acorda cedo (espurio permitido) */
  }
  return cond_wait_ms(cp, mp, ms);
}

/* ---- once ---- */
static int dw_pthread_once(void* op, void (*fn)(void)) {
  dw_once* o = op;
  if (!o || !fn || o->sig != SIG_ONCE_APPLE) return A_EINVAL;
  for (;;) {
    LONG s = o->state;
    if (s == 2) return 0;
    if (s == 0 && InterlockedCompareExchange(&o->state, 1, 0) == 0) {
      fn();
      InterlockedExchange(&o->state, 2);
      return 0;
    }
    Sleep(0);
  }
}

/* ---- attr ---- */
static int dw_pthread_attr_init(void* ap) {
  if (!ap) return A_EINVAL;
  memset(ap, 0, 64);
  dw_attr* a = ap;
  a->sig = SIG_ATTR_READY;
  a->stack = 512 * 1024;                       /* padrao de threads secundarias da Apple */
  return 0;
}
static int dw_pthread_attr_destroy(void* ap) {
  dw_attr* a = ap;
  if (!a || a->sig != SIG_ATTR_READY) return A_EINVAL;
  memset(ap, 0, 64);
  return 0;
}
static int dw_pthread_attr_setstacksize(void* ap, uint64_t size) {
  dw_attr* a = ap;
  if (!a || a->sig != SIG_ATTR_READY || size < 16384 || (size & 4095)) return A_EINVAL;
  a->stack = size;
  return 0;
}

/* ---- threads ---- */
typedef struct dw_thread {
  HANDLE h;                  /* NULL em thread criada fora (pthread_self lazy): nao pode ser juntada */
  DWORD id;
  void* ret;
  void* (*fn)(void*);
  void* arg;
} dw_thread;

static DWORD g_tls = TLS_OUT_OF_INDEXES;
static INIT_ONCE g_tls_once = INIT_ONCE_STATIC_INIT;
static BOOL CALLBACK tls_init(PINIT_ONCE o, PVOID p, PVOID* c) {
  (void)o; (void)p; (void)c;
  g_tls = TlsAlloc();
  return g_tls != TLS_OUT_OF_INDEXES;
}

static DWORD WINAPI thread_main(LPVOID p) {
  dw_thread* t = p;
  TlsSetValue(g_tls, t);
  t->ret = t->fn(t->arg);
  return 0;
}

static int dw_pthread_create(void** out, const void* attr, void* (*fn)(void*), void* arg) {
  if (!out || !fn) return A_EINVAL;
  if (!InitOnceExecuteOnce(&g_tls_once, tls_init, NULL, NULL)) return A_EAGAIN;
  uint64_t stack = 0;
  const dw_attr* a = attr;
  if (a && a->sig == SIG_ATTR_READY) stack = a->stack;
  if (stack && stack < 128 * 1024) stack = 128 * 1024;    /* piso: ucrt/Win32 usam mais pilha que a libSystem */
  dw_thread* t = calloc(1, sizeof *t);
  if (!t) return A_EAGAIN;
  t->fn = fn;
  t->arg = arg;
  t->h = CreateThread(NULL, (SIZE_T)stack, thread_main, t, STACK_SIZE_PARAM_IS_A_RESERVATION | CREATE_SUSPENDED, &t->id);
  if (!t->h) { free(t); return A_EAGAIN; }
  ResumeThread(t->h);
  *out = t;
  return 0;
}

static int dw_pthread_join(void* tp, void** ret) {
  dw_thread* t = tp;
  if (!t || !t->h) return A_EINVAL;
  if (t->id == GetCurrentThreadId()) return A_EDEADLK;
  WaitForSingleObject(t->h, INFINITE);
  if (ret) *ret = t->ret;
  CloseHandle(t->h);
  free(t);
  return 0;
}

static void* dw_pthread_self(void) {
  InitOnceExecuteOnce(&g_tls_once, tls_init, NULL, NULL);
  dw_thread* t = g_tls == TLS_OUT_OF_INDEXES ? NULL : TlsGetValue(g_tls);
  if (!t) {                                  /* thread criada fora (JVM, FMOD...): estrutura lazy */
    t = calloc(1, sizeof *t);
    if (!t) return NULL;
    t->id = GetCurrentThreadId();
    if (g_tls != TLS_OUT_OF_INDEXES) TlsSetValue(g_tls, t);
  }
  return t;
}

static int dw_pthread_equal(void* a, void* b) { return a == b; }
static uint32_t dw_pthread_mach_thread_np(void* tp) { return tp ? ((dw_thread*)tp)->id : 0; }

static int dw_pthread_setname_np(const char* name) {
  typedef HRESULT (WINAPI *fn_t)(HANDLE, PCWSTR);
  static fn_t fn;
  static volatile LONG tried;
  if (!tried) {
    HMODULE k = GetModuleHandleW(L"kernel32.dll");
    fn = k ? (fn_t)(void*)GetProcAddress(k, "SetThreadDescription") : NULL;
    tried = 1;
  }
  if (fn && name) {
    wchar_t w[64];
    if (MultiByteToWideChar(CP_UTF8, 0, name, -1, w, 64) > 0) fn(GetCurrentThread(), w);
  }
  return 0;
}

/* Prioridades: sem efeito (as threads do Windows ja rodam na prioridade normal). */
static int dw_pthread_setschedparam(void* t, int policy, const void* param) {
  (void)t; (void)policy; (void)param;
  return 0;
}
/* Faixa Apple: SCHED_OTHER(1), SCHED_RR(2), SCHED_FIFO(4) -> 15..47. */
static int dw_sched_get_priority_min(int policy) {
  if (policy == 1 || policy == 2 || policy == 4) return 15;
  errno = A_EINVAL;
  return -1;
}
static int dw_sched_get_priority_max(int policy) {
  if (policy == 1 || policy == 2 || policy == 4) return 47;
  errno = A_EINVAL;
  return -1;
}
static int dw_sched_yield(void) { SwitchToThread(); return 0; }

/* ---- dispatch_semaphore ---- */
typedef struct { volatile LONG refs; HANDLE h; volatile LONG waiters; } dw_sema;
#define DISPATCH_TIME_FOREVER (~0ull)

static uint64_t mach_now_ns(void) {          /* mach_absolute_time com timebase 1:1 */
  static LARGE_INTEGER f;
  LARGE_INTEGER c;
  if (!f.QuadPart) QueryPerformanceFrequency(&f);
  QueryPerformanceCounter(&c);
  return (uint64_t)((__int128)c.QuadPart * 1000000000 / f.QuadPart);
}

static void* dw_dispatch_semaphore_create(int64_t value) {
  if (value < 0) return NULL;
  dw_sema* s = calloc(1, sizeof *s);
  if (!s) return NULL;
  s->refs = 1;
  s->h = CreateSemaphoreW(NULL, (LONG)(value > 0x7FFFFFFF ? 0x7FFFFFFF : value), 0x7FFFFFFF, NULL);
  if (!s->h) { free(s); return NULL; }
  return s;
}
/* timeout: DISPATCH_TIME_NOW (0), FOREVER, ou prazo absoluto em ns na base de mach_absolute_time.
   Devolve 0 se pegou o semaforo, 49 (nao zero) se esgotou. */
static int64_t dw_dispatch_semaphore_wait(void* sp, uint64_t timeout) {
  dw_sema* s = sp;
  if (!s) return 49;
  InterlockedIncrement(&s->waiters);
  int64_t r = 49;
  if (timeout == DISPATCH_TIME_FOREVER) {
    r = WaitForSingleObject(s->h, INFINITE) == WAIT_OBJECT_0 ? 0 : 49;
  } else {
    for (;;) {
      DWORD ms = 0;
      if (timeout) {
        uint64_t now = mach_now_ns();
        if (timeout > now) {
          uint64_t m = (timeout - now + 999999) / 1000000;
          ms = m > 0x7FFFFFFE ? 0x7FFFFFFE : (DWORD)m;
        }
      }
      DWORD w = WaitForSingleObject(s->h, ms);
      if (w == WAIT_OBJECT_0) { r = 0; break; }
      if (w != WAIT_TIMEOUT || ms < 0x7FFFFFFE) break;
    }
  }
  InterlockedDecrement(&s->waiters);
  return r;
}
static int64_t dw_dispatch_semaphore_signal(void* sp) {
  dw_sema* s = sp;
  if (!s) return 0;
  LONG w = s->waiters;
  ReleaseSemaphore(s->h, 1, NULL);
  return w > 0;                              /* nao zero se havia thread esperando */
}
static void dw_dispatch_release(void* sp) {
  dw_sema* s = sp;
  if (s && InterlockedDecrement(&s->refs) == 0) { CloseHandle(s->h); free(s); }
}

/* std::mutex (libc++ da Apple) e um pthread_mutex_t; o construtor constexpr da dylib grava o
   inicializador estatico, entao lock/unlock/~mutex tem de ser os nossos. */
static void dw_mutex_lock(void* m) { dw_pthread_mutex_lock(m); }
static void dw_mutex_unlock(void* m) { dw_pthread_mutex_unlock(m); }
static void dw_mutex_dtor(void* m) { dw_pthread_mutex_destroy(m); }

/* errno da Apple: a mesma celula por thread que a ucrt usa, para que os erros definidos pelas funcoes
   proprias (dw_fopen, dw_rename...) aparecam aqui. Numeros iguais aos da Apple para ENOENT/EACCES/EEXIST/
   EINVAL/ENOMEM... (diferem em EAGAIN/EDEADLK/ETIMEDOUT, que as funcoes pthread devolvem direto). */
static int* dw_error(void) { return _errno(); }

const dw_entry dw_pthread_entries[] = {
  {"pthread_mutex_init", (void*)dw_pthread_mutex_init}, {"pthread_mutex_destroy", (void*)dw_pthread_mutex_destroy},
  {"pthread_mutex_lock", (void*)dw_pthread_mutex_lock}, {"pthread_mutex_unlock", (void*)dw_pthread_mutex_unlock},
  {"pthread_cond_init", (void*)dw_pthread_cond_init}, {"pthread_cond_destroy", (void*)dw_pthread_cond_destroy},
  {"pthread_cond_signal", (void*)dw_pthread_cond_signal}, {"pthread_cond_broadcast", (void*)dw_pthread_cond_broadcast},
  {"pthread_cond_wait", (void*)dw_pthread_cond_wait}, {"pthread_cond_timedwait", (void*)dw_pthread_cond_timedwait},
  {"pthread_once", (void*)dw_pthread_once},
  {"pthread_attr_init", (void*)dw_pthread_attr_init}, {"pthread_attr_destroy", (void*)dw_pthread_attr_destroy},
  {"pthread_attr_setstacksize", (void*)dw_pthread_attr_setstacksize},
  {"pthread_create", (void*)dw_pthread_create}, {"pthread_join", (void*)dw_pthread_join},
  {"pthread_self", (void*)dw_pthread_self}, {"pthread_equal", (void*)dw_pthread_equal},
  {"pthread_mach_thread_np", (void*)dw_pthread_mach_thread_np}, {"pthread_setname_np", (void*)dw_pthread_setname_np},
  {"pthread_setschedparam", (void*)dw_pthread_setschedparam},
  {"sched_get_priority_min", (void*)dw_sched_get_priority_min}, {"sched_get_priority_max", (void*)dw_sched_get_priority_max},
  {"sched_yield", (void*)dw_sched_yield},
  {"dispatch_semaphore_create", (void*)dw_dispatch_semaphore_create},
  {"dispatch_semaphore_wait", (void*)dw_dispatch_semaphore_wait},
  {"dispatch_semaphore_signal", (void*)dw_dispatch_semaphore_signal},
  {"dispatch_release", (void*)dw_dispatch_release},
  {"_ZNSt3__15mutex4lockEv", (void*)dw_mutex_lock}, {"_ZNSt3__15mutex6unlockEv", (void*)dw_mutex_unlock},
  {"_ZNSt3__15mutexD1Ev", (void*)dw_mutex_dtor},
  {"__error", (void*)dw_error},
  {NULL, NULL}
};
