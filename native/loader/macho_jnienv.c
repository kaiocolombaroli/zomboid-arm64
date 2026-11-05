/*
 * macho_jnienv.c - o JNIEnv embrulhado para dylibs que chamam funcoes JNI variadicas.
 * Portado de zomdroid (MIT), app/src/main/cpp/macho_jnienv.c; ver native/THIRD_PARTY.md e macho_jnienv.h.
 * Tres mecanismos (os dois primeiros abaixo; o terceiro: as entradas variadicas sem sufixo ganham um shim
 * que passa o sp de entrada como va_list a entrada *V do JNIEnv real):
 *
 *  - as entradas comuns ganham um thunk de 4 instrucoes cada, gerado uma vez e instalado RX
 *    (dw_install_code): troca o env pelo real, le a funcao real da tabela real no mesmo indice e
 *    salta. Nenhum C roda, nenhum outro registrador muda.
 *  - as 31 entradas *V sao funcoes C que leem o va_list Apple (ponteiro para slots de 8 bytes, todo
 *    argumento promovido: inteiros a 64 bits, float a double) com a assinatura Java do metodo na mao,
 *    montam um vetor de jvalue e chamam a entrada *A da tabela real.
 */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <jni.h>
#include <jvmti.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "darwin.h"
#include "macho_jnienv.h"

#define LOG_REPORTED(fmt, ...) do { printf(fmt "\n" __VA_OPT__(,) __VA_ARGS__); fflush(stdout); } while (0)

typedef struct { const char* name; int kind; } macho_jni_entry_t;   /* kind: 0 thunk, 1 V shim, 2 reservada */
#include "macho_jni_table.h"

#define JNI_NENTRIES (sizeof(struct JNINativeInterface_) / sizeof(void*))
_Static_assert(sizeof(struct JNINativeInterface_) / sizeof(void*) >= MACHO_JNI_TABLE_ENTRIES,
               "a tabela gerada tem entradas demais para este jni.h");

/* O objeto wrapper. `functions` primeiro, para `(*env)->X` funcionar sem mudar; `real` em +8 e o que os
 * thunks leem; o magic distingue wrapper de env real em unwrap(). */
typedef struct {
    const struct JNINativeInterface_* functions;
    JNIEnv* real;
    uint64_t magic;
} macho_env_wrapper_t;

#define MACHO_ENV_MAGIC 0x564e454f4843414dull   /* "MACHOENV" */

static void* g_table[sizeof(struct JNINativeInterface_) / sizeof(void*)];
static INIT_ONCE g_once = INIT_ONCE_STATIC_INIT;
static int g_table_ok = 0;
static __thread macho_env_wrapper_t t_wrapper;

static macho_jnienv_signature_fn g_signature_fn = NULL;
static jvmtiEnv* g_jvmti = NULL;

void macho_jnienv_set_signature_provider(macho_jnienv_signature_fn fn) {
    g_signature_fn = fn;
}

JNIEnv* macho_jnienv_unwrap(JNIEnv* env) {
    const macho_env_wrapper_t* w = (const macho_env_wrapper_t*)env;
    if (w && (const void*)w->functions == (const void*)g_table) return w->real;
    return env;
}

/* Padrao: assinatura via JVMTI. O JVMTI entrega copias proprias; quem chama guarda o resultado, entao
 * devolvemos uma copia (por thread). */
static const char* jvmti_signature(jmethodID mid) {
    static __thread char copy[1024];
    char* name = NULL;
    char* sig = NULL;
    if (!g_jvmti || !mid) return NULL;
    if ((*g_jvmti)->GetMethodName(g_jvmti, mid, &name, &sig, NULL) != JVMTI_ERROR_NONE) return NULL;
    const char* result = NULL;
    if (sig && strlen(sig) < sizeof(copy)) {
        strcpy(copy, sig);
        result = copy;
    }
    if (name) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)name);
    if (sig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)sig);
    return result;
}

/* --- assinaturas de metodos, em cache por jmethodID --------------------------------------- */

#define SIG_SLOTS 1024
static struct { jmethodID mid; char* sig; } g_sigs[SIG_SLOTS];
static SRWLOCK g_sig_lock = SRWLOCK_INIT;

static const char* signature_for(jmethodID mid) {
    size_t h = (size_t)((((uintptr_t)mid) >> 3) * 0x9E3779B97F4A7C15ull >> 40);
    AcquireSRWLockExclusive(&g_sig_lock);
    for (size_t probe = 0; probe < SIG_SLOTS; probe++) {
        size_t i = (h + probe) & (SIG_SLOTS - 1);
        if (g_sigs[i].mid == mid) { const char* s = g_sigs[i].sig; ReleaseSRWLockExclusive(&g_sig_lock); return s; }
        if (g_sigs[i].mid == NULL) {
            const char* fresh = g_signature_fn ? g_signature_fn(mid) : NULL;
            if (!fresh) { ReleaseSRWLockExclusive(&g_sig_lock); return NULL; }
            g_sigs[i].mid = mid;
            g_sigs[i].sig = strdup(fresh);
            const char* s = g_sigs[i].sig;
            ReleaseSRWLockExclusive(&g_sig_lock);
            return s;
        }
    }
    ReleaseSRWLockExclusive(&g_sig_lock);
    return NULL;   /* tabela cheia: improvavel, o jogo liga algumas centenas de metodos */
}

#define MACHO_JNI_MAX_ARGS 64

/* va_list Apple -> jvalue[]. Devolve o numero de argumentos, ou -1 se a chamada nao pode ser feita. */
static int unpack(jmethodID mid, const void* apple_va, jvalue* out, const char* who) {
    const char* sig = signature_for(mid);
    if (!sig || sig[0] != '(') {
        LOG_REPORTED("[macho] %s: no signature for method %p, call dropped", who, (void*)mid);
        return -1;
    }
    const uint64_t* slot = (const uint64_t*)apple_va;
    int n = 0;
    for (const char* p = sig + 1; *p && *p != ')'; ) {
        if (n == MACHO_JNI_MAX_ARGS) {
            LOG_REPORTED("[macho] %s: too many arguments in %s, call dropped", who, sig);
            return -1;
        }
        uint64_t raw = slot[n];
        switch (*p) {
        case 'Z': out[n].z = (jboolean)(raw & 0xFF); p++; break;
        case 'B': out[n].b = (jbyte)raw; p++; break;
        case 'C': out[n].c = (jchar)raw; p++; break;
        case 'S': out[n].s = (jshort)raw; p++; break;
        case 'I': out[n].i = (jint)raw; p++; break;
        case 'J': out[n].j = (jlong)raw; p++; break;
        case 'F': { double d; memcpy(&d, &raw, 8); out[n].f = (jfloat)d; p++; break; }
        case 'D': { double d; memcpy(&d, &raw, 8); out[n].d = d; p++; break; }
        case 'L':
            out[n].l = (jobject)(uintptr_t)raw;
            while (*p && *p != ';') p++;
            if (*p == ';') p++;
            break;
        case '[':
            out[n].l = (jobject)(uintptr_t)raw;
            while (*p == '[') p++;
            if (*p == 'L') { while (*p && *p != ';') p++; if (*p == ';') p++; }
            else if (*p) p++;
            break;
        default:
            LOG_REPORTED("[macho] %s: unexpected '%c' in %s, call dropped", who, *p, sig);
            return -1;
        }
        n++;
    }
    return n;
}

/* --- as 31 entradas V ------------------------------------------------------------------ */

#define SHIM_INSTANCE(RET, NAME, ZERO)                                                     \
static RET NAME##V_shim(JNIEnv* wenv, jobject obj, jmethodID mid, void* va) {              \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return ZERO;                                    \
    return (*env)->NAME##A(env, obj, mid, a); }
#define SHIM_INSTANCE_VOID(NAME)                                                           \
static void NAME##V_shim(JNIEnv* wenv, jobject obj, jmethodID mid, void* va) {             \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return;                                         \
    (*env)->NAME##A(env, obj, mid, a); }
#define SHIM_NONVIRTUAL(RET, NAME, ZERO)                                                   \
static RET NAME##V_shim(JNIEnv* wenv, jobject obj, jclass cls, jmethodID mid, void* va) {  \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return ZERO;                                    \
    return (*env)->NAME##A(env, obj, cls, mid, a); }
#define SHIM_NONVIRTUAL_VOID(NAME)                                                         \
static void NAME##V_shim(JNIEnv* wenv, jobject obj, jclass cls, jmethodID mid, void* va) { \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return;                                         \
    (*env)->NAME##A(env, obj, cls, mid, a); }
#define SHIM_STATIC(RET, NAME, ZERO)                                                       \
static RET NAME##V_shim(JNIEnv* wenv, jclass cls, jmethodID mid, void* va) {               \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return ZERO;                                    \
    return (*env)->NAME##A(env, cls, mid, a); }
#define SHIM_STATIC_VOID(NAME)                                                             \
static void NAME##V_shim(JNIEnv* wenv, jclass cls, jmethodID mid, void* va) {              \
    JNIEnv* env = macho_jnienv_unwrap(wenv); jvalue a[MACHO_JNI_MAX_ARGS];                 \
    if (unpack(mid, va, a, #NAME "V") < 0) return;                                         \
    (*env)->NAME##A(env, cls, mid, a); }

SHIM_STATIC(jobject, NewObject, NULL)
SHIM_INSTANCE(jobject,  CallObjectMethod,  NULL)
SHIM_INSTANCE(jboolean, CallBooleanMethod, JNI_FALSE)
SHIM_INSTANCE(jbyte,    CallByteMethod,    0)
SHIM_INSTANCE(jchar,    CallCharMethod,    0)
SHIM_INSTANCE(jshort,   CallShortMethod,   0)
SHIM_INSTANCE(jint,     CallIntMethod,     0)
SHIM_INSTANCE(jlong,    CallLongMethod,    0)
SHIM_INSTANCE(jfloat,   CallFloatMethod,   0.0f)
SHIM_INSTANCE(jdouble,  CallDoubleMethod,  0.0)
SHIM_INSTANCE_VOID(CallVoidMethod)
SHIM_NONVIRTUAL(jobject,  CallNonvirtualObjectMethod,  NULL)
SHIM_NONVIRTUAL(jboolean, CallNonvirtualBooleanMethod, JNI_FALSE)
SHIM_NONVIRTUAL(jbyte,    CallNonvirtualByteMethod,    0)
SHIM_NONVIRTUAL(jchar,    CallNonvirtualCharMethod,    0)
SHIM_NONVIRTUAL(jshort,   CallNonvirtualShortMethod,   0)
SHIM_NONVIRTUAL(jint,     CallNonvirtualIntMethod,     0)
SHIM_NONVIRTUAL(jlong,    CallNonvirtualLongMethod,    0)
SHIM_NONVIRTUAL(jfloat,   CallNonvirtualFloatMethod,   0.0f)
SHIM_NONVIRTUAL(jdouble,  CallNonvirtualDoubleMethod,  0.0)
SHIM_NONVIRTUAL_VOID(CallNonvirtualVoidMethod)
SHIM_STATIC(jobject,  CallStaticObjectMethod,  NULL)
SHIM_STATIC(jboolean, CallStaticBooleanMethod, JNI_FALSE)
SHIM_STATIC(jbyte,    CallStaticByteMethod,    0)
SHIM_STATIC(jchar,    CallStaticCharMethod,    0)
SHIM_STATIC(jshort,   CallStaticShortMethod,   0)
SHIM_STATIC(jint,     CallStaticIntMethod,     0)
SHIM_STATIC(jlong,    CallStaticLongMethod,    0)
SHIM_STATIC(jfloat,   CallStaticFloatMethod,   0.0f)
SHIM_STATIC(jdouble,  CallStaticDoubleMethod,  0.0)
SHIM_STATIC_VOID(CallStaticVoidMethod)

#define SHIM_ENTRY(NAME) { #NAME "V", (void*)NAME##V_shim }
static const struct { const char* name; void* fn; } g_vshims[] = {
    SHIM_ENTRY(NewObject),
    SHIM_ENTRY(CallObjectMethod), SHIM_ENTRY(CallBooleanMethod), SHIM_ENTRY(CallByteMethod),
    SHIM_ENTRY(CallCharMethod), SHIM_ENTRY(CallShortMethod), SHIM_ENTRY(CallIntMethod),
    SHIM_ENTRY(CallLongMethod), SHIM_ENTRY(CallFloatMethod), SHIM_ENTRY(CallDoubleMethod),
    SHIM_ENTRY(CallVoidMethod),
    SHIM_ENTRY(CallNonvirtualObjectMethod), SHIM_ENTRY(CallNonvirtualBooleanMethod),
    SHIM_ENTRY(CallNonvirtualByteMethod), SHIM_ENTRY(CallNonvirtualCharMethod),
    SHIM_ENTRY(CallNonvirtualShortMethod), SHIM_ENTRY(CallNonvirtualIntMethod),
    SHIM_ENTRY(CallNonvirtualLongMethod), SHIM_ENTRY(CallNonvirtualFloatMethod),
    SHIM_ENTRY(CallNonvirtualDoubleMethod), SHIM_ENTRY(CallNonvirtualVoidMethod),
    SHIM_ENTRY(CallStaticObjectMethod), SHIM_ENTRY(CallStaticBooleanMethod),
    SHIM_ENTRY(CallStaticByteMethod), SHIM_ENTRY(CallStaticCharMethod),
    SHIM_ENTRY(CallStaticShortMethod), SHIM_ENTRY(CallStaticIntMethod),
    SHIM_ENTRY(CallStaticLongMethod), SHIM_ENTRY(CallStaticFloatMethod),
    SHIM_ENTRY(CallStaticDoubleMethod), SHIM_ENTRY(CallStaticVoidMethod),
};

/* --- JavaVM embrulhado: GetEnv/Attach devolvem envs embrulhados ---------------------------- */

typedef struct macho_vm_wrapper {
    const struct JNIInvokeInterface_* functions;
    JavaVM* real;
    struct macho_vm_wrapper* next;
} macho_vm_wrapper;
static macho_vm_wrapper* g_vms;
static SRWLOCK g_vm_lock = SRWLOCK_INIT;
static JavaVM* real_vm(JavaVM* vm) { return ((macho_vm_wrapper*)vm)->real; }
static jint JNICALL vm_destroy(JavaVM* vm) { JavaVM* r = real_vm(vm); return (*r)->DestroyJavaVM(r); }
static jint JNICALL vm_detach(JavaVM* vm) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->DetachCurrentThread(r);
    if (result == JNI_OK) memset(&t_wrapper, 0, sizeof(t_wrapper));
    return result;
}
static jint JNICALL vm_getenv(JavaVM* vm, void** output, jint version) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->GetEnv(r, output, version);
    /* JVMTI e outras interfaces de GetEnv nao sao objetos JNIEnv. */
    if (result == JNI_OK && ((uint32_t)version >> 16) < 0x3000)
        *output = macho_jnienv_wrap((JNIEnv*)*output);
    return result;
}
static jint JNICALL vm_attach(JavaVM* vm, void** output, void* args) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->AttachCurrentThread(r, output, args);
    if (result == JNI_OK) *output = macho_jnienv_wrap((JNIEnv*)*output);
    return result;
}
static jint JNICALL vm_attach_daemon(JavaVM* vm, void** output, void* args) {
    JavaVM* r = real_vm(vm);
    jint result = (*r)->AttachCurrentThreadAsDaemon(r, output, args);
    if (result == JNI_OK) *output = macho_jnienv_wrap((JNIEnv*)*output);
    return result;
}
static const struct JNIInvokeInterface_ g_vm_table = {
    .DestroyJavaVM = vm_destroy, .AttachCurrentThread = vm_attach,
    .DetachCurrentThread = vm_detach, .GetEnv = vm_getenv,
    .AttachCurrentThreadAsDaemon = vm_attach_daemon
};
JavaVM* macho_javavm_wrap(JavaVM* real) {
    if (!real || *real == &g_vm_table) return real;
    AcquireSRWLockExclusive(&g_vm_lock);
    macho_vm_wrapper* w = g_vms;
    while (w && w->real != real) w = w->next;
    if (!w) {
        w = calloc(1, sizeof(*w));
        if (w) { w->functions = &g_vm_table; w->real = real; w->next = g_vms; g_vms = w; }
    }
    ReleaseSRWLockExclusive(&g_vm_lock);
    return (JavaVM*)w;
}
static jint JNICALL GetJavaVM_shim(JNIEnv* wrapped, JavaVM** output) {
    JNIEnv* real = macho_jnienv_unwrap(wrapped);
    JavaVM* vm = NULL;
    jint result = (*real)->GetJavaVM(real, &vm);
    if (result != JNI_OK) return result;
    *output = macho_javavm_wrap(vm);
    return *output ? JNI_OK : JNI_ENOMEM;
}

/* --- a tabela: thunks para o resto --------------------------------------------------------- */

static int entry_is_thunk(size_t i) {
    const macho_jni_entry_t* e = i < MACHO_JNI_TABLE_ENTRIES ? &macho_jni_table[i] : NULL;
    return !(e && (e->kind == 2 || e->kind == 1 || strcmp(e->name, "GetJavaVM") == 0));
}

/* Entradas variadicas sem sufixo (NewObject, Call*Method, CallStatic*Method, CallNonvirtual*Method):
 * o Apple passa os argumentos variaveis na pilha, em slots de 8 bytes (float promovido a double), e o
 * va_list do Windows ARM64 e um ponteiro para slots identicos; entao o sp de entrada JA E o va_list.
 * Retorna o indice da entrada *V correspondente (que vem logo depois), ou -1 se nao for variadica. */
static int variadic_v_index(size_t i) {
    if (i + 1 >= MACHO_JNI_TABLE_ENTRIES) return -1;
    const char* n = macho_jni_table[i].name;
    size_t len = strlen(n);
    if (macho_jni_table[i].kind != 0) return -1;
    if (strcmp(n, "NewObject") != 0 && !(strncmp(n, "Call", 4) == 0 && len > 6 && strcmp(n + len - 6, "Method") == 0))
        return -1;
    const char* v = macho_jni_table[i + 1].name;
    if (strncmp(v, n, len) != 0 || strcmp(v + len, "V") != 0) return -1;
    return (int)i + 1;
}

#define SHIM_INSNS 8   /* por entrada: cabe a variadica (6) e o thunk comum (4) */

static BOOL CALLBACK build_table(PINIT_ONCE once, PVOID param, PVOID* ctx) {
    (void)once; (void)ctx;
    JNIEnv* real = (JNIEnv*)param;
    if (!g_signature_fn) {
        JavaVM* vm = NULL;
        void* jvmti = NULL;
        if ((*real)->GetJavaVM(real, &vm) == JNI_OK && (*vm)->GetEnv(vm, &jvmti, JVMTI_VERSION_1_2) == JNI_OK) {
            g_jvmti = (jvmtiEnv*)jvmti;
            g_signature_fn = jvmti_signature;
        } else {
            LOG_REPORTED("[macho] JNIEnv wrapper: no JVMTI, variadic calls through *V entries will be dropped");
        }
    }
    static uint32_t code[(sizeof(struct JNINativeInterface_) / sizeof(void*)) * SHIM_INSNS];
    for (size_t i = 0; i < JNI_NENTRIES; i++) {
        const macho_jni_entry_t* e = i < MACHO_JNI_TABLE_ENTRIES ? &macho_jni_table[i] : NULL;
        if (e && e->kind == 2) { g_table[i] = NULL; continue; }
        if (e && strcmp(e->name, "GetJavaVM") == 0) { g_table[i] = (void*)GetJavaVM_shim; continue; }
        if (e && e->kind == 1) {
            void* fn = NULL;
            for (size_t k = 0; k < sizeof(g_vshims) / sizeof(g_vshims[0]); k++)
                if (strcmp(g_vshims[k].name, e->name) == 0) { fn = g_vshims[k].fn; break; }
            if (!fn) { LOG_REPORTED("[macho] JNIEnv wrapper: no shim for %s", e->name); return FALSE; }
            g_table[i] = fn;
            continue;
        }
        uint32_t* t = code + i * SHIM_INSNS;
        int vi = variadic_v_index(i);
        if (vi >= 0) {
            /* ldr x0,[x0,#8] ; add xN,sp,#0 ; ldr x16,[x0] ; ldr x16,[x16,#vi*8] ; br x16
             * N = 3 (env,cls|obj,mid fixos) ou 4 em CallNonvirtual* (env,obj,cls,mid). */
            unsigned reg = strstr(e->name, "Nonvirtual") ? 4 : 3;
            t[0] = 0xF9400400u;
            t[1] = 0x910003E0u | reg;
            t[2] = 0xF9400010u;
            t[3] = 0xF9400210u | ((uint32_t)vi << 10);
            t[4] = 0xD61F0200u;
            t[5] = 0xD503201Fu;   /* nop */
        } else {
            /* ldr x0, [x0, #8] ; ldr x16, [x0] ; ldr x16, [x16, #i*8] ; br x16   (imm12 = i, escala 8) */
            t[0] = 0xF9400400u;
            t[1] = 0xF9400010u;
            t[2] = 0xF9400210u | ((uint32_t)i << 10);
            t[3] = 0xD61F0200u;
        }
    }
    uint32_t* mem = dw_install_code(code, sizeof(code));
    if (!mem) { LOG_REPORTED("[macho] JNIEnv wrapper: no executable page"); return FALSE; }
    for (size_t i = 0; i < JNI_NENTRIES; i++)
        if (entry_is_thunk(i)) g_table[i] = (void*)(mem + i * SHIM_INSNS);
    g_table_ok = 1;
    return TRUE;
}

JNIEnv* macho_jnienv_wrap(JNIEnv* real) {
    if (!real) return NULL;
    if ((const void*)*real == (const void*)g_table) return real;
    if (!InitOnceExecuteOnce(&g_once, build_table, real, NULL) || !g_table_ok) return NULL;
    t_wrapper.functions = (const struct JNINativeInterface_*)g_table;
    t_wrapper.real = real;
    t_wrapper.magic = MACHO_ENV_MAGIC;
    return (JNIEnv*)&t_wrapper;
}

int macho_jnienv_ready(void) {
    return g_table_ok;
}
