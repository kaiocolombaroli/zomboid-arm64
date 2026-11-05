#ifndef DW_UNWIND_H
#define DW_UNWIND_H
/* Desenrolador de pilha para codigo das dylibs macOS arm64: le o compact unwind (__unwind_info) das
 * imagens publicadas por macho_image_for_pc. Nao usa a libunwind da toolchain (SEH, nao entende dylibs). */
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Estado dos registradores do quadro corrente. O layout e usado por dw_eh.S: NAO reordenar. */
typedef struct dw_ctx {
    uint64_t x[10];   /*   0: x19..x28 */
    uint64_t fp;      /*  80 */
    uint64_t sp;      /*  88 */
    uint64_t lr;      /*  96 */
    uint64_t pc;      /* 104: endereco de retorno para dentro do quadro corrente */
    uint64_t d[8];    /* 112: d8..d15 (so os 64 bits baixos sao callee-saved) */
} dw_ctx_t;           /* 176 bytes */

enum {
    DW_UW_OK = 0,
    DW_UW_NO_IMAGE = -1,     /* pc fora de qualquer dylib carregada */
    DW_UW_NO_INFO = -2,      /* imagem sem entrada de unwind para o pc */
    DW_UW_BAD_INFO = -3,     /* __unwind_info malformado */
    DW_UW_DWARF = -4,        /* modo DWARF (nao suportado) */
    DW_UW_BAD_MODE = -5,     /* encoding desconhecido */
    DW_UW_BAD_PERSONALITY = -6,  /* personality que nao e __gxx_personality_v0 */
    DW_UW_NO_PROGRESS = -7   /* quadro frameless: lr perdido */
};

typedef struct {
    const struct macho_image* im;
    uintptr_t func_start;
    uint32_t enc;
    uintptr_t lsda;           /* 0 se a funcao nao tem LSDA */
    int has_personality;      /* encoding indexa uma personality */
    void* personality;        /* valor da entrada de GOT da personality (0 sem personality) */
} dw_func_t;

/* Procura a funcao que contem lookup_pc (= endereco de retorno - 1). DW_UW_OK ou erro (< 0). */
int dw_unwind_find(uintptr_t lookup_pc, dw_func_t* out);
/* CFA do quadro descrito por ctx/f. */
int dw_unwind_cfa(const dw_ctx_t* ctx, const dw_func_t* f, uintptr_t* cfa);
/* Sobe um quadro: restaura sp/fp/lr/x19-x28/d8-d15 do chamador e poe pc = endereco de retorno dele.
 * DW_UW_OK ou erro (< 0); em erro ctx nao muda. */
int dw_unwind_step(dw_ctx_t* ctx);
int dw_unwind_step_f(dw_ctx_t* ctx, const dw_func_t* f);
const char* dw_unwind_strerror(int err);

#ifdef __cplusplus
}
#endif
#endif
