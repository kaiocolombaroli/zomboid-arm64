// Superficie C++ com semantica/layout Apple: std::string (dw_string.cpp), random_device,
// __sort, system_clock::now, operator new(nothrow), runtime_error(const string&).
// Nada aqui devolve ou recebe std::string da libc++ do llvm-mingw.
#include "dw_internal.h"
#include "dw_string.h"
#include "darwin.h"
#include <algorithm>
#include <new>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <windows.h>
#include <bcrypt.h>

// random_device Apple: arc4random, sem fd; o objeto Apple tem 1 byte, nada e escrito nele.
static void dw_random_construct(void*, const macho_string* token) {
    if (macho_string_size(token) != 12 || std::memcmp(macho_string_data(token), "/dev/urandom", 12) != 0)
        dw_fatal("std::random_device: token desconhecido");
}
static void dw_random_destroy(void*) {}
static unsigned dw_random_next(void*) {
    unsigned r = 0;
    if (BCryptGenRandom(nullptr, reinterpret_cast<PUCHAR>(&r), sizeof r, BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0)
        dw_fatal("BCryptGenRandom falhou");
    return r;
}

// std::__1::__sort<__less<int,int>&, int*>(first, last, comp): ordem crescente.
static void dw_sort_ints(int* first, int* last, void*) { std::sort(first, last); }

static void dw_sort_longlongs(long long* first, long long* last, void*) { std::sort(first, last); }

// std::exception_ptr da Apple: um unico ponteiro (o objeto lancado, de dw_eh.c). Tipo nao trivial, entao
// current_exception() devolve por ponteiro oculto (x8), como a dylib espera; o compilador gera o mesmo.
namespace {
struct dw_eptr {
    void* p;
    explicit dw_eptr(void* q) : p(q) {}
    dw_eptr(const dw_eptr& o) : p(o.p) { dw_eh_ptr_addref(p); }
    ~dw_eptr() { dw_eh_ptr_release(p); }
};
}
static dw_eptr dw_current_exception() { return dw_eptr(dw_eh_current()); }
static void dw_exception_ptr_dtor(void** self) { void* p = *self; *self = nullptr; dw_eh_ptr_release(p); }
static void** dw_exception_ptr_assign(void** self, void* const* other) {
    void* n = *other;
    dw_eh_ptr_addref(n);
    void* old = *self;
    *self = n;
    dw_eh_ptr_release(old);
    return self;
}

// system_clock::now() da Apple: microssegundos desde 1970 (time_point de rep int64).
static int64_t dw_system_clock_now() {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    uint64_t t = (static_cast<uint64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
    return static_cast<int64_t>((t - UINT64_C(116444736000000000)) / 10);
}

// operator new(size_t, const nothrow_t&)
static void* dw_new_nothrow(uint64_t n, const void*) { return dw_malloc(n ? n : 1); }

// operator new/delete globais sobre dw_malloc/dw_free: memoria que a libc++ estatica desta DLL aloca
// (std::runtime_error, etc.) pode ser liberada pela dylib (via _ZdlPv...) e vice-versa. Define TODAS as
// variantes, para nenhum membro de stdlib_new_delete da libc++abi ser puxado do arquivo estatico.
static void* dw_new_impl(std::size_t n, std::size_t align, bool may_throw) {
    if (!n) n = 1;
    for (;;) {
        void* p = align > 16 ? dw_aligned_alloc(align, n) : dw_malloc(n);
        if (p) return p;
        std::new_handler h = std::get_new_handler();
        if (!h) {
            if (may_throw) throw std::bad_alloc();
            return nullptr;
        }
        h();
    }
}
void* operator new(std::size_t n) { return dw_new_impl(n, 0, true); }
void* operator new[](std::size_t n) { return dw_new_impl(n, 0, true); }
void* operator new(std::size_t n, const std::nothrow_t&) noexcept { return dw_new_impl(n, 0, false); }
void* operator new[](std::size_t n, const std::nothrow_t&) noexcept { return dw_new_impl(n, 0, false); }
void* operator new(std::size_t n, std::align_val_t a) { return dw_new_impl(n, static_cast<std::size_t>(a), true); }
void* operator new[](std::size_t n, std::align_val_t a) { return dw_new_impl(n, static_cast<std::size_t>(a), true); }
void* operator new(std::size_t n, std::align_val_t a, const std::nothrow_t&) noexcept {
    return dw_new_impl(n, static_cast<std::size_t>(a), false);
}
void* operator new[](std::size_t n, std::align_val_t a, const std::nothrow_t&) noexcept {
    return dw_new_impl(n, static_cast<std::size_t>(a), false);
}
void operator delete(void* p) noexcept { dw_free(p); }
void operator delete[](void* p) noexcept { dw_free(p); }
void operator delete(void* p, const std::nothrow_t&) noexcept { dw_free(p); }
void operator delete[](void* p, const std::nothrow_t&) noexcept { dw_free(p); }
void operator delete(void* p, std::size_t) noexcept { dw_free(p); }
void operator delete[](void* p, std::size_t) noexcept { dw_free(p); }
void operator delete(void* p, std::align_val_t) noexcept { dw_free(p); }
void operator delete[](void* p, std::align_val_t) noexcept { dw_free(p); }
void operator delete(void* p, std::align_val_t, const std::nothrow_t&) noexcept { dw_free(p); }
void operator delete[](void* p, std::align_val_t, const std::nothrow_t&) noexcept { dw_free(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { dw_free(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { dw_free(p); }

extern "C" {
#define S "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
const dw_entry dw_cxx_own_entries[] = {
  {S "6appendEPKc", (void*)macho_string_append}, {S "6appendEPKcm", (void*)macho_string_append_n},
  {S "6assignEPKc", (void*)macho_string_assign}, {S "6insertEmPKc", (void*)macho_string_insert},
  {S "6resizeEmc", (void*)macho_string_resize}, {S "9push_backEc", (void*)macho_string_push_back},
  {S "aSERKS5_", (void*)macho_string_copy},
  {S "D1Ev", (void*)macho_string_destroy}, {S "D2Ev", (void*)macho_string_destroy},
  {"_ZNSt3__19to_stringEi", (void*)macho_string_from_int},
  {"_ZNSt3__19to_stringEj", (void*)macho_string_from_uint},
  {"_ZNSt3__1plIcNS_11char_traitsIcEENS_9allocatorIcEEEENS_12basic_stringIT_T0_T1_EEPKS6_RKS9_",
   (void*)macho_string_concat},
  {"_ZNSt3__113random_deviceC1ERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE",
   (void*)dw_random_construct},
  {"_ZNSt3__113random_deviceD1Ev", (void*)dw_random_destroy},
  {"_ZNSt3__113random_deviceclEv", (void*)dw_random_next},
  {"_ZNSt13runtime_errorC2ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE",
   (void*)dw_runtime_error_ctor_string},
  {"_ZNSt3__16__sortIRNS_6__lessIiiEEPiEEvT0_S5_T_", (void*)dw_sort_ints},
  {"_ZNSt3__16__sortIRNS_6__lessIxxEEPxEEvT0_S5_T_", (void*)dw_sort_longlongs},
  {"_ZSt17current_exceptionv", (void*)dw_current_exception},
  {"_ZNSt13exception_ptrD1Ev", (void*)dw_exception_ptr_dtor},
  {"_ZNSt13exception_ptraSERKS_", (void*)dw_exception_ptr_assign},
  {"_ZNKSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE4findEcm", (void*)macho_string_find_char},
  {"_ZNKSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE5rfindEcm", (void*)macho_string_rfind_char},
  {S "5eraseEmm", (void*)macho_string_erase},
  {S "6insertEmPKcm", (void*)macho_string_insert_n},
  {S "7replaceEmmPKc", (void*)macho_string_replace},
  {"_ZNSt3__16chrono12system_clock3nowEv", (void*)dw_system_clock_now},
  {"_ZnwmRKSt9nothrow_t", (void*)dw_new_nothrow},
  {nullptr, nullptr}
};
#undef S
}
