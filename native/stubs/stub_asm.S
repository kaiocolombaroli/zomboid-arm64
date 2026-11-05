// stub_common: x16 = indice. Primeira chamada de cada indice (bitset atomico g_stub_seen) chama
// pz_stub_first(indice) com x0-x7/d0-d7 salvos; sempre retorna x0 = 0, d0 = 0. Nao usa x18.
    .text
    .align 2
    .globl stub_common
stub_common:
    adrp x17, g_stub_seen
    add  x17, x17, :lo12:g_stub_seen
    lsr  x9, x16, #6
    add  x9, x17, x9, lsl #3
    and  x10, x16, #63
    mov  x11, #1
    lsl  x11, x11, x10
1:  ldxr x12, [x9]
    tst  x12, x11
    b.ne 2f
    orr  x13, x12, x11
    stxr w14, x13, [x9]
    cbnz w14, 1b
    stp  x29, x30, [sp, #-144]!
    mov  x29, sp
    stp  x0, x1, [sp, #16]
    stp  x2, x3, [sp, #32]
    stp  x4, x5, [sp, #48]
    stp  x6, x7, [sp, #64]
    stp  d0, d1, [sp, #80]
    stp  d2, d3, [sp, #96]
    stp  d4, d5, [sp, #112]
    stp  d6, d7, [sp, #128]
    mov  x0, x16
    bl   pz_stub_first
    ldp  x29, x30, [sp], #144
    mov  x0, #0
    fmov d0, xzr
    ret
2:  clrex
    mov  x0, #0
    fmov d0, xzr
    ret
