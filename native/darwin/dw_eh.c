/* Runtime de excecoes C++ para codigo das dylibs macOS arm64.
 *
 * As dylibs importam __cxa_*, _Unwind_Resume, __gxx_personality_v0 e _ZSt9terminatev. O libunwind e a
 * libc++abi da toolchain (SEH no Windows) nao entendem dylibs, entao aqui vive um runtime proprio de
 * duas fases (Itanium): fase 1 procura o handler sem efeito colateral, fase 2 sobe de novo e entra nas
 * landing pads (cleanups, depois o catch) com x0 = _Unwind_Exception e x1 = seletor.
 *
 * Contrato do desenrolador: dw_unwind.c (compact unwind). A personality e a tabela LSDA (call-site,
 * actions, types) sao lidas aqui. Os objetos _ZTI e _ZTV sao os reais da libc++abi estatica; a
 * comparacao de tipos e dw_ti_can_catch (dw_ti.cpp).
 *
 * Tudo que este runtime nao sabe tratar (modo DWARF, formato DW_EH_PE desconhecido, filtro de
 * especificacao de excecao, pc fora de dylib) registra "[eh] ..." em stderr e encerra o processo:
 * nunca finge que tratou. */
#include "darwin.h"
#include "dw_internal.h"
#include "dw_unwind.h"
#include "macho_loader.h"
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern char dw_ti_std_exception[] __asm__("_ZTISt9exception");
void dw_eh_install(dw_ctx_t* ctx, void* exc, int64_t selector, uintptr_t landing_pad) __attribute__((noreturn));

#define EH_MAX_FRAMES 100000
#define EXC_CLASS UINT64_C(0x4457454d41434830) /* "DWEMACH0" */

/* Cabecalho proprio, ANTES do objeto lancado. O _Unwind_Exception equivalente e o ultimo campo, logo antes
 * do objeto: x0 nas landing pads, argumento de __cxa_begin_catch e _Unwind_Resume. */
typedef struct dw_exc {
    void* type_info;                 /*   0 */
    void (*dtor)(void*);             /*   8 */
    int handler_count;               /*  16: <0 = relancada (como a libc++abi) */
    int handler_switch;              /*  20: seletor (indice na tabela de tipos) da fase 1 */
    struct dw_exc* next_caught;      /*  24: proxima da pilha de capturadas da thread */
    void* adjusted_ptr;              /*  32: ponteiro ajustado para a base capturada */
    uintptr_t handler_cfa;           /*  40: CFA do quadro que tem o handler */
    uint64_t ptr_refs;               /*  48: referencias de std::exception_ptr (dw_eh_ptr_*) */
    struct {
        uint64_t exception_class;    /*  56 */
        void* cleanup;               /*  64 */
        uint64_t private_1, private_2;
    } unwind;                        /*  56..88 */
    uint64_t pad;                    /*  88: o objeto comeca em 96 (multiplo de 16) */
} dw_exc_t;

_Static_assert(sizeof(dw_exc_t) % 16 == 0, "o objeto lancado precisa ficar alinhado a 16");

static __thread dw_exc_t* t_caught;  /* pilha de excecoes capturadas desta thread */

static void eh_vlog(const char* fmt, va_list ap) {
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    fflush(stderr);
}
static void eh_log(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    eh_vlog(fmt, ap);
    va_end(ap);
}
static void eh_die(const char* fmt, ...) __attribute__((noreturn, format(printf, 1, 2)));
static void eh_die(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    eh_vlog(fmt, ap);
    va_end(ap);
    abort();
}

static void* exc_object(dw_exc_t* e) { return (void*)(e + 1); }
static dw_exc_t* exc_from_object(void* obj) { return (dw_exc_t*)obj - 1; }
static dw_exc_t* exc_from_unwind(void* p) {
    dw_exc_t* e = (dw_exc_t*)((char*)p - offsetof(dw_exc_t, unwind));
    if (!p || e->unwind.exception_class != EXC_CLASS) eh_die("[eh] foreign or corrupt exception object %p", p);
    return e;
}

/* what() se o objeto derivar de std::exception (vtable: [offset-to-top, typeinfo, D1, D0, what]). */
static const char* exc_what(dw_exc_t* e) {
    void* adj = exc_object(e);
    if (!e->type_info || !dw_ti_can_catch(dw_ti_std_exception, e->type_info, &adj)) return NULL;
    void** vptr = *(void***)adj;
    return ((const char* (*)(void*))vptr[2])(adj);
}

/* ---- alocacao ----------------------------------------------------------------------------- */

void* dw_cxa_allocate_exception(uint64_t size) {
    dw_exc_t* e = dw_malloc(sizeof(dw_exc_t) + size);
    if (!e) eh_die("[eh] out of memory allocating an exception");
    memset(e, 0, sizeof(*e));
    return exc_object(e);
}

void dw_cxa_free_exception(void* obj) { dw_free(exc_from_object(obj)); }

/* ---- LSDA --------------------------------------------------------------------------------- */

#define PE_OMIT 0xff
#define PE_ABSPTR 0x00
#define PE_ULEB 0x01
#define PE_UDATA4 0x03
#define PE_SDATA4 0x0b
#define PE_PCREL 0x10
#define PE_INDIRECT 0x80

static uint64_t read_uleb(const uint8_t** p) {
    uint64_t r = 0;
    unsigned s = 0;
    for (;;) {
        uint8_t b = *(*p)++;
        r |= (uint64_t)(b & 0x7f) << s;
        s += 7;
        if (!(b & 0x80)) return r;
    }
}
static int64_t read_sleb(const uint8_t** p) {
    int64_t r = 0;
    unsigned s = 0;
    uint8_t b;
    do {
        b = *(*p)++;
        r |= (int64_t)(b & 0x7f) << s;
        s += 7;
    } while (b & 0x80);
    if (s < 64 && (b & 0x40)) r |= -((int64_t)1 << s);
    return r;
}

static size_t pe_size(uint8_t enc) {
    switch (enc & 0x0f) {
    case PE_ABSPTR: return 8;
    case PE_UDATA4:
    case PE_SDATA4: return 4;
    default: return 0;   /* uleb128 nao tem tamanho fixo */
    }
}

/* Le um valor DW_EH_PE_*. Formatos fora de {omit, absptr, uleb128, udata4, sdata4} x {pcrel, indirect}
 * encerram o processo. Valor bruto 0 continua 0 (typeinfo NULL = catch (...)). */
static uint64_t read_encoded(const uint8_t** pp, uint8_t enc) {
    if (enc == PE_OMIT) return 0;
    const unsigned fmt = enc & 0x0f, app = enc & 0x70;
    if (!(fmt == PE_ABSPTR || fmt == PE_ULEB || fmt == PE_UDATA4 || fmt == PE_SDATA4) || (app != 0 && app != PE_PCREL))
        eh_die("[eh] unsupported DW_EH_PE encoding 0x%02x in LSDA", enc);
    const uint8_t* at = *pp;
    uint64_t v;
    switch (enc & 0x0f) {
    case PE_ABSPTR: memcpy(&v, at, 8); *pp += 8; break;
    case PE_ULEB: v = read_uleb(pp); break;
    case PE_UDATA4: { uint32_t x; memcpy(&x, at, 4); v = x; *pp += 4; break; }
    case PE_SDATA4: { int32_t x; memcpy(&x, at, 4); v = (uint64_t)(int64_t)x; *pp += 4; break; }
    default: eh_die("[eh] unsupported DW_EH_PE format 0x%02x in LSDA", enc);
    }
    if (v == 0) return 0;
    if (enc & PE_PCREL) v += (uint64_t)(uintptr_t)at;
    if (enc & PE_INDIRECT) v = *(uint64_t*)(uintptr_t)v;
    return v;
}

typedef enum { SCAN_NONE = 0, SCAN_CLEANUP, SCAN_HANDLER } scan_kind_t;

/* O que a personality diz sobre o quadro de f em ip (= pc de retorno - 1).
 *  fase 1: so devolve SCAN_HANDLER (cleanups sao ignorados).
 *  fase 2: quadro com o handler -> SCAN_HANDLER (o catch); os outros -> SCAN_CLEANUP (landing pad de cleanup). */
static scan_kind_t scan_lsda(int phase, int handler_frame, const dw_func_t* f, uintptr_t ip, dw_exc_t* e,
                             uintptr_t* landing_pad, int64_t* selector, void** adjusted) {
    if (!f->lsda) return SCAN_NONE;
    const uint8_t* p = (const uint8_t*)f->lsda;
    const uint8_t lpstart_enc = *p++;
    uintptr_t lpstart = f->func_start;
    if (lpstart_enc != PE_OMIT) lpstart = (uintptr_t)read_encoded(&p, lpstart_enc);
    const uint8_t tt_enc = *p++;
    const uint8_t* tt_base = NULL;
    if (tt_enc != PE_OMIT) {
        uint64_t off = read_uleb(&p);
        tt_base = p + off;
    }
    const uint8_t cs_enc = *p++;
    const uint64_t cs_len = read_uleb(&p);
    const uint8_t* cs = p;
    const uint8_t* cs_end = p + cs_len;
    const uint8_t* actions = cs_end;

    uint64_t rel = (uint64_t)(ip - lpstart);
    while (cs < cs_end) {
        uint64_t start = read_encoded(&cs, cs_enc);
        uint64_t len = read_encoded(&cs, cs_enc);
        uint64_t lp = read_encoded(&cs, cs_enc);
        uint64_t action = read_uleb(&cs);
        if (rel < start) break;   /* tabela ordenada: ja passou */
        if (rel >= start + len) continue;
        if (!lp) return SCAN_NONE;   /* chamada coberta sem landing pad: segue subindo */
        const uintptr_t pad = lpstart + (uintptr_t)lp;
        if (!action) {   /* so cleanup */
            if (phase == 2 && !handler_frame) { *landing_pad = pad; *selector = 0; return SCAN_CLEANUP; }
            return SCAN_NONE;
        }
        const uint8_t* ap = actions + (action - 1);
        for (;;) {
            int64_t filter = read_sleb(&ap);
            const uint8_t* next_at = ap;
            int64_t next = read_sleb(&ap);
            if (filter > 0) {
                if (!tt_base) eh_die("[eh] LSDA catch clause without a type table");
                const size_t sz = pe_size(tt_enc);
                if (!sz) eh_die("[eh] unsupported type table encoding 0x%02x in LSDA", tt_enc);
                const uint8_t* ent = tt_base - (size_t)filter * sz;
                const uint8_t* q = ent;
                const void* catch_ti = (const void*)(uintptr_t)read_encoded(&q, tt_enc);
                void* adj = exc_object(e);
                if (dw_ti_can_catch(catch_ti, e->type_info, &adj)) {
                    if (phase == 1 || handler_frame) {
                        *landing_pad = pad;
                        *selector = filter;
                        *adjusted = adj;
                        return SCAN_HANDLER;
                    }
                }
            } else if (filter == 0) {
                if (phase == 2 && !handler_frame) { *landing_pad = pad; *selector = 0; return SCAN_CLEANUP; }
            } else {
                eh_die("[eh] exception-specification filter (%lld) in LSDA is not supported", (long long)filter);
            }
            if (!next) break;
            ap = next_at + next;
        }
        return SCAN_NONE;
    }
    /* sem registro para esta chamada: pela ABI, std::terminate */
    const macho_image_t* im = f->im;
    eh_die("[eh] no call-site record for pc=%p (%s+0x%llx): terminate", (void*)ip, im ? im->name : "?",
           (unsigned long long)(im ? ip - im->base : 0));
}

/* ---- as duas fases ------------------------------------------------------------------------ */

static void die_unwind(int err, uintptr_t pc, uintptr_t last_dylib_pc) __attribute__((noreturn));
static void die_unwind(int err, uintptr_t pc, uintptr_t last_dylib_pc) {
    const macho_image_t* im = macho_image_for_pc(pc - 1);
    eh_die("[eh] cannot unwind frame pc=%p lib=%s: %s (last dylib frame %p)", (void*)pc, im ? im->name : "?",
           dw_unwind_strerror(err), (void*)last_dylib_pc);
}

static void die_not_dylib(dw_exc_t* e, uintptr_t last_dylib_pc, uintptr_t pc) __attribute__((noreturn));
static void die_not_dylib(dw_exc_t* e, uintptr_t last_dylib_pc, uintptr_t pc) {
    const char* lib = "?";
    const char* fn = "?";
    if (last_dylib_pc) {
        const macho_image_t* im = macho_image_for_pc(last_dylib_pc);
        if (im) lib = im->name;
        const char* nm = macho_nearest_export(last_dylib_pc);
        if (nm) fn = nm;
    }
    const char* what = exc_what(e);
    eh_die("[eh] exception reached non-dylib frame: lib=%s fn=%s what=%s (pc=%p)", lib, fn, what ? what : "(not std::exception)",
           (void*)pc);
}

/* __gxx_personality_v0 so e aceita como valor de GOT: as chamadas sao feitas por aqui, nao pela ABI. */
void dw_gxx_personality_stub(void) { eh_die("[eh] __gxx_personality_v0 was called directly"); }

static void check_personality(const dw_func_t* f, uintptr_t pc, uintptr_t last) {
    if (f->has_personality && f->personality != (void*)dw_gxx_personality_stub) {
        eh_log("[eh] personality in %s is %p, not __gxx_personality_v0", f->im->name, f->personality);
        die_unwind(DW_UW_BAD_PERSONALITY, pc, last);
    }
}

static void phase1(dw_exc_t* e, const dw_ctx_t* start) {
    dw_ctx_t c = *start;
    uintptr_t last = 0;
    for (int depth = 0; depth < EH_MAX_FRAMES; depth++) {
        dw_func_t f;
        int r = dw_unwind_find(c.pc - 1, &f);
        if (r == DW_UW_NO_IMAGE) die_not_dylib(e, last, c.pc);
        if (r) die_unwind(r, c.pc, last);
        last = c.pc - 1;
        check_personality(&f, c.pc, last);
        uintptr_t cfa;
        if ((r = dw_unwind_cfa(&c, &f, &cfa)) != 0) die_unwind(r, c.pc, last);
        if (f.has_personality) {
            uintptr_t lp;
            int64_t sel;
            void* adj;
            if (scan_lsda(1, 0, &f, c.pc - 1, e, &lp, &sel, &adj) == SCAN_HANDLER) {
                e->handler_cfa = cfa;
                e->handler_switch = (int)sel;
                e->adjusted_ptr = adj;
                return;
            }
        }
        if ((r = dw_unwind_step_f(&c, &f)) != 0) die_unwind(r, c.pc, last);
    }
    eh_die("[eh] unwinding exceeded %d frames", EH_MAX_FRAMES);
}

static void phase2(dw_exc_t* e, const dw_ctx_t* start) __attribute__((noreturn));
static void phase2(dw_exc_t* e, const dw_ctx_t* start) {
    dw_ctx_t c = *start;
    uintptr_t last = 0;
    for (int depth = 0; depth < EH_MAX_FRAMES; depth++) {
        dw_func_t f;
        int r = dw_unwind_find(c.pc - 1, &f);
        if (r == DW_UW_NO_IMAGE) die_not_dylib(e, last, c.pc);
        if (r) die_unwind(r, c.pc, last);
        last = c.pc - 1;
        check_personality(&f, c.pc, last);
        uintptr_t cfa;
        if ((r = dw_unwind_cfa(&c, &f, &cfa)) != 0) die_unwind(r, c.pc, last);
        const int is_handler = (cfa == e->handler_cfa);
        if (f.has_personality) {
            uintptr_t lp;
            int64_t sel;
            void* adj = NULL;
            scan_kind_t k = scan_lsda(2, is_handler, &f, c.pc - 1, e, &lp, &sel, &adj);
            if (k == SCAN_HANDLER) {
                e->handler_switch = (int)sel;
                e->adjusted_ptr = adj;
                dw_eh_install(&c, &e->unwind, sel, lp);
            }
            if (k == SCAN_CLEANUP) dw_eh_install(&c, &e->unwind, 0, lp);
            if (is_handler) eh_die("[eh] phase 2 lost the handler found in phase 1 (frame %p)", (void*)cfa);
        } else if (is_handler) {
            eh_die("[eh] phase 2 lost the handler found in phase 1 (frame %p)", (void*)cfa);
        }
        if ((r = dw_unwind_step_f(&c, &f)) != 0) die_unwind(r, c.pc, last);
    }
    eh_die("[eh] unwinding exceeded %d frames", EH_MAX_FRAMES);
}

static void raise_exc(dw_exc_t* e, const dw_ctx_t* start) __attribute__((noreturn));
static void raise_exc(dw_exc_t* e, const dw_ctx_t* start) {
    phase1(e, start);
    phase2(e, start);
}

/* ---- pontos de entrada (chamados por dw_eh.S com o contexto ja capturado) ----------------- */

void dw_throw_impl(void* obj, void* type_info, void (*dtor)(void*), const dw_ctx_t* ctx) __attribute__((noreturn));
void dw_throw_impl(void* obj, void* type_info, void (*dtor)(void*), const dw_ctx_t* ctx) {
    dw_exc_t* e = exc_from_object(obj);
    e->type_info = type_info;
    e->dtor = dtor;
    e->handler_count = 0;
    e->next_caught = NULL;
    e->adjusted_ptr = obj;
    e->handler_cfa = 0;
    e->unwind.exception_class = EXC_CLASS;
    raise_exc(e, ctx);
}

void dw_rethrow_impl(const dw_ctx_t* ctx) __attribute__((noreturn));
void dw_rethrow_impl(const dw_ctx_t* ctx) {
    dw_exc_t* e = t_caught;
    if (!e) eh_die("[eh] __cxa_rethrow with no active exception: terminate");
    e->handler_count = -e->handler_count;   /* marca como relancada; end_catch nao a destroi */
    raise_exc(e, ctx);
}

void dw_resume_impl(void* unwind, const dw_ctx_t* ctx) __attribute__((noreturn));
void dw_resume_impl(void* unwind, const dw_ctx_t* ctx) { phase2(exc_from_unwind(unwind), ctx); }

/* ---- catch -------------------------------------------------------------------------------- */

void* dw_cxa_begin_catch(void* unwind) {
    dw_exc_t* e = exc_from_unwind(unwind);
    if (e->handler_count < 0) {   /* relancada e capturada de novo: ja esta na pilha */
        e->handler_count = -e->handler_count + 1;
    } else {
        e->handler_count++;
        if (t_caught != e) {
            e->next_caught = t_caught;
            t_caught = e;
        }
    }
    return e->adjusted_ptr;
}

void dw_cxa_end_catch(void) {
    dw_exc_t* e = t_caught;
    if (!e) return;
    if (e->handler_count < 0) {   /* saindo do catch por rethrow: so desempilha quando zera, sem destruir */
        if (++e->handler_count == 0) t_caught = e->next_caught;
    } else if (--e->handler_count == 0) {
        t_caught = e->next_caught;
        if (__atomic_load_n(&e->ptr_refs, __ATOMIC_ACQUIRE) == 0) {   /* exception_ptr ainda a segura: o ultimo release destroi */
            if (e->dtor) e->dtor(exc_object(e));
            dw_free(e);
        }
    }
}

/* std::exception_ptr: o ponteiro guarda o objeto lancado; cada copia conta uma referencia. */
void* dw_eh_current(void) {
    dw_exc_t* e = t_caught;
    if (!e) return NULL;
    __atomic_fetch_add(&e->ptr_refs, 1, __ATOMIC_ACQ_REL);
    return exc_object(e);
}
void dw_eh_ptr_addref(void* obj) {
    if (obj) __atomic_fetch_add(&exc_from_object(obj)->ptr_refs, 1, __ATOMIC_ACQ_REL);
}
void dw_eh_ptr_release(void* obj) {
    if (!obj) return;
    dw_exc_t* e = exc_from_object(obj);
    if (__atomic_fetch_sub(&e->ptr_refs, 1, __ATOMIC_ACQ_REL) == 1 && e->handler_count == 0) {
        if (e->dtor) e->dtor(exc_object(e));
        dw_free(e);
    }
}

void dw_terminate(void) {
    dw_exc_t* e = t_caught;
    const char* what = e ? exc_what(e) : NULL;
    if (what) eh_die("[eh] std::terminate called (active exception what=%s)", what);
    eh_die("[eh] std::terminate called");
}
