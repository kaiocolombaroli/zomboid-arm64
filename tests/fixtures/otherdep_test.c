/* Fixture Mach-O: depende de @rpath/libother.dylib, dependencia que o carregador deve recusar. */
int Other_Fn(int a);
int otherdep_call(int a) { return Other_Fn(a); }
