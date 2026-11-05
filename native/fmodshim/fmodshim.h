#ifndef PZARM_FMODSHIM_H
#define PZARM_FMODSHIM_H
/*
 * Ponte FMOD: entrega a libfmodintegration.dylib (Apple) as funcoes das DLLs oficiais fmod.dll/fmodstudio.dll (ARM64).
 *   - FMOD_*          : ponteiro direto (DLL e dylib usam a mesma AAPCS64), resolvido por GetProcAddress.
 *   - FMOD::Studio::* : shims C++ -> API C (fmodshim_gen.inc, gerado por tools/gen_fmod_shim.py).
 */
#include <stddef.h>
#include <windows.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Endereco para o nome de importacao sem o "_" de Mach-O ("FMOD_System_Init", "_ZN4FMOD6Studio..."), ou NULL.
 * So devolve algo depois de fmod_shim_load/fmod_shim_init. Serve a macho_set_extern_resolver. */
void* dw_fmod_resolve(const char* apple_name);

/* Resolve as tabelas nas DLLs ja carregadas. 0 = tudo resolvido; senao devolve quantos nomes faltam e preenche err
 * (UTF-8) com a lista COMPLETA dos ausentes (truncada so se passar de cap). */
int fmod_shim_load(HMODULE core, HMODULE studio, char* err, size_t cap);

/* Acha/carrega fmod.dll e fmodstudio.dll (os ja carregados pela JVM, senao da pasta desta DLL, senao do PATH) e
 * chama fmod_shim_load. Mesmo retorno. */
int fmod_shim_init(char* err, size_t cap);

/* Testes: troca o alvo C de um shim (nome da funcao FMOD_Studio_*); 0 se nao existe. */
int fmod_shim_set_target(const char* c_name, void* fn);
int fmod_shim_direct_count(void);
int fmod_shim_cxx_count(void);

#ifdef __cplusplus
}
#endif
#endif
