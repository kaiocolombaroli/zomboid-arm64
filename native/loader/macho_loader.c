/*
 * macho_loader.c - mapeia uma dylib macOS arm64 dentro deste processo Windows ARM64.
 * Portado de zomdroid (MIT), app/src/main/cpp/macho_loader.c; ver native/THIRD_PARTY.md.
 *
 * Ordem do trabalho: ler o arquivo, escolher a fatia arm64, percorrer os load commands e recusar
 * o que nao e tratado, reservar uma area para a imagem, copiar os segmentos, aplicar rebase,
 * bind, weak bind e lazy bind (tudo na carga, contra dw_resolve e os exports da propria dylib),
 * reescrever LDAPR se a CPU nao tem RCPC, ajustar protecoes, ler a trie de exports e rodar
 * __mod_init_func. Toda recusa e UMA linha "[macho] <arquivo> rejected: ..." e retorno NULL.
 *
 * TLS (__thread): secoes S_THREAD_LOCAL_* viram descritores dw_tlv (dw_tlv_register). Cada imagem
 * carregada e publicada em macho_image_for_pc (base, tamanho, __unwind_info, __eh_frame, nome).
 *
 * Fora desta versao: runtime de excecoes C++ (unwind tables, __cxa_throw). Volta no M2.
 */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>

#include "darwin.h"
#include "macho_loader.h"

#ifndef PF_ARM_V83_LRCPC_INSTRUCTIONS_AVAILABLE
#define PF_ARM_V83_LRCPC_INSTRUCTIONS_AVAILABLE 45
#endif

/* Tudo em stdout, prefixo [macho]. */
#define LOG_REPORTED(fmt, ...) do {                  \
        printf(fmt "\n" __VA_OPT__(,) __VA_ARGS__);  \
        fflush(stdout);                              \
    } while (0)

static int macho_verbose(void) {
    static int verbose = -1;
    if (verbose < 0) {
        const char* env = getenv("PZARM_NATIVE_VERBOSE");
        verbose = env != NULL && strcmp(env, "1") == 0;
    }
    return verbose;
}

#define LOG_VERBOSE(fmt, ...) do { if (macho_verbose()) LOG_REPORTED(fmt __VA_OPT__(,) __VA_ARGS__); } while (0)

/* ------------------------------------------------------------------------------------------ */
/* Mach-O on-disk structures - only the subset this loader reads. Layouts per Apple's mach-o headers. */

#define FAT_MAGIC_BE        0xCAFEBABEu   /* big-endian on disk */
#define MH_MAGIC_64         0xFEEDFACFu
#define MH_DYLIB            6u
#define CPU_TYPE_ARM64      0x0100000Cu
#define CPU_SUBTYPE_MASK    0x00FFFFFFu
#define CPU_SUBTYPE_ARM64E  2u

#define LC_REQ_DYLD             0x80000000u
#define LC_SEGMENT_64           0x19u
#define LC_LOAD_DYLIB           0x0Cu
#define LC_LOAD_WEAK_DYLIB      (0x18u | LC_REQ_DYLD)
#define LC_REEXPORT_DYLIB       (0x1Fu | LC_REQ_DYLD)
#define LC_LOAD_UPWARD_DYLIB    (0x23u | LC_REQ_DYLD)
#define LC_DYLD_INFO            0x22u
#define LC_DYLD_INFO_ONLY       (0x22u | LC_REQ_DYLD)
#define LC_DYLD_EXPORTS_TRIE    (0x33u | LC_REQ_DYLD)
#define LC_DYLD_CHAINED_FIXUPS  (0x34u | LC_REQ_DYLD)

#define SECTION_TYPE                          0xFFu
#define S_ZEROFILL                            0x01u
#define S_MOD_INIT_FUNC_POINTERS              0x09u
#define S_THREAD_LOCAL_REGULAR                0x11u
#define S_THREAD_LOCAL_ZEROFILL               0x12u
#define S_THREAD_LOCAL_VARIABLES              0x13u
#define S_THREAD_LOCAL_VARIABLE_POINTERS      0x14u
#define S_THREAD_LOCAL_INIT_FUNCTION_POINTERS 0x15u
#define S_INIT_FUNC_OFFSETS                   0x16u
#define S_ATTR_PURE_INSTRUCTIONS              0x80000000u
#define S_ATTR_SOME_INSTRUCTIONS              0x00000400u

#define LC_DATA_IN_CODE         0x29u

#define VM_PROT_READ    1
#define VM_PROT_WRITE   2
#define VM_PROT_EXECUTE 4

typedef struct { uint32_t magic, nfat_arch; } fat_header_t;                       /* big-endian */
typedef struct { uint32_t cputype, cpusubtype, offset, size, align; } fat_arch_t; /* big-endian */

typedef struct {
    uint32_t magic;
    uint32_t cputype, cpusubtype;
    uint32_t filetype, ncmds, sizeofcmds, flags, reserved;
} mach_header_64_t;

typedef struct { uint32_t cmd, cmdsize; } load_command_t;

typedef struct {
    uint32_t cmd, cmdsize;
    char segname[16];
    uint64_t vmaddr, vmsize, fileoff, filesize;
    int32_t maxprot, initprot;
    uint32_t nsects, flags;
} segment_command_64_t;

typedef struct {
    char sectname[16], segname[16];
    uint64_t addr, size;
    uint32_t offset, align, reloff, nreloc, flags, reserved1, reserved2, reserved3;
} section_64_t;

typedef struct {
    uint32_t cmd, cmdsize;
    uint32_t rebase_off, rebase_size;
    uint32_t bind_off, bind_size;
    uint32_t weak_bind_off, weak_bind_size;
    uint32_t lazy_bind_off, lazy_bind_size;
    uint32_t export_off, export_size;
} dyld_info_command_t;

typedef struct {
    uint32_t cmd, cmdsize;
    uint32_t name_off, timestamp, current_version, compat_version;
} dylib_command_t;

/* Rebase opcode stream (dyld). */
#define REBASE_OPCODE_MASK                               0xF0u
#define REBASE_IMMEDIATE_MASK                            0x0Fu
#define REBASE_TYPE_POINTER                              1u
#define REBASE_OPCODE_DONE                               0x00u
#define REBASE_OPCODE_SET_TYPE_IMM                       0x10u
#define REBASE_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB        0x20u
#define REBASE_OPCODE_ADD_ADDR_ULEB                      0x30u
#define REBASE_OPCODE_ADD_ADDR_IMM_SCALED                0x40u
#define REBASE_OPCODE_DO_REBASE_IMM_TIMES                0x50u
#define REBASE_OPCODE_DO_REBASE_ULEB_TIMES               0x60u
#define REBASE_OPCODE_DO_REBASE_ADD_ADDR_ULEB            0x70u
#define REBASE_OPCODE_DO_REBASE_ULEB_TIMES_SKIPPING_ULEB 0x80u

/* Bind opcode stream (dyld), shared by the bind, weak-bind and lazy-bind tables. */
#define BIND_OPCODE_MASK                                 0xF0u
#define BIND_IMMEDIATE_MASK                              0x0Fu
#define BIND_TYPE_POINTER                                1u
#define BIND_OPCODE_DONE                                 0x00u
#define BIND_OPCODE_SET_DYLIB_ORDINAL_IMM                0x10u
#define BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB               0x20u
#define BIND_OPCODE_SET_DYLIB_SPECIAL_IMM                0x30u
#define BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM        0x40u
#define BIND_OPCODE_SET_TYPE_IMM                         0x50u
#define BIND_OPCODE_SET_ADDEND_SLEB                      0x60u
#define BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB          0x70u
#define BIND_OPCODE_ADD_ADDR_ULEB                        0x80u
#define BIND_OPCODE_DO_BIND                              0x90u
#define BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB                0xA0u
#define BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED          0xB0u
#define BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB     0xC0u
#define BIND_OPCODE_THREADED                             0xD0u

/* Export trie terminal flags. */
#define EXPORT_SYMBOL_FLAGS_KIND_MASK          0x03u
#define EXPORT_SYMBOL_FLAGS_KIND_ABSOLUTE      0x02u
#define EXPORT_SYMBOL_FLAGS_REEXPORT           0x08u
#define EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER  0x10u

/* ------------------------------------------------------------------------------------------ */

#define MACHO_MAX_SEGMENTS   16
#define MACHO_MAX_INIT_SECTS 8
#define MACHO_MAX_CODE_SECTS 8
#define MACHO_MAX_FILE       (64u * 1024u * 1024u)
#define MACHO_MAX_UNRESOLVED 24
#define MACHO_NAME_MAX       1024

typedef struct {
    char name[17];
    uint64_t vmaddr, vmsize, fileoff, filesize;
    int initprot;
} macho_segment_t;

typedef struct {
    const char* name;   /* without the leading underscore, malloc'ed */
    void* addr;
} macho_export_t;

struct macho_lib {
    char name[64];              /* basename, for the log */
    uint8_t* base;              /* start of the mapping = address of the mach header */
    size_t map_size;
    macho_segment_t seg[MACHO_MAX_SEGMENTS];
    int nseg;
    macho_export_t* exports;
    int nexports, export_cap;
    int nbinds;
    int wrap_env;               /* exports the JNIEnv_ C++ wrappers -> needs the wrapper env */
};

typedef struct {
    uint32_t type;      /* S_MOD_INIT_FUNC_POINTERS or S_INIT_FUNC_OFFSETS */
    uint64_t addr, size;
} macho_init_sect_t;

/* Everything the load needs while it is in flight; freed or handed to the lib at the end. */
typedef struct {
    macho_lib_t* lib;
    const uint8_t* slice;       /* the arm64 slice inside the file buffer */
    size_t slice_size;
    const dyld_info_command_t* dyld;
    macho_init_sect_t init[MACHO_MAX_INIT_SECTS];
    int ninit;
    char* unresolved[MACHO_MAX_UNRESOLVED];
    int nunresolved;            /* total, may exceed the names kept */
    struct { uint64_t addr, size; } code[MACHO_MAX_CODE_SECTS];   /* sections holding instructions */
    int ncode;
    uint32_t dic_off, dic_size; /* LC_DATA_IN_CODE table (slice offsets): data inside __text */
    uint64_t unwind_addr, unwind_size;   /* __TEXT,__unwind_info */
    uint64_t eh_addr, eh_size;           /* __TEXT,__eh_frame (so para diagnostico) */
    uint64_t tvars_addr, tvars_size;     /* S_THREAD_LOCAL_VARIABLES: descritores {thunk,key,offset} */
    uint64_t tls_lo, tls_data_end, tls_hi;   /* modelo do bloco TLS: [lo, data_end) iniciais, [data_end, hi) zero */
    int has_tls_data;
    int fmod_dep;               /* depende de @rpath/libfmod[studio].dylib: os FMOD_* vem do resolvedor externo */
} macho_load_ctx_t;

static const char* base_name(const char* path) {
    const char* s = path;
    for (const char* p = path; *p; p++)
        if (*p == '/' || *p == '\\') s = p + 1;
    return s;
}

static uint32_t be32(uint32_t v) { return __builtin_bswap32(v); }

/* ------------------------------------------------------------------------------------------ */
/* Import resolution: a Mach-O import name -> an address in this process.                     */

/* dw_resolve primeiro (as chaves de imports/NOME.txt nao tem o "_" de Darwin), depois os exports da
 * propria dylib: definicoes fracas que ela mesma fornece (statics locais, guardas) chegam pela
 * tabela de weak bind para o dyld poder coalescer entre imagens; aqui ninguem mais as define.
 * Falta = NULL, e quem chama rejeita a carga. */
static macho_extern_resolver_t g_extern_resolver;

void macho_set_extern_resolver(macho_extern_resolver_t fn) { g_extern_resolver = fn; }

static void* macho_resolve_import(macho_load_ctx_t* ctx, const char* macho_name) {
    macho_lib_t* lib = ctx->lib;
    const char* name = (macho_name[0] == '_') ? macho_name + 1 : macho_name;
    void* a = dw_resolve(name);
    if (a) return a;
    /* So quem declarou a dependencia (libfmod/libfmodstudio) consulta o resolvedor externo. */
    if (ctx->fmod_dep && g_extern_resolver) {
        a = g_extern_resolver(name);
        if (a) return a;
    }
    return macho_dlsym(lib, name);
}

/* ------------------------------------------------------------------------------------------ */
/* Bounded LEB128 readers. A malformed stream fails the load instead of running off the end.   */

static int read_uleb(const uint8_t** p, const uint8_t* end, uint64_t* out) {
    uint64_t v = 0;
    int shift = 0;
    while (*p < end) {
        uint8_t b = *(*p)++;
        if (shift < 64) v |= (uint64_t)(b & 0x7F) << shift;
        shift += 7;
        if (!(b & 0x80)) { *out = v; return 1; }
        if (shift > 70) return 0;
    }
    return 0;
}

static int read_sleb(const uint8_t** p, const uint8_t* end, int64_t* out) {
    int64_t v = 0;
    int shift = 0;
    uint8_t b;
    do {
        if (*p >= end) return 0;
        b = *(*p)++;
        if (shift < 64) v |= (int64_t)(b & 0x7F) << shift;
        shift += 7;
        if (shift > 70) return 0;
    } while (b & 0x80);
    if (shift < 64 && (b & 0x40)) v |= -((int64_t)1 << shift);
    *out = v;
    return 1;
}

/* Address inside the mapping for (segment index, offset), or NULL when out of range. */
static uint8_t* macho_seg_addr(macho_lib_t* lib, int seg, uint64_t off, size_t need) {
    if (seg < 0 || seg >= lib->nseg) return NULL;
    macho_segment_t* s = &lib->seg[seg];
    if (off > s->vmsize || need > s->vmsize - off) return NULL;   /* sem soma antes do limite */
    uint64_t target = s->vmaddr + off;
    if (target < s->vmaddr || target + need > s->vmaddr + s->vmsize) return NULL;
    if (target + need > lib->map_size) return NULL;
    return lib->base + target;                  /* __TEXT is at vmaddr 0, so vmaddr == offset */
}

/* ------------------------------------------------------------------------------------------ */
/* Rebase: add the slide to every absolute pointer the image holds.                           */

static const char* macho_apply_rebase(macho_lib_t* lib, const uint8_t* p, const uint8_t* end) {
    uint32_t type = 0;
    int seg = -1;
    uint64_t off = 0;
    uint64_t slide = (uint64_t)(uintptr_t)lib->base;

#define REBASE_ONE() do {                                                        \
        uint8_t* a = macho_seg_addr(lib, seg, off, 8);                           \
        if (!a) return "rebase target outside its segment";                      \
        if (type != REBASE_TYPE_POINTER) return "rebase type other than pointer"; \
        *(uint64_t*)a += slide;                                                  \
    } while (0)

    while (p < end) {
        uint8_t b = *p++;
        uint8_t imm = b & REBASE_IMMEDIATE_MASK;
        uint64_t u1, u2;
        switch (b & REBASE_OPCODE_MASK) {
        case REBASE_OPCODE_DONE:
            return NULL;
        case REBASE_OPCODE_SET_TYPE_IMM:
            type = imm;
            break;
        case REBASE_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB:
            seg = imm;
            if (!read_uleb(&p, end, &off)) return "truncated rebase stream";
            break;
        case REBASE_OPCODE_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated rebase stream";
            off += u1;
            break;
        case REBASE_OPCODE_ADD_ADDR_IMM_SCALED:
            off += (uint64_t)imm * 8;
            break;
        case REBASE_OPCODE_DO_REBASE_IMM_TIMES:
            for (uint32_t i = 0; i < imm; i++) { REBASE_ONE(); off += 8; }
            break;
        case REBASE_OPCODE_DO_REBASE_ULEB_TIMES:
            if (!read_uleb(&p, end, &u1)) return "truncated rebase stream";
            for (uint64_t i = 0; i < u1; i++) { REBASE_ONE(); off += 8; }
            break;
        case REBASE_OPCODE_DO_REBASE_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated rebase stream";
            REBASE_ONE();
            off += u1 + 8;
            break;
        case REBASE_OPCODE_DO_REBASE_ULEB_TIMES_SKIPPING_ULEB:
            if (!read_uleb(&p, end, &u1) || !read_uleb(&p, end, &u2)) return "truncated rebase stream";
            for (uint64_t i = 0; i < u1; i++) { REBASE_ONE(); off += 8 + u2; }
            break;
        default:
            return "unknown rebase opcode";
        }
    }
#undef REBASE_ONE
    return NULL;
}

/* ------------------------------------------------------------------------------------------ */
/* Bind: point every import slot at the matching dw_resolve / own export.               */

static void macho_note_unresolved(macho_load_ctx_t* ctx, const char* name) {
    if (ctx->nunresolved < MACHO_MAX_UNRESOLVED) ctx->unresolved[ctx->nunresolved] = strdup(name);
    ctx->nunresolved++;
}

static const char* macho_apply_binds(macho_load_ctx_t* ctx, const uint8_t* p, const uint8_t* end,
                                     int lazy) {
    macho_lib_t* lib = ctx->lib;
    uint32_t type = BIND_TYPE_POINTER;
    int seg = -1;
    uint64_t off = 0;
    int64_t addend = 0;
    const char* sym = NULL;
    void* sym_addr = NULL;
    int sym_missing = 0;

#define BIND_ONE() do {                                                          \
        uint8_t* a = macho_seg_addr(lib, seg, off, 8);                           \
        if (!a) return "bind target outside its segment";                        \
        if (type != BIND_TYPE_POINTER) return "bind type other than pointer";    \
        if (!sym) return "bind without a symbol";                                \
        if (!sym_missing) *(uint64_t*)a = (uint64_t)(uintptr_t)sym_addr + (uint64_t)addend; \
        lib->nbinds++;                                                           \
    } while (0)

    while (p < end) {
        uint8_t b = *p++;
        uint8_t imm = b & BIND_IMMEDIATE_MASK;
        uint64_t u1, u2;
        switch (b & BIND_OPCODE_MASK) {
        case BIND_OPCODE_DONE:
            /* Ends the table - except in the lazy one, where it separates entries. */
            if (!lazy) return NULL;
            break;
        case BIND_OPCODE_SET_DYLIB_ORDINAL_IMM:
        case BIND_OPCODE_SET_DYLIB_SPECIAL_IMM:
            break;  /* which Apple library it came from does not matter here */
        case BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated bind stream";
            break;
        case BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM: {
            sym = (const char*)p;
            while (p < end && *p) p++;
            if (p >= end) return "unterminated symbol name in bind stream";
            p++;
            sym_addr = macho_resolve_import(ctx, sym);
            sym_missing = (sym_addr == NULL);
            if (sym_missing) macho_note_unresolved(ctx, sym);
            break;
        }
        case BIND_OPCODE_SET_TYPE_IMM:
            type = imm;
            break;
        case BIND_OPCODE_SET_ADDEND_SLEB:
            if (!read_sleb(&p, end, &addend)) return "truncated bind stream";
            break;
        case BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB:
            seg = imm;
            if (!read_uleb(&p, end, &off)) return "truncated bind stream";
            break;
        case BIND_OPCODE_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated bind stream";
            off += u1;
            break;
        case BIND_OPCODE_DO_BIND:
            BIND_ONE();
            off += 8;
            break;
        case BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB:
            if (!read_uleb(&p, end, &u1)) return "truncated bind stream";
            BIND_ONE();
            off += u1 + 8;
            break;
        case BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED:
            BIND_ONE();
            off += (uint64_t)imm * 8 + 8;
            break;
        case BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB:
            if (!read_uleb(&p, end, &u1) || !read_uleb(&p, end, &u2)) return "truncated bind stream";
            for (uint64_t i = 0; i < u1; i++) { BIND_ONE(); off += u2 + 8; }
            break;
        case BIND_OPCODE_THREADED:
            return "threaded (chained) binds";
        default:
            return "unknown bind opcode";
        }
    }
#undef BIND_ONE
    return NULL;
}

/* ------------------------------------------------------------------------------------------ */
/* Export trie -> flat sorted table.                                                          */

static int macho_add_export(macho_lib_t* lib, const char* name, void* addr) {
    if (lib->nexports == lib->export_cap) {
        int cap = lib->export_cap ? lib->export_cap * 2 : 64;
        macho_export_t* n = realloc(lib->exports, (size_t)cap * sizeof(*n));
        if (!n) return 0;
        lib->exports = n;
        lib->export_cap = cap;
    }
    const char* stored = strdup(name[0] == '_' ? name + 1 : name);
    if (!stored) return 0;
    /* The inline JNIEnv_ C++ wrappers are emitted as (weak) exports; their presence means the
     * dylib calls variadic JNI functions with an Apple va_list, which no wrapper env serves here. */
    if (strncmp(stored, "_ZN7JNIEnv_", 11) == 0) lib->wrap_env = 1;
    lib->exports[lib->nexports].name = stored;
    lib->exports[lib->nexports].addr = addr;
    lib->nexports++;
    return 1;
}

static const char* macho_walk_trie(macho_lib_t* lib, const uint8_t* start, const uint8_t* end,
                                   const uint8_t* node, char* name, size_t len, int depth) {
    if (depth > 128 || node < start || node >= end) return "malformed export trie";
    const uint8_t* p = node;
    uint64_t terminal_size;
    if (!read_uleb(&p, end, &terminal_size)) return "malformed export trie";
    const uint8_t* children = p + terminal_size;
    if (children > end) return "malformed export trie";

    if (terminal_size) {
        uint64_t flags, offset;
        if (!read_uleb(&p, end, &flags)) return "malformed export trie";
        if (flags & EXPORT_SYMBOL_FLAGS_REEXPORT) {
            /* A symbol that lives in another dylib. Nothing here needs one; skip it. */
        } else {
            if (!read_uleb(&p, end, &offset)) return "malformed export trie";
            if (flags & EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER) {
                /* Runtime-chosen implementation; not used by these files. Skip. */
            } else {
                void* addr = ((flags & EXPORT_SYMBOL_FLAGS_KIND_MASK) == EXPORT_SYMBOL_FLAGS_KIND_ABSOLUTE)
                             ? (void*)(uintptr_t)offset : (void*)(lib->base + offset);
                if (offset >= lib->map_size && (flags & EXPORT_SYMBOL_FLAGS_KIND_MASK) != EXPORT_SYMBOL_FLAGS_KIND_ABSOLUTE)
                    return "export outside the image";
                name[len] = '\0';
                if (!macho_add_export(lib, name, addr)) return "out of memory";
            }
        }
    }

    p = children;
    if (p >= end) return "malformed export trie";
    uint8_t nchildren = *p++;
    for (uint8_t i = 0; i < nchildren; i++) {
        size_t l = len;
        while (p < end && *p) {
            if (l + 1 >= MACHO_NAME_MAX) return "export name too long";
            name[l++] = (char)*p++;
        }
        if (p >= end) return "malformed export trie";
        p++;
        uint64_t child_off;
        if (!read_uleb(&p, end, &child_off)) return "malformed export trie";
        const char* err = macho_walk_trie(lib, start, end, start + child_off, name, l, depth + 1);
        if (err) return err;
    }
    return NULL;
}

static int macho_export_cmp(const void* a, const void* b) {
    return strcmp(((const macho_export_t*)a)->name, ((const macho_export_t*)b)->name);
}

void* macho_dlsym(macho_lib_t* lib, const char* name) {
    if (!lib || !name || !lib->nexports) return NULL;
    macho_export_t key = { name, NULL };
    macho_export_t* e = bsearch(&key, lib->exports, (size_t)lib->nexports, sizeof(*e), macho_export_cmp);
    return e ? e->addr : NULL;
}

int macho_lib_wraps_env(const macho_lib_t* lib) {
    return lib ? lib->wrap_env : 0;
}

const char* macho_lib_name(const macho_lib_t* lib) {
    return lib ? lib->name : "?";
}

/* ------------------------------------------------------------------------------------------ */
/* Load commands: collect segments, the dyld info, the init sections; refuse the unsupported. */

static const char* macho_parse_commands(macho_load_ctx_t* ctx, const mach_header_64_t* mh) {
    macho_lib_t* lib = ctx->lib;
    const uint8_t* p = (const uint8_t*)(mh + 1);
    const uint8_t* end = ctx->slice + ctx->slice_size;
    if ((size_t)mh->sizeofcmds > (size_t)(end - p)) return "load commands run past the slice";
    end = p + mh->sizeofcmds;

    for (uint32_t i = 0; i < mh->ncmds; i++) {
        if (p + sizeof(load_command_t) > end) return "truncated load commands";
        const load_command_t* lc = (const load_command_t*)p;
        if (lc->cmdsize < sizeof(load_command_t) || p + lc->cmdsize > end) return "bad load command size";

        switch (lc->cmd) {
        case 0x2: { /* LC_SYMTAB: C++ JNIEnv helpers are usually LOCAL, not trie exports. */
            if (lc->cmdsize < 24) return "bad symbol table command";
            uint32_t fields[4];
            memcpy(fields, p + 8, sizeof(fields));
            uint64_t symoff = fields[0], nsyms = fields[1], stroff = fields[2], strsize = fields[3];
            if (symoff > ctx->slice_size || nsyms > (ctx->slice_size - symoff) / 16
                    || stroff > ctx->slice_size || strsize > ctx->slice_size - stroff)
                return "symbol table outside slice";
            for (uint64_t k = 0; k < nsyms; ++k) {
                const uint8_t* entry = ctx->slice + symoff + k * 16;
                uint32_t strx;
                memcpy(&strx, entry, sizeof(strx));
                if (entry[4] & 0xe0) continue; /* STAB debugging entry */
                if (strx >= strsize) return "bad symbol string index";
                const char* name = (const char*)ctx->slice + stroff + strx;
                size_t available = (size_t)(strsize - strx);
                if (!memchr(name, 0, available)) return "unterminated symbol name";
                if (strncmp(name, "__ZN7JNIEnv_", sizeof("__ZN7JNIEnv_") - 1) == 0)
                    lib->wrap_env = 1;
            }
            break;
        }
        case LC_SEGMENT_64: {
            if (lc->cmdsize < sizeof(segment_command_64_t)) return "bad segment command";
            const segment_command_64_t* sc = (const segment_command_64_t*)p;
            if (lib->nseg == MACHO_MAX_SEGMENTS) return "too many segments";
            macho_segment_t* s = &lib->seg[lib->nseg++];
            memcpy(s->name, sc->segname, 16);
            s->name[16] = '\0';
            s->vmaddr = sc->vmaddr;
            s->vmsize = sc->vmsize;
            s->fileoff = sc->fileoff;
            s->filesize = sc->filesize;
            s->initprot = sc->initprot;
            if (sc->filesize > sc->vmsize) return "segment file size larger than its vm size";
            if (sc->vmaddr > UINT64_MAX - sc->vmsize) return "segment vm range overflows";
            if (sc->fileoff > ctx->slice_size || sc->filesize > ctx->slice_size - sc->fileoff)
                return "segment runs past the slice";
            if ((sc->initprot & VM_PROT_WRITE) && (sc->initprot & VM_PROT_EXECUTE))
                return "segment is both writable and executable";
            if (lc->cmdsize < sizeof(segment_command_64_t) + (size_t)sc->nsects * sizeof(section_64_t))
                return "bad section table";
            const section_64_t* sect = (const section_64_t*)(sc + 1);
            for (uint32_t k = 0; k < sc->nsects; k++, sect++) {
                uint32_t type = sect->flags & SECTION_TYPE;
                if ((sect->flags & (S_ATTR_PURE_INSTRUCTIONS | S_ATTR_SOME_INSTRUCTIONS)) && sect->size) {
                    if (ctx->ncode == MACHO_MAX_CODE_SECTS) return "too many code sections";
                    ctx->code[ctx->ncode].addr = sect->addr;
                    ctx->code[ctx->ncode].size = sect->size;
                    ctx->ncode++;
                }
                if (type == S_THREAD_LOCAL_INIT_FUNCTION_POINTERS)
                    return "thread-local initializer functions";
                if (type == S_THREAD_LOCAL_REGULAR || type == S_THREAD_LOCAL_ZEROFILL) {
                    /* conteudo inicial do bloco por thread: o menor endereco e a origem dos offsets */
                    if (sect->align > 6) return "thread-local section aligned above 64 bytes";
                    uint64_t end = sect->addr + sect->size;
                    if (end < sect->addr) return "thread-local section overflows";
                    if (!ctx->has_tls_data) { ctx->tls_lo = sect->addr; ctx->tls_hi = end; ctx->has_tls_data = 1; }
                    if (sect->addr < ctx->tls_lo) ctx->tls_lo = sect->addr;
                    if (end > ctx->tls_hi) ctx->tls_hi = end;
                    if (type == S_THREAD_LOCAL_REGULAR && end > ctx->tls_data_end) ctx->tls_data_end = end;
                }
                if (type == S_THREAD_LOCAL_VARIABLES) {
                    if (ctx->tvars_size) return "more than one thread-local variables section";
                    ctx->tvars_addr = sect->addr;
                    ctx->tvars_size = sect->size;
                }
                if (strncmp(sect->segname, "__TEXT", 7) == 0) {
                    if (strncmp(sect->sectname, "__unwind_info", 14) == 0) { ctx->unwind_addr = sect->addr; ctx->unwind_size = sect->size; }
                    if (strncmp(sect->sectname, "__eh_frame", 11) == 0) { ctx->eh_addr = sect->addr; ctx->eh_size = sect->size; }
                }
                if (strncmp(sect->sectname, "__objc", 6) == 0 || strncmp(sect->segname, "__OBJC", 6) == 0)
                    return "Objective-C";
                if (type == S_MOD_INIT_FUNC_POINTERS || type == S_INIT_FUNC_OFFSETS) {
                    if (ctx->ninit == MACHO_MAX_INIT_SECTS) return "too many init sections";
                    ctx->init[ctx->ninit].type = type;
                    ctx->init[ctx->ninit].addr = sect->addr;
                    ctx->init[ctx->ninit].size = sect->size;
                    ctx->ninit++;
                }
            }
            break;
        }
        case LC_DYLD_INFO:
        case LC_DYLD_INFO_ONLY:
            if (lc->cmdsize < sizeof(dyld_info_command_t)) return "bad dyld info command";
            ctx->dyld = (const dyld_info_command_t*)p;
            break;
        case LC_DYLD_CHAINED_FIXUPS:
            return "chained fixups (newer linker output than this loader reads)";
        case LC_DATA_IN_CODE: {
            if (lc->cmdsize < 16) return "bad data-in-code command";
            uint32_t f[2];
            memcpy(f, p + 8, sizeof(f));
            if (f[0] > ctx->slice_size || f[1] > ctx->slice_size - f[0] || f[1] % 8)
                return "data-in-code table outside the slice";
            ctx->dic_off = f[0];
            ctx->dic_size = f[1];
            break;
        }
        case LC_LOAD_DYLIB:
        case LC_LOAD_WEAK_DYLIB:
        case LC_REEXPORT_DYLIB:
        case LC_LOAD_UPWARD_DYLIB: {
            if (lc->cmdsize < sizeof(dylib_command_t)) return "bad dylib command";
            const dylib_command_t* dc = (const dylib_command_t*)p;
            if (dc->name_off >= lc->cmdsize) return "bad dylib name";
            const char* dep = (const char*)p + dc->name_off;
            size_t maxlen = lc->cmdsize - dc->name_off;
            if (strnlen(dep, maxlen) == maxlen) return "unterminated dylib name";
            if (strcmp(dep, "@rpath/libfmod.dylib") == 0 || strcmp(dep, "@rpath/libfmodstudio.dylib") == 0) {
                /* Nada e carregado: os FMOD_* e FMOD::* sao entregues pelo resolvedor externo (ponte FMOD). */
                ctx->fmod_dep = 1;
            } else if (!strstr(dep, "libc++") && !strstr(dep, "libSystem")) {
                static char why[160];
                snprintf(why, sizeof(why), "depends on %s", dep);
                return why;
            }
            break;
        }
        default:
            break;  /* symtab, uuid, build version, code signature, ...: not needed */
        }
        p += lc->cmdsize;
    }
    if (!ctx->dyld) return "no LC_DYLD_INFO (nothing tells us how to relocate it)";
    if (!lib->nseg) return "no segments";
    return NULL;
}

/* ------------------------------------------------------------------------------------------ */

/*
 * Apple built these dylibs for M1-class cores, and the compiler used LDAPR (ARMv8.3 RCPC, a
 * load-acquire with the weaker "processor consistent" ordering) in PopMan and PathFind - four
 * each, measured 2026-09-12. Cortex-A53/A57/A72/A73 do not have it and would die with SIGILL
 * the first time one runs. On such a CPU every LDAPR is rewritten into LDAR: the same load with
 * the same registers and size, and a stronger (sequentially consistent) acquire, which any code
 * written for the weaker one is correct under. Everything else in these files is plain ARMv8.0.
 *
 *   LDAPR{B,H,}  size 111000 101 11111 110000 Rn Rt   mask 0x3FFFFC00 == 0x38BFC000
 *   LDAR{B,H,}   size 001000 110 11111 111111 Rn Rt   = (insn & 0xC00003FF) | 0x08DFFC00
 *
 * Only sections that hold instructions are scanned, and the LC_DATA_IN_CODE ranges (jump tables
 * and literals the linker left inside __text) are skipped, so data that happens to look like an
 * LDAPR is never touched. PZARM_MACHO_FORCE_LDAR=1 applies the rewrite on any CPU (tests).
 */
static int macho_cpu_has_rcpc(void) {
    const char* force = getenv("PZARM_MACHO_FORCE_LDAR");
    if (force && strcmp(force, "1") == 0) return 0;
    return IsProcessorFeaturePresent(PF_ARM_V83_LRCPC_INSTRUCTIONS_AVAILABLE) != 0;
}

static int macho_is_data_in_code(const macho_load_ctx_t* ctx, uint64_t addr) {
    const macho_lib_t* lib = ctx->lib;
    for (uint32_t k = 0; k + 8 <= ctx->dic_size; k += 8) {
        const uint8_t* e = ctx->slice + ctx->dic_off + k;
        uint32_t off;
        uint16_t len;
        memcpy(&off, e, sizeof(off));
        memcpy(&len, e + 4, sizeof(len));
        for (int s = 0; s < lib->nseg; s++) {   /* the table holds file offsets */
            const macho_segment_t* sg = &lib->seg[s];
            if (off < sg->fileoff || off >= sg->fileoff + sg->filesize) continue;
            uint64_t start = sg->vmaddr + (off - sg->fileoff);
            if (addr + 4 > start && addr < start + len) return 1;
            break;
        }
    }
    return 0;
}

static void macho_rewrite_ldapr(macho_load_ctx_t* ctx) {
    macho_lib_t* lib = ctx->lib;
    int rewritten = 0, skipped = 0;
    for (int i = 0; i < ctx->ncode; i++) {
        uint64_t a = (ctx->code[i].addr + 3) & ~(uint64_t)3;
        uint64_t end = ctx->code[i].addr + ctx->code[i].size;
        if (end > lib->map_size || end < ctx->code[i].addr) continue;
        for (; a + 4 <= end; a += 4) {
            uint32_t insn;
            memcpy(&insn, lib->base + a, sizeof(insn));
            if ((insn & 0x3FFFFC00u) != 0x38BFC000u) continue;
            if (macho_is_data_in_code(ctx, a)) { skipped++; continue; }
            insn = (insn & 0xC00003FFu) | 0x08DFFC00u;
            memcpy(lib->base + a, &insn, sizeof(insn));
            LOG_VERBOSE("[macho] %s: LDAPR at +0x%llx -> LDAR", lib->name, (unsigned long long)a);
            rewritten++;
        }
    }
    if (rewritten || skipped)
        LOG_REPORTED("[macho] %s: CPU lacks RCPC, %d LDAPR rewritten to LDAR, %d data words left alone",
                     lib->name, rewritten, skipped);
}

static const char* macho_check_range(const macho_load_ctx_t* ctx, uint32_t off, uint32_t size) {
    if ((uint64_t)off + size > ctx->slice_size) return "dyld info table runs past the slice";
    return NULL;
}

static void macho_run_initializers(macho_load_ctx_t* ctx) {
    macho_lib_t* lib = ctx->lib;
    int ran = 0;
    for (int i = 0; i < ctx->ninit; i++) {
        macho_init_sect_t* s = &ctx->init[i];
        if (s->addr > lib->map_size || s->size > lib->map_size - s->addr) continue;   /* checked at load */
        if (s->type == S_MOD_INIT_FUNC_POINTERS) {
            uint64_t* fp = (uint64_t*)(lib->base + s->addr);   /* rebased already */
            for (uint64_t k = 0; k < s->size / 8; k++, fp++) {
                if (!*fp) continue;
                ((void (*)(int, char**, char**, char**, void*))(uintptr_t)*fp)(0, NULL, NULL, NULL, NULL);
                ran++;
            }
        } else {
            uint32_t* op = (uint32_t*)(lib->base + s->addr);
            for (uint64_t k = 0; k < s->size / 4; k++, op++) {
                if (*op >= lib->map_size) continue;
                ((void (*)(int, char**, char**, char**, void*))(lib->base + *op))(0, NULL, NULL, NULL, NULL);
                ran++;
            }
        }
    }
    if (ran) LOG_REPORTED("[macho] %s: ran %d static initializer(s)", lib->name, ran);
}

/* ------------------------------------------------------------------------------------------ */
/* Imagens carregadas, para o runtime de excecoes achar a imagem de um PC. Escritores (cargas) se
 * serializam no lock; leitores nao usam lock: g_images[i < g_nimages] e imutavel depois de publicado
 * (a contagem sobe com release, a leitura usa acquire) e nenhuma imagem e removida. */
#define MACHO_MAX_IMAGES 32
static macho_image_t g_images[MACHO_MAX_IMAGES];
static const macho_lib_t* g_image_libs[MACHO_MAX_IMAGES];   /* paralelo a g_images, so para logs */
static atomic_int g_nimages;
static SRWLOCK g_images_lock = SRWLOCK_INIT;

const macho_image_t* macho_image_for_pc(uintptr_t pc) {
    int n = atomic_load_explicit(&g_nimages, memory_order_acquire);
    for (int i = 0; i < n; i++) {
        const macho_image_t* im = &g_images[i];
        if (pc >= im->base && pc - im->base < im->size) return im;
    }
    return NULL;
}

int macho_image_count(void) { return atomic_load_explicit(&g_nimages, memory_order_acquire); }
const macho_image_t* macho_image_at(int i) { return (i >= 0 && i < macho_image_count()) ? &g_images[i] : NULL; }

/* Export mais proximo de pc (endereco <= pc) na imagem que o contem. Varredura linear: so em caminho de erro. */
const char* macho_nearest_export(uintptr_t pc) {
    int n = atomic_load_explicit(&g_nimages, memory_order_acquire);
    for (int i = 0; i < n; i++) {
        const macho_image_t* im = &g_images[i];
        if (pc < im->base || pc - im->base >= im->size) continue;
        const macho_lib_t* lib = g_image_libs[i];
        const char* best = NULL;
        uintptr_t best_addr = 0;
        for (int k = 0; lib && k < lib->nexports; k++) {
            uintptr_t a = (uintptr_t)lib->exports[k].addr;
            if (a <= pc && a >= im->base && a >= best_addr) { best = lib->exports[k].name; best_addr = a; }
        }
        return best;
    }
    return NULL;
}

/* 0 se a tabela esta cheia. */
static int macho_publish_image(const macho_load_ctx_t* ctx) {
    const macho_lib_t* lib = ctx->lib;
    int ok = 0;
    AcquireSRWLockExclusive(&g_images_lock);
    int n = atomic_load_explicit(&g_nimages, memory_order_relaxed);
    if (n < MACHO_MAX_IMAGES) {
        macho_image_t* im = &g_images[n];
        g_image_libs[n] = lib;
        im->name = lib->name;
        im->base = (uintptr_t)lib->base;
        im->size = lib->map_size;
        im->unwind_info = ctx->unwind_size ? im->base + ctx->unwind_addr : 0;
        im->unwind_info_size = (size_t)ctx->unwind_size;
        im->eh_frame = ctx->eh_size ? im->base + ctx->eh_addr : 0;
        im->eh_frame_size = (size_t)ctx->eh_size;
        atomic_store_explicit(&g_nimages, n + 1, memory_order_release);
        ok = 1;
    }
    ReleaseSRWLockExclusive(&g_images_lock);
    return ok;
}

static void macho_free_lib(macho_lib_t* lib) {
    if (!lib) return;
    if (lib->base) VirtualFree(lib->base, 0, MEM_RELEASE);
    for (int i = 0; i < lib->nexports; i++) free((void*)lib->exports[i].name);
    free(lib->exports);
    free(lib);
}

static macho_lib_t* macho_reject(macho_load_ctx_t* ctx, uint8_t* file, const char* name,
                                 const char* reason) {
    LOG_REPORTED("[macho] %s rejected: %s", name, reason);
    for (int i = 0; i < ctx->nunresolved && i < MACHO_MAX_UNRESOLVED; i++) free(ctx->unresolved[i]);
    macho_free_lib(ctx->lib);
    free(file);
    return NULL;
}

/* Le o arquivo inteiro; path em UTF-8. NULL e *why preenchido em caso de erro. */
static uint8_t* macho_read_file(const char* path, size_t* out_size, const char** why) {
    int wlen = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (wlen <= 0) { *why = "path is not valid UTF-8"; return NULL; }
    wchar_t* wpath = malloc((size_t)wlen * sizeof(wchar_t));
    if (!wpath) { *why = "out of memory"; return NULL; }
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wpath, wlen);
    HANDLE h = CreateFileW(wpath, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    free(wpath);
    if (h == INVALID_HANDLE_VALUE) { *why = "open failed"; return NULL; }
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(h, &sz) || sz.QuadPart <= 0 || (uint64_t)sz.QuadPart > MACHO_MAX_FILE) {
        CloseHandle(h);
        *why = "unusable file size";
        return NULL;
    }
    size_t size = (size_t)sz.QuadPart;
    uint8_t* buf = malloc(size);
    if (!buf) { CloseHandle(h); *why = "out of memory"; return NULL; }
    size_t got = 0;
    while (got < size) {
        DWORD want = (size - got > (1u << 30)) ? (1u << 30) : (DWORD)(size - got), r = 0;
        if (!ReadFile(h, buf + got, want, &r, NULL) || r == 0) break;
        got += r;
    }
    CloseHandle(h);
    if (got != size) { free(buf); *why = "short read"; return NULL; }
    *out_size = size;
    return buf;
}

static DWORD macho_win_prot(int initprot) {
    if (initprot & VM_PROT_EXECUTE) return PAGE_EXECUTE_READ;   /* W+X is rejected at parse time */
    if (initprot & VM_PROT_WRITE) return PAGE_READWRITE;
    if (initprot & VM_PROT_READ) return PAGE_READONLY;
    return PAGE_NOACCESS;
}

macho_lib_t* macho_load(const char* path) {
    const char* name = base_name(path);
    macho_load_ctx_t ctx;
    memset(&ctx, 0, sizeof(ctx));

    /* --- the file, whole: the dyld tables live in __LINKEDIT and are read from here --- */
    const char* why_file = NULL;
    size_t file_size = 0;
    uint8_t* file = macho_read_file(path, &file_size, &why_file);
    if (!file) { LOG_REPORTED("[macho] %s rejected: %s", name, why_file); return NULL; }

    ctx.lib = calloc(1, sizeof(macho_lib_t));
    if (!ctx.lib) { free(file); LOG_REPORTED("[macho] %s rejected: out of memory", name); return NULL; }
    snprintf(ctx.lib->name, sizeof(ctx.lib->name), "%s", name);

    /* --- the arm64 slice --- */
    ctx.slice = file;
    ctx.slice_size = file_size;
    if (file_size >= sizeof(fat_header_t) && be32(*(const uint32_t*)file) == FAT_MAGIC_BE) {
        const fat_header_t* fh = (const fat_header_t*)file;
        uint32_t n = be32(fh->nfat_arch);
        if (n > 16 || sizeof(fat_header_t) + (size_t)n * sizeof(fat_arch_t) > file_size)
            return macho_reject(&ctx, file, name, "bad fat header");
        const fat_arch_t* fa = (const fat_arch_t*)(fh + 1);
        const uint8_t* slice = NULL;
        size_t slice_size = 0;
        int saw_arm64e = 0;
        for (uint32_t i = 0; i < n; i++, fa++) {
            if (be32(fa->cputype) != CPU_TYPE_ARM64) continue;
            if ((be32(fa->cpusubtype) & CPU_SUBTYPE_MASK) == CPU_SUBTYPE_ARM64E) { saw_arm64e = 1; continue; }
            uint32_t off = be32(fa->offset), sz = be32(fa->size);
            if ((uint64_t)off + sz > file_size) return macho_reject(&ctx, file, name, "arm64 slice runs past the file");
            slice = file + off;
            slice_size = sz;
            break;
        }
        if (!slice) return macho_reject(&ctx, file, name, saw_arm64e ? "arm64e slice only (pointer authentication)" : "no arm64 slice");
        ctx.slice = slice;
        ctx.slice_size = slice_size;
    }

    if (ctx.slice_size < sizeof(mach_header_64_t)) return macho_reject(&ctx, file, name, "too small for a Mach-O header");
    const mach_header_64_t* mh = (const mach_header_64_t*)ctx.slice;
    if (mh->magic != MH_MAGIC_64) return macho_reject(&ctx, file, name, "not a 64-bit Mach-O");
    if (mh->cputype != CPU_TYPE_ARM64) return macho_reject(&ctx, file, name, "not arm64");
    if ((mh->cpusubtype & CPU_SUBTYPE_MASK) == CPU_SUBTYPE_ARM64E) return macho_reject(&ctx, file, name, "arm64e (pointer authentication)");
    if (mh->filetype != MH_DYLIB) return macho_reject(&ctx, file, name, "not a dylib");

    const char* err = macho_parse_commands(&ctx, mh);
    if (err) return macho_reject(&ctx, file, name, err);

    /* --- one mapping for the whole image; __TEXT must sit at vmaddr 0 (it does in a dylib) --- */
    macho_lib_t* lib = ctx.lib;
    uint64_t hi = 0;
    for (int i = 0; i < lib->nseg; i++) {
        if (lib->seg[i].vmsize == 0) continue;
        if (lib->seg[i].vmaddr + lib->seg[i].vmsize > hi) hi = lib->seg[i].vmaddr + lib->seg[i].vmsize;
    }
    if (lib->seg[0].vmaddr != 0 || strcmp(lib->seg[0].name, "__TEXT") != 0)
        return macho_reject(&ctx, file, name, "first segment is not __TEXT at address 0");
    if (hi == 0 || hi > (256u << 20)) return macho_reject(&ctx, file, name, "unreasonable image size");
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    uint64_t page = si.dwPageSize ? si.dwPageSize : 4096;   /* VirtualProtect works in these pages */
    lib->map_size = (size_t)((hi + page - 1) & ~(page - 1));
    void* base = VirtualAlloc(NULL, lib->map_size, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
    if (!base) { lib->base = NULL; return macho_reject(&ctx, file, name, "VirtualAlloc failed"); }
    lib->base = base;
    for (int i = 0; i < lib->nseg; i++) {
        macho_segment_t* s = &lib->seg[i];
        if (!s->filesize || !s->vmsize) continue;
        if (s->vmaddr > lib->map_size || s->filesize > lib->map_size - s->vmaddr)
            return macho_reject(&ctx, file, name, "segment outside the mapping");
        memcpy(lib->base + s->vmaddr, ctx.slice + s->fileoff, (size_t)s->filesize);
    }
    for (int i = 0; i < ctx.ninit; i++)
        if (ctx.init[i].addr > lib->map_size || ctx.init[i].size > lib->map_size - ctx.init[i].addr)
            return macho_reject(&ctx, file, name, "initializer section outside the image");

    const dyld_info_command_t* di = ctx.dyld;
    if ((err = macho_check_range(&ctx, di->rebase_off, di->rebase_size)) ||
        (err = macho_check_range(&ctx, di->bind_off, di->bind_size)) ||
        (err = macho_check_range(&ctx, di->weak_bind_off, di->weak_bind_size)) ||
        (err = macho_check_range(&ctx, di->lazy_bind_off, di->lazy_bind_size)) ||
        (err = macho_check_range(&ctx, di->export_off, di->export_size)))
        return macho_reject(&ctx, file, name, err);

    /* --- exports first: binding may have to fall back to the image's own weak definitions --- */
    {
        char namebuf[MACHO_NAME_MAX];
        const uint8_t* trie = ctx.slice + di->export_off;
        if (di->export_size) {
            err = macho_walk_trie(lib, trie, trie + di->export_size, trie, namebuf, 0, 0);
            if (err) return macho_reject(&ctx, file, name, err);
        }
        if (lib->nexports > 1) qsort(lib->exports, (size_t)lib->nexports, sizeof(*lib->exports), macho_export_cmp);
    }

    /* --- relocate and bind --- */
    err = macho_apply_rebase(lib, ctx.slice + di->rebase_off, ctx.slice + di->rebase_off + di->rebase_size);
    if (err) return macho_reject(&ctx, file, name, err);
    err = macho_apply_binds(&ctx, ctx.slice + di->bind_off, ctx.slice + di->bind_off + di->bind_size, 0);
    if (err) return macho_reject(&ctx, file, name, err);
    err = macho_apply_binds(&ctx, ctx.slice + di->weak_bind_off, ctx.slice + di->weak_bind_off + di->weak_bind_size, 0);
    if (err) return macho_reject(&ctx, file, name, err);
    /* Lazy pointers too, all of them now: nothing in this process can bind them on first use. */
    err = macho_apply_binds(&ctx, ctx.slice + di->lazy_bind_off, ctx.slice + di->lazy_bind_off + di->lazy_bind_size, 1);
    if (err) return macho_reject(&ctx, file, name, err);

    if (ctx.nunresolved) {
        /* A lista completa e o ponto: diz o que dw_resolve ainda nao cobre. */
        char why[1400];
        int n = snprintf(why, sizeof(why), "%d unresolved import(s):", ctx.nunresolved);
        for (int i = 0; i < ctx.nunresolved && i < MACHO_MAX_UNRESOLVED && n < (int)sizeof(why) - 2; i++)
            n += snprintf(why + n, sizeof(why) - (size_t)n, " %s", ctx.unresolved[i]);
        if (ctx.nunresolved > MACHO_MAX_UNRESOLVED && n < (int)sizeof(why) - 4) snprintf(why + n, sizeof(why) - (size_t)n, " ...");
        return macho_reject(&ctx, file, name, why);
    }

    /* --- TLS: valida agora; o registro (que grava nos descritores) so depois de publicar a imagem --- */
    int tls_pending = 0;
    size_t tls_tdata = 0, tls_tbss = 0;
    if (ctx.tvars_size) {
        if (ctx.tvars_size % 24 || ctx.tvars_addr > lib->map_size || ctx.tvars_size > lib->map_size - ctx.tvars_addr)
            return macho_reject(&ctx, file, name, "malformed thread-local variables section");
        if (!ctx.has_tls_data)
            return macho_reject(&ctx, file, name, "thread-local variables without a template section");
        if (ctx.tls_hi > lib->map_size || ctx.tls_data_end > ctx.tls_hi || (ctx.tls_data_end && ctx.tls_data_end < ctx.tls_lo))
            return macho_reject(&ctx, file, name, "thread-local template outside the image");
        size_t tdata = ctx.tls_data_end ? (size_t)(ctx.tls_data_end - ctx.tls_lo) : 0;
        size_t tbss = (size_t)(ctx.tls_hi - ctx.tls_lo) - tdata;
        /* o registro grava nos descritores depois da protecao: precisam estar em segmento gravavel,
         * e cada offset tem de caber no bloco; tudo recusavel fica aqui, antes. */
        int writable = 0;
        for (int i = 0; i < lib->nseg; i++)
            if ((lib->seg[i].initprot & VM_PROT_WRITE) && ctx.tvars_addr >= lib->seg[i].vmaddr &&
                ctx.tvars_size <= lib->seg[i].vmsize && ctx.tvars_addr - lib->seg[i].vmaddr <= lib->seg[i].vmsize - ctx.tvars_size)
                writable = 1;
        if (!writable) return macho_reject(&ctx, file, name, "thread-local variables in a read-only segment");
        for (const uint64_t* d = (const uint64_t*)(lib->base + ctx.tvars_addr);
             (const uint8_t*)d < lib->base + ctx.tvars_addr + ctx.tvars_size; d += 3)
            if (d[2] >= tdata + tbss)
                return macho_reject(&ctx, file, name, "thread-local variable offset outside the template");
        tls_tdata = tdata;
        tls_tbss = tbss;
        tls_pending = 1;
        LOG_VERBOSE("[macho] %s: TLS %llu variable(s), template %zu+%zu bytes", lib->name,
                    (unsigned long long)(ctx.tvars_size / 24), tdata, tbss);
    }

    /* --- ARMv8.3 loads on cores without them, while __TEXT is still writable --- */
    if (!macho_cpu_has_rcpc()) macho_rewrite_ldapr(&ctx);

    /* --- protections, in real pages: what the file asks for, executable ones get the icache flushed --- */
    for (int i = 0; i < lib->nseg; i++) {
        macho_segment_t* s = &lib->seg[i];
        if (!s->vmsize) continue;
        if (s->vmaddr & (page - 1)) return macho_reject(&ctx, file, name, "segment not aligned to the page size");
        uint64_t len = (s->vmsize + page - 1) & ~(page - 1);
        if (len > lib->map_size - s->vmaddr) len = lib->map_size - s->vmaddr;
        DWORD prot = macho_win_prot(s->initprot), oldp;
        if (!VirtualProtect(lib->base + s->vmaddr, (SIZE_T)len, prot, &oldp)) {
            static char why[96];
            snprintf(why, sizeof(why), "VirtualProtect(%s) failed (error %lu)", s->name, GetLastError());
            return macho_reject(&ctx, file, name, why);
        }
        if (s->initprot & VM_PROT_EXECUTE)
            FlushInstructionCache(GetCurrentProcess(), lib->base + s->vmaddr, (SIZE_T)len);
    }

    /* Daqui em diante nada recusa mais: a imagem fica para sempre e o nome em lib->name tambem. */
    if (!macho_publish_image(&ctx)) return macho_reject(&ctx, file, name, "too many loaded images");
    free(file);
    file = NULL;

    /* --- TLS: descritores {thunk,key,offset} -> dw_tlv_get; offsets contam a partir do menor endereco TLS --- */
    if (tls_pending)
        dw_tlv_register(lib->base + ctx.tvars_addr, lib->base + ctx.tvars_addr + ctx.tvars_size,
                        lib->base + ctx.tls_lo, tls_tdata, tls_tbss);

    /* --- static constructors, as dyld would run them, with nothing to hand them --- */
    macho_run_initializers(&ctx);

    LOG_REPORTED("[macho] %s loaded natively: arm64 slice, %d exports, %d binds, %zu KB at %p",
                 lib->name, lib->nexports, lib->nbinds, lib->map_size / 1024, (void*)lib->base);
    return lib;
}
