#include <math.h>
#include "dw_internal.h"

static double dw_acos(double x) { return acos(x); }
static double dw_asin(double x) { return asin(x); }
static double dw_atan2(double y, double x) { return atan2(y, x); }
static double dw_sin(double x) { return sin(x); }
static double dw_cos(double x) { return cos(x); }
static double dw_tan(double x) { return tan(x); }
static double dw_pow(double x, double y) { return pow(x, y); }
static float  dw_powf(float x, float y) { return powf(x, y); }
static double dw_fmod(double x, double y) { return fmod(x, y); }

/* Apple: __sincos_stret(double) devolve {sin, cos} em d0/d1 (HFA de dois doubles, igual no AAPCS64). */
typedef struct { double s, c; } dw_sincos;
static dw_sincos dw_sincos_stret(double x) { dw_sincos r = { sin(x), cos(x) }; return r; }

static float dw_acosf(float x) { return acosf(x); }
static float dw_asinf(float x) { return asinf(x); }
static float dw_atanf(float x) { return atanf(x); }
static float dw_atan2f(float y, float x) { return atan2f(y, x); }
static float dw_cosf(float x) { return cosf(x); }
static float dw_tanf(float x) { return tanf(x); }
/* __exp10f(x) = 10^x (a ucrt nao tem exp10f); em double e arredondado para float. */
static float dw_exp10f(float x) { return (float)pow(10.0, (double)x); }
/* Apple: __sincosf_stret(float) devolve {sin, cos} em s0/s1 (HFA de dois floats). */
typedef struct { float s, c; } dw_sincosf;
static dw_sincosf dw_sincosf_stret(float x) { dw_sincosf r = { sinf(x), cosf(x) }; return r; }

static float dw_logf(float x) { return logf(x); }
static float dw_log10f(float x) { return log10f(x); }

const dw_entry dw_math_entries[] = {
  {"logf", (void*)dw_logf}, {"log10f", (void*)dw_log10f},
  {"acosf", (void*)dw_acosf}, {"asinf", (void*)dw_asinf}, {"atanf", (void*)dw_atanf}, {"atan2f", (void*)dw_atan2f},
  {"cosf", (void*)dw_cosf}, {"tanf", (void*)dw_tanf},
  {"__exp10f", (void*)dw_exp10f}, {"___exp10f", (void*)dw_exp10f},
  {"__sincosf_stret", (void*)dw_sincosf_stret}, {"___sincosf_stret", (void*)dw_sincosf_stret},
  {"acos", (void*)dw_acos}, {"asin", (void*)dw_asin}, {"atan2", (void*)dw_atan2},
  {"sin", (void*)dw_sin}, {"cos", (void*)dw_cos}, {"tan", (void*)dw_tan},
  {"pow", (void*)dw_pow}, {"powf", (void*)dw_powf}, {"fmod", (void*)dw_fmod},
  {"__sincos_stret", (void*)dw_sincos_stret}, {"___sincos_stret", (void*)dw_sincos_stret},
  {NULL, NULL}
};
