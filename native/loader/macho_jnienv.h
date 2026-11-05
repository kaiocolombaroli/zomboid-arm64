#ifndef PZARM_MACHO_JNIENV_H
#define PZARM_MACHO_JNIENV_H
/*
 * Um JNIEnv que as dylibs macOS podem usar para chamar funcoes JNI variadicas. Portado de
 * zomdroid (MIT), app/src/main/cpp/macho_jnienv.c; ver native/THIRD_PARTY.md.
 *
 * PopMan, PathFind, Clipper e Bullet usam os wrappers C++ JNIEnv_ (env->CallIntMethod(obj, mid, ...)),
 * inline em jni.h: eles fazem va_start e chamam a entrada *V da tabela de funcoes com um va_list.
 * Nao ha garantia de que o va_list Apple (ponteiro para slots de 8 bytes na pilha) seja o que a JVM
 * espera; o wrapper nao depende disso: devolve a dylib um JNIEnv cuja tabela repassa toda entrada ao
 * ambiente real - com o ponteiro real no lugar do wrapper, pois a HotSpot deriva a thread dele -
 * exceto as 31 entradas *V, que leem o va_list Apple pela assinatura Java do metodo e chamam a
 * entrada *A com um vetor de jvalue.
 */
#include <jni.h>

/* Wrapper do env real desta thread; criado no primeiro uso. NULL se a tabela nao pode ser montada. */
JNIEnv* macho_jnienv_wrap(JNIEnv* real);
int macho_jnienv_ready(void);
/* JavaVM cujo GetEnv/AttachCurrentThread devolvem envs embrulhados. */
JavaVM* macho_javavm_wrap(JavaVM* real);

/* O env real por tras de um wrapper (ou o proprio argumento se nao for um dos nossos). */
JNIEnv* macho_jnienv_unwrap(JNIEnv* env);

/* De onde vem a assinatura de um metodo. Por padrao: JVMTI, obtido do JavaVM no primeiro wrap.
 * Deve devolver "(...)R" ou NULL; a string e copiada. */
typedef const char* (*macho_jnienv_signature_fn)(jmethodID mid);
void macho_jnienv_set_signature_provider(macho_jnienv_signature_fn fn);

#endif /* PZARM_MACHO_JNIENV_H */
