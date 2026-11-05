// Comparacao de tipos para catch: percorre a hierarquia de uma type_info lancada pelo layout PUBLICO
// da ABI Itanium (__class_type_info, __si_class_type_info, __vmi_class_type_info), identificando cada
// classe pelo ponteiro de vtable da propria type_info. Nao chama __do_catch de ninguem: a libc++abi
// estatica so fornece os objetos _ZTV*/_ZTI* (os mesmos que as dylibs importam).
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "dw_internal.h"
#include "dw_string.h"

extern "C" {
extern char dw_vt_class[] asm("_ZTVN10__cxxabiv117__class_type_infoE");
extern char dw_vt_si_class[] asm("_ZTVN10__cxxabiv120__si_class_type_infoE");
extern char dw_vt_vmi_class[] asm("_ZTVN10__cxxabiv121__vmi_class_type_infoE");
extern char dw_vt_pointer[] asm("_ZTVN10__cxxabiv119__pointer_type_infoE");
}

namespace {

struct TypeInfo {
    const void* vptr;
    const char* name;
};
struct SiClassTypeInfo : TypeInfo {
    const TypeInfo* base;
};
struct BaseInfo {
    const TypeInfo* base;
    int64_t offset_flags;   // offset << 8 | flags
};
struct VmiClassTypeInfo : TypeInfo {
    unsigned flags;
    unsigned base_count;
    BaseInfo bases[1];
};
constexpr long kBaseVirtual = 0x1;
constexpr long kBasePublic = 0x2;
constexpr int kMaxDepth = 64;

// O ponteiro de vtable guardado na type_info aponta 16 bytes dentro do simbolo _ZTV (depois de
// offset-to-top e do typeinfo da vtable).
bool is_kind(const TypeInfo* t, const char* vt) { return t->vptr == static_cast<const void*>(vt + 16); }

// Em arm64 da Apple o bit 63 do ponteiro do nome marca "RTTI nao unico" (comparar por strcmp).
const char* name_of(const TypeInfo* t) {
    return reinterpret_cast<const char*>(reinterpret_cast<uintptr_t>(t->name) & ~(UINT64_C(1) << 63));
}

bool same_type(const TypeInfo* a, const TypeInfo* b) {
    if (a == b) return true;
    const char* na = name_of(a);
    const char* nb = name_of(b);
    if (na == nb) return true;
    if (na[0] == '*' || nb[0] == '*') return false;   // nome com '*': interno a uma unidade, so por ponteiro
    return std::strcmp(na, nb) == 0;
}

struct Found {
    void* ptr = nullptr;
    int distinct = 0;   // subobjetos distintos do tipo procurado
};

void search(const TypeInfo* t, void* obj, const TypeInfo* target, Found& out, int depth) {
    if (same_type(t, target)) {
        if (out.distinct == 0) {
            out.ptr = obj;
            out.distinct = 1;
        } else if (out.ptr != obj) {
            out.distinct++;   // ambiguo
        }
        return;
    }
    if (depth > kMaxDepth) dw_fatal("[eh] type_info hierarchy too deep");
    if (is_kind(t, dw_vt_si_class)) {
        search(static_cast<const SiClassTypeInfo*>(t)->base, obj, target, out, depth + 1);
    } else if (is_kind(t, dw_vt_vmi_class)) {
        const auto* v = static_cast<const VmiClassTypeInfo*>(t);
        for (unsigned i = 0; i < v->base_count; i++) {
            const BaseInfo& b = v->bases[i];
            if (!(b.offset_flags & kBasePublic)) continue;   // base nao publica nao e capturavel
            int64_t offset = b.offset_flags >> 8;
            char* sub = static_cast<char*>(obj);
            if (b.offset_flags & kBaseVirtual) {
                // offset e o deslocamento DENTRO da vtable do objeto ate o offset da base virtual
                const char* vtable = *static_cast<char* const*>(obj);
                offset = *reinterpret_cast<const int64_t*>(vtable + offset);
            }
            search(b.base, sub + offset, target, out, depth + 1);
        }
    }
    // __class_type_info (sem bases) e tipos fundamentais/enum/array/etc.: nada a percorrer
}

}  // namespace

extern "C" int dw_ti_can_catch(const void* catch_ti, const void* thrown_ti, void** adjusted_ptr) {
    if (!catch_ti) return 1;   // catch (...)
    const auto* c = static_cast<const TypeInfo*>(catch_ti);
    const auto* t = static_cast<const TypeInfo*>(thrown_ti);
    if (same_type(c, t)) return 1;
    if (is_kind(t, dw_vt_class) || is_kind(t, dw_vt_si_class) || is_kind(t, dw_vt_vmi_class)) {
        Found f;
        search(t, *adjusted_ptr, c, f, 0);
        if (f.distinct == 1) {
            *adjusted_ptr = f.ptr;
            return 1;
        }
        return 0;   // nao e base publica, ou a base e ambigua
    }
    if (is_kind(c, dw_vt_pointer) && is_kind(t, dw_vt_pointer)) {
        // Conversao entre ponteiros (qualificacao, derivada->base) so seria necessaria se a tabela de
        // tipos do LSDA trouxesse um catch de ponteiro com nome diferente do lancado: nao ha caso nas
        // 5 dylibs. Nao adivinha.
        std::fprintf(stderr, "[eh] unsupported pointer catch conversion: catch=%s thrown=%s\n", name_of(c), name_of(t));
        std::fflush(stderr);
        std::abort();
    }
    return 0;   // tipos fundamentais, enum, etc.: so casam por igualdade
}
