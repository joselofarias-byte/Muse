/*
 * v2.x Fase terminal II:Puente PTY autocompilado.
 *
 * Estándar POSIX forkpty(3) Solución,Implementación autoescrita(No se utiliza código de terceros).
 * proporcionar:Crear proceso hijo(pseudoterminal), bloqueando lectura y escritura, esperando la salida del proceso y sincronización del tamaño de la ventana.
 *
 * JNI Corresponde al singleton de Kotlin io.zer0.muse.terminal.Pty.
 * Toda la semántica de socket/fd está desnuda fd,Java Sin reflejo de FileDescriptor——
 * Toda la lectura y escritura se realiza a través de la función nativa de este archivo.,Evite las restricciones de API ocultas.
 */
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>

#define LOG_TAG "MusePty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_READ_CHUNK 8192

/**
 * Cree un subproceso pseudo-terminal.
 * alineación semántica forkpty(3):El primer parámetro es el parámetro de salida. master fd,El valor de retorno es el proceso hijo. pid(0 = Actualmente un proceso hijo).
 * @return extremo principal(master) fd;Devuelve -1 en caso de error. Escritura pid del proceso secundario pidArray[0].
 */
JNIEXPORT jint JNICALL
Java_io_zer0_muse_terminal_Pty_createSubprocess(
        JNIEnv *env, jobject thiz,
        jstring execPath, jstring cwd, jintArray pidArray,
        jstring extraPath, jstring preloadLib) {
    const char *exec_c = (*env)->GetStringUTFChars(env, execPath, NULL);
    const char *cwd_c = (*env)->GetStringUTFChars(env, cwd, NULL);
    const char *extra_c = extraPath ? (*env)->GetStringUTFChars(env, extraPath, NULL) : NULL;
    const char *preload_c = preloadLib ? (*env)->GetStringUTFChars(env, preloadLib, NULL) : NULL;
    if (exec_c == NULL || cwd_c == NULL) {
        if (exec_c) (*env)->ReleaseStringUTFChars(env, execPath, exec_c);
        if (cwd_c) (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        if (extra_c) (*env)->ReleaseStringUTFChars(env, extraPath, extra_c);
        if (preload_c) (*env)->ReleaseStringUTFChars(env, preloadLib, preload_c);
        return -1;
    }

    struct winsize ws = {24, 80, 0, 0};
    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, execPath, exec_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        return -1;
    }

    if (pid == 0) {
        /* proceso hijo:solo haz lo minimo syscall,Luego ejecutivo.*/
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", cwd_c, 1);
        /* P1-B: Terminal PATH agregar tiempo de ejecución bin;Inyectar cuña ejecutiva(Mapeo de nombres + análisis de shebang).*/
        if (extra_c != NULL && extra_c[0] != '\0') {
            char pathbuf[4096];
            snprintf(pathbuf, sizeof(pathbuf), "%s:/system/bin:/system/xbin", extra_c);
            setenv("PATH", pathbuf, 1);
        } else {
            setenv("PATH", "/system/bin:/system/xbin", 1);
        }
        if (preload_c != NULL && preload_c[0] != '\0') {
            setenv("LD_PRELOAD", preload_c, 1);
        }
        if (cwd_c[0] != '\0') {
            if (chdir(cwd_c) != 0) {
                chdir("/");
            }
        }
        execl(exec_c, "sh", (char *) NULL);
        _exit(127);
    }

    /* proceso padre:master para pty maestro fd,pid para proceso hijo pid */
    jint pidVal = (jint) pid;
    (*env)->SetIntArrayRegion(env, pidArray, 0, 1, &pidVal);
    (*env)->ReleaseStringUTFChars(env, execPath, exec_c);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
    if (extra_c) (*env)->ReleaseStringUTFChars(env, extraPath, extra_c);
    if (preload_c) (*env)->ReleaseStringUTFChars(env, preloadLib, preload_c);
    LOGI("pty created: pid=%d master=%d", (int) pid, master);
    return master;
}

/**
 * Bloquear la espera de que salga el proceso secundario;devolver código de salida(retorno de señal terminada 128+signo).
 */
JNIEXPORT jint JNICALL
Java_io_zer0_muse_terminal_Pty_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    int status = 0;
    int r;
    do {
        r = waitpid(pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/**
 * Leer del maestro pty(bloqueando). Devuelve el número de bytes leídos.;<=0 Indica EOF/Error.
 */
JNIEXPORT jint JNICALL
Java_io_zer0_muse_terminal_Pty_read(
        JNIEnv *env, jobject thiz,
        jint fd, jbyteArray buffer, jint offset, jint length) {
    int cap = length > MAX_READ_CHUNK ? MAX_READ_CHUNK : length;
    if (cap <= 0) return 0;
    jbyte tmp[MAX_READ_CHUNK];
    int n = (int) read(fd, tmp, (size_t) cap);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, offset, n, tmp);
    }
    return n;
}

/**
 * Escribir al maestro pty,La parte de procesamiento interno está escrita con EINTR. Devuelve el número total de bytes escritos.(<0 error).
 */
JNIEXPORT jint JNICALL
Java_io_zer0_muse_terminal_Pty_write(
        JNIEnv *env, jobject thiz,
        jint fd, jbyteArray data, jint offset, jint length) {
    if (length <= 0) return 0;
    jbyte *p = (*env)->GetByteArrayElements(env, data, NULL);
    if (p == NULL) return -1;
    int total = 0;
    while (total < length) {
        ssize_t n = write(fd, (const char *) p + offset + total, (size_t) (length - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            total = -1;
            break;
        }
        total += (int) n;
    }
    (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
    return total;
}

/** El número de bytes que el host puede leer actualmente.(FIONREAD);Devuelve -1 en caso de error. */
JNIEXPORT jint JNICALL
Java_io_zer0_muse_terminal_Pty_available(JNIEnv *env, jobject thiz, jint fd) {
    int n = 0;
    if (ioctl(fd, FIONREAD, &n) < 0) return -1;
    return n;
}

/** Sincronizar el tamaño de la ventana del terminal(rows/cols),Haga que los programas de pantalla completa se distribuyan correctamente. */
JNIEXPORT void JNICALL
Java_io_zer0_muse_terminal_Pty_setWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    if (rows <= 0 || cols <= 0) return;
    struct winsize ws;
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/** Cerrar terminal principal fd(Bloquear la lectura después del cierre devolverá un error,Se utiliza para terminar la sesión.). */
JNIEXPORT void JNICALL
Java_io_zer0_muse_terminal_Pty_closeFd(JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) close(fd);
}

/** llamada al sistema más reciente errno(Para depuración y diagnóstico.;Solo hilo actual). */
JNIEXPORT jint JNICALL
Java_io_zer0_muse_terminal_Pty_errnoValue(JNIEnv *env, jobject thiz) {
    return errno;
}
