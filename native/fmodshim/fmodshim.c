/*
 * fmodshim.c - ponte FMOD (ver fmodshim.h). As tabelas e os shims C++ vem de fmodshim_gen.inc.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <windows.h>

#include "fmodshim.h"
#include "fmodshim_gen.inc"

static void* g_direct_addr[FMOD_DIRECT_COUNT];
static int g_loaded;

static void err_append(char* err, size_t cap, size_t* len, const char* s) {
    size_t n = strlen(s);
    if (!err || !cap) return;
    if (*len + n + 2 >= cap) {
        if (*len + 4 < cap) { memcpy(err + *len, "...", 3); *len += 3; err[*len] = 0; }
        return;
    }
    err[(*len)++] = ' ';
    memcpy(err + *len, s, n);
    *len += n;
    err[*len] = 0;
}

int fmod_shim_load(HMODULE core, HMODULE studio, char* err, size_t cap) {
    int missing = 0;
    size_t len = 0;
    if (err && cap) err[0] = 0;
    for (int i = 0; i < FMOD_DIRECT_COUNT; i++) {
        FARPROC p = core ? GetProcAddress(core, g_fmod_direct[i]) : NULL;
        if (!p && studio) p = GetProcAddress(studio, g_fmod_direct[i]);
        g_direct_addr[i] = (void*)p;
        if (!p) { missing++; err_append(err, cap, &len, g_fmod_direct[i]); }
    }
    for (int i = 0; i < FMOD_TARGET_COUNT; i++) {
        FARPROC p = studio ? GetProcAddress(studio, g_fmod_targets[i].name) : NULL;
        if (!p && core) p = GetProcAddress(core, g_fmod_targets[i].name);
        *g_fmod_targets[i].slot = (void*)p;
        if (!p) { missing++; err_append(err, cap, &len, g_fmod_targets[i].name); }
    }
    g_loaded = (missing == 0);
    return missing;
}

static HMODULE find_module(const wchar_t* dll, const wchar_t* dir) {
    HMODULE m = GetModuleHandleW(dll);      /* a que a JVM carregou (System.loadLibrary), se ja carregou */
    if (m) return m;
    wchar_t full[MAX_PATH * 2];
    if (dir && _snwprintf(full, MAX_PATH * 2, L"%ls%ls", dir, dll) > 0) {
        full[MAX_PATH * 2 - 1] = 0;
        m = LoadLibraryExW(full, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
        if (m) return m;
    }
    return LoadLibraryW(dll);
}

int fmod_shim_init(char* err, size_t cap) {
    wchar_t dir[MAX_PATH * 2];
    int have_dir = 0;
    HMODULE self = NULL;
    if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCWSTR)(void*)&fmod_shim_init, &self)) {
        DWORD n = GetModuleFileNameW(self, dir, MAX_PATH * 2);
        wchar_t* slash = n ? wcsrchr(dir, L'\\') : NULL;
        if (n && n < MAX_PATH * 2 && slash) { slash[1] = 0; have_dir = 1; }
    }
    HMODULE core = find_module(L"fmod.dll", have_dir ? dir : NULL);
    HMODULE studio = find_module(L"fmodstudio.dll", have_dir ? dir : NULL);
    if (!core || !studio) {
        if (err && cap) snprintf(err, cap, "nao foi possivel carregar %s%s%s", core ? "" : "fmod.dll",
                                 (!core && !studio) ? " e " : "", studio ? "" : "fmodstudio.dll");
        return 1;
    }
    return fmod_shim_load(core, studio, err, cap);
}

void* dw_fmod_resolve(const char* name) {
    if (!name || !g_loaded) return NULL;
    if (strncmp(name, "FMOD_", 5) == 0) {
        for (int i = 0; i < FMOD_DIRECT_COUNT; i++)
            if (strcmp(g_fmod_direct[i], name) == 0) return g_direct_addr[i];
        return NULL;
    }
    for (int i = 0; i < FMOD_SHIM_COUNT; i++)
        if (strcmp(g_fmod_shims[i].name, name) == 0) return g_fmod_shims[i].fn;
    return NULL;
}

int fmod_shim_set_target(const char* c_name, void* fn) {
    for (int i = 0; i < FMOD_TARGET_COUNT; i++)
        if (strcmp(g_fmod_targets[i].name, c_name) == 0) { *g_fmod_targets[i].slot = fn; g_loaded = 1; return 1; }
    return 0;
}

int fmod_shim_direct_count(void) { return FMOD_DIRECT_COUNT; }
int fmod_shim_cxx_count(void) { return FMOD_SHIM_COUNT; }
