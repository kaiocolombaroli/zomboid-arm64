#include "darwin.h"
#include <string.h>
#include "dw_internal.h"

/* Tabelas: libc/math/runtime/cxx proprias e a tabela gerada com referencias de link a libc++ estatica. */
#ifdef DW_TESTING
static const char* g_fail_import;
void dw_test_fail_import(const char* name) { g_fail_import = name; }
#endif

/* libc com semantica Apple (nomes de imports/NOME.txt, sem o "_" de Mach-O). */
static const dw_entry dw_libc_entries[] = {
  {"calloc", (void*)dw_calloc}, {"malloc", (void*)dw_malloc}, {"free", (void*)dw_free},
  {"aligned_alloc", (void*)dw_aligned_alloc}, {"malloc_size", (void*)dw_malloc_size},
  {"memcmp", (void*)dw_memcmp}, {"strlen", (void*)dw_strlen}, {"strrchr", (void*)dw_strrchr},
  {"memset_pattern16", (void*)dw_memset_pattern16},
  {"fopen", (void*)dw_fopen}, {"fclose", (void*)dw_fclose}, {"fflush", (void*)dw_fflush},
  {"fread", (void*)dw_fread}, {"fwrite", (void*)dw_fwrite}, {"setbuf", (void*)dw_setbuf},
  {"fseeko", (void*)dw_fseeko}, {"ftello", (void*)dw_ftello}, {"rename", (void*)dw_rename},
  {"printf", (void*)dw_printf_apple}, {"puts", (void*)dw_puts}, {"putchar", (void*)dw_putchar},
  {"vsnprintf", (void*)dw_vsnprintf},
  {"rand", (void*)dw_rand}, {"gettimeofday", (void*)dw_gettimeofday},
  {"realloc", (void*)dw_realloc}, {"atoi", (void*)dw_atoi}, {"strchr", (void*)dw_strchr},
  {"strcmp", (void*)dw_strcmp}, {"strcpy", (void*)dw_strcpy}, {"strncpy", (void*)dw_strncpy},
  {"strncmp", (void*)dw_strncmp}, {"strstr", (void*)dw_strstr}, {"strncasecmp", (void*)dw_strncasecmp},
  {"memchr", (void*)dw_memchr}, {"wmemchr", (void*)dw_wmemchr}, {"exit", (void*)dw_exit},
  {"fseek", (void*)dw_fseeko}, {"ftell", (void*)dw_ftello},   /* long Apple = 64 bits */
  {"stat", (void*)dw_stat}, {"mkdir", (void*)dw_mkdir}, {"chdir", (void*)dw_chdir}, {"remove", (void*)dw_remove},
  {"realpath$DARWIN_EXTSN", (void*)dw_realpath_extsn}, {"snprintf", (void*)dw_snprintf_apple},
  {"sprintf", (void*)dw_sprintf_apple}, {"vfprintf", (void*)dw_vfprintf},
  {"feof", (void*)dw_feof}, {"rewind", (void*)dw_rewind},
  {"__stderrp", &dw_stderrp}, {"__stdoutp", &dw_stdoutp},
  {"abort", (void*)dw_abort}, {"qsort", (void*)dw_qsort},
  {"time", (void*)dw_time}, {"ctime_r", (void*)dw_ctime_r}, {"localtime_r", (void*)dw_localtime_r},
  {"_DefaultRuneLocale", (void*)dw_default_rune_locale},
  {"__maskrune", (void*)dw_maskrune}, {"___maskrune", (void*)dw_maskrune},
  {"__tolower", (void*)dw_tolower_rune}, {"___tolower", (void*)dw_tolower_rune},
  {NULL, NULL}
};

static void* lookup(const dw_entry* t, const char* name) {
  for (const dw_entry* e = t; e->name; e++)
    if (strcmp(e->name, name) == 0) return e->addr;
  return NULL;
}

void* dw_resolve(const char* macho_name) {
  if (!macho_name) return NULL;
#ifdef DW_TESTING
  if (g_fail_import && strcmp(g_fail_import, macho_name) == 0) return NULL;
#endif
  dw_libc_init();
  void* a = lookup(dw_libc_entries, macho_name);
  if (!a) a = lookup(dw_math_entries, macho_name);
  if (!a) a = lookup(dw_pthread_entries, macho_name);
  if (!a) a = lookup(dw_runtime_entries, macho_name);
  if (!a) a = lookup(dw_cxx_own_entries, macho_name);
  if (!a) a = lookup(dw_cxx_entries, macho_name);
  return a;
}
