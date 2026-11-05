/* Testes da ponte FMOD (native/fmodshim): shims C++ -> API C com alvos falsos, tabelas e as DLLs reais.
 * Uso: test_fmodshim <teste> [pasta com fmod.dll/fmodstudio.dll] */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include "fmodshim.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

typedef struct { unsigned int data1, data2; } param_id;

static int test_counts(void) {
  CHECK(fmod_shim_direct_count() == 367);
  CHECK(fmod_shim_cxx_count() == 61);
  return 0;
}

/* --- bool por valor: a Apple entrega o byte baixo valido; o resto do registrador nao conta --- */
static int g_flag = -1;
static void* g_self;
static int fake_set_paused(void* self, int paused) { g_self = self; g_flag = paused; return 0; }

static int test_bool_in_low_byte(void) {
  CHECK(fmod_shim_set_target("FMOD_Studio_EventInstance_SetPaused", (void*)fake_set_paused));
  typedef int (*fn_t)(void*, unsigned int);
  fn_t f = (fn_t)dw_fmod_resolve("_ZN4FMOD6Studio13EventInstance9setPausedEb");
  CHECK(f);
  static const struct { unsigned int in; int want; } cases[] = {
    { 0u, 0 }, { 1u, 1 }, { 0xABCDEF00u, 0 }, { 0xABCDEF01u, 1 }, { 0xFFFFFF00u, 0 }, { 0x00000100u, 0 }, { 0x000000FFu, 1 },
  };
  for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
    g_flag = -1;
    CHECK(f((void*)0x1234, cases[i].in) == 0);
    CHECK(g_flag == cases[i].want);
    CHECK(g_self == (void*)0x1234);
  }
  /* mesma regra no Bus::setMute */
  CHECK(fmod_shim_set_target("FMOD_Studio_Bus_SetMute", (void*)fake_set_paused));
  f = (fn_t)dw_fmod_resolve("_ZN4FMOD6Studio3Bus7setMuteEb");
  CHECK(f);
  g_flag = -1;
  f((void*)1, 0x5500u);
  CHECK(g_flag == 0);
  return 0;
}

/* --- bool* de saida: 1 byte no C++, FMOD_BOOL (int) no C --- */
static int g_rc;
static int fake_get_paused(void* self, int* out) { (void)self; if (out) *out = 7; return g_rc; }

static int test_bool_out_one_byte(void) {
  CHECK(fmod_shim_set_target("FMOD_Studio_EventInstance_GetPaused", (void*)fake_get_paused));
  typedef int (*fn_t)(void*, uint8_t*);
  fn_t f = (fn_t)dw_fmod_resolve("_ZNK4FMOD6Studio13EventInstance9getPausedEPb");
  CHECK(f);
  uint8_t buf[8];
  memset(buf, 0xAA, sizeof buf);
  g_rc = 0;
  CHECK(f((void*)1, buf + 3) == 0);
  CHECK(buf[3] == 1);                                              /* normalizado para 0/1 */
  CHECK(buf[0] == 0xAA && buf[1] == 0xAA && buf[2] == 0xAA && buf[4] == 0xAA && buf[7] == 0xAA);
  memset(buf, 0xAA, sizeof buf);
  g_rc = 36;                                                        /* erro: nada e escrito */
  CHECK(f((void*)1, buf + 3) == 36);
  CHECK(buf[3] == 0xAA);
  g_rc = 0;
  CHECK(f((void*)1, NULL) == 0);                                    /* NULL segue como NULL */
  return 0;
}

/* --- FMOD_STUDIO_PARAMETER_ID por valor (8 bytes, um registrador nas duas convencoes) --- */
static param_id g_id;
static float g_val;
static int g_ign;
static int fake_set_param_id(void* self, param_id id, float v, int ign) { (void)self; g_id = id; g_val = v; g_ign = ign; return 0; }

static int test_param_id_by_value(void) {
  CHECK(fmod_shim_set_target("FMOD_Studio_EventInstance_SetParameterByID", (void*)fake_set_param_id));
  typedef int (*fn_t)(void*, param_id, float, unsigned int);
  fn_t f = (fn_t)dw_fmod_resolve("_ZN4FMOD6Studio13EventInstance16setParameterByIDE24FMOD_STUDIO_PARAMETER_IDfb");
  CHECK(f);
  param_id id = { 0xDEADBEEFu, 0x01020304u };
  CHECK(f((void*)1, id, 2.5f, 0xFFFF0001u) == 0);
  CHECK(g_id.data1 == 0xDEADBEEFu && g_id.data2 == 0x01020304u);
  CHECK(g_val == 2.5f && g_ign == 1);
  return 0;
}

/* --- ponteiros, ints e floats passam intactos --- */
static void* g_cb; static unsigned g_mask;
static int fake_set_cb(void* self, void* cb, unsigned mask) { (void)self; g_cb = cb; g_mask = mask; return 0; }
static char* g_pbuf; static int g_psize; static int* g_pret;
static int fake_get_path(void* self, char* p, int size, int* ret) { (void)self; g_pbuf = p; g_psize = size; g_pret = ret; return 5; }
static int fake_create(void** sys, unsigned ver) { *sys = (void*)(uintptr_t)ver; return 0; }

static int test_passthrough_args(void) {
  CHECK(fmod_shim_set_target("FMOD_Studio_EventInstance_SetCallback", (void*)fake_set_cb));
  CHECK(fmod_shim_set_target("FMOD_Studio_EventDescription_GetPath", (void*)fake_get_path));
  CHECK(fmod_shim_set_target("FMOD_Studio_System_Create", (void*)fake_create));
  typedef int (*cb_t)(void*, void*, unsigned);
  cb_t f = (cb_t)dw_fmod_resolve("_ZN4FMOD6Studio13EventInstance11setCallbackEPF11FMOD_RESULTjP25FMOD_STUDIO_EVENTINSTANCEPvEj");
  CHECK(f && f((void*)1, (void*)0x777, 0x28u) == 0);
  CHECK(g_cb == (void*)0x777 && g_mask == 0x28u);
  typedef int (*path_t)(void*, char*, int, int*);
  path_t p = (path_t)dw_fmod_resolve("_ZNK4FMOD6Studio16EventDescription7getPathEPciPi");
  char b[4]; int r;
  CHECK(p && p((void*)1, b, 4, &r) == 5);
  CHECK(g_pbuf == b && g_psize == 4 && g_pret == &r);
  typedef int (*cr_t)(void**, unsigned);
  cr_t c = (cr_t)dw_fmod_resolve("_ZN4FMOD6Studio6System6createEPPS1_j");   /* estatico: sem this */
  void* sys = NULL;
  CHECK(c && c(&sys, 0x00020309u) == 0 && sys == (void*)0x00020309u);
  return 0;
}

static int test_unresolved_listed(void) {
  static char err[32768];
  int n = fmod_shim_load(NULL, NULL, err, sizeof err);
  CHECK(n > 367);
  CHECK(strstr(err, "FMOD_System_CreateSound") != NULL);
  CHECK(strstr(err, "FMOD_Studio_System_Create") != NULL);
  CHECK(strstr(err, "FMOD_Channel_Stop") != NULL);
  CHECK(dw_fmod_resolve("FMOD_System_CreateSound") == NULL);
  char tiny[40];
  n = fmod_shim_load(NULL, NULL, tiny, sizeof tiny);        /* lista truncada, sem estourar */
  CHECK(n > 367 && strlen(tiny) < sizeof tiny);
  return 0;
}

static int test_resolves_real_dlls(const char* dir) {
  if (!dir) return 77;
  char p1[MAX_PATH * 2], p2[MAX_PATH * 2];
  snprintf(p1, sizeof p1, "%s/fmod.dll", dir);
  snprintf(p2, sizeof p2, "%s/fmodstudio.dll", dir);
  if (GetFileAttributesA(p1) == INVALID_FILE_ATTRIBUTES) { printf("SKIP: sem %s\n", p1); return 77; }
  HMODULE c = LoadLibraryExA(p1, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
  HMODULE s = LoadLibraryExA(p2, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
  CHECK(c && s);
  static char err[32768];
  int n = fmod_shim_load(c, s, err, sizeof err);
  if (n) fprintf(stderr, "ausentes (%d):%s\n", n, err);
  CHECK(n == 0);
  void* a = dw_fmod_resolve("FMOD_System_CreateSound");
  CHECK(a && a == (void*)GetProcAddress(c, "FMOD_System_CreateSound"));
  CHECK(dw_fmod_resolve("FMOD_Studio_EventDescription_GetParameterLabelByIndex") ==
        (void*)GetProcAddress(s, "FMOD_Studio_EventDescription_GetParameterLabelByIndex"));
  CHECK(dw_fmod_resolve("_ZN4FMOD6Studio6System6updateEv") != NULL);
  CHECK(dw_fmod_resolve("FMOD_Nao_Existe") == NULL);
  CHECK(dw_fmod_resolve("_ZN4FMOD6Studio6System7naoExisteEv") == NULL);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "uso: test_fmodshim <teste> [dir]\n"); return 2; }
  const char* t = argv[1];
  int rc;
  if (!strcmp(t, "counts")) rc = test_counts();
  else if (!strcmp(t, "bool_in_low_byte")) rc = test_bool_in_low_byte();
  else if (!strcmp(t, "bool_out_one_byte")) rc = test_bool_out_one_byte();
  else if (!strcmp(t, "param_id_by_value")) rc = test_param_id_by_value();
  else if (!strcmp(t, "passthrough_args")) rc = test_passthrough_args();
  else if (!strcmp(t, "unresolved_listed")) rc = test_unresolved_listed();
  else if (!strcmp(t, "resolves_real_dlls")) rc = test_resolves_real_dlls(argc > 2 ? argv[2] : NULL);
  else { fprintf(stderr, "teste desconhecido: %s\n", t); return 2; }
  if (rc == 0) printf("PASS %s\n", t);
  return rc;
}
