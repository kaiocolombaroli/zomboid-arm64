cmake_minimum_required(VERSION 3.20)
project(zomboid_native C CXX ASM)
enable_testing()

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-Wall -Wextra -O2 -ffixed-x18)

# Tabela C++ gerada: referencias de LINK aos simbolos da libc++/libc++abi estaticas (erro de nome = erro de link).
find_program(PZ_PYTHON NAMES python python3 REQUIRED)
set(GEN_DIR ${CMAKE_BINARY_DIR}/generated)
set(IMPORTS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/imports)
set(DYLIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../game-libs/macos-42.21)
set(JDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../spike-java-arm/jdk-25-arm64)
set(JNI_INC ${JDK_DIR}/include)
add_custom_command(
  OUTPUT ${GEN_DIR}/dw_cxx_table.cpp ${GEN_DIR}/cxx_selfdefined.txt
  COMMAND ${PZ_PYTHON} -I ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_cxx_table.py
          --tsv ${IMPORTS_DIR}/classes.tsv --imports ${IMPORTS_DIR} --dylibs ${DYLIB_DIR} --out ${GEN_DIR}
  DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_cxx_table.py
          ${CMAKE_CURRENT_SOURCE_DIR}/../tools/macho_inventory.py ${IMPORTS_DIR}/classes.tsv
  COMMENT "Gerando dw_cxx_table.cpp")

set(DARWIN_SRCS
  darwin/dw_string.cpp
  darwin/dw_cxx.cpp
  ${GEN_DIR}/dw_cxx_table.cpp
  darwin/dw_alloc.c
  darwin/dw_runtime.c
  darwin/dw_eh.c
  darwin/dw_eh.S
  darwin/dw_unwind.c
  darwin/dw_ti.cpp
  darwin/dw_table.c
  darwin/dw_stdio.c
  darwin/dw_fs.c
  darwin/dw_libc.c
  darwin/dw_math.c
  darwin/dw_pthread.c
  darwin/dw_format.c
  darwin/dw_variadic.S
  darwin/dw_tlv.c
  darwin/dw_tlv.S)

# libc++/libc++abi/libunwind estaticos (nunca a libc++.dll): vale para a DLL e para as copias de teste.
add_library(cxxrt INTERFACE)
target_link_libraries(cxxrt INTERFACE ucrt bcrypt)
target_link_options(cxxrt INTERFACE -static-libgcc -Wl,-Bstatic -lc++ -lc++abi -lunwind -Wl,-Bdynamic)

# Copia estatica so para os testes unitarios; o artefato de producao e pzdarwin.dll (abaixo).
add_library(darwin STATIC ${DARWIN_SRCS})
target_include_directories(darwin PUBLIC darwin PRIVATE loader)
target_link_libraries(darwin PUBLIC cxxrt)

set(TEST_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../tests/darwin/test_darwin.c)
add_executable(test_darwin ${TEST_SRC})
target_link_libraries(test_darwin darwin macholoader)

foreach(t alloc_contract alloc_threads chkstk_large_frame)
  add_test(NAME darwin_${t} COMMAND test_darwin ${t})
endforeach()
add_test(NAME darwin_import_coverage
  COMMAND test_darwin import_coverage ${CMAKE_CURRENT_SOURCE_DIR}/imports/lighting.txt)

# --- carregador Mach-O ---
add_library(macholoader STATIC loader/macho_loader.c)
target_include_directories(macholoader PUBLIC loader)
target_link_libraries(macholoader PUBLIC darwin)

# Copia da darwin com o gancho de teste (dw_test_fail_import); so o alvo de teste a usa.
add_library(darwin_testing STATIC ${DARWIN_SRCS})
target_include_directories(darwin_testing PUBLIC darwin PRIVATE loader)
target_compile_definitions(darwin_testing PUBLIC DW_TESTING)
target_link_libraries(darwin_testing PUBLIC cxxrt)

add_executable(test_loader ${CMAKE_CURRENT_SOURCE_DIR}/../tests/loader/test_loader.c
  loader/macho_loader.c)
target_include_directories(test_loader PRIVATE loader)
target_link_libraries(test_loader darwin_testing)

set(LIGHTING_DYLIB ${CMAKE_CURRENT_SOURCE_DIR}/../game-libs/macos-42.21/libLighting.dylib)
foreach(t load_lighting unicode_path rejects_garbage rejects_unknown_import
         rejects_segment_overflow rejects_wx_segment rejects_bind_offset_overflow)
  add_test(NAME loader_${t} COMMAND test_loader ${t} ${LIGHTING_DYLIB} ${CMAKE_BINARY_DIR})
endforeach()

# --- shims de ABI (JNI Apple -> AAPCS64) ---
add_library(machobridge STATIC loader/macho_bridge.c loader/macho_jnienv.c)
target_include_directories(machobridge PUBLIC loader ${JNI_INC} ${JNI_INC}/win32)
target_link_libraries(machobridge PUBLIC darwin)

add_executable(test_bridge ${CMAKE_CURRENT_SOURCE_DIR}/../tests/loader/test_bridge.c
  ${CMAKE_CURRENT_SOURCE_DIR}/../tests/loader/capture_target.S)
target_link_libraries(test_bridge machobridge macholoader)

foreach(t direct_when_no_fix twenty_floats_packed mixed_stack_alignment boolean_extended shim_is_rx)
  add_test(NAME bridge_${t} COMMAND test_bridge ${t})
endforeach()

# --- runtime unico pzdarwin.dll: darwin + macholoader + machobridge, libc++/libc++abi estaticos ---
set(DIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../dist)
add_library(pzdarwin SHARED ${DARWIN_SRCS} loader/macho_loader.c loader/macho_bridge.c
  loader/macho_jnienv.c darwin/pzdarwin.def)
set_target_properties(pzdarwin PROPERTIES PREFIX ""
  RUNTIME_OUTPUT_DIRECTORY ${DIST_DIR} LIBRARY_OUTPUT_DIRECTORY ${DIST_DIR})
target_include_directories(pzdarwin PUBLIC darwin loader ${JNI_INC} ${JNI_INC}/win32)
target_link_libraries(pzdarwin PRIVATE cxxrt)
# Os testes rodam em build/ e carregam esta DLL de la: copia a cada relink (sem copia velha).
add_custom_command(TARGET pzdarwin POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> ${CMAKE_BINARY_DIR})
# Mapa de link: prova que libc++/libc++abi/libunwind entram estaticas (nenhuma .dll irma).
target_link_options(pzdarwin PRIVATE -Wl,-Map,${CMAKE_BINARY_DIR}/pzdarwin.map)

# --- testes da libc Apple, contra a DLL ---
add_executable(test_libc ${CMAKE_CURRENT_SOURCE_DIR}/../tests/darwin/test_libc.c)
target_link_libraries(test_libc pzdarwin ucrt)
add_custom_command(TARGET test_libc POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_libc>)
foreach(t fopen_binary fseeko_64bit fopen_unicode rename_overwrites rand_range fix_format
         printf_apple_variadic vsnprintf_truncates sincos_stret gettimeofday misc
         snprintf_apple ctype_table string_funcs math_float fseek_ftell fs exit)
  add_test(NAME libc_${t} COMMAND test_libc ${t} ${CMAKE_BINARY_DIR})
endforeach()

# --- M5 Tarefa 2: pthread/sched/dispatch e demais importacoes de libfmodintegration.dylib ---
add_executable(test_pthread ${CMAKE_CURRENT_SOURCE_DIR}/../tests/darwin/test_pthread.c)
target_link_libraries(test_pthread pzdarwin ucrt)
add_custom_command(TARGET test_pthread POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_pthread>)
foreach(t mutex_contention mutex_reinit mutex_std cond_signal cond_timeout_past once_single create_join_retval
         stack_size self_equal setname_noop dispatch_semaphore libc_misc)
  add_test(NAME pthread_${t} COMMAND test_pthread ${t})
endforeach()
add_test(NAME pthread_import_coverage_fmod COMMAND test_pthread import_coverage_fmod ${IMPORTS_DIR}/fmodintegration.txt)

# --- DLL ponte Lighting64.dll (JVM -> dylib macOS) ---
set(PZ_JAR "C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar" CACHE FILEPATH "projectzomboid.jar (so leitura)")
# Uma DLL ponte por dylib. Ao trocar o jar (ou o gerador, ou a dylib) os arquivos gerados sao refeitos.
function(pz_bridge_dll LIBNAME DYLIB_FILE)
  set(_with_fmod OFF)
  if(ARGC GREATER 2 AND ARGV2 STREQUAL "FMOD")
    set(_with_fmod ON)
  endif()
  set(_dylib ${CMAKE_CURRENT_SOURCE_DIR}/../game-libs/macos-42.21/${DYLIB_FILE})
  add_custom_command(
    OUTPUT ${GEN_DIR}/${LIBNAME}_table.h ${GEN_DIR}/${LIBNAME}_thunks.S ${GEN_DIR}/${LIBNAME}.def
    COMMAND ${PZ_PYTHON} -I ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_bridge.py
            --lib ${LIBNAME} --dylib ${_dylib} --jar ${PZ_JAR} --out ${GEN_DIR}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_bridge.py
            ${CMAKE_CURRENT_SOURCE_DIR}/../tools/macho_inventory.py ${_dylib} ${PZ_JAR}
    COMMENT "Gerando thunks de ${LIBNAME}"
    VERBATIM)
  add_library(${LIBNAME} SHARED bridge/bridge_main.c
    ${GEN_DIR}/${LIBNAME}_thunks.S ${GEN_DIR}/${LIBNAME}.def)
  set_target_properties(${LIBNAME} PROPERTIES PREFIX ""
    RUNTIME_OUTPUT_DIRECTORY ${DIST_DIR} LIBRARY_OUTPUT_DIRECTORY ${DIST_DIR})
  target_include_directories(${LIBNAME} PRIVATE ${GEN_DIR} ${JNI_INC} ${JNI_INC}/win32)
  target_compile_definitions(${LIBNAME} PRIVATE
    BRIDGE_TABLE_H="${LIBNAME}_table.h" BRIDGE_LIB_NAME="${LIBNAME}"
    BRIDGE_DYLIB_REL="../game-libs/macos-42.21/${DYLIB_FILE}")
  target_link_libraries(${LIBNAME} PRIVATE pzdarwin bcrypt)
  target_link_options(${LIBNAME} PRIVATE -static -static-libgcc)
  if(_with_fmod)
    # Ponte FMOD (M5 Tarefa 3): a dylib depende de @rpath/libfmod[studio].dylib; os simbolos vem das DLLs oficiais.
    target_sources(${LIBNAME} PRIVATE fmodshim/fmodshim.c)
    target_include_directories(${LIBNAME} PRIVATE fmodshim)
    target_compile_definitions(${LIBNAME} PRIVATE BRIDGE_FMOD)
  endif()
  list(APPEND PZ_BRIDGE_DLLS ${LIBNAME})
  set(PZ_BRIDGE_DLLS ${PZ_BRIDGE_DLLS} PARENT_SCOPE)
endfunction()

set(PZ_BRIDGE_DLLS "")
pz_bridge_dll(Lighting64 libLighting.dylib)
pz_bridge_dll(PZPopMan64 libPZPopMan.dylib)
pz_bridge_dll(PZPathFind64 libPZPathFind.dylib)
pz_bridge_dll(PZBullet64 libPZBullet.dylib)
pz_bridge_dll(PZClipper64 libPZClipper.dylib)
pz_bridge_dll(jassimp64 libjassimp.dylib)
pz_bridge_dll(fmodintegration64 libfmodintegration.dylib FMOD)
add_custom_target(bridges DEPENDS ${PZ_BRIDGE_DLLS})

# --- M5 Tarefa 3: ponte FMOD (shims C++ -> API C, tabelas FMOD_*), sem depender de pzdarwin ---
add_executable(test_fmodshim ${CMAKE_CURRENT_SOURCE_DIR}/../tests/darwin/test_fmodshim.c fmodshim/fmodshim.c)
target_include_directories(test_fmodshim PRIVATE fmodshim)
target_link_options(test_fmodshim PRIVATE -static -static-libgcc)
foreach(t counts bool_in_low_byte bool_out_one_byte param_id_by_value passthrough_args unresolved_listed)
  add_test(NAME fmodshim_${t} COMMAND test_fmodshim ${t})
endforeach()
add_test(NAME fmodshim_resolves_real_dlls
  COMMAND test_fmodshim resolves_real_dlls ${CMAKE_CURRENT_SOURCE_DIR}/../third-party/fmod)
set_tests_properties(fmodshim_resolves_real_dlls PROPERTIES SKIP_RETURN_CODE 77)
add_test(NAME fmodshim_gen_up_to_date
  COMMAND ${PZ_PYTHON} -I ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_fmod_shim.py --check
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/..)
set_tests_properties(fmodshim_gen_up_to_date PROPERTIES SKIP_RETURN_CODE 77)

# --- testes da superficie C++ (layout Apple), compilados com clang++ contra a DLL ---
add_executable(test_cxx ${CMAKE_CURRENT_SOURCE_DIR}/../tests/darwin/test_cxx.cpp)
# libc++ estatica no teste: sem libc++.dll no PATH (copia propria, separada da da DLL)
target_link_libraries(test_cxx pzdarwin cxxrt)
add_custom_command(TARGET test_cxx POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_cxx>)
foreach(t string_layout string_ops runtime_error_from_string random_device guard_once sort_ints push_back_many system_clock_now guard_abort cxx_sizes new_delete_cross
         string_extra_ops sort_longlongs ostream_shift_names)
  add_test(NAME cxx_${t} COMMAND test_cxx ${t})
endforeach()
add_test(NAME cxx_import_coverage_all
  COMMAND test_cxx import_coverage_all ${IMPORTS_DIR} ${GEN_DIR}/cxx_selfdefined.txt)

# --- fixtures Mach-O (clang --target=arm64-apple-macos11 -nostdinc + ld.lld -flavor darwin) e Java ---
get_filename_component(PZ_LLVM_BIN ${CMAKE_C_COMPILER} DIRECTORY)
find_program(PZ_CLANG clang HINTS ${PZ_LLVM_BIN} NO_DEFAULT_PATH REQUIRED)
find_program(PZ_LD_LLD ld.lld HINTS ${PZ_LLVM_BIN} NO_DEFAULT_PATH REQUIRED)
set(FIXTURE_DIR ${CMAKE_BINARY_DIR}/fixtures)
set(FIXTURE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../tests/fixtures)
set(PZ_FIXTURES "")
foreach(f IN ITEMS tlv_test.c jnienv_test.cpp throw_test.cpp)
  get_filename_component(fn ${f} NAME_WE)
  add_custom_command(
    OUTPUT ${FIXTURE_DIR}/${fn}.dylib
    COMMAND ${CMAKE_COMMAND} -E make_directory ${FIXTURE_DIR}
    COMMAND ${PZ_CLANG} --target=arm64-apple-macos11 -nostdinc -O1 -ffixed-x18 -fno-stack-protector -fno-sized-deallocation
            -c ${FIXTURE_SRC}/${f} -o ${FIXTURE_DIR}/${fn}.o
    COMMAND ${PZ_LD_LLD} -flavor darwin -arch arm64 -platform_version macos 11.0 11.0 -dylib
            -undefined dynamic_lookup -o ${FIXTURE_DIR}/${fn}.dylib ${FIXTURE_DIR}/${fn}.o
    DEPENDS ${FIXTURE_SRC}/${f}
    COMMENT "Fixture Mach-O ${fn}.dylib")
  list(APPEND PZ_FIXTURES ${FIXTURE_DIR}/${fn}.dylib)
endforeach()
# Dependencias de dylib: o carregador aceita so @rpath/libfmod[studio].dylib (resolvidas pelo resolvedor externo).
function(pz_dep_fixture STUB_C STUB_NAME STUB_INSTALL DEP_C DEP_NAME)
  add_custom_command(
    OUTPUT ${FIXTURE_DIR}/deps/lib${STUB_NAME}.dylib ${FIXTURE_DIR}/${DEP_NAME}.dylib
    COMMAND ${CMAKE_COMMAND} -E make_directory ${FIXTURE_DIR}/deps
    COMMAND ${PZ_CLANG} --target=arm64-apple-macos11 -nostdinc -O1 -ffixed-x18 -fno-stack-protector
            -c ${FIXTURE_SRC}/${STUB_C} -o ${FIXTURE_DIR}/deps/${STUB_NAME}.o
    COMMAND ${PZ_LD_LLD} -flavor darwin -arch arm64 -platform_version macos 11.0 11.0 -dylib
            -install_name ${STUB_INSTALL} -undefined dynamic_lookup
            -o ${FIXTURE_DIR}/deps/lib${STUB_NAME}.dylib ${FIXTURE_DIR}/deps/${STUB_NAME}.o
    COMMAND ${PZ_CLANG} --target=arm64-apple-macos11 -nostdinc -O1 -ffixed-x18 -fno-stack-protector
            -c ${FIXTURE_SRC}/${DEP_C} -o ${FIXTURE_DIR}/${DEP_NAME}.o
    COMMAND ${PZ_LD_LLD} -flavor darwin -arch arm64 -platform_version macos 11.0 11.0 -dylib
            -undefined dynamic_lookup -L${FIXTURE_DIR}/deps -l${STUB_NAME}
            -o ${FIXTURE_DIR}/${DEP_NAME}.dylib ${FIXTURE_DIR}/${DEP_NAME}.o
    DEPENDS ${FIXTURE_SRC}/${STUB_C} ${FIXTURE_SRC}/${DEP_C}
    COMMENT "Fixture Mach-O ${DEP_NAME}.dylib (depende de ${STUB_INSTALL})")
  set(PZ_FIXTURES ${PZ_FIXTURES} ${FIXTURE_DIR}/${DEP_NAME}.dylib PARENT_SCOPE)
endfunction()
pz_dep_fixture(fmod_stub.c fmod @rpath/libfmod.dylib fmoddep_test.c fmoddep_test)
pz_dep_fixture(other_stub.c other @rpath/libother.dylib otherdep_test.c otherdep_test)

set(JAVAC ${JDK_DIR}/bin/javac.exe)
add_custom_command(
  OUTPUT ${FIXTURE_DIR}/JniEnvTest.class
  COMMAND ${CMAKE_COMMAND} -E make_directory ${FIXTURE_DIR}
  COMMAND ${JAVAC} -d ${FIXTURE_DIR} ${FIXTURE_SRC}/JniEnvTest.java
  DEPENDS ${FIXTURE_SRC}/JniEnvTest.java
  COMMENT "javac JniEnvTest.java")
add_custom_target(pz_fixtures DEPENDS ${PZ_FIXTURES} ${FIXTURE_DIR}/JniEnvTest.class)

# Dependencias de dylib no carregador (M5 Tarefa 3)
add_dependencies(test_loader pz_fixtures)
foreach(t fmod_dependency_resolved fmod_dependency_needs_resolver other_dependency_rejected)
  add_test(NAME loader_${t} COMMAND test_loader ${t} ${LIGHTING_DYLIB} ${CMAKE_BINARY_DIR})
endforeach()

# --- Tarefa 4: TLS, carga das 5 libs, wrapper JNIEnv ---
set(LOADER_TESTS ${CMAKE_CURRENT_SOURCE_DIR}/../tests/loader)
add_executable(test_tlv ${LOADER_TESTS}/test_tlv.c ${LOADER_TESTS}/tlv_probe.S)
target_link_libraries(test_tlv pzdarwin)
add_dependencies(test_tlv pz_fixtures)
add_custom_command(TARGET test_tlv POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_tlv>)
foreach(t per_thread preserves_registers)
  add_test(NAME tlv_${t} COMMAND test_tlv ${t} ${FIXTURE_DIR})
endforeach()

add_executable(test_all_libs ${LOADER_TESTS}/test_all_libs.c)
target_link_libraries(test_all_libs pzdarwin)
add_custom_command(TARGET test_all_libs POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_all_libs>)
add_test(NAME all_libs_load_each_lib COMMAND test_all_libs load_each_lib ${DYLIB_DIR} ${CMAKE_BINARY_DIR})

add_executable(test_jnienv ${LOADER_TESTS}/test_jnienv.c)
target_include_directories(test_jnienv PRIVATE ${JNI_INC} ${JNI_INC}/win32)
target_link_libraries(test_jnienv pzdarwin)
add_dependencies(test_jnienv pz_fixtures)
add_custom_command(TARGET test_jnienv POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_jnienv>)
add_test(NAME jnienv_wrap COMMAND test_jnienv wrap ${FIXTURE_DIR} ${JDK_DIR})

# --- Tarefa 5: runtime de excecoes C++ das dylibs (throw_test.dylib + desenrolador de compact unwind) ---
add_executable(test_eh ${CMAKE_CURRENT_SOURCE_DIR}/../tests/darwin/test_eh.c)
target_link_libraries(test_eh pzdarwin)
add_dependencies(test_eh pz_fixtures)
add_custom_command(TARGET test_eh POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:pzdarwin> $<TARGET_FILE_DIR:test_eh>)
foreach(t throw_catch_same_frame throw_across_frames cleanup_runs catch_base_class rethrow nested_exception
         catch_all hierarchy ti_can_catch callee_saved_restored uncaught_logs_and_aborts exception_ptr)
  add_test(NAME eh_${t} COMMAND test_eh ${t} ${FIXTURE_DIR})
endforeach()
add_test(NAME eh_real_dylibs_unwind_tables COMMAND test_eh real_dylibs_unwind_tables ${FIXTURE_DIR} ${DYLIB_DIR})

# --- M3 Tarefa 1: DLLs stub ARM64 (rede, Steam, FMOD, Bink, imgui) ---
# Nomes dos exports Java_* vem da DLL x64 do jogo (so leitura); o jar da classe/descritor.
set(PZ_GAME_DIR "C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid" CACHE PATH "pasta do jogo (so leitura)")
set(PZ_STUB_DLLS "")
function(pz_stub_dll LIBNAME)
  set(_dll ${PZ_GAME_DIR}/${LIBNAME}.dll)
  string(MAKE_C_IDENTIFIER ${LIBNAME} _id)
  set(_tgt stub_${_id})
  add_custom_command(
    OUTPUT ${GEN_DIR}/${LIBNAME}_stub.S ${GEN_DIR}/${LIBNAME}_names.h ${GEN_DIR}/${LIBNAME}.def
           ${GEN_DIR}/${LIBNAME}_methods.tsv ${GEN_DIR}/${LIBNAME}_unmatched.txt
    COMMAND ${PZ_PYTHON} -I ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_stub.py
            --lib ${LIBNAME} --dll ${_dll} --jar ${PZ_JAR} --out ${GEN_DIR}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_stub.py ${CMAKE_CURRENT_SOURCE_DIR}/../tools/pe_exports.py
            ${CMAKE_CURRENT_SOURCE_DIR}/../tools/gen_bridge.py ${_dll} ${PZ_JAR}
    COMMENT "Gerando stub de ${LIBNAME}"
    VERBATIM)
  add_library(${_tgt} SHARED stubs/stub_main.c stubs/stub_asm.S
    ${GEN_DIR}/${LIBNAME}_stub.S ${GEN_DIR}/${LIBNAME}.def)
  set_target_properties(${_tgt} PROPERTIES PREFIX "" OUTPUT_NAME ${LIBNAME}
    RUNTIME_OUTPUT_DIRECTORY ${DIST_DIR} LIBRARY_OUTPUT_DIRECTORY ${DIST_DIR})
  target_include_directories(${_tgt} PRIVATE ${GEN_DIR})
  target_compile_definitions(${_tgt} PRIVATE STUB_LIB="${LIBNAME}" STUB_WLIB=L"${LIBNAME}"
    STUB_NAMES_H="${LIBNAME}_names.h")
  target_link_options(${_tgt} PRIVATE -static -static-libgcc)
  list(APPEND PZ_STUB_DLLS ${_tgt})
  set(PZ_STUB_DLLS ${PZ_STUB_DLLS} PARENT_SCOPE)
endfunction()

# DLL vazia: so DllMain, sem exports de jogo.
function(pz_empty_dll LIBNAME)
  string(MAKE_C_IDENTIFIER ${LIBNAME} _id)
  set(_tgt stub_${_id})
  add_library(${_tgt} SHARED stubs/stub_main.c)
  set_target_properties(${_tgt} PROPERTIES PREFIX "" OUTPUT_NAME ${LIBNAME}
    RUNTIME_OUTPUT_DIRECTORY ${DIST_DIR} LIBRARY_OUTPUT_DIRECTORY ${DIST_DIR})
  target_compile_definitions(${_tgt} PRIVATE STUB_EMPTY)
  target_link_options(${_tgt} PRIVATE -static -static-libgcc)
  list(APPEND PZ_STUB_DLLS ${_tgt})
  set(PZ_STUB_DLLS ${PZ_STUB_DLLS} PARENT_SCOPE)
endfunction()

foreach(l ZNetNoSteam64 ZNetJNI64 bink64 imgui-java64)
  pz_stub_dll(${l})
endforeach()
# fmod.dll/fmodstudio.dll NAO sao mais vazias (M5 Tarefa 4): dist_fmod copia as oficiais ARM64 de third-party/fmod.
foreach(l RakNet64 steam_api64 bink2w64)
  pz_empty_dll(${l})
endforeach()
add_custom_target(stubs DEPENDS ${PZ_STUB_DLLS})

add_test(NAME stub_unittest
  COMMAND ${PZ_PYTHON} -I -m unittest discover -s ${CMAKE_CURRENT_SOURCE_DIR}/../tests/tools -t ${CMAKE_CURRENT_SOURCE_DIR}/.. -p test_gen_stub.py
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/..)

# --- M3 Tarefa 3: dist completo (LWJGL ARM64 + manifesto) ---
file(GLOB PZ_LWJGL_DLLS ${CMAKE_CURRENT_SOURCE_DIR}/../spike-java-arm/natives-arm64/*.dll)
add_custom_target(dist_lwjgl
  COMMAND ${CMAKE_COMMAND} -E make_directory ${DIST_DIR}
  COMMAND ${CMAKE_COMMAND} -E copy_if_different ${PZ_LWJGL_DLLS} ${DIST_DIR}
  COMMENT "Copiando DLLs LWJGL ARM64 para dist/")
set(PZ_FMOD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third-party/fmod)
add_custom_target(dist_fmod
  COMMAND ${CMAKE_COMMAND} -E make_directory ${DIST_DIR}
  COMMAND ${CMAKE_COMMAND} -E copy_if_different ${PZ_FMOD_DIR}/fmod.dll ${PZ_FMOD_DIR}/fmodstudio.dll ${DIST_DIR}
  COMMENT "Copiando fmod.dll/fmodstudio.dll ARM64 de third-party/fmod para dist/ (Tarefa 1 do M5)")
add_custom_target(dist_manifest ALL
  COMMAND ${PZ_PYTHON} -I ${CMAKE_CURRENT_SOURCE_DIR}/../tools/make_manifest.py
          --dist ${DIST_DIR} --jar ${PZ_JAR}
  DEPENDS dist_lwjgl dist_fmod bridges stubs pzdarwin
  COMMENT "Gerando dist/manifest.json")

add_test(NAME launcher_unittest
  COMMAND ${PZ_PYTHON} -I -m unittest discover -s ${CMAKE_CURRENT_SOURCE_DIR}/../tests/tools -t ${CMAKE_CURRENT_SOURCE_DIR}/.. -p test_launcher.py
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/..)
