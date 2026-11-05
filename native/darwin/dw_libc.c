#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <windows.h>
#include "darwin.h"
#include "dw_internal.h"

void* dw_calloc(uint64_t n, uint64_t size) {
  if (n && size > UINT64_MAX / n) return NULL;
  uint64_t total = n * size;
  void* p = dw_malloc(total ? total : 1);
  if (p) memset(p, 0, (size_t)total);
  return p;
}

uint64_t dw_strlen(const char* s) { return (uint64_t)strlen(s); }
char* dw_strrchr(const char* s, int c) { return strrchr(s, c); }
int dw_memcmp(const void* a, const void* b, uint64_t n) { return memcmp(a, b, (size_t)n); }

/* Repete os 16 bytes de pattern16 por len bytes; o ultimo bloco e truncado. */
void dw_memset_pattern16(void* b, const void* pattern16, uint64_t len) {
  unsigned char* d = b;
  const unsigned char* p = pattern16;
  for (uint64_t i = 0; i < len; i++) d[i] = p[i & 15];
}

/* rand: Park-Miller (16807 mod 2^31-1), como a libc da Apple. Valores em [1, 2147483646].
   Sem trava: troca atomica do estado. */
static volatile LONG dw_rand_state = 1;
int dw_rand(void) {
  for (;;) {
    LONG old = dw_rand_state;
    uint64_t next = ((uint64_t)old * 16807u) % 2147483647u;
    if (!next) next = 1;
    if (InterlockedCompareExchange(&dw_rand_state, (LONG)next, old) == old) return (int)next;
  }
}

/* struct timeval da Apple arm64: {int64 tv_sec; int32 tv_usec}, 16 bytes. */
typedef struct { int64_t tv_sec; int32_t tv_usec; } dw_timeval;
int dw_gettimeofday(void* tvp, void* tz) {
  (void)tz;
  if (!tvp) return 0;
  FILETIME ft;
  GetSystemTimePreciseAsFileTime(&ft);
  uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;   /* 100 ns desde 1601 */
  t -= 116444736000000000ull;                                           /* desde 1970 */
  dw_timeval* tv = tvp;
  tv->tv_sec = (int64_t)(t / 10000000ull);
  tv->tv_usec = (int32_t)((t % 10000000ull) / 10);
  return 0;
}

/* ---- string.h / stdlib.h simples (semantica Apple; tamanhos de 64 bits) ---- */
void* dw_realloc(void* p, uint64_t size) {
  if (!p) return dw_malloc(size);
  uint64_t old = dw_malloc_size(p);
  void* n = dw_malloc(size ? size : 1);
  if (!n) return NULL;                      /* falha: o bloco antigo continua valido */
  memcpy(n, p, (size_t)(old < size ? old : size));
  dw_free(p);
  return n;
}
int dw_atoi(const char* s) { return atoi(s); }
char* dw_strchr(const char* s, int c) { return strchr(s, c); }
int dw_strcmp(const char* a, const char* b) { return strcmp(a, b); }
char* dw_strcpy(char* d, const char* s) { return strcpy(d, s); }
char* dw_strncpy(char* d, const char* s, uint64_t n) { return strncpy(d, s, (size_t)n); }
int dw_strncmp(const char* a, const char* b, uint64_t n) { return strncmp(a, b, (size_t)n); }
char* dw_strstr(const char* a, const char* b) { return strstr(a, b); }
int dw_strncasecmp(const char* a, const char* b, uint64_t n) { return _strnicmp(a, b, (size_t)n); }
void* dw_memchr(const void* s, int c, uint64_t n) { return memchr(s, c, (size_t)n); }
/* wchar_t da Apple tem 32 bits (o do Windows, 16): wmemchr por uint32_t. */
int32_t* dw_wmemchr(const int32_t* s, int32_t c, uint64_t n) {
  for (uint64_t i = 0; i < n; i++) if (s[i] == c) return (int32_t*)(s + i);
  return NULL;
}
/* exit: descarrega os streams e sai (nao roda atexit de dylibs; o processo e a JVM). */
void dw_exit(int code) {
  fflush(NULL);
  ExitProcess((UINT)code);
}

/* ---- ctype da Apple: struct _RuneLocale (runetype.h) ----
   Offsets Apple confirmados no desmonte de libjassimp.dylib: `ldr w8, [x20, x0, lsl #2 + 0x3c]` = __runetype[c]
   (0x3c = 8 magic + 32 encoding + 8 sgetrune + 8 sputrune + 4 invalid_rune = 60). So __runetype e lido
   inline pelas dylibs; __maplower/__mapupper seguem o layout Apple por completude. */
#define CT_A 0x00000100u
#define CT_C 0x00000200u
#define CT_D 0x00000400u
#define CT_G 0x00000800u
#define CT_L 0x00001000u
#define CT_P 0x00002000u
#define CT_S 0x00004000u
#define CT_U 0x00008000u
#define CT_X 0x00010000u
#define CT_B 0x00020000u
#define CT_R 0x00040000u
typedef struct { int32_t nranges; void* ranges; } dw_rune_range;
typedef struct {
  char magic[8];
  char encoding[32];
  void* sgetrune;
  void* sputrune;
  int32_t invalid_rune;
  uint32_t runetype[256];
  int32_t maplower[256];
  int32_t mapupper[256];
  dw_rune_range runetype_ext, maplower_ext, mapupper_ext;
  void* variable;
  int32_t variable_len;
  int32_t ncharclasses;
  void* charclasses;
} dw_rune_locale;
_Static_assert(offsetof(dw_rune_locale, runetype) == 60, "layout Apple de _RuneLocale");
_Static_assert(offsetof(dw_rune_locale, maplower) == 60 + 1024, "layout Apple de _RuneLocale");

unsigned char dw_default_rune_locale[sizeof(dw_rune_locale)] __attribute__((aligned(16)));
static void dw_rune_init(void) {
  dw_rune_locale* r = (dw_rune_locale*)dw_default_rune_locale;
  memcpy(r->magic, "RuneMagi", 8);
  strcpy(r->encoding, "NONE");
  r->invalid_rune = 0xFFFD;
  for (int c = 0; c < 128; c++) {
    uint32_t t = 0;
    if (c < 32 || c == 127) t |= CT_C;
    if (c >= 32 && c < 127) t |= CT_R;
    if (c > 32 && c < 127) t |= CT_G;
    if ((c >= 9 && c <= 13) || c == 32) t |= CT_S;
    if (c == 9 || c == 32) t |= CT_B;
    if (c >= '0' && c <= '9') t |= CT_D | CT_X | (uint32_t)(c - '0');
    if (c >= 'A' && c <= 'Z') t |= CT_A | CT_U;
    if (c >= 'a' && c <= 'z') t |= CT_A | CT_L;
    if ((c >= 'A' && c <= 'F')) t |= CT_X | (uint32_t)(c - 'A' + 10);
    if ((c >= 'a' && c <= 'f')) t |= CT_X | (uint32_t)(c - 'a' + 10);
    if (c > 32 && c < 127 && !((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) t |= CT_P;
    r->runetype[c] = t;
  }
  for (int c = 0; c < 256; c++) {
    r->maplower[c] = (c >= 'A' && c <= 'Z') ? c + 32 : c;
    r->mapupper[c] = (c >= 'a' && c <= 'z') ? c - 32 : c;
  }
}
/* Tabela preenchida (idempotente) por dw_resolve, antes de qualquer dylib poder ler _DefaultRuneLocale. */
void dw_libc_init(void) {
  dw_rune_init();
  dw_stderrp = stderr;
  dw_stdoutp = stdout;
}

/* __maskrune(c, mask): chamado quando c nao cabe no caminho inline (c >= 128); locale "C": 0 acima do ASCII. */
int dw_maskrune(int32_t c, uint64_t mask) {
  return (c >= 0 && c < 256) ? (int)(((dw_rune_locale*)dw_default_rune_locale)->runetype[c] & (uint32_t)mask) : 0;
}
int dw_tolower_rune(int32_t c) {
  return (c >= 0 && c < 256) ? ((dw_rune_locale*)dw_default_rune_locale)->maplower[c] : c;
}

/* ---- abort / qsort ---- */
void dw_abort(void) { abort(); }
/* O callback Apple int(const void*, const void*) tem a mesma ABI do da ucrt; size_t tem 64 bits nos dois. */
void dw_qsort(void* base, uint64_t n, uint64_t size, int (*cmp)(const void*, const void*)) {
  qsort(base, (size_t)n, (size_t)size, cmp);
}

/* ---- time.h: time_t de 64 bits e struct tm da Apple (9 int + tm_gmtoff + tm_zone = 56 bytes) ---- */
int64_t dw_time(int64_t* t) {
  int64_t r = (int64_t)_time64(NULL);
  if (t) *t = r;
  return r;
}
/* ctime_r: "Thu Nov  5 12:00:00 2026
" em buf (>= 26 bytes). */
char* dw_ctime_r(const int64_t* t, char* buf) {
  if (!t || !buf) return NULL;
  __time64_t v = (__time64_t)*t;
  return _ctime64_s(buf, 26, &v) == 0 ? buf : NULL;
}
typedef struct {
  int32_t tm_sec, tm_min, tm_hour, tm_mday, tm_mon, tm_year, tm_wday, tm_yday, tm_isdst;
  int64_t tm_gmtoff;
  const char* tm_zone;
} dw_tm;
_Static_assert(sizeof(dw_tm) == 56 && offsetof(dw_tm, tm_gmtoff) == 40, "layout Apple de struct tm");
void* dw_localtime_r(const int64_t* t, void* result) {
  static char zone[2][64];                  /* tm_zone aponta para copia estatica por tipo (padrao/verao) */
  if (!t || !result) return NULL;
  __time64_t v = (__time64_t)*t;
  struct tm w;
  if (_localtime64_s(&w, &v) != 0) { errno = 75; return NULL; }   /* EOVERFLOW (Apple) */
  dw_tm* r = result;
  r->tm_sec = w.tm_sec; r->tm_min = w.tm_min; r->tm_hour = w.tm_hour;
  r->tm_mday = w.tm_mday; r->tm_mon = w.tm_mon; r->tm_year = w.tm_year;
  r->tm_wday = w.tm_wday; r->tm_yday = w.tm_yday; r->tm_isdst = w.tm_isdst > 0;
  long tz = 0, bias = 0;
  _get_timezone(&tz);
  _get_dstbias(&bias);
  r->tm_gmtoff = -(int64_t)(tz + (r->tm_isdst ? bias : 0));
  size_t n = 0;
  int k = r->tm_isdst ? 1 : 0;
  if (_get_tzname(&n, zone[k], sizeof zone[k], k) != 0) zone[k][0] = 0;
  r->tm_zone = zone[k];
  return r;
}
