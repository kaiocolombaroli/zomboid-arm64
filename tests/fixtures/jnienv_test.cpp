// Fixture Mach-O (arm64-apple-macos11, sem headers): reproduz o wrapper inline JNIEnv_ de jni.h, que
// faz va_start (va_list Apple) e chama a entrada *V da tabela de funcoes. O simbolo _ZN7JNIEnv_...
// exportado faz macho_lib_wraps_env() ser verdadeiro, como nas dylibs reais.
typedef void* jclass;
typedef void* jmethodID;
struct JNINativeInterface_ { void* fn[235]; };

// indice de CallStaticIntMethodV na tabela JNI (macho_jni_table.h)
enum { kCallStaticIntMethodV = 130 };

struct JNIEnv_ {
  const JNINativeInterface_* functions;
  __attribute__((used, noinline, visibility("default")))
  int CallStaticIntMethod(jclass cls, jmethodID mid, ...) {
    __builtin_va_list ap;
    __builtin_va_start(ap, mid);
    typedef int (*fn_t)(JNIEnv_*, jclass, jmethodID, __builtin_va_list);
    int r = reinterpret_cast<fn_t>(functions->fn[kCallStaticIntMethodV])(this, cls, mid, ap);
    __builtin_va_end(ap);
    return r;
  }
};

// JNI nativa: (env, cls, mid, int a, long b, float c, double d) -> env->CallStaticIntMethod(cls, mid, a, b, c, d)
extern "C" __attribute__((visibility("default")))
int jnienv_test_call(JNIEnv_* env, jclass cls, jmethodID mid, int a, long b, float c, double d) {
  return env->CallStaticIntMethod(cls, mid, a, b, c, d);
}

// Chamada Apple DIRETA a uma entrada variadica sem sufixo V da tabela (argumentos variaveis na pilha, float
// promovido a double): o env que chega e o wrapper, cuja entrada deve passar o sp como va_list.
enum { kCallStaticIntMethod = 129, kCallNonvirtualIntMethod = 79 };
typedef int (*static_var_fn)(void*, jclass, jmethodID, ...);
typedef int (*nonvirt_var_fn)(void*, void*, jclass, jmethodID, ...);

extern "C" __attribute__((visibility("default")))
int jnienv_test_direct_static(JNIEnv_* env, jclass cls, jmethodID mid, int a, long b, float c, double d) {
  return reinterpret_cast<static_var_fn>(env->functions->fn[kCallStaticIntMethod])(env, cls, mid, a, b, c, d);
}

extern "C" __attribute__((visibility("default")))
int jnienv_test_direct_nonvirtual(JNIEnv_* env, void* obj, jclass cls, jmethodID mid, int a, long b, float c, double d) {
  return reinterpret_cast<nonvirt_var_fn>(env->functions->fn[kCallNonvirtualIntMethod])(env, obj, cls, mid, a, b, c, d);
}
