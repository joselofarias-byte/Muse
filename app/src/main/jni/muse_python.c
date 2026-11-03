/*
 * muse_python.c — Muse Punto de entrada ejecutable para el tiempo de ejecución de Python integrado (stub).
 *
 * ## ¿Por qué necesitas esto? stub
 *
 * python.org El paquete integrable de Android solo proporciona libpython3.14.so,**No bin/python3
 * archivo ejecutable**(Android no hay"Instalación a nivel del sistema Python"concepto, la documentación oficial establece claramente que solo se puede utilizar en modo integrado).
 * Muse Adopte un "modelo de subproceso" que sea isomorfo al tiempo de ejecución integrado de Node: se requiere una entrada ejecutable.
 * Por lo tanto, escriba aquí un iniciador con docenas de líneas, que carga libpython y llama a Py_BytesMain.
 *
 * ## ¿Por qué utilizar dlopen en lugar de enlace directo? libpython
 *
 * Si vincula directamente libpython3.14.so en CMake, entonces**Cada compilación de Gradle requiere que jniLibs ya exista
 * la biblioteca**——El paquete Python se descarga y genera mediante tools/python-pack antes de compilarlo (el binario no está incluido en la biblioteca, a diferencia de Node
 * misma convención en tiempo de ejecución). Después de usar dlopen: la construcción ordinaria no depende de la existencia de paquetes de Python y se analiza en tiempo de ejecución;
 * Cuando no está listo para ejecutarse, sólo las capacidades de Python no estarán disponibles y no provocarán que todo el proyecto falle.
 *
 * ## Estrategia de ubicación de la biblioteca
 *
 * Busque el directorio donde se encuentra a través de /proc/self/exe (es decir, NativeLibraryDir después de la instalación del APK).
 * Luego cargue libpython3.14.so desde el mismo directorio. No hay dependencias de LD_LIBRARY_PATH o cwd,
 * Tampoco se ve afectado por las diferencias en la ruta de búsqueda del espacio de nombres del vinculador de Android.
 *
 * ## construir
 *
 * Compilado desde app/src/main/jni/CMakeLists.txt en libmuse_python.so (jniLibs Requisitos de especificación
 * El archivo ejecutable comienza con lib*.so denominación). Requiere alineación de página de 16 KB (Android 15+ requisitos).
 */

#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* libpython3.14.so La firma de entrada principal en (consulte Programas CPython/python.c). */
typedef int (*py_bytes_main_fn)(int argc, char **argv);

/**
 * Analiza el directorio donde se encuentra (nativeLibraryDir), explica la ruta absoluta a libpython.
 *
 * @param out  buffer de salida
 * @param size tamaño del buffer
 * @return 0 éxito,-1 falló
 */
static int resolve_libpython_path(char *out, size_t size) {
    char exe_path[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (n <= 0) {
        return -1;
    }
    exe_path[n] = '\0';

    /* Trunca el nombre del archivo y conserva el directorio (incluido el final '/'). */
    char *slash = strrchr(exe_path, '/');
    if (slash == NULL) {
        return -1;
    }
    *(slash + 1) = '\0';

    int written = snprintf(out, size, "%slibpython3.14.so", exe_path);
    if (written <= 0 || (size_t)written >= size) {
        return -1;
    }
    return 0;
}

int main(int argc, char **argv) {
    char lib_path[PATH_MAX];

    if (resolve_libpython_path(lib_path, sizeof(lib_path)) != 0) {
        fprintf(stderr, "muse_python: No se puede localizar el directorio propio (/proc/self/exe)\n");
        return 127;
    }

    /* RTLD_NOW: Analice todos los símbolos inmediatamente y exponga las dependencias faltantes lo antes posible;
     * RTLD_GLOBAL: Hacer que los símbolos de libpython sean globalmente visibles para su posterior apertura
     *   lib-dynload/*.so C Reutilización de la extensión (cuyo DT_NEEDED apunta a libpython3.14.so). */
    void *handle = dlopen(lib_path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        fprintf(stderr, "muse_python: Error al cargar %s: %s\n", lib_path, dlerror());
        return 127;
    }

    py_bytes_main_fn py_main = (py_bytes_main_fn)dlsym(handle, "Py_BytesMain");
    if (py_main == NULL) {
        fprintf(stderr, "muse_python: no encontrado Py_BytesMain: %s\n", dlerror());
        return 127;
    }

    /* Entregar a CPython: manejará argv(-c / Ruta del script/-m/REPL) y código de salida. */
    return py_main(argc, argv);
}
