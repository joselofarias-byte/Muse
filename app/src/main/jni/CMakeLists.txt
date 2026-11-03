# v2.x Fase terminal II:Puente PTY autocompilado(Implementación forkpty autoescrita,Cero código de terceros)
cmake_minimum_required(VERSION 3.22.1)
project("pty")

add_library(pty SHARED pty.c)

# Android 15+ 16KB soporte de pagina:Requiere explícitamente una alineación de 16 KB
# (4KB Compatibilidad con versiones anteriores del dispositivo de página;16KB Si el dispositivo de búsqueda carece de esta alineación, dlopen fallará.)
target_link_options(pty PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

find_library(log-lib log)
target_link_libraries(pty ${log-lib})

# P1-B: exec Junta(LD_PRELOAD;Interceptar execve/execvp/execvpe para mapeo de nombres y resolución de shebang)
add_library(muse_exec SHARED muse_exec.c)
target_link_options(muse_exec PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
find_library(dl-lib dl)
target_link_libraries(muse_exec ${dl-lib})

# v2.7 Tiempo de ejecución extendido(Python):Código auxiliar de entrada ejecutable de Python incorporado.
# python.org El paquete de Android solo proporciona libpython3.14.so,No bin/python3;Este trozo se utiliza
# dlopen Cargue libpython y llame Py_BytesMain,El ejecutivo de ProcessBuilder puede acceder a la salida.
#
# ⚠️ clave:debe construirse como **archivo ejecutable**(Con PT_INTERP + punto de entrada distinto de cero).
#    add_library(SHARED) El resultado es una biblioteca compartida.(e_entry=0x0 y ninguno PT_INTERP),
#    Cuando se ejecute, saltará directamente a la dirección 0. SIGSEGV(exit=139,medido).
#    Anular con add_executable + PREFIJO/SUFIJO,Produce un verdadero ejecutable llamado libmuse_python.so.
# Utilice dlopen en lugar de vincular directamente con libpython: la compilación normal no requiere la descarga de paquetes de Python(Los binarios no están incluidos en la biblioteca.).
# Consulte los comentarios del encabezado app/src/main/jni/muse_python.c para obtener más detalles.
add_executable(muse_python muse_python.c)
set_target_properties(muse_python PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    OUTPUT_NAME "muse_python")
target_link_options(muse_python PRIVATE
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
find_library(dl-lib-python dl)
target_link_libraries(muse_python ${dl-lib-python})
