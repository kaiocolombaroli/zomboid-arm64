/* Wrapper de JNIEnv contra uma JVM real (JNI_CreateJavaVM em jvm.dll do JDK ARM64, no proprio processo).
 * Uso: test_jnienv wrap <pasta de fixtures> <pasta do JDK>
 *
 * Por que uma JVM embutida e nao um harness Java: o que importa e a chamada variadica da dylib chegar
 * ao JNIEnv real por CallStaticIntMethod; no mesmo processo isso e uma chamada de C (sem DLL ponte, sem
 * System.load, sem passos de Java), e o teste controla exatamente o que a dylib recebe. */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <jni.h>
#include "macho_bridge.h"
#include "macho_jnienv.h"
#include "macho_loader.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

typedef jint (JNICALL *create_vm_fn)(JavaVM**, void**, void*);

static int start_jvm(const char* fixtures, const char* jdk, JavaVM** vm, JNIEnv** env) {
  char bin[MAX_PATH * 2], dll[MAX_PATH * 2], path[MAX_PATH * 8];
  snprintf(bin, sizeof bin, "%s\\bin", jdk);
  snprintf(dll, sizeof dll, "%s\\bin\\server\\jvm.dll", jdk);
  const char* old = getenv("PATH");
  snprintf(path, sizeof path, "%s\\bin\\server;%s;%s", jdk, bin, old ? old : "");
  SetEnvironmentVariableA("PATH", path);          /* so neste processo */
  HMODULE m = LoadLibraryExA(dll, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
  if (!m) { fprintf(stderr, "LoadLibrary(%s) falhou: %lu\n", dll, GetLastError()); return 0; }
  create_vm_fn create = (create_vm_fn)(void*)GetProcAddress(m, "JNI_CreateJavaVM");
  if (!create) return 0;
  char cp[MAX_PATH * 2 + 32];
  snprintf(cp, sizeof cp, "-Djava.class.path=%s", fixtures);
  JavaVMOption opt[2] = {{cp, NULL}, {"-Xcheck:jni", NULL}};
  JavaVMInitArgs args = {JNI_VERSION_1_8, 2, opt, JNI_FALSE};
  jint r = create(vm, (void**)env, &args);
  if (r != JNI_OK) { fprintf(stderr, "JNI_CreateJavaVM = %d\n", (int)r); return 0; }
  return 1;
}

typedef jint (*call_fn)(void* env, void* cls, void* mid, jint a, jlong b, jfloat c, jdouble d);

static jint expected(jint a, jlong b, jfloat c, jdouble d) {
  return a + (jint)b * 10 + (jint)(c * 2) * 100 + (jint)(d * 4) * 1000;
}

static int test_wrap(const char* fixtures, const char* jdk) {
  JavaVM* vm = NULL;
  JNIEnv* env = NULL;
  CHECK(start_jvm(fixtures, jdk, &vm, &env));

  jclass cls = (*env)->FindClass(env, "JniEnvTest");
  CHECK(cls != NULL && !(*env)->ExceptionCheck(env));
  jmethodID mid = (*env)->GetStaticMethodID(env, cls, "m", "(IJFD)I");
  CHECK(mid != NULL && !(*env)->ExceptionCheck(env));
  CHECK((*env)->CallStaticIntMethod(env, cls, mid, 3, (jlong)5, 2.5, 1.5) == 6553);   /* o proprio Java, sem wrapper */
  CHECK(!(*env)->ExceptionCheck(env));
  CHECK(expected(3, 5, 2.5f, 1.5) == 6553);

  char path[MAX_PATH * 2];
  snprintf(path, sizeof path, "%s/jnienv_test.dylib", fixtures);
  macho_lib_t* lib = macho_load(path);
  CHECK(lib != NULL);
  CHECK(macho_lib_wraps_env(lib) == 1);         /* exporta JNIEnv_::CallStaticIntMethod */
  void* fn = macho_dlsym(lib, "jnienv_test_call");
  CHECK(fn != NULL);

  /* env, cls, mid, int, long, float, double: so o env precisa trocar */
  call_fn shim = (call_fn)macho_jni_bridge(fn, "pppiIfd", "jnienv_test_call", 1);
  CHECK(shim != NULL && shim != (call_fn)fn);

  jint r1 = shim(env, cls, mid, 3, 5, 2.5f, 1.5);
  CHECK(!(*env)->ExceptionCheck(env));
  printf("jnienv_test_call(3,5,2.5f,1.5) = %d (esperado %d)\n", (int)r1, (int)expected(3, 5, 2.5f, 1.5));
  CHECK(r1 == expected(3, 5, 2.5f, 1.5));
  jint r2 = shim(env, cls, mid, -7, (jlong)(123456789012LL % 1000), -0.75f, 100.25);
  CHECK(!(*env)->ExceptionCheck(env));
  CHECK(r2 == expected(-7, (jlong)(123456789012LL % 1000), -0.75f, 100.25));
  jint r3 = shim(env, cls, mid, 2000000000, (jlong)-4, 1e6f, -2.0);
  CHECK(!(*env)->ExceptionCheck(env));
  CHECK(r3 == expected(2000000000, -4, 1e6f, -2.0));

  /* o wrapper e do tipo certo, repassa o real e o JavaVM embrulhado devolve envs embrulhados */
  JNIEnv* w = macho_jnienv_wrap(env);
  CHECK(w != NULL && w != env && macho_jnienv_unwrap(w) == env && macho_jnienv_wrap(w) == w);
  CHECK((*w)->GetVersion(w) == (*env)->GetVersion(env));
  CHECK((*w)->FindClass(w, "java/lang/String") != NULL);
  JavaVM* wvm = NULL;
  CHECK((*w)->GetJavaVM(w, &wvm) == JNI_OK && wvm != vm && macho_javavm_wrap(vm) == wvm);
  void* e2 = NULL;
  CHECK((*wvm)->GetEnv(wvm, &e2, JNI_VERSION_1_8) == JNI_OK && (JNIEnv*)e2 == w);
  jint r4 = shim(e2, cls, mid, 3, 5, 2.5f, 1.5);   /* env ja embrulhado tambem funciona */
  CHECK(!(*env)->ExceptionCheck(env));
  CHECK(r4 == 6553);

  /* chamada Apple DIRETA as entradas variadicas sem sufixo V (argumentos na pilha) */
  typedef jint (*dstatic_fn)(void*, void*, void*, jint, jlong, jfloat, jdouble);
  typedef jint (*dnonvirt_fn)(void*, void*, void*, void*, jint, jlong, jfloat, jdouble);
  dstatic_fn ds = (dstatic_fn)macho_dlsym(lib, "jnienv_test_direct_static");
  dnonvirt_fn dn = (dnonvirt_fn)macho_dlsym(lib, "jnienv_test_direct_nonvirtual");
  CHECK(ds != NULL && dn != NULL);
  jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
  jmethodID imid = (*env)->GetMethodID(env, cls, "inst", "(IJFD)I");
  CHECK(ctor != NULL && imid != NULL);
  jobject obj = (*env)->NewObject(env, cls, ctor);
  CHECK(obj != NULL && !(*env)->ExceptionCheck(env));
  static const struct { jint a; jlong b; jfloat c; jdouble d; } cases[] = {
    {3, 5, 2.5f, 1.5}, {-7, 789, -0.75f, 100.25}, {2000000000, -4, 1e6f, -2.0}, {0, 0, 0.0f, 0.0}, {1, 7, 0.125f, 3.0}
  };
  for (size_t k = 0; k < sizeof cases / sizeof cases[0]; k++) {
    jint want = expected(cases[k].a, cases[k].b, cases[k].c, cases[k].d);
    jint g1 = ds(w, cls, mid, cases[k].a, cases[k].b, cases[k].c, cases[k].d);
    CHECK(!(*env)->ExceptionCheck(env));
    jint g2 = dn(w, obj, cls, imid, cases[k].a, cases[k].b, cases[k].c, cases[k].d);
    CHECK(!(*env)->ExceptionCheck(env));
    printf("direto caso %zu: static=%d nonvirtual=%d esperado=%d\n", k, (int)g1, (int)g2, (int)want);
    CHECK(g1 == want);
    CHECK(g2 == want);
  }

  /* informativo: a mesma dylib com o env REAL, sem wrapper (o va_list do Windows ARM64 tambem e um
   * ponteiro para slots de 8 bytes). So roda com PZARM_PROBE_NOWRAP=1. */
  const char* probe = getenv("PZARM_PROBE_NOWRAP");
  if (probe && !strcmp(probe, "1")) {
    jint rn = ((call_fn)fn)(env, cls, mid, 3, 5, 2.5f, 1.5);
    printf("SEM wrapper: jnienv_test_call(3,5,2.5f,1.5) = %d\n", (int)rn);
  }
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 4 || strcmp(argv[1], "wrap") != 0) { fputs("uso: test_jnienv wrap <fixtures> <jdk>\n", stderr); return 2; }
  int r = test_wrap(argv[2], argv[3]);
  fflush(stdout);
  if (!r) printf("PASS wrap\n");
  fflush(stdout);
  _exit(r);   /* nao destroi a JVM: o teste termina aqui */
}
