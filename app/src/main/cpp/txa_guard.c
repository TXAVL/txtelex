/*
 * txa_guard.c — TxaNativeGuard: "hộp đen" tầng native của TxTelex.
 *
 * Bắt tín hiệu chết (SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP, SIGSYS) trên MỌI luồng
 * của tiến trình (cả luồng của thư viện hệ thống / đồ hoạ / ML Kit), ghi một file dump văn bản:
 *   - tín hiệu + mã con (SEGV_MAPERR…), địa chỉ lỗi, pid/tid, tên luồng, thời điểm
 *   - thanh ghi PC / LR / SP lấy thẳng từ ucontext (đáng tin nhất)
 *   - backtrace (_Unwind_Backtrace) + tên thư viện / hàm / offset (dladdr)
 * rồi trả tín hiệu về handler cũ (debuggerd) ⇒ Android vẫn tạo tombstone như bình thường.
 *
 * Trong handler chỉ dùng hàm async-signal-safe (open/write/close/syscall); định dạng số tự viết,
 * không malloc, không printf. Lần mở app sau, Kotlin (TxaNativeGuard.parseDump) đọc dump.
 */
#include <jni.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <time.h>
#include <stdint.h>
#include <stdlib.h>
#include <ucontext.h>
#include <unwind.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <android/log.h>

#define TAG "TXA/NativeGuard"
#define MAX_FRAMES 48

static const int kSignals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP, SIGSYS };
#define NSIGS ((int)(sizeof(kSignals) / sizeof(kSignals[0])))

static char g_dir[512];
static struct sigaction g_old[NSIGS];
static volatile sig_atomic_t g_busy = 0;
static int g_installed = 0;

/* ---------- ghi số / chuỗi không cấp phát ---------- */

static void w_str(int fd, const char *s) { if (s) write(fd, s, strlen(s)); }

static void w_dec(int fd, long long v) {
    char buf[24]; int i = 23; int neg = v < 0; unsigned long long u = neg ? (unsigned long long)(-v) : (unsigned long long)v;
    buf[i] = 0;
    do { buf[--i] = (char)('0' + (u % 10)); u /= 10; } while (u && i > 1);
    if (neg) buf[--i] = '-';
    w_str(fd, buf + i);
}

static void w_hex(int fd, uintptr_t v) {
    char buf[2 + 16 + 1]; int i = (int)sizeof(buf) - 1; buf[i] = 0;
    do { int d = (int)(v & 0xF); buf[--i] = (char)(d < 10 ? '0' + d : 'a' + d - 10); v >>= 4; } while (v && i > 2);
    buf[--i] = 'x'; buf[--i] = '0';
    w_str(fd, buf + i);
}

static const char *sig_name(int s) {
    switch (s) {
        case SIGSEGV: return "SIGSEGV"; case SIGABRT: return "SIGABRT"; case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE"; case SIGILL: return "SIGILL"; case SIGTRAP: return "SIGTRAP";
        case SIGSYS: return "SIGSYS"; default: return "SIG?";
    }
}

static const char *code_name(int s, int c) {
    if (c == SI_USER) return "SI_USER";
    if (c == SI_TKILL) return "SI_TKILL";
    if (c == SI_QUEUE) return "SI_QUEUE";
    switch (s) {
        case SIGSEGV: return c == SEGV_MAPERR ? "SEGV_MAPERR" : c == SEGV_ACCERR ? "SEGV_ACCERR" : "SEGV_?";
        case SIGBUS:  return c == BUS_ADRALN ? "BUS_ADRALN" : c == BUS_ADRERR ? "BUS_ADRERR" : c == BUS_OBJERR ? "BUS_OBJERR" : "BUS_?";
        case SIGFPE:  return c == FPE_INTDIV ? "FPE_INTDIV" : c == FPE_INTOVF ? "FPE_INTOVF" : c == FPE_FLTDIV ? "FPE_FLTDIV" : "FPE_?";
        case SIGILL:  return c == ILL_ILLOPC ? "ILL_ILLOPC" : c == ILL_ILLOPN ? "ILL_ILLOPN" : c == ILL_PRVOPC ? "ILL_PRVOPC" : "ILL_?";
        default: return "-";
    }
}

/* ---------- backtrace ---------- */

struct bt { uintptr_t pcs[MAX_FRAMES]; int n; };

static _Unwind_Reason_Code on_frame(struct _Unwind_Context *ctx, void *arg) {
    struct bt *b = (struct bt *)arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc && b->n < MAX_FRAMES) b->pcs[b->n++] = pc;
    return b->n >= MAX_FRAMES ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void w_frame(int fd, int idx, const char *label, uintptr_t pc) {
    Dl_info info; memset(&info, 0, sizeof(info));
    w_str(fd, "#"); if (idx < 10) w_str(fd, "0"); w_dec(fd, idx); w_str(fd, " ");
    w_str(fd, label); w_str(fd, " ");
    if (dladdr((void *)pc, &info) && info.dli_fname) {
        w_hex(fd, pc - (uintptr_t)info.dli_fbase); w_str(fd, " "); w_str(fd, info.dli_fname);
        if (info.dli_sname) { w_str(fd, " ("); w_str(fd, info.dli_sname); w_str(fd, "+"); w_dec(fd, (long long)(pc - (uintptr_t)info.dli_saddr)); w_str(fd, ")"); }
    } else {
        w_hex(fd, pc); w_str(fd, " <?>");
    }
    w_str(fd, "\n");
}

static void regs_from(void *uc_, uintptr_t *pc, uintptr_t *lr, uintptr_t *sp) {
    ucontext_t *uc = (ucontext_t *)uc_;
    *pc = *lr = *sp = 0;
    if (!uc) return;
#if defined(__aarch64__)
    *pc = uc->uc_mcontext.pc; *lr = uc->uc_mcontext.regs[30]; *sp = uc->uc_mcontext.sp;
#elif defined(__arm__)
    *pc = uc->uc_mcontext.arm_pc; *lr = uc->uc_mcontext.arm_lr; *sp = uc->uc_mcontext.arm_sp;
#elif defined(__x86_64__)
    *pc = (uintptr_t)uc->uc_mcontext.gregs[REG_RIP]; *sp = (uintptr_t)uc->uc_mcontext.gregs[REG_RSP];
#elif defined(__i386__)
    *pc = (uintptr_t)uc->uc_mcontext.gregs[REG_EIP]; *sp = (uintptr_t)uc->uc_mcontext.gregs[REG_ESP];
#endif
}

/* ---------- handler ---------- */

static int index_of(int sig) { for (int i = 0; i < NSIGS; i++) if (kSignals[i] == sig) return i; return -1; }

static void handler(int sig, siginfo_t *si, void *uc) {
    int idx = index_of(sig);
    if (!g_busy) {
        g_busy = 1;
        struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
        pid_t tid = (pid_t)syscall(SYS_gettid);

        /* đường dẫn: <dir>/native_<epoch>_<tid>.txt — tự ghép, không snprintf */
        char path[600]; size_t p = 0;
        for (const char *s = g_dir; *s && p < 500; s++) path[p++] = *s;
        const char *pre = "/native_"; for (const char *s = pre; *s; s++) path[p++] = *s;
        char num[24]; int k = 23; num[k] = 0; long long v = ts.tv_sec;
        do { num[--k] = (char)('0' + v % 10); v /= 10; } while (v && k > 0);
        for (char *s = num + k; *s; s++) path[p++] = *s;
        path[p++] = '_';
        k = 23; v = tid; do { num[--k] = (char)('0' + v % 10); v /= 10; } while (v && k > 0);
        for (char *s = num + k; *s; s++) path[p++] = *s;
        const char *ext = ".txt"; for (const char *s = ext; *s; s++) path[p++] = *s;
        path[p] = 0;

        int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
        if (fd >= 0) {
            char tname[32]; memset(tname, 0, sizeof(tname)); prctl(PR_GET_NAME, tname, 0, 0, 0);
            uintptr_t pc, lr, sp; regs_from(uc, &pc, &lr, &sp);

            w_str(fd, "TXA-NATIVE v1\n");
            w_str(fd, "signal="); w_dec(fd, sig); w_str(fd, " "); w_str(fd, sig_name(sig)); w_str(fd, "\n");
            w_str(fd, "code="); w_dec(fd, si ? si->si_code : 0); w_str(fd, " "); w_str(fd, code_name(sig, si ? si->si_code : 0)); w_str(fd, "\n");
            w_str(fd, "addr="); w_hex(fd, si ? (uintptr_t)si->si_addr : 0); w_str(fd, "\n");
            w_str(fd, "pid="); w_dec(fd, getpid()); w_str(fd, "\n");
            w_str(fd, "tid="); w_dec(fd, tid); w_str(fd, "\n");
            w_str(fd, "thread="); w_str(fd, tname); w_str(fd, "\n");
            w_str(fd, "time="); w_dec(fd, (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000); w_str(fd, "\n");
#if defined(__aarch64__)
            w_str(fd, "abi=arm64-v8a\n");
#elif defined(__arm__)
            w_str(fd, "abi=armeabi-v7a\n");
#elif defined(__x86_64__)
            w_str(fd, "abi=x86_64\n");
#elif defined(__i386__)
            w_str(fd, "abi=x86\n");
#endif
            w_str(fd, "regs=pc "); w_hex(fd, pc); w_str(fd, " lr "); w_hex(fd, lr); w_str(fd, " sp "); w_hex(fd, sp); w_str(fd, "\n");
            w_str(fd, "--- frames ---\n");
            int n = 0;
            if (pc) w_frame(fd, n++, "pc", pc);
            if (lr) w_frame(fd, n++, "lr", lr);
            struct bt b; b.n = 0;
            _Unwind_Backtrace(on_frame, &b);
            for (int i = 0; i < b.n; i++) w_frame(fd, n++, "bt", b.pcs[i]);
            close(fd);
        }
        __android_log_print(ANDROID_LOG_FATAL, TAG, "Bắt được %s (code %d) tại %p — đã ghi dump", sig_name(sig), si ? si->si_code : 0, si ? si->si_addr : 0);
    }

    /* trả về handler cũ rồi phát lại đúng tín hiệu ⇒ debuggerd tạo tombstone, app thoát như thường */
    if (idx >= 0) sigaction(sig, &g_old[idx], NULL);
    else signal(sig, SIG_DFL);
    if (si) syscall(SYS_rt_tgsigqueueinfo, getpid(), (pid_t)syscall(SYS_gettid), sig, si);
    else raise(sig);
}

/* ---------- JNI ---------- */

JNIEXPORT jboolean JNICALL
Java_com_customkey_vietime_diag_TxaNativeGuard_nativeInstall(JNIEnv *env, jclass clazz, jstring dir) {
    (void)clazz;
    if (g_installed) return JNI_TRUE;
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    if (!d) return JNI_FALSE;
    strncpy(g_dir, d, sizeof(g_dir) - 1);
    (*env)->ReleaseStringUTFChars(env, dir, d);

    /* ngăn xếp phụ cho luồng gọi (tràn stack vẫn ghi được dump) */
    stack_t ss; ss.ss_size = 64 * 1024; ss.ss_sp = malloc(ss.ss_size); ss.ss_flags = 0;
    if (ss.ss_sp) sigaltstack(&ss, NULL);

    struct sigaction sa; memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    int ok = 1;
    for (int i = 0; i < NSIGS; i++) if (sigaction(kSignals[i], &sa, &g_old[i]) != 0) ok = 0;
    g_installed = ok;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Đã cài bộ bắt %d tín hiệu → %s", NSIGS, g_dir);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* Phòng thử nghiệm: cố ý gây crash native để kiểm tra bộ bắt. 0 = SIGSEGV, 1 = SIGABRT, 2 = SIGFPE */
JNIEXPORT void JNICALL
Java_com_customkey_vietime_diag_TxaNativeGuard_nativeCrashTest(JNIEnv *env, jclass clazz, jint kind) {
    (void)env; (void)clazz;
    if (kind == 1) abort();
    if (kind == 2) raise(SIGFPE);
    volatile int *bad = (volatile int *)(uintptr_t)0x10;
    *bad = 0x7A7;
}
