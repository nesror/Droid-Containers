/*
 * libpty.so — App 内容器终端的伪终端桥（JNI）。
 *
 * 职责：openpty + fork + setsid/登录 tty（forkpty）→ 子进程 execv 指定 argv
 * （即 proot 命令行），父进程持有 pty master fd。Kotlin 侧通过读/写/resize/
 * 收尸等入口操作会话。Android toybox 无 `script`（AOSP 裁剪），Java 层又拿不到
 * ioctl，PTY 只能由这里创建。
 *
 * 会话句柄是 malloc 的 session_t 指针（jlong）。生命周期约定：
 *   open → (read* | write* | resize*) → kill → read 返回 EOF 收尾 → close（waitpid 收尸 + 释放）
 */
#include <jni.h>
#include <pty.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <pthread.h>

typedef struct {
    int master;
    pid_t pid;
} session_t;

static char **copy_string_array(JNIEnv *env, jobjectArray arr) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t)n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        out[i] = strdup(utf == NULL ? "" : utf);
        (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
    }
    return out;
}

static void free_string_array(char **arr) {
    if (arr == NULL) return;
    for (size_t i = 0; arr[i] != NULL; i++) free(arr[i]);
    free(arr);
}

static session_t *as_session(jlong handle) {
    return (session_t *)(intptr_t)handle;
}

static void throw_io(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof(msg), "%s: %s", what, strerror(errno));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT jlong JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_open(
        JNIEnv *env, jclass clazz, jint cols, jint rows,
        jobjectArray jargv, jobjectArray jenv) {
    (void)clazz;
    char **argv = copy_string_array(env, jargv);
    char **envp = copy_string_array(env, jenv);
    if (argv == NULL || envp == NULL || argv[0] == NULL) {
        free_string_array(argv);
        free_string_array(envp);
        throw_io(env, "pty open: allocation failed");
        return 0;
    }

    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        free_string_array(argv);
        free_string_array(envp);
        throw_io(env, "forkpty failed");
        return 0;
    }
    if (pid == 0) {
        /* 子进程：master 不关也无妨（exec 后由 CLOEXEC 处理不了，forkpty 无 CLOEXEC），
         * 但显式关掉更干净；slave 已是 0/1/2 与控制终端。 */
        close(master);
        signal(SIGPIPE, SIG_DFL);
        execve(argv[0], argv, envp);
        _exit(127);
    }
    free_string_array(argv);
    free_string_array(envp);

    session_t *s = malloc(sizeof(session_t));
    if (s == NULL) {
        kill(pid, SIGKILL);
        close(master);
        throw_io(env, "pty session allocation failed");
        return 0;
    }
    s->master = master;
    s->pid = pid;
    return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_read(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray jbuf) {
    (void)clazz;
    session_t *s = as_session(handle);
    if (s == NULL) return -1;
    jsize len = (*env)->GetArrayLength(env, jbuf);
    if (len <= 0) return 0;
    char *buf = malloc((size_t)len);
    if (buf == NULL) return -1;
    ssize_t n;
    do {
        n = read(s->master, buf, (size_t)len);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        free(buf);
        /* EIO = slave 侧全部关闭（子进程退出），按 EOF 语义返回 0 */
        if (errno == EIO) return 0;
        return -1;
    }
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize)n, (const jbyte *)buf);
    }
    free(buf);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_write(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray jbuf, jint len) {
    (void)clazz;
    session_t *s = as_session(handle);
    if (s == NULL) return -1;
    if (len <= 0) return 0;
    jsize alen = (*env)->GetArrayLength(env, jbuf);
    if (len > alen) len = alen;
    char *buf = malloc((size_t)len);
    if (buf == NULL) return -1;
    (*env)->GetByteArrayRegion(env, jbuf, 0, len, (jbyte *)buf);

    /* 阻 SIGPIPE 写 pty：对端关闭时 write 得 EPIPE 而不是杀死线程，
     * 零超时 sigtimedwait 顺手消费掉挂起的 SIGPIPE。 */
    sigset_t mask, old;
    sigemptyset(&mask);
    sigaddset(&mask, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &mask, &old);
    ssize_t n;
    do {
        n = write(s->master, buf, (size_t)len);
    } while (n < 0 && errno == EINTR);
    struct timespec zero = {.tv_sec = 0, .tv_nsec = 0};
    sigtimedwait(&mask, NULL, &zero);
    pthread_sigmask(SIG_SETMASK, &old, NULL);
    free(buf);

    if (n < 0) return -1;
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_resize(
        JNIEnv *env, jclass clazz, jlong handle, jint cols, jint rows) {
    (void)env;
    (void)clazz;
    session_t *s = as_session(handle);
    if (s == NULL) return;
    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    /* SIGWINCH 会投递给前台进程组（vim/top 等自适应） */
    ioctl(s->master, TIOCSWINSZ, &ws);
}

/** 非阻塞探测退出码：已退出返回 0-255，未退出返回 -1。 */
JNIEXPORT jint JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_exitCode(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    session_t *s = as_session(handle);
    if (s == NULL) return -1;
    int status = 0;
    pid_t r = waitpid(s->pid, &status, WNOHANG);
    if (r == s->pid) {
        if (WIFEXITED(status)) return WEXITSTATUS(status);
        if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    }
    return -1;
}

/** 杀整个进程组（forkpty 的子进程是会话首进程，pgid = pid）。 */
JNIEXPORT void JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_kill(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    session_t *s = as_session(handle);
    if (s == NULL) return;
    kill(-s->pid, SIGKILL);
    kill(s->pid, SIGKILL);
}

/** 收尾：阻塞 waitpid 收尸（kill 后通常瞬时）→ 关 master → 释放。幂等。 */
JNIEXPORT void JNICALL
Java_cn_yzapp_androidcontainer_core_engine_terminal_PtyJni_close(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    session_t *s = as_session(handle);
    if (s == NULL) return;
    int status = 0;
    pid_t r;
    do {
        r = waitpid(s->pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    close(s->master);
    free(s);
}
