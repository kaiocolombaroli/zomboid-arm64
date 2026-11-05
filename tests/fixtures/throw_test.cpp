// Fixture Mach-O (arm64-apple-macos11, sem headers) para o runtime de excecoes (M2 Tarefa 5).
// Codigo C++ com throw/catch/cleanup REAL, compilado pelo clang para Apple: o compact unwind e as
// tabelas LSDA sao as que o ld64/lld gera para as dylibs do jogo. Nada de tipos fundamentais lancados
// (o runtime nao fornece _ZTIi: as dylibs reais so lancam classes).
#define EXPORT extern "C" __attribute__((visibility("default"), used))
#define NOINLINE __attribute__((noinline))

extern "C" {
void* __cxa_allocate_exception(unsigned long);
__attribute__((noreturn)) void __cxa_throw(void*, void*, void (*)(void*));
}

namespace std {
class exception {
 public:
  virtual ~exception() noexcept;               // funcao-chave: a type_info e a vtable vem da libc++ (real)
  virtual const char* what() const noexcept;
};
}  // namespace std

// std::runtime_error pela ABI da dylib: construtor de std::string da Apple (24 bytes, curta inline).
struct AppleStr { char b[24]; };
extern "C" void rt_ctor(void* self, const AppleStr* s)
    asm("__ZNSt13runtime_errorC2ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE");
extern "C" void rt_dtor(void* self) asm("__ZNSt13runtime_errorD1Ev");
extern "C" char rt_ti[] asm("__ZTISt13runtime_error");

__attribute__((noreturn)) static void throw_runtime_error(const char* msg) {
  AppleStr s;
  __builtin_memset(&s, 0, sizeof s);
  unsigned long n = __builtin_strlen(msg);
  if (n > 22) n = 22;
  __builtin_memcpy(s.b, msg, n);
  s.b[23] = (char)n;
  void* e = __cxa_allocate_exception(16);
  rt_ctor(e, &s);
  __cxa_throw(e, rt_ti, rt_dtor);
}

// ---- contabilidade ----
static int g_log[16];
static int g_log_n;
static int g_dtors;
EXPORT void eh_reset() { g_log_n = 0; g_dtors = 0; }
EXPORT int eh_log_n() { return g_log_n; }
EXPORT int eh_log_get(int i) { return g_log[i]; }
EXPORT int eh_dtors() { return g_dtors; }
static void log_push(int v) { if (g_log_n < 16) g_log[g_log_n++] = v; }

struct IntExc { int v; };
struct Counted {
  int id;
  ~Counted() { g_dtors++; log_push(-id); }
};
struct Guard {
  int id;
  ~Guard() { log_push(id); }
};

static volatile int g_sink;

// ---- throw / catch ----
EXPORT int eh_same_frame(int x) {
  try {
    if (x > 0) throw IntExc{x};
    return -1;
  } catch (IntExc& e) {
    return e.v + 1000;
  }
}

static NOINLINE void thrower(int depth, int x) {
  if (depth == 0) throw IntExc{x};
  thrower(depth - 1, x);
  g_sink = depth;   // impede a chamada em cauda
}
EXPORT int eh_across(int depth, int x) {
  try {
    thrower(depth, x);
  } catch (IntExc& e) {
    return e.v;
  }
  return -1;
}

// ---- cleanup ----
static NOINLINE void thrower_guarded(int depth, int x) {
  Guard g{10 + depth};
  if (depth == 0) throw IntExc{x};
  thrower_guarded(depth - 1, x);
  g_sink = depth;
}
EXPORT int eh_cleanup_runs(int x) {
  g_log_n = 0;
  try {
    Guard a{1};
    thrower_guarded(2, x);
  } catch (IntExc& e) {
    log_push(100 + e.v);
  }
  return g_log_n;   // esperado: 10 11 12 1 (cleanups, do mais fundo ao mais raso) e 100+x (o catch)
}

// ---- hierarquia de classes ----
struct Base { virtual ~Base() {} int tag = 11; };
struct Derived : Base { int extra = 12; };
struct M1 { virtual ~M1() {} int a = 21; };
struct M2 { virtual ~M2() {} int b = 22; };
struct Multi : M1, M2 { int c = 23; };
struct VBase { virtual ~VBase() {} int v = 31; };
struct VDer : virtual VBase { int d = 32; };
struct Priv : private Base { int p = 41; };
struct A1 : Base {};
struct A2 : Base {};
struct Amb : A1, A2 {};

EXPORT int eh_hier(int kind) {
  try {
    switch (kind) {
      case 0: throw Derived();
      case 1: throw Multi();
      case 2: throw VDer();
      case 4: throw Priv();
      case 5: throw Amb();
      default: throw IntExc{kind};
    }
  } catch (Priv& p) {
    return p.p;   // Priv: a base Base e privada
  } catch (Base& b) {
    return b.tag;   // 11 (Derived)
  } catch (M2& m) {
    return m.b;     // 22 (Multi: base nao primaria, ponteiro ajustado)
  } catch (VBase& v) {
    return v.v;     // 31 (VDer: base virtual)
  } catch (Amb&) {
    return 55;      // Base e ambigua em Amb
  } catch (...) {
    return -2;
  }
}
EXPORT int eh_multi_first() {
  try {
    throw Multi();
  } catch (M1& m) {
    return m.a;   // 21: base primaria, mesmo endereco
  }
}
// Apenas o caso 5 precisa que Base seja ambigua: o catch (Base&) acima nao pode casar. Como a ordem
// dos catch e a mesma, testamos a ambiguidade isoladamente aqui.
EXPORT int eh_ambiguous() {
  try {
    throw Amb();
  } catch (Base&) {
    return -1;
  } catch (Amb&) {
    return 55;
  }
}
EXPORT int eh_private_base() {
  try {
    throw Priv();
  } catch (Base&) {
    return -1;
  } catch (Priv& p) {
    return p.p;
  }
}

// ---- std::exception ----
EXPORT int eh_catch_base(char* out) {
  try {
    throw_runtime_error("runtime_error message");
  } catch (std::exception& e) {
    const char* w = e.what();
    unsigned long i = 0;
    while (w[i]) { out[i] = w[i]; i++; }
    out[i] = 0;
    return 1;
  } catch (...) {
    return 2;
  }
}

// ---- rethrow / aninhadas ----
static NOINLINE void rethrower(int x) {
  try {
    thrower(1, x);
  } catch (IntExc& e) {
    log_push(e.v);
    throw;
  }
}
EXPORT int eh_rethrow(int x) {
  g_log_n = 0;
  try {
    rethrower(x);
  } catch (IntExc& e) {
    return e.v * 10 + g_log_n;
  }
  return -1;
}
EXPORT int eh_rethrow_counted() {
  g_dtors = 0;
  try {
    try {
      throw Counted{5};
    } catch (Counted&) {
      throw;
    }
  } catch (Counted& c) {
    return c.id * 100 + g_dtors;   // o objeto ainda vive dentro do catch externo: 5 * 100 + 0
  }
  return -1;
}
EXPORT int eh_nested_in_catch() {
  try {
    try {
      throw IntExc{1};
    } catch (IntExc& a) {
      try {
        throw IntExc{2};
      } catch (IntExc& b) {
        return a.v * 10 + b.v;   // 12: as duas vivem ao mesmo tempo
      }
    }
  } catch (...) {
    return -1;
  }
  return -2;
}
EXPORT int eh_throw_from_catch() {
  g_dtors = 0;
  try {
    try {
      throw Counted{1};
    } catch (Counted& a) {
      throw Counted{a.id + 1};   // lanca dentro do catch: a primeira ainda e destruida ao sair
    }
  } catch (Counted& b) {
    return b.id * 100 + g_dtors;   // 2 * 100 + 1
  }
  return -1;
}
EXPORT int eh_catch_all(int kind) {
  g_dtors = 0;
  try {
    if (kind == 0) throw IntExc{1};
    if (kind == 1) throw_runtime_error("all");
    throw Counted{3};
  } catch (...) {
    return 7;
  }
}
EXPORT int eh_dtor_once() {
  g_dtors = 0;
  try {
    throw Counted{9};
  } catch (Counted&) {
  }
  return g_dtors;   // destruido exatamente uma vez, ao sair do catch
}

// ---- nao capturadas ----
EXPORT void eh_uncaught(const char* msg) { throw_runtime_error(msg); }
EXPORT void eh_uncaught_custom() { throw IntExc{1}; }
EXPORT void eh_call_through_host(void (*cb)()) {
  try {
    cb();   // o callback do host chama eh_uncaught_custom: um quadro fora de dylib no meio
  } catch (...) {
  }
}

// type_info das classes de teste (para testar dw_ti_can_catch isoladamente)
extern "C" char ti_Base[] asm("__ZTI4Base");
extern "C" char ti_Derived[] asm("__ZTI7Derived");
extern "C" char ti_M1[] asm("__ZTI2M1");
extern "C" char ti_M2[] asm("__ZTI2M2");
extern "C" char ti_Multi[] asm("__ZTI5Multi");
extern "C" char ti_Priv[] asm("__ZTI4Priv");
extern "C" char ti_Amb[] asm("__ZTI3Amb");
extern "C" char ti_VBase[] asm("__ZTI5VBase");
EXPORT void* eh_ti(int k) {
  switch (k) {
    case 0: return ti_Base;
    case 1: return ti_Derived;
    case 2: return ti_M1;
    case 3: return ti_M2;
    case 4: return ti_Multi;
    case 5: return ti_Priv;
    case 6: return ti_Amb;
    default: return ti_VBase;
  }
}

// ---- registradores callee-saved ----
// Salva x19-x28 e d8-d15 (18 valores de 8 bytes) em buf. Folha, sem prologo.
extern "C" __attribute__((naked, noinline, visibility("default"), used)) void eh_save_regs(long* buf) {
  asm volatile(
      "stp x19, x20, [x0, #0]\n"
      "stp x21, x22, [x0, #16]\n"
      "stp x23, x24, [x0, #32]\n"
      "stp x25, x26, [x0, #48]\n"
      "stp x27, x28, [x0, #64]\n"
      "stp d8, d9, [x0, #80]\n"
      "stp d10, d11, [x0, #96]\n"
      "stp d12, d13, [x0, #112]\n"
      "stp d14, d15, [x0, #128]\n"
      "ret\n");
}

#define CLOBBER_ALL "x19", "x20", "x21", "x22", "x23", "x24", "x25", "x26", "x27", "x28", \
                    "d8", "d9", "d10", "d11", "d12", "d13", "d14", "d15"

// Quadros intermediarios que sujam subconjuntos diferentes dos registradores: cada um tem um compact
// unwind com pares salvos diferentes (a prova de que o desenrolador restaura de cada quadro certo).
static NOINLINE void chain_c(int depth) {
  asm volatile("mov x19, #0x7777\n mov x20, #0x7778\n fmov d8, #3.0\n fmov d9, #3.5" ::: "x19", "x20", "d8", "d9");
  if (depth == 0) throw IntExc{depth};
  chain_c(depth - 1);
  g_sink = depth;
}
static NOINLINE void chain_b(int depth) {
  asm volatile("mov x23, #0x6666\n mov x24, #0x6667\n mov x27, #0x6668\n mov x28, #0x6669\n fmov d14, #2.0\n fmov d15, #2.5"
               ::: "x23", "x24", "x27", "x28", "d14", "d15");
  chain_c(depth);
  g_sink = depth;
}
static NOINLINE void chain_a(int depth) {
  asm volatile("mov x21, #0x5555\n mov x22, #0x5556\n mov x25, #0x5557\n mov x26, #0x5558\n fmov d10, #1.5\n fmov d11, #1.25\n fmov d12, #1.75\n fmov d13, #1.125"
               ::: "x21", "x22", "x25", "x26", "d10", "d11", "d12", "d13");
  chain_b(depth);
  g_sink = depth;
}
// Poe valores conhecidos em TODOS os callee-saved (o compilador salva todos no prologo) e lanca.
EXPORT NOINLINE void eh_trial(int depth) {
  asm volatile(
      "mov x19, #0x1111\n mov x20, #0x1112\n mov x21, #0x1113\n mov x22, #0x1114\n mov x23, #0x1115\n"
      "mov x24, #0x1116\n mov x25, #0x1117\n mov x26, #0x1118\n mov x27, #0x1119\n mov x28, #0x111a\n"
      "fmov d8, #4.0\n fmov d9, #4.25\n fmov d10, #4.5\n fmov d11, #4.75\n"
      "fmov d12, #5.0\n fmov d13, #5.25\n fmov d14, #5.5\n fmov d15, #5.75\n" ::: CLOBBER_ALL);
  chain_a(depth);
  g_sink = depth;
}
// Captura os callee-saved antes da chamada e dentro do catch; eh_trial os suja e lanca por cima.
EXPORT NOINLINE void eh_regs_probe(long* before, long* after, int depth) {
  eh_save_regs(before);
  try {
    eh_trial(depth);
  } catch (IntExc&) {
    eh_save_regs(after);
  }
}

// ---- std::exception_ptr (M3 Tarefa 2): current_exception + operator= + destrutor, ABI da dylib ----
namespace std {
class exception_ptr {
 public:
  void* p;
  exception_ptr() noexcept : p(nullptr) {}
  exception_ptr(const exception_ptr&) = delete;
  ~exception_ptr() noexcept;                                      // _ZNSt13exception_ptrD1Ev
  exception_ptr& operator=(const exception_ptr&) noexcept;        // _ZNSt13exception_ptraSERKS_
};
exception_ptr current_exception() noexcept;                       // _ZSt17current_exceptionv (retorno por ponteiro oculto)
}  // namespace std

struct DExc {
  int v;
  ~DExc() { g_dtors++; }
};
// A excecao capturada fica viva enquanto houver exception_ptr, mesmo depois do fim do catch.
static NOINLINE void eptr_scope(int* dtors_in_catch, int* dtors_after_catch, int* v) {
  std::exception_ptr keep;
  try {
    throw DExc{77};
  } catch (DExc&) {
    std::exception_ptr cur = std::current_exception();            // +1
    keep = cur;                                                   // +1 (operator=)
    *dtors_in_catch = g_dtors;
  }                                                               // cur destruida (-1), end_catch: ainda ha 1 referencia
  *dtors_after_catch = g_dtors;
  *v = static_cast<DExc*>(keep.p)->v;
}                                                                 // keep destruida: ultima referencia, DExc destruida
EXPORT int eh_eptr(int* dtors_in_catch, int* dtors_after_catch, int* v) {
  g_dtors = 0;
  eptr_scope(dtors_in_catch, dtors_after_catch, v);
  return g_dtors;
}
// Fora de qualquer catch, current_exception() e nulo; e reatribuir a mesma referencia nao a perde.
EXPORT int eh_eptr_null() {
  std::exception_ptr e = std::current_exception();
  return e.p == nullptr;
}
EXPORT int eh_eptr_self_assign() {
  g_dtors = 0;
  int r = 0;
  try {
    throw DExc{5};
  } catch (DExc&) {
    std::exception_ptr a = std::current_exception();
    std::exception_ptr b;
    b = a;
    b = a;                                                        // reatribuir o mesmo alvo: conta uma so vez
    r = (b.p == a.p) ? 1 : 0;
  }
  return r * 10 + g_dtors;                                        // objeto destruido uma unica vez (1)
}
