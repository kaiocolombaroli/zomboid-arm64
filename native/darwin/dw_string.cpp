// Porta de zomdroid/app/src/main/cpp/macho_string.cpp para o Windows ARM64.
// Diferencas: memoria de dw_malloc/dw_free; erros de tamanho abortam com log (sem throw no M2);
// to_string(unsigned); sem random_device aqui (esta em dw_cxx.cpp).
#include "dw_string.h"
#include "darwin.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <stdexcept>

static_assert(sizeof(void*) == 8 && sizeof(size_t) == 8, "somente 64 bits");
static_assert(sizeof(macho_string) == 24 && alignof(macho_string) == 8, "layout da string Apple");

static constexpr uint64_t long_flag = UINT64_C(1) << 63;
static constexpr size_t short_capacity = 22;

extern "C" void dw_fatal(const char* what) {
    std::fprintf(stderr, "dw: erro fatal: %s\n", what);
    std::fflush(stderr);
    std::abort();
}

static bool is_long(const macho_string* s) { return (s->words[2] & long_flag) != 0; }
static char* data(macho_string* s) {
    return is_long(s) ? reinterpret_cast<char*>(static_cast<uintptr_t>(s->words[0]))
                      : reinterpret_cast<char*>(s);
}
extern "C" size_t macho_string_size(const macho_string* s) {
    return is_long(s) ? s->words[1] : reinterpret_cast<const unsigned char*>(s)[23];
}
extern "C" const char* macho_string_data(const macho_string* s) {
    return data(const_cast<macho_string*>(s));
}
static size_t checked_add(size_t a, size_t b) {
    constexpr size_t limit = (SIZE_MAX >> 1) - 32;
    if (a > limit || b > limit - a) dw_fatal("std::length_error: string Darwin grande demais");
    return a + b;
}
static macho_string allocate(size_t size) {
    checked_add(size, 0);
    macho_string result{};
    if (size <= short_capacity) {
        reinterpret_cast<unsigned char*>(&result)[23] = static_cast<unsigned char>(size);
    } else {
        // capacidade (com o NUL) arredondada a 16, como a libc++ da Apple
        size_t count = (size + 16) & ~size_t(15);
        void* mem = dw_malloc(count);
        if (!mem) dw_fatal("sem memoria para string");
        result.words[0] = reinterpret_cast<uintptr_t>(mem);
        result.words[1] = size;
        result.words[2] = long_flag | count;
    }
    data(&result)[size] = '\0';
    return result;
}
extern "C" void macho_string_destroy(macho_string* s) {
    if (is_long(s)) dw_free(data(s));
    std::memset(s, 0, sizeof(*s));
}
static void commit(macho_string* target, macho_string value) {
    macho_string_destroy(target);
    *target = value;
}
static size_t capacity_chars(const macho_string* s) {
    return is_long(s) ? static_cast<size_t>(s->words[2] & ~long_flag) - 1 : short_capacity;
}
static void set_size(macho_string* s, size_t size) {
    if (is_long(s)) s->words[1] = size;
    else reinterpret_cast<unsigned char*>(s)[23] = static_cast<unsigned char>(size);
}
// Aloca com capacidade de pelo menos `cap` caracteres (arredondada a 16 contando o NUL, como a libc++ da
// Apple); strings que cabem inline continuam inline.
static macho_string allocate_cap(size_t size, size_t cap) {
    if (cap < size) cap = size;
    checked_add(cap, 0);
    if (cap <= short_capacity) return allocate(size);
    size_t count = (cap + 16) & ~size_t(15);
    void* mem = dw_malloc(count);
    if (!mem) dw_fatal("sem memoria para string");
    macho_string result{};
    result.words[0] = reinterpret_cast<uintptr_t>(mem);
    result.words[1] = size;
    result.words[2] = long_flag | count;
    data(&result)[size] = '\0';
    return result;
}
// Cresce geometricamente (2x a capacidade, como __grow_by): N appends custam O(N) no total. Cabendo na
// capacidade atual, escreve no lugar. Em ambos os casos o texto de origem pode apontar para a propria string.
extern "C" macho_string* macho_string_append_n(macho_string* s, const char* text, size_t count) {
    const size_t old = macho_string_size(s);
    const size_t total = checked_add(old, count);
    if (total <= capacity_chars(s)) {
        char* d = data(s);
        if (count) std::memmove(d + old, text, count);
        d[total] = '\0';
        set_size(s, total);
        return s;
    }
    size_t cap = capacity_chars(s);
    cap = cap > (SIZE_MAX >> 2) ? total : cap * 2;
    macho_string result = allocate_cap(total, cap);
    if (old) std::memcpy(data(&result), data(s), old);
    if (count) std::memcpy(data(&result) + old, text, count);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_append(macho_string* s, const char* text) {
    return macho_string_append_n(s, text, std::strlen(text));
}
static macho_string* assign_n(macho_string* s, const char* text, size_t count) {
    macho_string result = allocate(count);
    if (count) std::memcpy(data(&result), text, count);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_assign(macho_string* s, const char* text) {
    return assign_n(s, text, std::strlen(text));
}
extern "C" macho_string* macho_string_copy(macho_string* s, const macho_string* source) {
    if (s != source) assign_n(s, macho_string_data(source), macho_string_size(source));
    return s;
}
extern "C" macho_string* macho_string_insert(macho_string* s, size_t position, const char* text) {
    const size_t old = macho_string_size(s);
    if (position > old) dw_fatal("std::out_of_range: insert em string Darwin");
    const size_t count = std::strlen(text);
    macho_string result = allocate(checked_add(old, count));
    if (position) std::memcpy(data(&result), data(s), position);
    if (count) std::memcpy(data(&result) + position, text, count);
    if (old != position) std::memcpy(data(&result) + position + count, data(s) + position, old - position);
    commit(s, result);
    return s;
}
// Troca [pos, pos+n1) por text[0, n2). text pode apontar para dentro de s (monta o resultado antes de commit).
static macho_string* splice(macho_string* s, size_t pos, size_t n1, const char* text, size_t n2) {
    const size_t old = macho_string_size(s);
    if (pos > old) dw_fatal("std::out_of_range: posicao fora da string Darwin");
    if (n1 > old - pos) n1 = old - pos;
    macho_string result = allocate(checked_add(old - n1, n2));
    if (pos) std::memcpy(data(&result), data(s), pos);
    if (n2) std::memcpy(data(&result) + pos, text, n2);
    if (old - pos - n1) std::memcpy(data(&result) + pos + n2, data(s) + pos + n1, old - pos - n1);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_erase(macho_string* s, size_t pos, size_t count) {
    return splice(s, pos, count, nullptr, 0);
}
extern "C" macho_string* macho_string_insert_n(macho_string* s, size_t pos, const char* text, size_t count) {
    return splice(s, pos, 0, text, count);
}
extern "C" macho_string* macho_string_replace(macho_string* s, size_t pos, size_t count, const char* text) {
    return splice(s, pos, count, text, std::strlen(text));
}
extern "C" size_t macho_string_find_char(const macho_string* s, char ch, size_t pos) {
    const size_t n = macho_string_size(s);
    if (pos >= n) return SIZE_MAX;
    const char* d = macho_string_data(s);
    const void* p = std::memchr(d + pos, static_cast<unsigned char>(ch), n - pos);
    return p ? static_cast<size_t>(static_cast<const char*>(p) - d) : SIZE_MAX;
}
extern "C" size_t macho_string_rfind_char(const macho_string* s, char ch, size_t pos) {
    const size_t n = macho_string_size(s);
    if (!n) return SIZE_MAX;
    const char* d = macho_string_data(s);
    for (size_t i = (pos < n ? pos : n - 1) + 1; i-- > 0;)
        if (d[i] == ch) return i;
    return SIZE_MAX;
}
extern "C" void macho_string_resize(macho_string* s, size_t count, char fill) {
    const size_t old = macho_string_size(s), copy = old < count ? old : count;
    macho_string result = allocate(count);
    if (copy) std::memcpy(data(&result), data(s), copy);
    if (count > copy) std::memset(data(&result) + copy, static_cast<unsigned char>(fill), count - copy);
    commit(s, result);
}
extern "C" void macho_string_push_back(macho_string* s, char ch) { macho_string_append_n(s, &ch, 1); }
extern "C" macho_string macho_string_concat(const char* prefix, const macho_string* suffix) {
    size_t a = std::strlen(prefix), b = macho_string_size(suffix);
    macho_string result = allocate(checked_add(a, b));
    if (a) std::memcpy(data(&result), prefix, a);
    if (b) std::memcpy(data(&result) + a, macho_string_data(suffix), b);
    return result;
}
extern "C" macho_string macho_string_from_int(int value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%d", value);
    macho_string result{};
    return *macho_string_assign(&result, buffer);
}
extern "C" macho_string macho_string_from_uint(unsigned value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%u", value);
    macho_string result{};
    return *macho_string_assign(&result, buffer);
}
extern "C" void dw_runtime_error_ctor_string(void* self, const macho_string* text) {
    // Construtor de const char* da libc++ estatica; a string Apple nunca entra na libc++.
    new (self) std::runtime_error(macho_string_data(text));
}
