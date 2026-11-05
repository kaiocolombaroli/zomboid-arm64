#include "darwin.h"
#include <windows.h>

/* Cabecalho de 32 bytes imediatamente antes do ponteiro do usuario. */
#define DW_MAGIC 0x44574d41u
typedef struct {
  void*    raw;     /* ponteiro devolvido por HeapAlloc */
  uint64_t size;    /* tamanho pedido */
  uint64_t offset;  /* user - raw */
  uint32_t magic;
  uint32_t pad;
} dw_hdr;

void* dw_aligned_alloc(uint64_t align, uint64_t size) {
  if (align < 16) align = 16;
  if (align & (align - 1)) return NULL;
  uint64_t total = size + align + sizeof(dw_hdr);
  if (total < size) return NULL;
  char* raw = HeapAlloc(GetProcessHeap(), 0, (SIZE_T)total);
  if (!raw) return NULL;
  uintptr_t u = ((uintptr_t)raw + sizeof(dw_hdr) + (align - 1)) & ~(uintptr_t)(align - 1);
  dw_hdr* h = (dw_hdr*)u - 1;
  h->raw = raw; h->size = size; h->offset = u - (uintptr_t)raw; h->magic = DW_MAGIC; h->pad = 0;
  return (void*)u;
}

void* dw_malloc(uint64_t size) { return dw_aligned_alloc(16, size); }

void dw_free(void* p) {
  if (!p) return;
  dw_hdr* h = (dw_hdr*)p - 1;
  if (h->magic != DW_MAGIC) __builtin_trap();
  h->magic = 0;
  HeapFree(GetProcessHeap(), 0, h->raw);
}

uint64_t dw_malloc_size(const void* p) {
  if (!p) return 0;
  const dw_hdr* h = (const dw_hdr*)p - 1;
  return h->magic == DW_MAGIC ? h->size : 0;
}

void* dw_install_code(const void* code, size_t bytes) {
  if (!bytes) return NULL;
  void* m = VirtualAlloc(NULL, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
  if (!m) return NULL;
  memcpy(m, code, bytes);
  DWORD old;
  if (!VirtualProtect(m, bytes, PAGE_EXECUTE_READ, &old)) { VirtualFree(m, 0, MEM_RELEASE); return NULL; }
  FlushInstructionCache(GetCurrentProcess(), m, bytes);
  return m;
}
