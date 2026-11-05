/* Testes do runtime de excecoes C++ das dylibs (M2 Tarefa 5), contra a fixture Mach-O throw_test.dylib
 * (tests/fixtures/throw_test.cpp), carregada pelo carregador real e desenrolada pelo runtime real.
 * Uso: test_eh <teste> <pasta das fixtures>
 *      test_eh child <std|custom|host> <pasta>   (modo filho do teste de excecao nao capturada) */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <crtdbg.h>
#include "darwin.h"
#include "macho_loader.h"
#include "dw_unwind.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)
#define CHECK_EQ(a, b) do { long long _a = (long long)(a), _b = (long long)(b); \
    if (_a != _b) { fprintf(stderr, "FAIL %s:%d: %s == %s (%lld != %lld)\n", __FILE__, __LINE__, #a, #b, _a, _b); return 1; } } while (0)

extern int dw_ti_can_catch(const void* catch_ti, const void* thrown_ti, void** adjusted_ptr);

static macho_lib_t* g_lib;
static char g_dir[MAX_PATH * 2];

#define SYM(name) ((void*)macho_dlsym(g_lib, name))

typedef int (*f_int)(int);
typedef int (*f_int2)(int, int);
typedef int (*f_void)(void);
typedef void (*f_vvoid)(void);

static int load_fixture(void) {
  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/throw_test.dylib", g_dir);
  g_lib = macho_load(path);
  return g_lib != NULL;
}

static int test_throw_catch_same_frame(void) {
  f_int f = (f_int)SYM("eh_same_frame");
  CHECK(f);
  CHECK_EQ(f(5), 1005);
  CHECK_EQ(f(0), -1);        /* sem throw: caminho normal intacto */
  for (int i = 1; i < 200; i++) CHECK_EQ(f(i), 1000 + i);   /* repetido: nada vaza nem trava */
  return 0;
}

static int test_throw_across_frames(void) {
  f_int2 f = (f_int2)SYM("eh_across");
  CHECK(f);
  CHECK_EQ(f(0, 42), 42);
  CHECK_EQ(f(1, 43), 43);
  CHECK_EQ(f(3, 44), 44);    /* 3 niveis acima do throw */
  CHECK_EQ(f(50, 45), 45);   /* pilha funda */
  return 0;
}

static int test_cleanup_runs(void) {
  f_int f = (f_int)SYM("eh_cleanup_runs");
  f_vvoid reset = (f_vvoid)SYM("eh_reset");
  f_int get = (f_int)SYM("eh_log_get");
  CHECK(f && reset && get);
  reset();
  CHECK_EQ(f(7), 5);
  /* destrutores do mais fundo ao mais raso, ANTES do catch (que registra 100 + valor) */
  CHECK_EQ(get(0), 10);
  CHECK_EQ(get(1), 11);
  CHECK_EQ(get(2), 12);
  CHECK_EQ(get(3), 1);
  CHECK_EQ(get(4), 107);
  return 0;
}

static int test_catch_base_class(void) {
  typedef int (*f_t)(char*);
  f_t f = (f_t)SYM("eh_catch_base");
  CHECK(f);
  char out[64] = {0};
  CHECK_EQ(f(out), 1);       /* runtime_error capturado como std::exception& */
  CHECK(strcmp(out, "runtime_error message") == 0);
  return 0;
}

static int test_rethrow(void) {
  f_int f = (f_int)SYM("eh_rethrow");
  f_void counted = (f_void)SYM("eh_rethrow_counted");
  CHECK(f && counted);
  CHECK_EQ(f(6), 6 * 10 + 1);     /* relancada uma vez, capturada fora */
  CHECK_EQ(counted(), 500);       /* relancar nao destroi: o objeto vive no catch externo */
  f_void dtors = (f_void)SYM("eh_dtors");
  CHECK_EQ(dtors(), 1);           /* e e destruido uma unica vez ao sair dele */
  return 0;
}

static int test_nested_exception(void) {
  f_void nested = (f_void)SYM("eh_nested_in_catch");
  f_void from_catch = (f_void)SYM("eh_throw_from_catch");
  CHECK(nested && from_catch);
  CHECK_EQ(nested(), 12);         /* throw dentro de catch, capturado dentro do mesmo catch */
  CHECK_EQ(from_catch(), 201);    /* throw dentro de catch propagando; a primeira e destruida */
  return 0;
}

static int test_catch_all(void) {
  f_int f = (f_int)SYM("eh_catch_all");
  f_void dtor_once = (f_void)SYM("eh_dtor_once");
  CHECK(f && dtor_once);
  CHECK_EQ(f(0), 7);              /* classe propria */
  CHECK_EQ(f(1), 7);              /* runtime_error */
  CHECK_EQ(f(2), 7);              /* com destrutor */
  CHECK_EQ(dtor_once(), 1);
  return 0;
}

static int test_hierarchy(void) {
  f_int hier = (f_int)SYM("eh_hier");
  f_void amb = (f_void)SYM("eh_ambiguous");
  f_void priv = (f_void)SYM("eh_private_base");
  f_void multi = (f_void)SYM("eh_multi_first");
  CHECK(hier && amb && priv && multi);
  CHECK_EQ(hier(0), 11);          /* Derived -> Base& */
  CHECK_EQ(hier(1), 22);          /* Multi -> M2& (base nao primaria: ponteiro ajustado) */
  CHECK_EQ(hier(2), 31);          /* VDer -> VBase& (base virtual) */
  CHECK_EQ(hier(4), 41);          /* Priv: nao casa Base& (privada), casa Priv& */
  CHECK_EQ(hier(5), 55);          /* Amb: Base ambigua, nao casa */
  CHECK_EQ(hier(9), -2);          /* nenhum catch de classe: catch (...) */
  CHECK_EQ(amb(), 55);
  CHECK_EQ(priv(), 41);
  CHECK_EQ(multi(), 21);
  return 0;
}

static int test_ti_can_catch(void) {
  typedef void* (*f_ti)(int);
  f_ti ti = (f_ti)SYM("eh_ti");
  CHECK(ti);
  void *base = ti(0), *derived = ti(1), *m1 = ti(2), *m2 = ti(3), *multi = ti(4), *priv = ti(5), *amb = ti(6),
       *vbase = ti(7);
  CHECK(base && derived && m1 && m2 && multi && priv && amb && vbase);
  char obj[64];
  void* p;
  p = obj; CHECK(dw_ti_can_catch(NULL, multi, &p) && p == obj);               /* catch (...) */
  p = obj; CHECK(dw_ti_can_catch(multi, multi, &p) && p == obj);              /* mesmo tipo */
  p = obj; CHECK(dw_ti_can_catch(base, derived, &p) && p == obj);             /* si_class */
  p = obj; CHECK(dw_ti_can_catch(m1, multi, &p) && p == obj);                 /* vmi, base primaria */
  p = obj; CHECK(dw_ti_can_catch(m2, multi, &p) && p == obj + 16);           /* vmi, offset 16 */
  p = obj; CHECK(!dw_ti_can_catch(derived, base, &p) && p == obj);           /* nao desce, so sobe */
  p = obj; CHECK(!dw_ti_can_catch(m1, derived, &p));                         /* sem relacao */
  p = obj; CHECK(!dw_ti_can_catch(base, priv, &p) && p == obj);              /* base privada */
  p = obj; CHECK(!dw_ti_can_catch(base, amb, &p));                           /* base ambigua */
  p = obj; CHECK(!dw_ti_can_catch(vbase, derived, &p));
  /* mesma classe por nome (strcmp), com typeinfo em outro endereco */
  struct { const void* vptr; const char* name; } clone;
  const char* dname_raw = ((const char* const*)derived)[1];
  /* o ponteiro do nome pode ja trazer o bit 63 (RTTI nao unico): mascarar antes de ler a string */
  const char* dname = (const char*)((uintptr_t)dname_raw & ~((uintptr_t)1 << 63));
  char* name_copy = strdup(dname);   /* ponteiro diferente, mesmo conteudo */
  CHECK(name_copy && name_copy != dname);
  clone.vptr = *(const void* const*)derived;
  clone.name = name_copy;
  p = obj; CHECK(dw_ti_can_catch(&clone, derived, &p) && p == obj);
  /* bit 63 do nome (RTTI nao unico, Apple arm64): comparar por strcmp com o bit mascarado */
  clone.name = (const char*)((uintptr_t)name_copy | ((uintptr_t)1 << 63));
  p = obj; CHECK(dw_ti_can_catch(&clone, derived, &p) && p == obj);
  free(name_copy);
  return 0;
}

#define NREGS 18
static int test_callee_saved_restored(void) {
  typedef void (*f_t)(int64_t*, int64_t*, int);
  f_t f = (f_t)SYM("eh_regs_probe");
  CHECK(f);
  for (int depth = 0; depth < 6; depth++) {
    int64_t before[NREGS], after[NREGS];
    memset(before, 0xA5, sizeof before);
    memset(after, 0x5A, sizeof after);
    f(before, after, depth);
    int bad = 0;
    for (int i = 0; i < NREGS; i++) {
      if (before[i] != after[i]) {
        fprintf(stderr, "FAIL depth %d: %s%d before=%#llx after=%#llx\n", depth, i < 10 ? "x" : "d",
                i < 10 ? 19 + i : 8 + i - 10, (long long)before[i], (long long)after[i]);
        bad = 1;
      }
    }
    if (bad) return 1;
  }
  return 0;
}

/* ---- as 5 dylibs reais: todo pc de codigo tem entrada de unwind legivel, com a personality certa ---- */

static int test_real_dylibs_unwind_tables(const char* dylib_dir) {
  const char* libs[] = {"libLighting.dylib", "libPZPopMan.dylib", "libPZPathFind.dylib", "libPZBullet.dylib",
                        "libPZClipper.dylib"};
  void* personality = dw_resolve("__gxx_personality_v0");
  CHECK(personality);
  for (size_t i = 0; i < sizeof libs / sizeof libs[0]; i++) {
    char path[MAX_PATH * 2];
    snprintf(path, sizeof path, "%s/%s", dylib_dir, libs[i]);
    CHECK(macho_load(path));
  }
  /* o padrao das 5 libs: todo pc de codigo acha uma entrada; frame/frameless com bits conhecidos; so uma
     funcao em modo DWARF (Bullet) e toda personality e a do runtime */
  const uint32_t known = 0x0F000000u | 0x40000000u | 0x30000000u | 0x0F1Fu | 0x00FFF000u;
  long checked_libs = 0, dwarf = 0, frame = 0, frameless = 0, lsda = 0, with_pers = 0, unknown_bits = 0, bad_pers = 0;
  for (int k = 0; k < macho_image_count(); k++) {
    const macho_image_t* im = macho_image_at(k);
    int is_real = 0;
    for (size_t i = 0; i < sizeof libs / sizeof libs[0]; i++) is_real |= strcmp(im->name, libs[i]) == 0;
    if (!is_real) continue;
    checked_libs++;
    long lib_funcs = 0;
    uintptr_t last_func = 0;
    for (uintptr_t pc = im->base; pc < im->base + im->size; pc += 4) {
      dw_func_t f;
      int r = dw_unwind_find(pc, &f);
      if (r == DW_UW_NO_INFO) continue;   /* pc fora de __text (stubs, dados) */
      if (r != DW_UW_OK) { fprintf(stderr, "FAIL %s+%#llx: unwind lookup error %d\n", im->name, (unsigned long long)(pc - im->base), r); return 1; }
      if (f.func_start == last_func) continue;   /* mesma funcao: conta uma vez */
      last_func = f.func_start;
      lib_funcs++;
      const uint32_t mode = f.enc & 0x0F000000u;
      if (mode == 0x03000000u) dwarf++;
      else if (mode == 0x04000000u) { frame++; if (f.enc & ~known) unknown_bits++; }
      else if (mode == 0x02000000u) frameless++;
      else { fprintf(stderr, "FAIL %s: encoding %#x\n", im->name, f.enc); return 1; }
      if (f.lsda) lsda++;
      if (f.has_personality) { with_pers++; if (f.personality != personality) bad_pers++; }
    }
    printf("%s: %ld funcoes com unwind\n", im->name, lib_funcs);
    CHECK(lib_funcs > 100);
  }
  printf("5 libs: frame=%ld frameless=%ld dwarf=%ld com_lsda=%ld com_personality=%ld bits_desconhecidos=%ld personality_errada=%ld\n",
         frame, frameless, dwarf, lsda, with_pers, unknown_bits, bad_pers);
  CHECK(checked_libs == 5);
  CHECK(dwarf <= 1);          /* a unica funcao DWARF (Bullet) nao tem LSDA */
  CHECK(unknown_bits == 0);
  CHECK(bad_pers == 0);
  CHECK(lsda > 0 && with_pers >= lsda);
  return 0;
}

/* ---- excecao nao capturada: processo filho ---- */

static int run_child(const char* kind, char* out, size_t outcap, DWORD* exit_code) {
  wchar_t exe[MAX_PATH * 2];
  GetModuleFileNameW(NULL, exe, (DWORD)(sizeof exe / sizeof exe[0]));
  wchar_t wdir[MAX_PATH * 2], wkind[64];
  MultiByteToWideChar(CP_UTF8, 0, g_dir, -1, wdir, (int)(sizeof wdir / sizeof wdir[0]));
  MultiByteToWideChar(CP_UTF8, 0, kind, -1, wkind, (int)(sizeof wkind / sizeof wkind[0]));
  wchar_t cmd[MAX_PATH * 6];
  swprintf(cmd, sizeof cmd / sizeof cmd[0], L"\"%ls\" child %ls \"%ls\"", exe, wkind, wdir);

  SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
  HANDLE rd = NULL, wr = NULL;
  if (!CreatePipe(&rd, &wr, &sa, 0)) return 0;
  SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);
  STARTUPINFOW si;
  memset(&si, 0, sizeof si);
  si.cb = sizeof si;
  si.dwFlags = STARTF_USESTDHANDLES;
  si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
  si.hStdOutput = wr;
  si.hStdError = wr;
  PROCESS_INFORMATION pi;
  if (!CreateProcessW(NULL, cmd, NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
    CloseHandle(rd); CloseHandle(wr);
    return 0;
  }
  CloseHandle(wr);
  size_t n = 0;
  for (;;) {
    DWORD got = 0;
    if (!ReadFile(rd, out + n, (DWORD)(outcap - 1 - n), &got, NULL) || got == 0) break;
    n += got;
    if (n >= outcap - 1) break;
  }
  out[n] = 0;
  DWORD w = WaitForSingleObject(pi.hProcess, 20000);
  if (w != WAIT_OBJECT_0) TerminateProcess(pi.hProcess, 99);
  GetExitCodeProcess(pi.hProcess, exit_code);
  CloseHandle(pi.hProcess); CloseHandle(pi.hThread); CloseHandle(rd);
  return w == WAIT_OBJECT_0;
}

static int test_uncaught_logs_and_aborts(void) {
  char out[4096];
  DWORD code = 0;

  /* 1. runtime_error nao capturada, lancada de dentro de uma dylib e chegando ao codigo do host */
  CHECK(run_child("std", out, sizeof out, &code));
  printf("--- child std (exit %lu) ---\n%s\n", (unsigned long)code, out);
  CHECK(code != 0);
  CHECK(strstr(out, "[eh] exception reached non-dylib frame:") != NULL);
  CHECK(strstr(out, "lib=throw_test.dylib") != NULL);
  CHECK(strstr(out, "fn=eh_uncaught") != NULL);
  CHECK(strstr(out, "what=boom nobody catches") != NULL);

  /* 2. tipo sem std::exception */
  CHECK(run_child("custom", out, sizeof out, &code));
  printf("--- child custom (exit %lu) ---\n%s\n", (unsigned long)code, out);
  CHECK(code != 0);
  CHECK(strstr(out, "[eh] exception reached non-dylib frame:") != NULL);
  CHECK(strstr(out, "fn=eh_uncaught_custom") != NULL);
  CHECK(strstr(out, "what=(not std::exception)") != NULL);

  /* 3. dylib -> host -> dylib que lanca: um quadro de host no meio encerra mesmo havendo catch (...) acima */
  CHECK(run_child("host", out, sizeof out, &code));
  printf("--- child host (exit %lu) ---\n%s\n", (unsigned long)code, out);
  CHECK(code != 0);
  CHECK(strstr(out, "[eh] exception reached non-dylib frame:") != NULL);
  CHECK(strstr(out, "fn=eh_uncaught_custom") != NULL);
  return 0;
}

static volatile int g_after_call;
static void host_cb(void) {
  ((f_vvoid)SYM("eh_uncaught_custom"))();
  g_after_call = 1;   /* sem chamada em cauda: este quadro (de host) tem que existir na pilha */
}

static int child_main(const char* kind) {
  _set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);   /* sem dialogo do Windows */
  SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX);
  if (!load_fixture()) return 70;
  if (strcmp(kind, "std") == 0) {
    ((void (*)(const char*))SYM("eh_uncaught"))("boom nobody catches");
  } else if (strcmp(kind, "custom") == 0) {
    ((f_vvoid)SYM("eh_uncaught_custom"))();
  } else if (strcmp(kind, "host") == 0) {
    ((void (*)(f_vvoid))SYM("eh_call_through_host"))(host_cb);
  }
  return 71;   /* se chegou aqui, a excecao foi engolida: o teste pai falha pelo codigo/ausencia do log */
}

static int test_exception_ptr(void) {
  int (*eptr)(int*, int*, int*) = (int (*)(int*, int*, int*))SYM("eh_eptr");
  f_void none = (f_void)SYM("eh_eptr_null");
  f_void self = (f_void)SYM("eh_eptr_self_assign");
  CHECK(eptr && none && self);
  CHECK_EQ(none(), 1);                  /* sem excecao ativa: ponteiro nulo */
  int in_catch = -1, after = -1, v = -1;
  int total = eptr(&in_catch, &after, &v);
  CHECK_EQ(in_catch, 0);                /* dentro do catch: nada destruido */
  CHECK_EQ(after, 0);                   /* fim do catch com exception_ptr viva: continua viva */
  CHECK_EQ(v, 77);                      /* e acessivel pelo ponteiro */
  CHECK_EQ(total, 1);                   /* destruida ao soltar a ultima referencia (uma vez) */
  CHECK_EQ(self(), 11);                 /* a = b duas vezes: sem vazamento nem dupla destruicao */
  for (int i = 0; i < 50; i++) { CHECK_EQ(eptr(&in_catch, &after, &v), 1); }
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 3) { fputs("uso: test_eh <teste> <pasta das fixtures>\n", stderr); return 2; }
  if (strcmp(argv[1], "child") == 0) {
    if (argc < 4) return 2;
    snprintf(g_dir, sizeof g_dir, "%s", argv[3]);
    return child_main(argv[2]);
  }
  snprintf(g_dir, sizeof g_dir, "%s", argv[2]);
  if (!load_fixture()) { fputs("FAIL: nao carregou throw_test.dylib\n", stderr); return 1; }
  const char* t = argv[1];
  int r;
  if (!strcmp(t, "throw_catch_same_frame")) r = test_throw_catch_same_frame();
  else if (!strcmp(t, "throw_across_frames")) r = test_throw_across_frames();
  else if (!strcmp(t, "cleanup_runs")) r = test_cleanup_runs();
  else if (!strcmp(t, "catch_base_class")) r = test_catch_base_class();
  else if (!strcmp(t, "rethrow")) r = test_rethrow();
  else if (!strcmp(t, "nested_exception")) r = test_nested_exception();
  else if (!strcmp(t, "catch_all")) r = test_catch_all();
  else if (!strcmp(t, "hierarchy")) r = test_hierarchy();
  else if (!strcmp(t, "ti_can_catch")) r = test_ti_can_catch();
  else if (!strcmp(t, "exception_ptr")) r = test_exception_ptr();
  else if (!strcmp(t, "callee_saved_restored")) r = test_callee_saved_restored();
  else if (!strcmp(t, "uncaught_logs_and_aborts")) r = test_uncaught_logs_and_aborts();
  else if (!strcmp(t, "real_dylibs_unwind_tables") && argc > 3) r = test_real_dylibs_unwind_tables(argv[3]);
  else { fprintf(stderr, "teste desconhecido: %s\n", t); return 2; }
  if (!r) printf("PASS %s\n", t);
  return r;
}
