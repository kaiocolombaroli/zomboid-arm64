/* Fixture Mach-O: substitui libfmod.dylib so para o linker (install_name @rpath/libfmod.dylib). Nunca e carregada. */
int FMOD_Test_Add(int a, int b) { return a + b; }
