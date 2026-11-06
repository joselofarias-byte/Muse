/*
 * muse_exec — Muse Ajuste ejecutivo de tiempo de ejecución incorporado (P1-B,LD_PRELOAD)
 * ============================================================================
 * Objetivo: en el entorno limitado de la aplicación (tiempo de ejecución de nodo integrado), permita que el ecosistema npm "llame por nombre"
 *      node / npm / npx”y el guión shebang (#!/usr/bin/env node) se puede ejecutar normalmente.
 *
 * fondo (Android W^X):targetSdk 29+ La aplicación no puede ejecutar archivos en el directorio de datos.
 *      npx El script contenedor .bin ensamblado (shebang apuntando al nodo) y por lo tanto será interceptado por SELinux
 *      (execute_no_trans denied,exit 126). Esta junta intercepta este tipo de
 *      Llamada, reescrita como "ejecutar directamente usando el nodo integrado"——El objetivo es apk_data_file (permitido).
 *
 * Mecanismo:
 *   1. interceptar execve / execvp / execvpe;
 *   2. Mapeo de nombres (solo si el objetivo es un "nombre simple", es decir, semántica de búsqueda de RUTA):
 *        node / nodejs  ->  $MUSE_NODE_BIN
 *        npm            ->  $MUSE_NODE_BIN $MUSE_NPM_CLI
 *        npx            ->  $MUSE_NODE_BIN $MUSE_NPX_CLI
 *   3. shebang Análisis: cuando el objetivo es un script y la primera línea apunta al nodo, reescríbalo como
 *        $MUSE_NODE_BIN <script> <Parámetros originales...>
 *      (open Siga el enlace simbólico y cubra la entrada del enlace suave de node_modules/.bin)
 *   4. Cualquier análisis falló/condición no cumplida → Llame a la función real tal cual (defensiva: nunca bloquee el proceso hijo).
 *
 * Variables de entorno (inyectadas por MuseRuntime.buildEnv):
 *   MUSE_NODE_BIN = <nativeLibraryDir>/libmuse_node.so
 *   MUSE_NPM_CLI  = <runtimeDir>/npm/bin/npm-cli.js
 *   MUSE_NPX_CLI  = <runtimeDir>/npm/bin/npx-cli.js
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*execvp_fn)(const char *, char *const[]);
typedef int (*execvpe_fn)(const char *, char *const[], char *const[]);

static execve_fn real_execve = NULL;
static execvp_fn real_execvp = NULL;
static execvpe_fn real_execvpe = NULL;

extern char **environ;

static void muse_init(void) {
    if (!real_execve) real_execve = (execve_fn)dlsym(RTLD_NEXT, "execve");
    if (!real_execvp) real_execvp = (execvp_fn)dlsym(RTLD_NEXT, "execvp");
    if (!real_execvpe) real_execvpe = (execvpe_fn)dlsym(RTLD_NEXT, "execvpe");
}

static const char *muse_env(const char *k) {
    const char *v = getenv(k);
    return (v && *v) ? v : NULL;
}

static int muse_argc(char *const argv[]) {
    int n = 0;
    if (!argv) return 0;
    while (argv[n]) n++;
    return n;
}

/* Lea la primera línea del archivo y determine si es un "script shebang que apunta al nodo". */
static int muse_is_node_script(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    char buf[256];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 2) return 0;
    buf[n] = '\0';
    if (buf[0] != '#' || buf[1] != '!') return 0;
    char *p = buf + 2;
    while (*p == ' ' || *p == '\t') p++;
    /* en forma de #!node / #!/.../node */
    if (strncmp(p, "node", 4) == 0) return 1;
    if (strstr(p, "/node") != NULL) return 1;
    /* en forma de #!/usr/bin/env node */
    if (strstr(p, "env") != NULL && strstr(p, " node") != NULL) return 1;
    return 0;
}

/* Ejecutar con nodo:node [script] <argv[1..]> */
static int muse_exec_with_node(execve_fn fn, const char *node, const char *script,
                               char *const argv[], char *const envp[]) {
    int tail = muse_argc(argv);
    char **nv = (char **)malloc(sizeof(char *) * (size_t)(3 + tail + 1));
    if (!nv) {
        errno = ENOMEM;
        return -1;
    }
    int i = 0;
    nv[i++] = (char *)node;
    if (script) nv[i++] = (char *)script;
    /* argv[0] Es la semántica de "nombre del programa" y se omite; los parámetros restantes se pasan tal como están. */
    for (int j = 1; j < tail; j++) nv[i++] = argv[j];
    nv[i] = NULL;
    int rc = fn(node, nv, envp);
    free(nv);
    return rc;
}

/* Intentar anular la ejecución; volver 1 = Tomado el control (rc válido),0 = No asumido (la persona que llama se ejecuta sin cambios). */
static int muse_try_rewrite(const char *path, char *const argv[], char *const envp[],
                            int *rc_out) {
    if (!path || path[0] == '\0') return 0;
    if (!real_execve) return 0;
    const char *node = muse_env("MUSE_NODE_BIN");
    if (!node) return 0;

    /* Con separador de ruta: no se realiza ninguna asignación de nombres; sólo se intenta el análisis shebang. */
    if (strchr(path, '/') != NULL) {
        if (muse_is_node_script(path)) {
            *rc_out = muse_exec_with_node(real_execve, node, path, argv, envp);
            return 1;
        }
        return 0;
    }

    /* Mapeo de nombres desnudos */
    if (strcmp(path, "node") == 0 || strcmp(path, "nodejs") == 0) {
        *rc_out = muse_exec_with_node(real_execve, node, NULL, argv, envp);
        return 1;
    }
    if (strcmp(path, "npm") == 0 || strcmp(path, "npx") == 0) {
        const char *cli = muse_env(strcmp(path, "npm") == 0 ? "MUSE_NPM_CLI" : "MUSE_NPX_CLI");
        if (cli) {
            *rc_out = muse_exec_with_node(real_execve, node, cli, argv, envp);
            return 1;
        }
    }
    return 0;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    muse_init();
    if (!real_execve) {
        errno = ENOSYS;
        return -1;
    }
    int rc = 0;
    if (muse_try_rewrite(path, argv, envp, &rc)) return rc;
    return real_execve(path, argv, envp);
}

int execvp(const char *file, char *const argv[]) {
    muse_init();
    if (!real_execvp) {
        errno = ENOSYS;
        return -1;
    }
    int rc = 0;
    if (muse_try_rewrite(file, argv, environ, &rc)) return rc;
    return real_execvp(file, argv);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    muse_init();
    if (!real_execvpe) {
        errno = ENOSYS;
        return -1;
    }
    int rc = 0;
    if (muse_try_rewrite(file, argv, envp, &rc)) return rc;
    return real_execvpe(file, argv, envp);
}
