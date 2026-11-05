/* Entradas do runtime de excecoes que precisam ver os registradores do CHAMADOR (dylib):
 * __cxa_throw, __cxa_rethrow e _Unwind_Resume capturam x19-x28, fp, sp, lr e d8-d15 no momento da chamada
 * (nada foi alterado ainda: estas funcoes sao so uma etapa de salto) e entregam um dw_ctx_t ao C.
 * dw_eh_install faz o caminho inverso: restaura os registradores e salta para a landing pad.
 * Layout de dw_ctx_t (dw_unwind.h): x19..x28 +0, fp +80, sp +88, lr +96, pc +104, d8..d15 +112 (176 bytes).
 * Nao mexe em x18. */
    .text
    .p2align 2

/* Captura em [sp, #0..175] (sp ja abaixado em 192). Usa x9 como rascunho; preserva x0-x7. */
    .macro DW_CAPTURE
    sub     sp, sp, #192
    stp     x19, x20, [sp, #0]
    stp     x21, x22, [sp, #16]
    stp     x23, x24, [sp, #32]
    stp     x25, x26, [sp, #48]
    stp     x27, x28, [sp, #64]
    str     x29, [sp, #80]
    add     x9, sp, #192
    str     x9, [sp, #88]
    str     x30, [sp, #96]
    str     x30, [sp, #104]
    stp     d8, d9, [sp, #112]
    stp     d10, d11, [sp, #128]
    stp     d12, d13, [sp, #144]
    stp     d14, d15, [sp, #160]
    .endm

/* void __cxa_throw(void* obj, void* type_info, void (*dtor)(void*)) */
    .globl dw_cxa_throw
dw_cxa_throw:
    DW_CAPTURE
    mov     x3, sp
    bl      dw_throw_impl
    brk     #1

/* void __cxa_rethrow(void) */
    .globl dw_cxa_rethrow
dw_cxa_rethrow:
    DW_CAPTURE
    mov     x0, sp
    bl      dw_rethrow_impl
    brk     #1

/* void _Unwind_Resume(_Unwind_Exception* exc) */
    .globl dw_unwind_resume
dw_unwind_resume:
    DW_CAPTURE
    mov     x1, sp
    bl      dw_resume_impl
    brk     #1

/* void dw_eh_install(dw_ctx_t* ctx, void* exc, int64_t selector, uintptr_t landing_pad)
 * Restaura os registradores callee-saved do quadro e salta para a landing pad com x0 = exc, x1 = seletor. */
    .globl dw_eh_install
dw_eh_install:
    ldp     d8, d9, [x0, #112]
    ldp     d10, d11, [x0, #128]
    ldp     d12, d13, [x0, #144]
    ldp     d14, d15, [x0, #160]
    ldp     x19, x20, [x0, #0]
    ldp     x21, x22, [x0, #16]
    ldp     x23, x24, [x0, #32]
    ldp     x25, x26, [x0, #48]
    ldp     x27, x28, [x0, #64]
    ldr     x29, [x0, #80]
    ldr     x30, [x0, #96]
    ldr     x4, [x0, #88]
    mov     x5, x3
    mov     x0, x1
    mov     x1, x2
    mov     sp, x4
    br      x5
