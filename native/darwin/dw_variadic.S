/* printf com a convencao variadica Apple arm64: os argumentos anonimos ficam na pilha, em slots de
   8 bytes, a partir do sp de entrada. O va_list do Windows ARM64 tambem e um ponteiro para slots de
   8 bytes, entao o sp de entrada vira o va_list de dw_vprintf. Nao mexe em x18. */
    .text
    .p2align 2
    .globl dw_printf_apple
dw_printf_apple:
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    add x1, sp, #16
    bl dw_vprintf
    ldp x29, x30, [sp], #16
    ret

/* snprintf(buf x0, n x1, fmt x2, ...): variadicos Apple na pilha -> va_list de dw_vsnprintf (x3). */
    .globl dw_snprintf_apple
dw_snprintf_apple:
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    add x3, sp, #16
    bl dw_vsnprintf
    ldp x29, x30, [sp], #16
    ret

/* sprintf(buf x0, fmt x1, ...): variadicos Apple na pilha -> va_list de dw_vsprintf (x2). */
    .globl dw_sprintf_apple
dw_sprintf_apple:
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    add x2, sp, #16
    bl dw_vsprintf
    ldp x29, x30, [sp], #16
    ret
