/* Arquivos e diretorios com a semantica da libc da Apple sobre a API wide do Windows:
   caminhos UTF-8, struct stat do arm64 (144 bytes), mkdir ignora o modo, remove tambem apaga diretorio vazio,
   realpath devolve caminho absoluto com '/' e exige que o alvo exista. */
#include <errno.h>
#include <string.h>
#include <windows.h>
#include "darwin.h"
#include "dw_internal.h"

#define DW_PATH_CAP (MAX_PATH * 4)

static int wide(const char* s, wchar_t* out) {
  if (!s) { errno = EFAULT; return 0; }
  if (MultiByteToWideChar(CP_UTF8, 0, s, -1, out, DW_PATH_CAP) <= 0) { errno = s[0] ? ENAMETOOLONG : ENOENT; return 0; }
  return 1;
}
static void set_errno(DWORD e) {
  switch (e) {
  case ERROR_FILE_NOT_FOUND: case ERROR_PATH_NOT_FOUND: case ERROR_INVALID_NAME: errno = ENOENT; break;
  case ERROR_ALREADY_EXISTS: case ERROR_FILE_EXISTS: errno = EEXIST; break;
  case ERROR_DIR_NOT_EMPTY: errno = ENOTEMPTY; break;
  case ERROR_DIRECTORY: errno = ENOTDIR; break;
  case ERROR_FILENAME_EXCED_RANGE: errno = ENAMETOOLONG; break;
  default: errno = EACCES; break;
  }
}

/* struct stat Apple arm64 (sys/stat.h, _DARWIN_FEATURE_64_BIT_INODE): st_dev@0 st_mode@4(u16) st_nlink@6(u16)
   st_ino@8 st_uid@16 st_gid@20 st_rdev@24 atime@32 mtime@48 ctime@64 birthtime@80 (timespec 16) st_size@96
   st_blocks@104 st_blksize@112 st_flags@116 st_gen@120. O unico acesso visto em libjassimp.dylib
   (DefaultIOStream::FileSize) e `ldr x0, [sp, #0x60]` = st_size @96. */
typedef struct { int64_t tv_sec; int64_t tv_nsec; } dw_timespec;
typedef struct {
  int32_t st_dev; uint16_t st_mode; uint16_t st_nlink; uint64_t st_ino; uint32_t st_uid; uint32_t st_gid; int32_t st_rdev;
  int32_t pad0;
  dw_timespec st_atimespec, st_mtimespec, st_ctimespec, st_birthtimespec;
  int64_t st_size; int64_t st_blocks; int32_t st_blksize; uint32_t st_flags; uint32_t st_gen; int32_t st_lspare;
  int64_t st_qspare[2];
} dw_apple_stat;
_Static_assert(sizeof(dw_apple_stat) == 144, "struct stat Apple arm64 tem 144 bytes");
_Static_assert(offsetof(dw_apple_stat, st_mode) == 4 && offsetof(dw_apple_stat, st_ino) == 8 && offsetof(dw_apple_stat, st_atimespec) == 32 &&
               offsetof(dw_apple_stat, st_birthtimespec) == 80 && offsetof(dw_apple_stat, st_size) == 96 &&
               offsetof(dw_apple_stat, st_blksize) == 112, "offsets de struct stat da Apple");

static int64_t ft_to_unix(FILETIME ft) {
  uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
  return (int64_t)((t - 116444736000000000ull) / 10000000ull);
}

int dw_stat(const char* path, void* st) {
  wchar_t wp[DW_PATH_CAP];
  if (!wide(path, wp)) return -1;
  WIN32_FILE_ATTRIBUTE_DATA d;
  if (!GetFileAttributesExW(wp, GetFileExInfoStandard, &d)) { set_errno(GetLastError()); return -1; }
  dw_apple_stat* s = st;
  memset(s, 0, sizeof *s);
  const int dir = (d.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
  const int ro = (d.dwFileAttributes & FILE_ATTRIBUTE_READONLY) != 0;
  s->st_mode = (uint16_t)(dir ? (0040000 | 0755) : (0100000 | (ro ? 0444 : 0644)));
  s->st_nlink = 1;
  s->st_size = dir ? 0 : (int64_t)(((uint64_t)d.nFileSizeHigh << 32) | d.nFileSizeLow);
  s->st_atimespec.tv_sec = ft_to_unix(d.ftLastAccessTime);
  s->st_mtimespec.tv_sec = ft_to_unix(d.ftLastWriteTime);
  s->st_ctimespec.tv_sec = s->st_mtimespec.tv_sec;
  s->st_birthtimespec.tv_sec = ft_to_unix(d.ftCreationTime);
  s->st_blocks = (s->st_size + 511) / 512;
  s->st_blksize = 4096;
  return 0;
}

int dw_mkdir(const char* path, int mode) {
  (void)mode;   /* o Windows nao tem modo POSIX */
  wchar_t wp[DW_PATH_CAP];
  if (!wide(path, wp)) return -1;
  if (CreateDirectoryW(wp, NULL)) return 0;
  set_errno(GetLastError());
  return -1;
}

int dw_chdir(const char* path) {
  wchar_t wp[DW_PATH_CAP];
  if (!wide(path, wp)) return -1;
  if (SetCurrentDirectoryW(wp)) return 0;
  set_errno(GetLastError());
  return -1;
}

int dw_remove(const char* path) {
  wchar_t wp[DW_PATH_CAP];
  if (!wide(path, wp)) return -1;
  DWORD a = GetFileAttributesW(wp);
  if (a == INVALID_FILE_ATTRIBUTES) { set_errno(GetLastError()); return -1; }
  BOOL ok = (a & FILE_ATTRIBUTE_DIRECTORY) ? RemoveDirectoryW(wp) : DeleteFileW(wp);
  if (ok) return 0;
  set_errno(GetLastError());
  return -1;
}

/* realpath$DARWIN_EXTSN(path, resolved): resolved NULL -> aloca com dw_malloc (a dylib libera com free). */
char* dw_realpath_extsn(const char* path, char* resolved) {
  wchar_t wp[DW_PATH_CAP], full[DW_PATH_CAP];
  if (!wide(path, wp)) return NULL;
  if (GetFileAttributesW(wp) == INVALID_FILE_ATTRIBUTES) { set_errno(GetLastError()); return NULL; }
  DWORD n = GetFullPathNameW(wp, DW_PATH_CAP, full, NULL);
  if (!n || n >= DW_PATH_CAP) { errno = n ? ENAMETOOLONG : ENOENT; return NULL; }
  int bytes = WideCharToMultiByte(CP_UTF8, 0, full, -1, NULL, 0, NULL, NULL);   /* inclui o NUL */
  if (bytes <= 0) { errno = EINVAL; return NULL; }
  if (resolved && bytes > 1024) { errno = ENAMETOOLONG; return NULL; }   /* PATH_MAX Apple, com o NUL */
  if (!resolved) {
    resolved = dw_malloc((uint64_t)bytes);
    if (!resolved) { errno = ENOMEM; return NULL; }
  }   /* buffer do chamador: PATH_MAX (1024) na Apple */
  WideCharToMultiByte(CP_UTF8, 0, full, -1, resolved, bytes, NULL, NULL);
  for (char* p = resolved; *p; p++) if (*p == '\\') *p = '/';
  return resolved;
}
