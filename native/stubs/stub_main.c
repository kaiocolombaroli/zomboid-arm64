/* DLL stub ARM64: cada export Java_* cai em stub_common (stub_asm.S), que registra o primeiro uso e
 * retorna 0. Com STUB_EMPTY so existe DllMain. Falha de log nunca falha a chamada. */
#include <windows.h>

#ifndef STUB_EMPTY
#include <stdlib.h>
#include <string.h>
#include STUB_NAMES_H

static void log_write(const char* line, DWORD len) {
    WCHAR env[8];
    DWORD n = GetEnvironmentVariableW(L"PZARM_STUB_LOG", env, 8);
    if (n > 0 && n < 8 && env[0] == L'0') return;
    HMODULE self = NULL;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCWSTR)&log_write, &self)) return;
    WCHAR path[MAX_PATH + 64];
    DWORD m = GetModuleFileNameW(self, path, MAX_PATH);
    if (m == 0 || m >= MAX_PATH) return;
    while (m > 0 && path[m - 1] != L'\\' && path[m - 1] != L'/') m--;
    path[m] = 0;
    WCHAR logs[MAX_PATH + 64];
    lstrcpyW(logs, path);
    lstrcatW(logs, L"..\\logs");
    CreateDirectoryW(logs, NULL); /* falha (ja existe) e ignorada */
    lstrcatW(logs, L"\\stub-" STUB_WLIB L".log");
    HANDLE h = CreateFileW(logs, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    DWORD w;
    WriteFile(h, line, len, &w, NULL);
    CloseHandle(h);
}

/* chamada por stub_common so na primeira vez de cada indice */
void pz_stub_first(unsigned long long idx) {
    if (idx >= STUB_COUNT) return;
    char buf[512];
    const char* name = g_stub_names[idx];
    size_t k = 0;
    const char* pre = "[stub] " STUB_LIB " ";
    size_t pl = strlen(pre);
    memcpy(buf, pre, pl);
    k = pl;
    size_t nl = strlen(name);
    if (nl > sizeof(buf) - pl - 2) nl = sizeof(buf) - pl - 2;
    memcpy(buf + k, name, nl);
    k += nl;
    buf[k++] = '\n';
    log_write(buf, (DWORD)k);
}
#endif

BOOL WINAPI DllMain(HINSTANCE h, DWORD reason, LPVOID r) {
    (void)h; (void)reason; (void)r;
    return TRUE;
}
