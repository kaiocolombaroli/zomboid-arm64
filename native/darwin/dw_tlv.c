/* TLS de Mach-O (__thread em dylibs): descritores {thunk, key, offset}. Cada imagem registrada ganha um
 * indice TlsAlloc (a "key" do descritor); cada thread tem um bloco proprio {tdata copiado, resto zero},
 * alocado no primeiro acesso (dw_tlv_slow) e liberado no fim da thread (DllMain, DLL_THREAD_DETACH).
 * dw_tlv.S le TEB->TlsSlots[key] direto no caminho rapido. */
#include "darwin.h"
#include <windows.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DW_TLV_MAX_IMAGES 32
#define DW_TLV_ALIGN 64

typedef struct {
  DWORD idx;
  const void* tdata;
  size_t tdata_size, total;
} dw_tlv_image;

static dw_tlv_image g_img[DW_TLV_MAX_IMAGES];
static atomic_int g_nimg;               /* publicado com release; g_img[i<n] nunca muda */
static SRWLOCK g_lock = SRWLOCK_INIT;

extern void* dw_tlv_get(void* desc);    /* dw_tlv.S */

void* dw_tlv_slow(uint64_t* desc);
void dw_tlv_register(void* vars_begin, void* vars_end, void* tdata, size_t tdata_size, size_t tbss_size) {
  if (!vars_begin || vars_end < vars_begin || ((uintptr_t)vars_end - (uintptr_t)vars_begin) % 24) {
    fprintf(stderr, "[macho] dw_tlv_register: descritores invalidos\n");
    abort();
  }
  AcquireSRWLockExclusive(&g_lock);
  int n = atomic_load_explicit(&g_nimg, memory_order_relaxed);
  if (n == DW_TLV_MAX_IMAGES) {
    fprintf(stderr, "[macho] dw_tlv_register: imagens demais com TLS\n");
    abort();
  }
  DWORD idx = TlsAlloc();
  if (idx == TLS_OUT_OF_INDEXES) {
    fprintf(stderr, "[macho] dw_tlv_register: TlsAlloc falhou\n");
    abort();
  }
  /* o caminho rapido assume TEB(x18)+0x1480+8*idx: confere uma vez por imagem (idx < 64). */
  if (idx < 64) {
    void* marker = (void*)(uintptr_t)0x5A5A1234u;
    TlsSetValue(idx, marker);
    if (((void**)((char*)NtCurrentTeb() + 0x1480))[idx] != marker) {
      fprintf(stderr, "[macho] dw_tlv_register: TlsSlots nao esta em TEB+0x1480\n");
      abort();
    }
    TlsSetValue(idx, NULL);
  }
  dw_tlv_image* im = &g_img[n];
  im->idx = idx;
  im->tdata = tdata;
  im->tdata_size = tdata_size;
  im->total = tdata_size + tbss_size;
  for (uint64_t* d = vars_begin; (void*)d < vars_end; d += 3) {
    d[0] = (uint64_t)(uintptr_t)dw_tlv_get;
    d[1] = idx;                          /* d[2] (offset) fica como o linker gravou */
  }
  atomic_store_explicit(&g_nimg, n + 1, memory_order_release);
  ReleaseSRWLockExclusive(&g_lock);
}

static const dw_tlv_image* find_image(DWORD idx) {
  int n = atomic_load_explicit(&g_nimg, memory_order_acquire);
  for (int i = 0; i < n; i++)
    if (g_img[i].idx == idx) return &g_img[i];
  return NULL;
}

/* Caminho lento (primeiro acesso da thread a esta imagem). Devolve o endereco da variavel. */
void* dw_tlv_slow(uint64_t* desc) {
  DWORD err = GetLastError();            /* TlsGetValue/TlsSetValue mexem no LastError */
  const dw_tlv_image* im = find_image((DWORD)desc[1]);
  if (!im) {
    fprintf(stderr, "[macho] TLV com key %llu desconhecida\n", (unsigned long long)desc[1]);
    abort();
  }
  if (desc[2] >= im->total) {
    fprintf(stderr, "[macho] TLV: offset %llu fora do bloco de %zu bytes\n", (unsigned long long)desc[2], im->total);
    abort();
  }
  char* block = TlsGetValue(im->idx);
  if (!block) {
    block = dw_aligned_alloc(DW_TLV_ALIGN, im->total ? im->total : 1);
    if (!block) {
      fprintf(stderr, "[macho] TLV: sem memoria\n");
      abort();
    }
    if (im->tdata_size) memcpy(block, im->tdata, im->tdata_size);
    memset(block + im->tdata_size, 0, im->total - im->tdata_size);
    TlsSetValue(im->idx, block);
  }
  SetLastError(err);
  return block + desc[2];
}

/* _tlv_atexit(termFunc, objAddr, dso): destrutores de thread_local nao rodam no M2 (a thread so some). */
void dw_tlv_atexit(void (*term)(void*), void* obj, void* dso) { (void)term; (void)obj; (void)dso; }

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved) {
  (void)inst; (void)reserved;
  if (reason == DLL_THREAD_DETACH) {
    int n = atomic_load_explicit(&g_nimg, memory_order_acquire);
    for (int i = 0; i < n; i++) {
      void* b = TlsGetValue(g_img[i].idx);
      if (b) { TlsSetValue(g_img[i].idx, NULL); dw_free(b); }
    }
  }
  return TRUE;
}
