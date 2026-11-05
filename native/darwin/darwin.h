#ifndef DARWIN_H
#define DARWIN_H
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Resolve um nome de importacao (como em native/imports/NOME.txt) para o
   endereco da implementacao Darwin-compativel. NULL se nao atendido. */
void* dw_resolve(const char* macho_name);

void*    dw_malloc(uint64_t size);
void     dw_free(void* p);
void*    dw_aligned_alloc(uint64_t align, uint64_t size);
uint64_t dw_malloc_size(const void* p);

/* Reescreve %ld/%lu/%lx (long Apple = 64 bits) para %lld/%llu/%llx; preserva flags, largura,
   precisao e %%. Devolve o tamanho necessario sem o NUL (out pode ser NULL com cap 0). */
size_t dw_fix_format(const char* in, char* out, size_t cap);

/* Copia codigo para memoria nova (RW -> RX) e invalida o cache de instrucoes. */
void* dw_install_code(const void* code, size_t bytes);

/* TLS de Mach-O (dw_tlv.c/.S). Troca o thunk e a key de cada descritor {thunk,key,offset} em
   [vars_begin, vars_end) por dw_tlv_get e um indice TlsAlloc; tdata/tdata_size = conteudo inicial
   (__thread_data), tbss_size = o resto, zerado. Offsets dos descritores contam a partir de tdata. */
void dw_tlv_register(void* vars_begin, void* vars_end, void* tdata, size_t tdata_size, size_t tbss_size);
/* Thunk do descritor: x0 = descritor, devolve o endereco em x0; preserva tudo salvo x0/x16/x17. */
void* dw_tlv_get(void* desc);

#ifdef DW_TESTING
/* So para testes: faz dw_resolve devolver NULL para este nome (NULL limpa). */
void dw_test_fail_import(const char* name);
#endif

#ifdef __cplusplus
}
#endif
#endif
