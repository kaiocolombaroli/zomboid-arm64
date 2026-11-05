/* Thunk de variaveis thread-local de Mach-O (_tlv_bootstrap): o codigo da dylib faz
 *     ldr x8, [x0]   ; x0 = descritor {thunk, key, offset}
 *     blr x8
 * e espera o ENDERECO da variavel em x0. Preserva TUDO, salvo x0, x16 e x17 (a convencao do dyld,
 * que o compilador da Apple assume): x1-x15, x8, d0-d31 etc. Nao mexe em x18 (TEB no Windows).
 *
 * key = indice do TlsAlloc da imagem (dw_tlv.c). Caminho rapido, sem chamar C: o slot esta em
 * TEB->TlsSlots[key] (TEB = x18, TlsSlots em +0x1480, so para key < 64). Slot vazio (primeiro
 * acesso nesta thread) ou key >= 64: caminho lento, salva tudo e chama dw_tlv_slow(descritor). */
    .text
    .p2align 2
    .globl dw_tlv_get
dw_tlv_get:
    ldr     x16, [x0, #8]
    cmp     x16, #64
    b.hs    1f
    add     x17, x18, #0x1000        /* TEB + 0x1480: imediato de 12 bits nao cobre, soma em duas vezes */
    add     x17, x17, #0x480
    ldr     x17, [x17, x16, lsl #3]
    cbz     x17, 1f
    ldr     x16, [x0, #16]
    add     x0, x17, x16
    ret
1:
    /* frame: 16 (x29,x30) + 128 (x1-x15) + 512 (q0-q31) = 656; sp sempre multiplo de 16 */
    stp     x29, x30, [sp, #-16]!
    mov     x29, sp
    sub     sp, sp, #640
    stp     x1, x2, [sp, #0]
    stp     x3, x4, [sp, #16]
    stp     x5, x6, [sp, #32]
    stp     x7, x8, [sp, #48]
    stp     x9, x10, [sp, #64]
    stp     x11, x12, [sp, #80]
    stp     x13, x14, [sp, #96]
    stp     x15, xzr, [sp, #112]
    stp     q0, q1, [sp, #128]
    stp     q2, q3, [sp, #160]
    stp     q4, q5, [sp, #192]
    stp     q6, q7, [sp, #224]
    stp     q8, q9, [sp, #256]
    stp     q10, q11, [sp, #288]
    stp     q12, q13, [sp, #320]
    stp     q14, q15, [sp, #352]
    stp     q16, q17, [sp, #384]
    stp     q18, q19, [sp, #416]
    stp     q20, q21, [sp, #448]
    stp     q22, q23, [sp, #480]
    stp     q24, q25, [sp, #512]
    stp     q26, q27, [sp, #544]
    stp     q28, q29, [sp, #576]
    stp     q30, q31, [sp, #608]
    bl      dw_tlv_slow
    ldp     x1, x2, [sp, #0]
    ldp     x3, x4, [sp, #16]
    ldp     x5, x6, [sp, #32]
    ldp     x7, x8, [sp, #48]
    ldp     x9, x10, [sp, #64]
    ldp     x11, x12, [sp, #80]
    ldp     x13, x14, [sp, #96]
    ldp     x15, xzr, [sp, #112]
    ldp     q0, q1, [sp, #128]
    ldp     q2, q3, [sp, #160]
    ldp     q4, q5, [sp, #192]
    ldp     q6, q7, [sp, #224]
    ldp     q8, q9, [sp, #256]
    ldp     q10, q11, [sp, #288]
    ldp     q12, q13, [sp, #320]
    ldp     q14, q15, [sp, #352]
    ldp     q16, q17, [sp, #384]
    ldp     q18, q19, [sp, #416]
    ldp     q20, q21, [sp, #448]
    ldp     q22, q23, [sp, #480]
    ldp     q24, q25, [sp, #512]
    ldp     q26, q27, [sp, #544]
    ldp     q28, q29, [sp, #576]
    ldp     q30, q31, [sp, #608]
    mov     sp, x29
    ldp     x29, x30, [sp], #16
    ret
