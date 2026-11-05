/* Desenrolador de pilha: compact unwind (__unwind_info) da Apple, arm64. Formato e ordem dos
 * registradores: LLVM libunwind (CompactUnwinder_arm64), o leitor de referencia dessas codificacoes.
 *
 *  - modo frame: fp aponta para o par {fp, lr} salvo, CFA = fp + 16; os pares de x19..x28 e d8..d15
 *    ficam ABAIXO de fp, na ordem x19/x20, x21/x22, ..., d8/d9, ..., com o primeiro registrador do par
 *    no endereco mais alto (x19 em fp-8, x20 em fp-16, ...). Pares ausentes nao ocupam espaco.
 *  - modo frameless: CFA = sp + tamanho; nada e salvo, lr continua no registrador.
 *  - modo DWARF e qualquer outro: recusado (o chamador registra e encerra).
 * Todas as paginas de segundo nivel das dylibs do jogo sao comprimidas; as regulares tambem sao lidas. */
#include <stdint.h>
#include <string.h>
#include "dw_unwind.h"
#include "macho_loader.h"

#define UW_HAS_LSDA          0x40000000u
#define UW_PERS_MASK         0x30000000u
#define UW_PERS_SHIFT        28
#define UW_MODE_MASK         0x0F000000u
#define UW_MODE_FRAMELESS    0x02000000u
#define UW_MODE_DWARF        0x03000000u
#define UW_MODE_FRAME        0x04000000u
#define UW_FRAMELESS_SIZE    0x00FFF000u
#define UW_X_PAIRS           0x0000001Fu   /* bits 0..4: x19/20 .. x27/28 */
#define UW_D_PAIRS           0x00000F00u   /* bits 8..11: d8/9 .. d14/15 */
#define UW_KNOWN_FRAME_BITS  (UW_X_PAIRS | UW_D_PAIRS)

typedef struct { uint32_t version, common_off, common_cnt, pers_off, pers_cnt, index_off, index_cnt; } uw_hdr_t;
typedef struct { uint32_t func, page, lsda; } uw_idx_t;

static int uw_in(size_t total, uint32_t off, size_t need) { return (uint64_t)off + need <= total; }

const char* dw_unwind_strerror(int err) {
    switch (err) {
    case DW_UW_OK: return "ok";
    case DW_UW_NO_IMAGE: return "pc is not inside a loaded dylib";
    case DW_UW_NO_INFO: return "no compact unwind entry for pc";
    case DW_UW_BAD_INFO: return "malformed __unwind_info";
    case DW_UW_DWARF: return "function uses DWARF unwind mode (unsupported)";
    case DW_UW_BAD_MODE: return "unknown compact unwind encoding";
    case DW_UW_BAD_PERSONALITY: return "personality is not __gxx_personality_v0";
    case DW_UW_NO_PROGRESS: return "frame cannot be unwound (no saved return address / stack did not grow)";
    default: return "unknown unwind error";
    }
}

int dw_unwind_find(uintptr_t pc, dw_func_t* out) {
    const macho_image_t* im = macho_image_for_pc(pc);
    if (!im) return DW_UW_NO_IMAGE;
    if (!im->unwind_info) return DW_UW_NO_INFO;
    const uint8_t* u = (const uint8_t*)im->unwind_info;
    const size_t usz = im->unwind_info_size;
    uw_hdr_t h;
    if (usz < sizeof h) return DW_UW_BAD_INFO;
    memcpy(&h, u, sizeof h);
    if (h.version != 1 || h.index_cnt < 2 || !uw_in(usz, h.index_off, (size_t)h.index_cnt * sizeof(uw_idx_t)))
        return DW_UW_BAD_INFO;
    const uw_idx_t* idx = (const uw_idx_t*)(u + h.index_off);
    const uint32_t off = (uint32_t)(pc - im->base);
    if (off < idx[0].func || off >= idx[h.index_cnt - 1].func) return DW_UW_NO_INFO;
    uint32_t lo = 0, hi = h.index_cnt - 1;   /* ultimo i com idx[i].func <= off; a ultima entrada e a sentinela */
    while (hi - lo > 1) {
        uint32_t mid = lo + (hi - lo) / 2;
        if (idx[mid].func <= off) lo = mid; else hi = mid;
    }
    const uw_idx_t* e = &idx[lo];
    if (!e->page) return DW_UW_NO_INFO;
    if (!uw_in(usz, e->page, 4)) return DW_UW_BAD_INFO;
    uint32_t kind;
    memcpy(&kind, u + e->page, 4);

    uint32_t func = 0, enc = 0;
    if (kind == 3) {   /* comprimida: offsets de 24 bits relativos ao primeiro nivel; indice de 8 bits */
        uint16_t hdr16[4];
        if (!uw_in(usz, e->page, 12)) return DW_UW_BAD_INFO;
        memcpy(hdr16, u + e->page + 4, 8);
        const uint32_t epo = hdr16[0], ecnt = hdr16[1], eno = hdr16[2], enc_cnt = hdr16[3];
        if (!uw_in(usz, e->page + epo, (size_t)ecnt * 4) || !uw_in(usz, e->page + eno, (size_t)enc_cnt * 4) || !ecnt)
            return DW_UW_BAD_INFO;
        const uint32_t* ent = (const uint32_t*)(u + e->page + epo);
        uint32_t a = 0, b = ecnt;   /* ultimo j com func(j) <= off */
        while (b - a > 1) {
            uint32_t mid = a + (b - a) / 2;
            if (e->func + (ent[mid] & 0x00FFFFFFu) <= off) a = mid; else b = mid;
        }
        if (e->func + (ent[a] & 0x00FFFFFFu) > off) return DW_UW_NO_INFO;
        func = e->func + (ent[a] & 0x00FFFFFFu);
        const uint32_t k = ent[a] >> 24;
        if (k < h.common_cnt) {
            if (!uw_in(usz, h.common_off + 4 * k, 4)) return DW_UW_BAD_INFO;
            memcpy(&enc, u + h.common_off + 4 * k, 4);
        } else if (k - h.common_cnt < enc_cnt) {
            memcpy(&enc, u + e->page + eno + 4 * (k - h.common_cnt), 4);
        } else {
            return DW_UW_BAD_INFO;
        }
    } else if (kind == 2) {   /* regular: {funcao, encoding} */
        uint16_t hdr16[2];
        if (!uw_in(usz, e->page, 8)) return DW_UW_BAD_INFO;
        memcpy(hdr16, u + e->page + 4, 4);
        const uint32_t epo = hdr16[0], ecnt = hdr16[1];
        if (!uw_in(usz, e->page + epo, (size_t)ecnt * 8) || !ecnt) return DW_UW_BAD_INFO;
        const uint32_t* ent = (const uint32_t*)(u + e->page + epo);
        uint32_t a = 0, b = ecnt;
        while (b - a > 1) {
            uint32_t mid = a + (b - a) / 2;
            if (ent[2 * mid] <= off) a = mid; else b = mid;
        }
        if (ent[2 * a] > off) return DW_UW_NO_INFO;
        func = ent[2 * a];
        enc = ent[2 * a + 1];
    } else {
        return DW_UW_BAD_INFO;
    }

    out->im = im;
    out->func_start = im->base + func;
    out->enc = enc;
    out->lsda = 0;
    out->has_personality = 0;
    out->personality = NULL;

    const uint32_t pidx = (enc & UW_PERS_MASK) >> UW_PERS_SHIFT;
    if (pidx) {
        if (pidx > h.pers_cnt || !uw_in(usz, h.pers_off + 4 * (pidx - 1), 4)) return DW_UW_BAD_INFO;
        uint32_t slot;
        memcpy(&slot, u + h.pers_off + 4 * (pidx - 1), 4);
        if ((uint64_t)slot + 8 > im->size) return DW_UW_BAD_INFO;
        out->has_personality = 1;
        out->personality = *(void* const*)(im->base + slot);
    }
    if (enc & UW_HAS_LSDA) {   /* busca exata do offset da funcao no indice de LSDA deste primeiro nivel */
        const uint32_t l0 = e->lsda, l1 = idx[lo + 1].lsda;
        if (l1 < l0 || !uw_in(usz, l0, l1 - l0)) return DW_UW_BAD_INFO;
        uint32_t a = 0, b = (l1 - l0) / 8;
        const uint32_t* ls = (const uint32_t*)(u + l0);
        while (a < b) {
            uint32_t mid = a + (b - a) / 2;
            if (ls[2 * mid] < func) a = mid + 1;
            else if (ls[2 * mid] > func) b = mid;
            else {
                if ((uint64_t)ls[2 * mid + 1] >= im->size) return DW_UW_BAD_INFO;
                out->lsda = im->base + ls[2 * mid + 1];
                break;
            }
        }
        if (!out->lsda) return DW_UW_BAD_INFO;   /* a funcao declara LSDA e nao ha entrada */
    }
    return DW_UW_OK;
}

int dw_unwind_cfa(const dw_ctx_t* ctx, const dw_func_t* f, uintptr_t* cfa) {
    const uint32_t mode = f->enc & UW_MODE_MASK;
    if (mode == UW_MODE_FRAME) { *cfa = (uintptr_t)ctx->fp + 16; return DW_UW_OK; }
    if (mode == UW_MODE_FRAMELESS) {
        *cfa = (uintptr_t)ctx->sp + (uintptr_t)(((f->enc & UW_FRAMELESS_SIZE) >> 12) * 16);
        return DW_UW_OK;
    }
    return mode == UW_MODE_DWARF ? DW_UW_DWARF : DW_UW_BAD_MODE;
}

int dw_unwind_step_f(dw_ctx_t* ctx, const dw_func_t* f) {
    const uint32_t enc = f->enc, mode = enc & UW_MODE_MASK;
    if (mode == UW_MODE_FRAMELESS) {
        /* leaf: lr ainda esta no registrador, nada foi salvo. Se lr aponta para dentro deste mesmo quadro
         * (a funcao chamou algo e perdeu o lr), nao ha como subir. */
        dw_ctx_t n = *ctx;
        n.sp = ctx->sp + (((enc & UW_FRAMELESS_SIZE) >> 12) * 16);
        n.pc = ctx->lr;
        if (n.pc == ctx->pc || !n.pc) return DW_UW_NO_PROGRESS;
        *ctx = n;
        return DW_UW_OK;
    }
    if (mode == UW_MODE_DWARF) return DW_UW_DWARF;
    if (mode != UW_MODE_FRAME) return DW_UW_BAD_MODE;
    /* bits de registradores que este leitor nao conhece: recusa em vez de adivinhar */
    if (enc & ~(UW_MODE_MASK | UW_HAS_LSDA | UW_PERS_MASK | UW_KNOWN_FRAME_BITS)) return DW_UW_BAD_MODE;

    const uint64_t* fp = (const uint64_t*)(uintptr_t)ctx->fp;
    if (!fp || ((uintptr_t)fp & 7)) return DW_UW_NO_PROGRESS;
    dw_ctx_t n = *ctx;
    const uint64_t* loc = fp - 1;   /* primeiro registrador salvo: fp-8 */
    for (int i = 0; i < 5; i++) {
        if (!(enc & (1u << i))) continue;
        n.x[2 * i] = *loc--;
        n.x[2 * i + 1] = *loc--;
    }
    for (int i = 0; i < 4; i++) {
        if (!(enc & (0x100u << i))) continue;
        n.d[2 * i] = *loc--;
        n.d[2 * i + 1] = *loc--;
    }
    n.sp = (uint64_t)(uintptr_t)(fp + 2);   /* CFA */
    n.lr = fp[1];
    n.fp = fp[0];
    n.pc = n.lr;
    if (!n.pc || n.sp <= ctx->sp) return DW_UW_NO_PROGRESS;   /* a pilha tem que crescer */
    *ctx = n;
    return DW_UW_OK;
}

int dw_unwind_step(dw_ctx_t* ctx) {
    dw_func_t f;
    int r = dw_unwind_find(ctx->pc - 1, &f);
    if (r) return r;
    return dw_unwind_step_f(ctx, &f);
}
