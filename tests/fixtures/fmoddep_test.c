/* Fixture Mach-O: depende de @rpath/libfmod.dylib e chama FMOD_Test_Add (entregue pelo resolvedor externo). */
int FMOD_Test_Add(int a, int b);
int fmoddep_call(int a) { return FMOD_Test_Add(a, 40); }
