#ifndef DW_INTERNAL_H
#define DW_INTERNAL_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct { const char* name; void* addr; } dw_entry;
extern const dw_entry dw_runtime_entries[];
extern const dw_entry dw_math_entries[];
/* dw_pthread.c: pthread/sched/dispatch_semaphore/std::mutex/__error com layout Apple. */
extern const dw_entry dw_pthread_entries[];
/* dw_cxx.cpp: C++ proprio com layout Apple (string, random_device, __sort, ...). */
extern const dw_entry dw_cxx_own_entries[];
/* build/generated/dw_cxx_table.cpp: referencias de link a simbolos reais da libc++/libc++abi estaticas. */
extern const dw_entry dw_cxx_entries[];

/* dw_format.c: reescreve %ld/%lu/%lx... (long Apple = 64 bits) para %lld/%llu/%llx... Devolve o
   tamanho necessario (sem o NUL), como snprintf; out pode ser NULL com cap 0. Termina em NUL se cap > 0. */
size_t dw_fix_format(const char* in, char* out, size_t cap);
int dw_vsnprintf(char* buf, uint64_t n, const char* fmt, char* ap);
int dw_vprintf(const char* fmt, char* ap);
int dw_vfprintf(void* f, const char* fmt, char* ap);
int dw_vsprintf(char* buf, const char* fmt, char* ap);
extern int dw_sprintf_apple(char* buf, const char* fmt, ...);
/* dw_variadic.S: printf com a convencao variadica Apple (args na pilha, slots de 8 bytes). */
extern int dw_printf_apple(const char* fmt, ...);

/* dw_eh.c / dw_eh.S: runtime de excecoes C++ das dylibs (ver dw_eh.c). Nomes Apple: __cxa_*, _Unwind_Resume... */
void* dw_cxa_allocate_exception(uint64_t size);
void  dw_cxa_free_exception(void* obj);
void  dw_cxa_throw(void* obj, void* type_info, void (*dtor)(void*));
void  dw_cxa_rethrow(void);
void* dw_cxa_begin_catch(void* unwind_exception);
void  dw_cxa_end_catch(void);
void  dw_unwind_resume(void* unwind_exception);
void  dw_terminate(void);
void  dw_gxx_personality_stub(void);
/* std::exception_ptr (referencias contadas sobre a pilha de capturadas de dw_eh.c). */
void* dw_eh_current(void);                 /* topo da pilha de capturadas (+1 referencia) ou NULL */
void  dw_eh_ptr_addref(void* obj);
void  dw_eh_ptr_release(void* obj);

/* dw_ti.cpp: a type_info lancada casa com o catch? catch_ti NULL = catch (...). *adjusted_ptr entra com o
   ponteiro do objeto lancado e sai com o ponteiro ajustado para a base capturada. */
int dw_ti_can_catch(const void* catch_ti, const void* thrown_ti, void** adjusted_ptr);

/* dw_tlv.c: _tlv_atexit (sem efeito no M2) */
void dw_tlv_atexit(void (*term)(void*), void* obj, void* dso);

/* dw_libc.c */
void*    dw_calloc(uint64_t n, uint64_t size);
uint64_t dw_strlen(const char* s);
char*    dw_strrchr(const char* s, int c);
int      dw_memcmp(const void* a, const void* b, uint64_t n);
void     dw_memset_pattern16(void* b, const void* pattern16, uint64_t len);
int      dw_rand(void);
int      dw_gettimeofday(void* tv, void* tz);
int64_t  dw_time(int64_t* t);
char*    dw_ctime_r(const int64_t* t, char* buf);
void*    dw_localtime_r(const int64_t* t, void* result);
void     dw_abort(void);
void     dw_qsort(void* base, uint64_t n, uint64_t size, int (*cmp)(const void*, const void*));
void*    dw_realloc(void* p, uint64_t size);
int      dw_atoi(const char* s);
char*    dw_strchr(const char* s, int c);
int      dw_strcmp(const char* a, const char* b);
char*    dw_strcpy(char* d, const char* s);
char*    dw_strncpy(char* d, const char* s, uint64_t n);
int      dw_strncmp(const char* a, const char* b, uint64_t n);
char*    dw_strstr(const char* a, const char* b);
int      dw_strncasecmp(const char* a, const char* b, uint64_t n);
void*    dw_memchr(const void* s, int c, uint64_t n);
int32_t* dw_wmemchr(const int32_t* s, int32_t c, uint64_t n);
void     dw_exit(int code);
/* ctype Apple: dw_default_rune_locale e a struct _RuneLocale (runetype em +60). */
extern unsigned char dw_default_rune_locale[];
void     dw_libc_init(void);
int      dw_maskrune(int32_t c, uint64_t mask);
int      dw_tolower_rune(int32_t c);

/* dw_stdio.c (o FILE* e o da ucrt) */
void*    dw_fopen(const char* path, const char* mode);
int      dw_fclose(void* f);
int      dw_fflush(void* f);
uint64_t dw_fread(void* p, uint64_t size, uint64_t n, void* f);
uint64_t dw_fwrite(const void* p, uint64_t size, uint64_t n, void* f);
void     dw_setbuf(void* f, char* buf);
int      dw_fseeko(void* f, int64_t off, int whence);
int64_t  dw_ftello(void* f);
int      dw_rename(const char* from, const char* to);
int      dw_feof(void* f);
void     dw_rewind(void* f);
extern void* dw_stderrp;
extern void* dw_stdoutp;
/* dw_fs.c: arquivos/diretorios com caminhos UTF-8 (API wide) e struct stat da Apple arm64. */
int      dw_stat(const char* path, void* st);
int      dw_mkdir(const char* path, int mode);
int      dw_chdir(const char* path);
int      dw_remove(const char* path);
char*    dw_realpath_extsn(const char* path, char* resolved);
/* dw_variadic.S: snprintf com a convencao variadica Apple. */
extern int dw_snprintf_apple(char* buf, uint64_t n, const char* fmt, ...);
int      dw_puts(const char* s);
int      dw_putchar(int c);
#ifdef __cplusplus
}
#endif
#endif
