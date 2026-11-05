// Sonda de unwind: so prova que a toolchain gera Mach-O arm64 com __unwind_info,
// __gcc_except_tab e __TEXT. Sem headers da Apple; tudo declarado a mao.
extern "C" {
void* __cxa_allocate_exception(unsigned long);
void __cxa_throw(void*, void*, void (*)(void*));
void* __cxa_begin_catch(void*);
void __cxa_end_catch();
extern void* _ZTIi;  // typeinfo de int
int probe_ext(int);
}

struct Guard {
    int* p;
    ~Guard() { *p += 1; }
};

static int thrower(int x) {
    if (x > 0) {
        void* e = __cxa_allocate_exception(sizeof(int));
        *static_cast<int*>(e) = x;
        __cxa_throw(e, &_ZTIi, nullptr);
    }
    return probe_ext(x);
}

extern "C" int probe_run(int x) {
    int cleaned = 0;
    try {
        Guard g{&cleaned};
        return thrower(x) + 100;
    } catch (int v) {
        return v + cleaned;
    }
}
