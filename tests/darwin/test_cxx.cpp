// Testes da superficie C++ com layout Apple. Tudo passa por dw_resolve (a tabela real) contra
// pzdarwin.dll. Nenhum std::string da libc++ cruza para a DLL: so o layout Apple de 24 bytes.
// Uso: test_cxx <teste> [args]
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <cstring>
#include <ios>
#include <istream>
#include <locale>
#include <mutex>
#include <ostream>
#include <set>
#include <sstream>
#include <stdexcept>
#include <streambuf>
#include <string>
#include <vector>
#include <windows.h>
#include "darwin.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

struct ms { uint64_t w[3]; };   // std::string da Apple
extern "C" void dw_runtime_error_ctor_string(void* self, const ms* s);

#define S "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
template <class T> static T R(const char* name) {
  void* p = dw_resolve(name);
  if (!p) { fprintf(stderr, "sem %s\n", name); exit(1); }
  return reinterpret_cast<T>(p);
}
typedef ms* (*f_assign)(ms*, const char*);
typedef ms* (*f_append)(ms*, const char*);
typedef ms* (*f_appendn)(ms*, const char*, uint64_t);
typedef ms* (*f_insert)(ms*, uint64_t, const char*);
typedef void (*f_resize)(ms*, uint64_t, char);
typedef void (*f_push)(ms*, char);
typedef ms* (*f_copy)(ms*, const ms*);
typedef void (*f_dtor)(ms*);
typedef ms (*f_concat)(const char*, const ms*);
typedef ms (*f_tostr_i)(int);
typedef ms (*f_tostr_u)(unsigned);

struct Str {   // envoltorio de teste sobre as funcoes resolvidas
  ms v{};
  f_assign assign = R<f_assign>(S "6assignEPKc");
  f_append append = R<f_append>(S "6appendEPKc");
  f_appendn appendn = R<f_appendn>(S "6appendEPKcm");
  f_insert insert = R<f_insert>(S "6insertEmPKc");
  f_resize resize = R<f_resize>(S "6resizeEmc");
  f_push push = R<f_push>(S "9push_backEc");
  f_copy copy = R<f_copy>(S "aSERKS5_");
  f_dtor dtor = R<f_dtor>(S "D1Ev");
  ~Str() { dtor(&v); }
  bool is_long() const { return (v.w[2] >> 63) != 0; }
  size_t size() const { return is_long() ? v.w[1] : reinterpret_cast<const unsigned char*>(&v)[23]; }
  const char* data() const { return is_long() ? reinterpret_cast<const char*>(v.w[0]) : reinterpret_cast<const char*>(&v); }
  std::string str() const { return std::string(data(), size()); }
};

static int test_string_layout() {
  Str s;
  std::string t22(22, 'a'), t23(23, 'b');
  s.assign(&s.v, t22.c_str());
  const unsigned char* b = reinterpret_cast<const unsigned char*>(&s.v);
  CHECK(!s.is_long());
  CHECK(b[23] == 22);                       // tamanho no ultimo byte, bit 7 limpo
  CHECK(memcmp(b, t22.data(), 22) == 0 && b[22] == 0);   // 22 inline + NUL
  s.assign(&s.v, t23.c_str());
  CHECK(s.is_long());
  CHECK(s.v.w[1] == 23);                    // tamanho
  CHECK((s.v.w[2] & ~(1ull << 63)) == 32);  // capacidade com NUL, arredondada a 16
  CHECK((s.v.w[2] >> 63) == 1);             // flag de string longa
  const char* p = reinterpret_cast<const char*>(s.v.w[0]);
  CHECK(p != reinterpret_cast<const char*>(&s.v));
  CHECK(memcmp(p, t23.data(), 23) == 0 && p[23] == 0);   // ponteiro, NUL no fim
  CHECK(dw_malloc_size(p) >= 32);           // alocacao do heap Apple (dw_malloc)
  s.assign(&s.v, "x");                      // volta a ser curta
  CHECK(!s.is_long() && b[23] == 1);
  return 0;
}

static int test_string_ops() {
  auto concat = R<f_concat>("_ZNSt3__1plIcNS_11char_traitsIcEENS_9allocatorIcEEEENS_12basic_stringIT_T0_T1_EEPKS6_RKS9_");
  auto to_i = R<f_tostr_i>("_ZNSt3__19to_stringEi");
  auto to_u = R<f_tostr_u>("_ZNSt3__19to_stringEj");
  for (int len = 0; len <= 60; len++) {
    std::string model(len, 'k');
    Str s; s.assign(&s.v, model.c_str());
    CHECK(s.str() == model);
    model += "AB"; s.append(&s.v, "AB"); CHECK(s.str() == model);
    model += "xyz"; s.appendn(&s.v, "xyzQQ", 3); CHECK(s.str() == model);
    model.push_back('!'); s.push(&s.v, '!'); CHECK(s.str() == model);
    size_t pos = model.size() / 2;
    model.insert(pos, "<mid>"); s.insert(&s.v, pos, "<mid>"); CHECK(s.str() == model);
    model.insert(0, "^"); s.insert(&s.v, 0, "^"); CHECK(s.str() == model);
    for (size_t n : {size_t(5), size_t(22), size_t(23), size_t(70), size_t(3)}) {
      model.resize(n, '#'); s.resize(&s.v, n, '#'); CHECK(s.str() == model);
      CHECK(s.is_long() == (n > 22));
    }
    model.assign(len, 'z'); s.assign(&s.v, model.c_str()); CHECK(s.str() == model);
    Str c; c.copy(&c.v, &s.v); CHECK(c.str() == model);
    c.copy(&c.v, &c.v); CHECK(c.str() == model);          // auto-atribuicao
    std::string pre(len % 30, 'p');
    ms r = concat(pre.c_str(), &s.v);
    Str rr; rr.v = r;
    CHECK(rr.str() == pre + model);
    CHECK(rr.is_long() == (rr.size() > 22));
  }
  Str a; a.assign(&a.v, "0123456789012345678901");        // alias: s.append(s.data())
  a.append(&a.v, a.data());
  CHECK(a.str() == std::string("0123456789012345678901") + "0123456789012345678901");
  for (int v : {0, 7, -1, 123456789, -2147483647}) {
    Str t; t.v = to_i(v); CHECK(t.str() == std::to_string(v));
  }
  for (unsigned v : {0u, 9u, 4000000000u, 4294967295u}) {
    Str t; t.v = to_u(v); CHECK(t.str() == std::to_string(v));
  }
  return 0;
}

static int test_runtime_error_from_string() {
  typedef void (*f_ctor)(void*, const ms*);
  auto ctor = R<f_ctor>("_ZNSt13runtime_errorC2ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
  auto what = R<const char* (*)(void*)>("_ZNKSt13runtime_error4whatEv");
  auto dtor = R<void (*)(void*)>("_ZNSt13runtime_errorD1Ev");
  for (const std::string& text : {std::string("curto"), std::string(22, 'q'), std::string(23, 'r'), std::string(200, 's')}) {
    Str s; s.assign(&s.v, text.c_str());
    alignas(16) unsigned char obj[64];
    memset(obj, 0xAA, sizeof obj);
    ctor(obj, &s.v);                           // pela tabela (dw_resolve)
    CHECK(strcmp(what(obj), text.c_str()) == 0);
    dtor(obj);
    memset(obj, 0xAA, sizeof obj);
    dw_runtime_error_ctor_string(obj, &s.v);   // interface exportada direta
    CHECK(strcmp(what(obj), text.c_str()) == 0);
    s.assign(&s.v, "mudou");                  // a mensagem foi copiada, nao referenciada
    CHECK(strcmp(what(obj), text.c_str()) == 0);
    dtor(obj);
    for (int i = 16; i < 64; i++) CHECK(obj[i] == 0xAA);   // nao escreve alem dos 16 bytes do objeto
  }
  return 0;
}

static int test_random_device() {
  Str token; token.assign(&token.v, "/dev/urandom");
  auto ctor = R<void (*)(void*, const ms*)>("_ZNSt3__113random_deviceC1ERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE");
  auto call = R<unsigned (*)(void*)>("_ZNSt3__113random_deviceclEv");
  auto dtor = R<void (*)(void*)>("_ZNSt3__113random_deviceD1Ev");
  unsigned char obj[16]; memset(obj, 0x5A, sizeof obj);
  ctor(obj, &token.v);
  std::set<unsigned> seen;
  for (int i = 0; i < 100; i++) seen.insert(call(obj));
  CHECK(seen.size() > 90);
  dtor(obj);
  for (int i = 1; i < 16; i++) CHECK(obj[i] == 0x5A);   // o objeto Apple tem 1 byte; o resto nunca e tocado
  return 0;
}

struct GuardCtx { uint64_t* guard; volatile LONG* inits; volatile LONG* ready; };
typedef int (*f_acq)(uint64_t*);
typedef void (*f_rel)(uint64_t*);
static volatile LONG g_start;
static DWORD WINAPI guard_thread(void* p) {
  GuardCtx* c = static_cast<GuardCtx*>(p);
  auto acq = R<f_acq>("__cxa_guard_acquire");
  auto rel = R<f_rel>("__cxa_guard_release");
  while (!g_start) Sleep(0);
  if (!(*reinterpret_cast<volatile unsigned char*>(c->guard))) {   // teste do guard como no codigo Apple
    if (acq(c->guard)) {
      InterlockedIncrement(c->inits);
      Sleep(20);                       // janela para as outras threads esperarem
      rel(c->guard);
    }
  }
  if (!(*reinterpret_cast<volatile unsigned char*>(c->guard))) return 1;   // apos a espera, inicializado
  InterlockedIncrement(c->ready);
  return 0;
}
static int test_guard_once() {
  uint64_t guard = 0; volatile LONG inits = 0, ready = 0;
  GuardCtx ctx{&guard, &inits, &ready};
  HANDLE th[8];
  for (auto& h : th) h = CreateThread(nullptr, 0, guard_thread, &ctx, 0, nullptr);
  g_start = 1;
  WaitForMultipleObjects(8, th, TRUE, 10000);
  for (auto h : th) { DWORD code = 99; GetExitCodeThread(h, &code); CHECK(code == 0); CloseHandle(h); }
  CHECK(inits == 1);
  CHECK(ready == 8);
  CHECK(*reinterpret_cast<unsigned char*>(&guard) == 1);
  return 0;
}

static int test_sort_ints() {
  auto sort = R<void (*)(int*, int*, void*)>("_ZNSt3__16__sortIRNS_6__lessIiiEEPiEEvT0_S5_T_");
  char comp = 0;
  std::vector<int> v(10000);
  uint32_t x = 12345;
  for (auto& e : v) { x = x * 1664525u + 1013904223u; e = static_cast<int>(x); }
  v[0] = INT32_MIN; v[1] = INT32_MAX;
  std::vector<int> ref = v;
  std::sort(ref.begin(), ref.end());
  sort(v.data(), v.data() + v.size(), &comp);
  CHECK(v == ref);
  sort(v.data(), v.data(), &comp);                 // vazio
  int one[1] = {7};                                // 0 elementos (intervalo vazio) e 1 elemento
  sort(one, one, &comp); CHECK(one[0] == 7);
  sort(one, one + 1, &comp); CHECK(one[0] == 7);
  std::vector<int> dup;                            // duplicados
  for (int i = 0; i < 5000; i++) dup.push_back((i * 7919) % 13 - 6);
  dup.push_back(3); dup.push_back(3); dup.push_back(3);
  std::vector<int> dref = dup;
  std::sort(dref.begin(), dref.end());
  sort(dup.data(), dup.data() + dup.size(), &comp);
  CHECK(dup == dref);
  std::vector<int> same(1000, 42);
  sort(same.data(), same.data() + same.size(), &comp);
  CHECK(same == std::vector<int>(1000, 42));
  return 0;
}

static int test_push_back_many() {
  Str s;
  DWORD t0 = GetTickCount();
  for (int i = 0; i < 100000; i++) s.push(&s.v, static_cast<char>('a' + i % 26));
  DWORD dt = GetTickCount() - t0;
  CHECK(s.size() == 100000);
  for (int i = 0; i < 100000; i += 997) CHECK(s.data()[i] == 'a' + i % 26);
  CHECK(s.data()[100000] == 0);
  CHECK(((s.v.w[2] & ~(1ull << 63)) % 16) == 0);   // capacidade (com o NUL) multipla de 16
  CHECK(dt < 1000);
  Str a;
  for (int i = 0; i < 20000; i++) a.append(&a.v, "xy");   // append tambem e geometrico
  CHECK(a.size() == 40000);
  return 0;
}

static int test_system_clock_now() {
  auto now = R<int64_t (*)()>("_ZNSt3__16chrono12system_clock3nowEv");
  int64_t a = now();
  std::chrono::microseconds ref = std::chrono::duration_cast<std::chrono::microseconds>(
      std::chrono::system_clock::now().time_since_epoch());
  int64_t b = now();
  CHECK(a > 1700000000LL * 1000000);               // microssegundos desde 1970 (depois de 2023)
  CHECK(llabs(ref.count() - a) < 5 * 1000000);     // mesma epoca que a libc++ do host
  CHECK(b >= a && b - a < 5 * 1000000);
  Sleep(20);
  CHECK(now() - b >= 15000);                       // avanca em us (20 ms ~ 20000 us)
  return 0;
}

static int test_guard_abort() {
  auto acq = R<int (*)(uint64_t*)>("__cxa_guard_acquire");
  auto abt = R<void (*)(uint64_t*)>("__cxa_guard_abort");
  auto rel = R<void (*)(uint64_t*)>("__cxa_guard_release");
  uint64_t g = 0;
  CHECK(acq(&g) == 1);                             // esta thread inicializa
  abt(&g);                                         // construtor lancou: nada fica marcado
  CHECK(*reinterpret_cast<unsigned char*>(&g) == 0);
  CHECK(acq(&g) == 1);                             // outra tentativa pode inicializar de novo
  rel(&g);
  CHECK(*reinterpret_cast<unsigned char*>(&g) == 1);
  CHECK(acq(&g) == 0);                             // ja inicializado
  return 0;
}

static int test_cxx_sizes() {
  // Layout Apple (arm64, libc++ da Apple), confirmado no desmonte de libPZPopMan.dylib
  const size_t apple_ios_base = 136, apple_streambuf = 64, apple_locale = 8, apple_rmutex_max = 64;
  printf("ios_base=%zu streambuf=%zu locale=%zu recursive_mutex=%zu\n", sizeof(std::ios_base),
         sizeof(std::basic_streambuf<char>), sizeof(std::locale), sizeof(std::recursive_mutex));
  CHECK(sizeof(std::ios_base) == apple_ios_base);
  CHECK(sizeof(std::basic_streambuf<char>) == apple_streambuf);
  CHECK(sizeof(std::locale) == apple_locale);
  CHECK(sizeof(std::recursive_mutex) <= apple_rmutex_max);
  CHECK(sizeof(std::ostream::sentry) == 16);       // bool + ostream&, igual ao objeto Apple
  CHECK(sizeof(std::runtime_error) == 16);
  return 0;
}

// operator new/delete da libc++ estatica da DLL sao os da dw_malloc/dw_free: memoria cruza nos dois sentidos.
static int test_new_delete_cross() {
  typedef void* (*f_new)(uint64_t);
  typedef void (*f_del)(void*);
  typedef void (*f_ctor_c)(void*, const char*);
  auto dnew = R<f_new>("_Znwm");          // "dylib": new / delete que a dylib importa
  auto dnewa = R<f_new>("_Znam");
  auto ddel = R<f_del>("_ZdlPv");
  auto ddela = R<f_del>("_ZdaPv");
  auto ctor = R<f_ctor_c>("_ZNSt13runtime_errorC2EPKc");        // libc++abi estatica da DLL
  auto what = R<const char* (*)(void*)>("_ZNKSt13runtime_error4whatEv");
  auto dtor = R<void (*)(void*)>("_ZNSt13runtime_errorD2Ev");

  // 1. alocado pela libc++ (new[] dentro de __libcpp_refstring) -> pertence a dw_malloc -> a dylib pode liberar.
  const char* msg = "mensagem alocada pela libc++ da DLL";
  const size_t len = strlen(msg);
  alignas(16) unsigned char obj[16];
  memset(obj, 0, sizeof obj);
  ctor(obj, msg);
  const char* w = what(obj);
  CHECK(strcmp(w, msg) == 0);
  void* rep = const_cast<char*>(w) - 24;                         // _Rep_base { len, cap, count } = 24 bytes
  CHECK(dw_malloc_size(rep) >= 24 + len + 1);                    // cabecalho dw_malloc valido
  CHECK(*reinterpret_cast<uint64_t*>(rep) == len);               // layout confere
  CHECK(*reinterpret_cast<int*>(static_cast<char*>(rep) + 16) == 0);
  ddela(rep);                                                    // a dylib libera memoria da libc++ (sem crash)

  // 2. alocado pela dylib (_Znam) -> liberado pelo operator delete[] da libc++ (destrutor de runtime_error).
  const size_t total = 24 + len + 1;
  char* mine = static_cast<char*>(dnewa(total));
  CHECK(mine != nullptr && dw_malloc_size(mine) == total);
  memset(mine, 0, total);
  *reinterpret_cast<uint64_t*>(mine) = len;
  *reinterpret_cast<uint64_t*>(mine + 8) = len;
  memcpy(mine + 24, msg, len + 1);
  unsigned char obj2[16];
  memcpy(obj2, obj, 8);                                          // vptr do runtime_error
  *reinterpret_cast<char**>(obj2 + 8) = mine + 24;
  CHECK(strcmp(what(obj2), msg) == 0);
  dtor(obj2);                                                    // count 0 -> -1 -> delete[] da libc++: sem crash

  // 3. new/delete simples e aligned
  void* a = dnew(100);
  CHECK(a && dw_malloc_size(a) == 100);
  ddel(a);
  return 0;
}

// ---- M3 Tarefa 2: superficie da libjassimp.dylib ----
#define SK "_ZNKSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
typedef uint64_t (*f_find)(const ms*, char, uint64_t);
typedef ms* (*f_erase)(ms*, uint64_t, uint64_t);
typedef ms* (*f_insert_n)(ms*, uint64_t, const char*, uint64_t);
typedef ms* (*f_replace)(ms*, uint64_t, uint64_t, const char*);

static int test_string_extra_ops() {
  auto find = R<f_find>(SK "4findEcm");
  auto rfind = R<f_find>(SK "5rfindEcm");
  auto erase = R<f_erase>(S "5eraseEmm");
  auto insert_n = R<f_insert_n>(S "6insertEmPKcm");
  auto replace = R<f_replace>(S "7replaceEmmPKc");
  const uint64_t npos = ~0ull;
  const char* ins = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  for (int len = 0; len <= 60; len++) {
    std::string model;
    for (int i = 0; i < len; i++) model.push_back("abcxyz./"[i % 8]);
    Str s; s.assign(&s.v, model.c_str());
    // find / rfind (char, pos)
    for (char c : {'a', 'z', '/', 'Q'}) {
      for (uint64_t pos : {uint64_t(0), uint64_t(len / 2), uint64_t(len ? len - 1 : 0), uint64_t(len), uint64_t(len + 5), npos}) {
        CHECK(find(&s.v, c, pos) == model.find(c, pos));
        CHECK(rfind(&s.v, c, pos) == model.rfind(c, pos));
      }
    }
    // erase(pos, n)
    for (uint64_t pos : {uint64_t(0), uint64_t(len / 2), uint64_t(len)}) {
      for (uint64_t n : {uint64_t(0), uint64_t(1), uint64_t(5), uint64_t(23), npos}) {
        std::string m = model; Str t; t.assign(&t.v, model.c_str());
        m.erase(pos, n);
        ms* r = erase(&t.v, pos, n);
        CHECK(r == &t.v && t.str() == m && t.is_long() == (m.size() > 22));
      }
    }
    // insert(pos, s, n): parte do buffer, inclusive o que cruza a fronteira curta/longa
    for (uint64_t pos : {uint64_t(0), uint64_t(len / 2), uint64_t(len)}) {
      for (uint64_t n : {uint64_t(0), uint64_t(1), uint64_t(5), uint64_t(30)}) {
        std::string m = model; Str t; t.assign(&t.v, model.c_str());
        m.insert(pos, ins, n);
        ms* r = insert_n(&t.v, pos, ins, n);
        CHECK(r == &t.v && t.str() == m && t.is_long() == (m.size() > 22));
      }
    }
    // replace(pos, n1, const char*)
    for (uint64_t pos : {uint64_t(0), uint64_t(len / 2), uint64_t(len)}) {
      for (uint64_t n1 : {uint64_t(0), uint64_t(3), uint64_t(40), npos}) {
        for (const char* rep : {"", "Q", "0123456789ABCDEFGHIJKLMNOPQ"}) {
          std::string m = model; Str t; t.assign(&t.v, model.c_str());
          m.replace(pos, n1, rep);
          ms* r = replace(&t.v, pos, n1, rep);
          CHECK(r == &t.v && t.str() == m && t.is_long() == (m.size() > 22));
        }
      }
    }
  }
  // origem apontando para dentro da propria string (replace(0, 2, data()+3); insert(1, data(), 4))
  { Str a; a.assign(&a.v, "0123456789012345678901234567"); std::string m = "0123456789012345678901234567";
    m.replace(0, 2, m.c_str() + 3);
    replace(&a.v, 0, 2, a.data() + 3);
    CHECK(a.str() == m);
    std::string m2 = m; m2.insert(1, m2.c_str(), 4);
    insert_n(&a.v, 1, a.data(), 4);
    CHECK(a.str() == m2); }
  return 0;
}

static int test_sort_longlongs() {
  typedef void (*f_sort)(long long*, long long*, void*);
  auto sort_ll = R<f_sort>("_ZNSt3__16__sortIRNS_6__lessIxxEEPxEEvT0_S5_T_");
  std::vector<long long> v;
  uint64_t x = 88172645463325252ull;
  for (int i = 0; i < 1000; i++) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; v.push_back(static_cast<long long>(x) >> (i % 40)); }
  v.push_back(5000000000LL); v.push_back(-5000000000LL); v.push_back(0);
  auto w = v;
  std::sort(w.begin(), w.end());
  char cmp = 0;
  sort_ll(v.data(), v.data() + v.size(), &cmp);
  CHECK(v == w);
  sort_ll(v.data(), v.data(), &cmp);   // vazio
  return 0;
}

// operator<<(unsigned long) Apple (m, 64 bits) e unsigned long long (y) apontam para o mesmo alvo do Windows (y)
static int test_ostream_shift_names() {
  typedef std::ostream& (*f_shift_u64)(std::ostream*, unsigned long long);
  typedef std::ostream& (*f_shift_i)(std::ostream*, int);
  typedef std::ostream& (*f_shift_j)(std::ostream*, unsigned);
  typedef std::ostream& (*f_shift_d)(std::ostream*, double);
  typedef std::ostream& (*f_shift_f)(std::ostream*, float);
#define OS "_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEElsE"
  void* pm = dw_resolve(OS "m");
  void* py = dw_resolve(OS "y");
  CHECK(pm && py && pm == py);
  std::ostringstream os;
  reinterpret_cast<f_shift_u64>(pm)(&os, 5000000000ull);
  os << ' ';
  reinterpret_cast<f_shift_u64>(py)(&os, 18446744073709551615ull);
  os << ' ';
  reinterpret_cast<f_shift_i>(R<void*>(OS "i"))(&os, -17);
  os << ' ';
  reinterpret_cast<f_shift_j>(R<void*>(OS "j"))(&os, 4000000000u);
  os << ' ';
  reinterpret_cast<f_shift_d>(R<void*>(OS "d"))(&os, 2.5);
  os << ' ';
  reinterpret_cast<f_shift_f>(R<void*>(OS "f"))(&os, 0.25f);
  CHECK(os.str() == "5000000000 18446744073709551615 -17 4000000000 2.5 0.25");
  CHECK(os.good());
#undef OS
  return 0;
}

static int test_import_coverage_all(const char* dir, const char* selfdef_path) {
  std::set<std::string> selfdef;
  {
    FILE* f = fopen(selfdef_path, "r"); CHECK(f);
    char line[1024];
    while (fgets(line, sizeof line, f)) {
      std::string s(line);
      while (!s.empty() && isspace((unsigned char)s.back())) s.pop_back();
      if (!s.empty()) selfdef.insert(s);
    }
    fclose(f);
  }
  int total = 0, missing = 0, skipped = 0;
  for (const char* lib : {"lighting", "popman", "pathfind", "bullet", "clipper", "jassimp", "fmodintegration"}) {
    std::string path = std::string(dir) + "/" + lib + ".txt";
    FILE* f = fopen(path.c_str(), "r"); CHECK(f);
    char line[1024];
    while (fgets(line, sizeof line, f)) {
      std::string s(line);
      while (!s.empty() && isspace((unsigned char)s.back())) s.pop_back();
      if (s.empty()) continue;
      total++;
      if (s.rfind("_ZGV", 0) == 0 || s.rfind("_ZZ", 0) == 0) {   // definidos pela propria dylib
        if (!selfdef.count(s)) { fprintf(stderr, "autodefinido nao verificado: %s (%s)\n", s.c_str(), lib); missing++; }
        else skipped++;
        continue;
      }
      if (!dw_resolve(s.c_str())) { fprintf(stderr, "missing import: %s (%s)\n", s.c_str(), lib); missing++; }
    }
    fclose(f);
  }
  fprintf(stderr, "imports: %d, autodefinidos: %d, missing: %d\n", total, skipped, missing);
  CHECK(total > 0 && missing == 0);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) { fputs("uso: test_cxx <teste> [args]\n", stderr); return 2; }
  std::string t = argv[1];
  int r;
  if (t == "string_layout") r = test_string_layout();
  else if (t == "string_ops") r = test_string_ops();
  else if (t == "runtime_error_from_string") r = test_runtime_error_from_string();
  else if (t == "random_device") r = test_random_device();
  else if (t == "guard_once") r = test_guard_once();
  else if (t == "sort_ints") r = test_sort_ints();
  else if (t == "push_back_many") r = test_push_back_many();
  else if (t == "system_clock_now") r = test_system_clock_now();
  else if (t == "guard_abort") r = test_guard_abort();
  else if (t == "cxx_sizes") r = test_cxx_sizes();
  else if (t == "new_delete_cross") r = test_new_delete_cross();
  else if (t == "string_extra_ops") r = test_string_extra_ops();
  else if (t == "sort_longlongs") r = test_sort_longlongs();
  else if (t == "ostream_shift_names") r = test_ostream_shift_names();
  else if (t == "import_coverage_all" && argc > 3) r = test_import_coverage_all(argv[2], argv[3]);
  else { fprintf(stderr, "teste desconhecido: %s\n", t.c_str()); return 2; }
  if (!r) printf("PASS %s\n", t.c_str());
  return r;
}
