// Alvo de teste: grava x0-x7, d0-d7 e 128 bytes a partir de sp em g_capture e retorna.
// Layout de g_capture: x[8] (0), d[8] (64), stack[128] (128). Temporarios: x9-x15.
    .text
    .globl capture_target
    .balign 4
capture_target:
    adrp    x9, g_capture
    add     x9, x9, :lo12:g_capture
    stp     x0, x1, [x9, #0]
    stp     x2, x3, [x9, #16]
    stp     x4, x5, [x9, #32]
    stp     x6, x7, [x9, #48]
    stp     d0, d1, [x9, #64]
    stp     d2, d3, [x9, #80]
    stp     d4, d5, [x9, #96]
    stp     d6, d7, [x9, #112]
    add     x10, x9, #128
    ldp     x11, x12, [sp, #0]
    stp     x11, x12, [x10, #0]
    ldp     x11, x12, [sp, #16]
    stp     x11, x12, [x10, #16]
    ldp     x11, x12, [sp, #32]
    stp     x11, x12, [x10, #32]
    ldp     x11, x12, [sp, #48]
    stp     x11, x12, [x10, #48]
    ldp     x11, x12, [sp, #64]
    stp     x11, x12, [x10, #64]
    ldp     x11, x12, [sp, #80]
    stp     x11, x12, [x10, #80]
    ldp     x11, x12, [sp, #96]
    stp     x11, x12, [x10, #96]
    ldp     x11, x12, [sp, #112]
    stp     x11, x12, [x10, #112]
    ret

    .bss
    .globl g_capture
    .balign 16
g_capture:
    .space 256
