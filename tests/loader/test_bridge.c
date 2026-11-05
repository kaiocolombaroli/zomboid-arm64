#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <windows.h>

#include "macho_bridge.h"

typedef struct {
  uint64_t x[8];
  uint64_t d[8];
  uint8_t stack[128];
} capture_t;

extern capture_t g_capture;
extern void capture_target(void);

#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static int test_direct_when_no_fix(void) {
  void* s = macho_jni_bridge((void*)capture_target, "ppii", "t", 0);
  CHECK(s == (void*)capture_target);
  return 0;
}

typedef void (*fn20f)(void*, void*, float, float, float, float, float, float, float, float,
                      float, float, float, float, float, float, float, float, float, float, float, float);

static int test_twenty_floats_packed(void) {
  void* s = macho_jni_bridge((void*)capture_target, "ppffffffffffffffffffff", "t", 0);
  CHECK(s && s != (void*)capture_target);
  memset(&g_capture, 0, sizeof g_capture);
  ((fn20f)s)(0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
  for (int i = 0; i < 8; i++) {
    float f; memcpy(&f, &g_capture.d[i], 4);
    CHECK(f == (float)(i + 1));
  }
  for (int i = 0; i < 12; i++) {
    float f; memcpy(&f, g_capture.stack + 4 * i, 4);
    CHECK(f == (float)(9 + i));
  }
  return 0;
}

typedef void (*fnmix)(void*, void*, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
                      uint8_t, int32_t, uint64_t);

static int test_mixed_stack_alignment(void) {
  void* s = macho_jni_bridge((void*)capture_target, "ppIIIIIICiI", "t", 0);
  CHECK(s && s != (void*)capture_target);
  memset(&g_capture, 0, sizeof g_capture);
  ((fnmix)s)(0, 0, 1, 2, 3, 4, 5, 6, 0x7B, 0x11223344, 0x0102030405060708ull);
  CHECK(g_capture.stack[0] == 0x7B);
  int32_t i; memcpy(&i, g_capture.stack + 4, 4);
  CHECK(i == 0x11223344);
  uint64_t l; memcpy(&l, g_capture.stack + 8, 8);
  CHECK(l == 0x0102030405060708ull);
  return 0;
}

typedef void (*fnbool)(void*, void*, uint64_t);

static int test_boolean_extended(void) {
  void* s = macho_jni_bridge((void*)capture_target, "ppC", "t", 0);
  CHECK(s && s != (void*)capture_target);
  memset(&g_capture, 0, sizeof g_capture);
  ((fnbool)s)(0, 0, 0xFFFFFF01ull);
  CHECK((uint32_t)g_capture.x[2] == 1);
  return 0;
}

static int test_shim_is_rx(void) {
  void* s = macho_jni_bridge((void*)capture_target, "ppC", "t", 0);
  CHECK(s && s != (void*)capture_target);
  MEMORY_BASIC_INFORMATION mbi;
  CHECK(VirtualQuery(s, &mbi, sizeof mbi) == sizeof mbi);
  CHECK(mbi.Protect == PAGE_EXECUTE_READ);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) { printf("uso: test_bridge <teste>\n"); return 2; }
  const char* n = argv[1];
  if (!strcmp(n, "direct_when_no_fix")) return test_direct_when_no_fix();
  if (!strcmp(n, "twenty_floats_packed")) return test_twenty_floats_packed();
  if (!strcmp(n, "mixed_stack_alignment")) return test_mixed_stack_alignment();
  if (!strcmp(n, "boolean_extended")) return test_boolean_extended();
  if (!strcmp(n, "shim_is_rx")) return test_shim_is_rx();
  printf("teste desconhecido: %s\n", n);
  return 2;
}
