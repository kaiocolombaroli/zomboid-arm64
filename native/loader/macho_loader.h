#ifndef PZARM_MACHO_LOADER_H
#define PZARM_MACHO_LOADER_H
/*
 * Carregador em processo das dylibs macOS arm64 do jogo (libLighting.dylib e outras), no
 * Windows ARM64. Mapeia a fatia arm64, aplica rebase/bind do dyld contra dw_resolve, le a trie
 * de exports e roda __mod_init_func.
 *
 * Recusados de forma limpa (uma linha "[macho] ... rejected: ..." e NULL): arm64e, chained
 * fixups, funcoes de inicializacao de TLV, Objective-C, dependencias alem de libc++/libSystem (exceto @rpath/libfmod[studio].dylib, ver macho_set_extern_resolver) e
 * qualquer importacao que dw_resolve nao fornece. TLV (__thread) e suportado (dw_tlv.c). O wrapper
 * de JNIEnv nao e decidido aqui: ver macho_lib_wraps_env e macho_jnienv.h.
 * Logs em stdout; PZARM_NATIVE_VERBOSE=1 liga o detalhe por instrucao.
 */
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct macho_lib macho_lib_t;

/* Carrega a fatia arm64 da dylib em utf8_path. NULL em qualquer recusa. A imagem nunca e liberada. */
macho_lib_t* macho_load(const char* utf8_path);

/* Export por nome C, sem o "_" de Mach-O: "Java_..." acha "_Java_...". NULL se nao exporta. */
void* macho_dlsym(macho_lib_t* lib, const char* name);

/* Nome do arquivo de onde veio (para logs). */
const char* macho_lib_name(const macho_lib_t* lib);

/* Resolvedor de importacoes das dylibs que declaram @rpath/libfmod.dylib ou @rpath/libfmodstudio.dylib (as unicas
 * dependencias alem de libc++/libSystem aceitas, e nunca carregadas): recebe o nome sem o "_" de Mach-O
 * ("FMOD_System_Init", "_ZN4FMOD6Studio...") e devolve o endereco, ou NULL. Chamado depois de dw_resolve. */
typedef void* (*macho_extern_resolver_t)(const char* name);
void macho_set_extern_resolver(macho_extern_resolver_t fn);

/* Verdadeiro quando a dylib exporta os wrappers C++ JNIEnv_ (JNI variadica com va_list Apple). */
int macho_lib_wraps_env(const macho_lib_t* lib);

/* Imagem carregada, para o runtime de excecoes (Tarefa 5). Endereco e tamanho cobrem a imagem toda. */
typedef struct macho_image {
    const char* name;           /* nome do arquivo; vale enquanto o processo viver */
    uintptr_t base;             /* endereco do cabecalho Mach-O */
    size_t size;                /* bytes mapeados */
    uintptr_t unwind_info;      /* __TEXT,__unwind_info (0 se nao ha) */
    size_t unwind_info_size;
    uintptr_t eh_frame;         /* __TEXT,__eh_frame (0 se nao ha); so diagnostico */
    size_t eh_frame_size;
} macho_image_t;

/* Imagem que contem pc, ou NULL. Sem lock: a lista so cresce e cada entrada e imutavel depois de
 * publicada, entao pode ser chamada de qualquer thread, inclusive durante um unwind. */
const macho_image_t* macho_image_for_pc(uintptr_t pc);

/* Enumeracao das imagens publicadas (para diagnostico e testes): i em [0, macho_image_count()). */
int macho_image_count(void);
const macho_image_t* macho_image_at(int i);

/* Nome do export mais proximo (endereco <= pc) dentro da imagem de pc, para logs; NULL se nao ha. */
const char* macho_nearest_export(uintptr_t pc);

#ifdef __cplusplus
}
#endif
#endif
