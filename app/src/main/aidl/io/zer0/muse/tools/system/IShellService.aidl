// IShellService.aidl
package io.zer0.muse.tools.system;

import android.os.ParcelFileDescriptor;

/**
 * P3-3: Shizuku UserService Interfaz de ejecución de shell.
 *
 * Esta interfaz consta de [ShellService] darse cuenta,Pase Shizuku.bindUserService() Se ejecuta en un proceso con privilegios de shell.
 * La persona que llama es [ShizukuAuthorizer],Llamadas entre procesos vía AIDL IPC.
 *
 * Formato del valor de retorno: "<exitCode>\u0000<stdout>\u0000<stderr>"
 *  - uso \u0000 (null char) como delimitador,shell no aparece en la salida
 *  - exitCode: código de salida del comando(0=éxito,no0=falló,-1=excepción de ejecución)
 *  - stdout: comando de salida estándar
 *  - stderr: error estándar de comando
 *
 * Large interactive tool results use executeToPipe so payload bytes do not cross Binder as a String.
 */
interface IShellService {
    String execute(String command) = 2;
    int executeToPipe(String command, in ParcelFileDescriptor output) = 3;

    // Shizuku UserService reserved transaction. The implementation must
    // terminate the privileged process when Shizuku removes the service.
    void destroy() = 16777114;

    // Optional application-defined transaction used by the demo pattern.
    void exit() = 1;
}
