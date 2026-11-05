/* stdio da ucrt (o FILE* devolvido e da ucrt) com a semantica da libc da Apple:
   modo texto nao traduz CRLF, caminhos sao UTF-8, offsets de 64 bits, rename substitui. */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <windows.h>
#include "dw_internal.h"

static int to_wide(const char* s, wchar_t* out, int cap) {
  return MultiByteToWideChar(CP_UTF8, 0, s, -1, out, cap) > 0;
}

void* dw_fopen(const char* path, const char* mode) {
  wchar_t wp[MAX_PATH * 4], wm[16];
  if (!path || !mode || !to_wide(path, wp, MAX_PATH * 4)) { errno = EINVAL; return NULL; }
  int n = 0;
  for (const char* m = mode; *m && n < 12; m++)
    if (*m != 'b' && *m != 't') wm[n++] = (wchar_t)(unsigned char)*m;   /* sempre binario */
  wm[n++] = L'b';
  wm[n] = 0;
  return _wfopen(wp, wm);
}

int dw_fclose(void* f) { return fclose(f); }
int dw_fflush(void* f) { return fflush(f); }
uint64_t dw_fread(void* p, uint64_t size, uint64_t n, void* f) { return fread(p, (size_t)size, (size_t)n, f); }
uint64_t dw_fwrite(const void* p, uint64_t size, uint64_t n, void* f) { return fwrite(p, (size_t)size, (size_t)n, f); }
void dw_setbuf(void* f, char* buf) { setbuf(f, buf); }
int dw_fseeko(void* f, int64_t off, int whence) { return _fseeki64(f, off, whence); }
int64_t dw_ftello(void* f) { return _ftelli64(f); }

int dw_rename(const char* from, const char* to) {
  wchar_t wf[MAX_PATH * 4], wt[MAX_PATH * 4];
  if (!from || !to || !to_wide(from, wf, MAX_PATH * 4) || !to_wide(to, wt, MAX_PATH * 4)) { errno = EINVAL; return -1; }
  if (MoveFileExW(wf, wt, MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED)) return 0;
  DWORD e = GetLastError();
  errno = (e == ERROR_FILE_NOT_FOUND || e == ERROR_PATH_NOT_FOUND) ? ENOENT : EACCES;
  return -1;
}

int dw_puts(const char* s) { return puts(s); }
int dw_putchar(int c) { return putchar(c); }

int dw_feof(void* f) { return feof(f); }
void dw_rewind(void* f) { rewind(f); }

/* __stderrp/__stdoutp: DADOS da libSystem (FILE* stderr/stdout); a dylib le a variavel e usa o FILE* da ucrt. */
void* dw_stderrp;
void* dw_stdoutp;
