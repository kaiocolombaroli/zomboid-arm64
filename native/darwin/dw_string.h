#ifndef DW_STRING_H
#define DW_STRING_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* std::string da libc++ da Apple (arm64): curta = 22 chars + NUL, tamanho no byte 23;
   longa = ponteiro, tamanho, (capacidade incluindo o NUL | bit 63). Nenhum std::string da libc++
   do llvm-mingw cruza esta API. Memoria longa vem de dw_malloc (a dylib libera com _ZdlPv). */
typedef struct { uint64_t words[3]; } macho_string;
size_t macho_string_size(const macho_string* s);
const char* macho_string_data(const macho_string* s);
void macho_string_destroy(macho_string* s);
macho_string* macho_string_assign(macho_string* s, const char* text);
macho_string* macho_string_copy(macho_string* s, const macho_string* source);
macho_string* macho_string_append_n(macho_string* s, const char* text, size_t count);
macho_string* macho_string_append(macho_string* s, const char* text);
macho_string* macho_string_insert(macho_string* s, size_t position, const char* text);
void macho_string_resize(macho_string* s, size_t count, char fill);
void macho_string_push_back(macho_string* s, char ch);
/* find/rfind(char, pos), erase(pos, n), insert(pos, text, n), replace(pos, n1, text): layout Apple.
   npos = (size_t)-1. pos > size aborta com log (como insert). */
size_t macho_string_find_char(const macho_string* s, char ch, size_t pos);
size_t macho_string_rfind_char(const macho_string* s, char ch, size_t pos);
macho_string* macho_string_erase(macho_string* s, size_t pos, size_t count);
macho_string* macho_string_insert_n(macho_string* s, size_t pos, const char* text, size_t count);
macho_string* macho_string_replace(macho_string* s, size_t pos, size_t count, const char* text);
macho_string macho_string_concat(const char* prefix, const macho_string* suffix);
macho_string macho_string_from_int(int value);
macho_string macho_string_from_uint(unsigned value);

/* Erro irrecuperavel (as excecoes de string chegam so no M2 Tarefa 5): loga e aborta. */
void dw_fatal(const char* what);

/* std::runtime_error(const std::string&) da Apple: constroi em self o runtime_error da libc++
   estatica a partir do texto (o objeto string nunca e passado para a libc++). */
void dw_runtime_error_ctor_string(void* self, const macho_string* s);
#ifdef __cplusplus
}
#endif
#endif
