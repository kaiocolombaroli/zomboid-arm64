#ifndef PZARM_MACHO_BRIDGE_H
#define PZARM_MACHO_BRIDGE_H
/*
 * Shims de ABI para entradas JNI de dylibs macOS arm64 (Apple) chamadas pela JVM (AAPCS64).
 * arg_types: uma letra por argumento apos JNIEnv* e jclass, na ordem: p ponteiro, i int, I long,
 * f float, d double, c byte, C boolean, w short, W char.
 * wrap_env != 0: o shim troca o JNIEnv* de x0 pelo wrapper de macho_jnienv.h (dylibs que chamam
 * JNI variadica por macho_lib_wraps_env), o que sempre exige um shim.
 * Devolve target quando nada precisa de ajuste, ou um shim executavel (RX). NULL com uma linha
 * "[macho] bridge ..." para letra desconhecida, area de pilha > 4095 bytes ou falha de instalacao.
 */
#ifdef __cplusplus
extern "C" {
#endif

void* macho_jni_bridge(void* target, const char* arg_types, const char* sym_name, int wrap_env);

#ifdef __cplusplus
}
#endif
#endif
