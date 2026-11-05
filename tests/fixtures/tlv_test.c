/* Fixture Mach-O (arm64-apple-macos11, sem headers): variaveis __thread, uma com valor inicial e as
 * outras zeradas. Cada thread tem de ver os valores iniciais e a propria copia. */
__thread int tls_init = 42;
__thread int tls_zero;
__thread long tls_arr[4] = {11, 22, 33, 44};
__thread char tls_big[200];

/* out[0]=tls_init out[1]=tls_zero out[2..5]=tls_arr out[6]=tls_big[0] out[7]=tls_big[199] */
void tlv_snapshot(long* out) {
    out[0] = tls_init;
    out[1] = tls_zero;
    for (int i = 0; i < 4; i++) out[2 + i] = tls_arr[i];
    out[6] = tls_big[0];
    out[7] = tls_big[199];
}

void tlv_set(long a, long b) {
    tls_init = (int)a;
    tls_zero = (int)b;
    tls_arr[3] = a + b;
    tls_big[0] = (char)a;
    tls_big[199] = (char)b;
}

int* tlv_addr_init(void) { return &tls_init; }
