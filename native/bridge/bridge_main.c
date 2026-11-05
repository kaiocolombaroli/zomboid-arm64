/*
 * bridge_main.c - DLL ponte ARM64: a JVM carrega esta DLL no lugar da DLL x64 do jogo; cada Java_*
 * exportado (thunks gerados) salta para o alvo na dylib macOS carregada por macho_load.
 * Definicoes de build: BRIDGE_TABLE_H (cabecalho gerado), BRIDGE_LIB_NAME, BRIDGE_DYLIB_REL.
 */
#include <windows.h>
#include <bcrypt.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "macho_bridge.h"
#include "macho_jnienv.h"
#include "macho_loader.h"
#ifdef BRIDGE_FMOD
#include "fmodshim.h"
#endif

#include BRIDGE_TABLE_H

void* g_targets[BRIDGE_COUNT];

static void say(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
#include <stdarg.h>
static void say(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    printf("%s: ", BRIDGE_LIB_NAME);
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
    fflush(stdout);
}

static int sha256_hex(const uint8_t* data, size_t len, char out[65]) {
    BCRYPT_ALG_HANDLE alg = NULL;
    BCRYPT_HASH_HANDLE h = NULL;
    uint8_t dig[32];
    int ok = 0;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0) < 0) return 0;
    if (BCryptCreateHash(alg, &h, NULL, 0, NULL, 0, 0) >= 0) {
        size_t off = 0;
        ok = 1;
        while (ok && off < len) {
            ULONG n = (len - off > 0x10000000u) ? 0x10000000u : (ULONG)(len - off);
            ok = BCryptHashData(h, (PUCHAR)(data + off), n, 0) >= 0;
            off += n;
        }
        if (ok) ok = BCryptFinishHash(h, dig, sizeof dig, 0) >= 0;
        BCryptDestroyHash(h);
    }
    BCryptCloseAlgorithmProvider(alg, 0);
    if (!ok) return 0;
    for (int i = 0; i < 32; i++) sprintf(out + 2 * i, "%02x", dig[i]);
    return 1;
}

static int utf8_to_wide(const char* s, wchar_t* out, int cap) {
    return MultiByteToWideChar(CP_UTF8, 0, s, -1, out, cap) > 0;
}

/* Preenche path (UTF-8) com o caminho da dylib. */
static int dylib_path(char* path, size_t cap) {
    const char* ov = getenv("PZARM_DYLIB_OVERRIDE");
    if (ov && *ov) {
        if (strlen(ov) >= cap) return 0;
        strcpy(path, ov);
        return 1;
    }
    HMODULE self = NULL;
    wchar_t w[MAX_PATH * 2], full[MAX_PATH * 2], rel[MAX_PATH], cat[MAX_PATH * 3];
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCWSTR)(void*)&dylib_path, &self)) return 0;
    DWORD n = GetModuleFileNameW(self, w, MAX_PATH * 2);
    if (n == 0 || n >= MAX_PATH * 2) return 0;
    wchar_t* slash = wcsrchr(w, L'\\');
    if (!slash) return 0;
    slash[1] = 0;
    if (!utf8_to_wide(BRIDGE_DYLIB_REL, rel, MAX_PATH)) return 0;
    _snwprintf(cat, MAX_PATH * 3, L"%ls%ls", w, rel);
    cat[MAX_PATH * 3 - 1] = 0;
    n = GetFullPathNameW(cat, MAX_PATH * 2, full, NULL);
    if (n == 0 || n >= MAX_PATH * 2) return 0;
    return WideCharToMultiByte(CP_UTF8, 0, full, -1, path, (int)cap, NULL, NULL) > 0;
}

static uint8_t* read_file(const char* utf8, size_t* len) {
    wchar_t w[MAX_PATH * 3];
    if (!utf8_to_wide(utf8, w, MAX_PATH * 3)) return NULL;
    HANDLE f = CreateFileW(w, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return NULL;
    LARGE_INTEGER sz;
    uint8_t* buf = NULL;
    if (GetFileSizeEx(f, &sz) && sz.QuadPart > 0 && sz.QuadPart < (1LL << 30)) {
        buf = malloc((size_t)sz.QuadPart);
        DWORD got = 0;
        if (buf && (!ReadFile(f, buf, (DWORD)sz.QuadPart, &got, NULL) || got != sz.QuadPart)) {
            free(buf);
            buf = NULL;
        }
        if (buf) *len = (size_t)sz.QuadPart;
    }
    CloseHandle(f);
    return buf;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    char path[MAX_PATH * 4];
    if (!dylib_path(path, sizeof path)) {
        say("nao foi possivel localizar a dylib");
        return JNI_ERR;
    }
    size_t len = 0;
    uint8_t* data = read_file(path, &len);
    if (!data) {
        say("nao foi possivel ler a dylib: %s", path);
        return JNI_ERR;
    }
    char sha[65];
    int ok = sha256_hex(data, len, sha);
    free(data);
    if (!ok) {
        say("falha ao calcular SHA-256 da dylib");
        return JNI_ERR;
    }
    if (len != BRIDGE_DYLIB_SIZE || strcmp(sha, BRIDGE_DYLIB_SHA256) != 0) {
        say("dylib incompativel com esta build (esperado %s, obtido %s)", BRIDGE_DYLIB_SHA256, sha);
        return JNI_ERR;
    }

#ifdef BRIDGE_FMOD
    {
        /* A dylib depende de libfmod/libfmodstudio: resolve as DLLs oficiais ARM64 antes de carregar; todos os
         * ausentes saem numa unica mensagem. */
        char miss[2048];
        int nmiss = fmod_shim_init(miss, sizeof miss);
        if (nmiss) {
            say("%d simbolo(s) FMOD ausente(s) em fmod.dll/fmodstudio.dll:%s", nmiss, miss);
            return JNI_ERR;
        }
        macho_set_extern_resolver(dw_fmod_resolve);
    }
#endif

    macho_lib_t* lib = macho_load(path);
    if (!lib) {
        say("macho_load falhou para %s", path);
        return JNI_ERR;
    }
    /* Dylibs que chamam JNI variadica (JNIEnv_ C++) recebem o JNIEnv embrulhado de macho_jnienv.h. */
    int wrap = macho_lib_wraps_env(lib);
    if (wrap) {
        say("a dylib usa JNI variadica: JNIEnv embrulhado");
        /* Monta a tabela do wrapper agora: se falhar, a carga falha (nunca um NULL para a dylib). */
        void* cur = NULL;
        if ((*vm)->GetEnv(vm, &cur, JNI_VERSION_1_8) != JNI_OK || !cur || !macho_jnienv_wrap((JNIEnv*)cur)) {
            say("nao foi possivel montar o JNIEnv embrulhado");
            return JNI_ERR;
        }
    }

    int missing = 0;
    for (int i = 0; i < BRIDGE_COUNT; i++) {
        void* t = macho_dlsym(lib, g_table[i].name);
        if (!t) {
            say("simbolo ausente na dylib: %s", g_table[i].name);
            missing++;
        }
        g_targets[i] = t;
    }
    if (missing) return JNI_ERR;
    for (int i = 0; i < BRIDGE_COUNT; i++) {
        void* s = macho_jni_bridge(g_targets[i], g_table[i].letters, g_table[i].name, wrap);
        if (!s) {
            say("shim falhou para %s", g_table[i].name);
            return JNI_ERR;
        }
        g_targets[i] = s;
    }

    jint ver = JNI_VERSION_1_8;
    void* onload = macho_dlsym(lib, "JNI_OnLoad");
    if (onload) {
        /* x0 de JNI_OnLoad e o JavaVM, nao um env: o shim nao troca nada (wrap_env 0); a dylib recebe
         * um JavaVM embrulhado cujo GetEnv/Attach devolvem envs embrulhados. */
        void* s = macho_jni_bridge(onload, "pp", "JNI_OnLoad", 0);
        if (!s) return JNI_ERR;
        ver = ((jint(*)(JavaVM*, void*))s)(wrap ? macho_javavm_wrap(vm) : vm, reserved);
    }
    say("dylib carregada (%d entradas JNI)", BRIDGE_COUNT);
    return ver;
}
