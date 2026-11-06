package io.zer0.muse.data

data class AccountState(
    val isLoggedIn: Boolean = false,
    val userName: String = "",
    val loginAt: Long = 0L,
    val loginMethod: String = "",
    /** Experiencia sin conexión(turistas)modo — Omita el inicio de sesión y vaya directamente a la interfaz principal.,Sincronización sin servidor. */
    val isGuestMode: Boolean = false,
    /** v2.x: avatar de usuario URI(fotos locales,Vacío significa usar la primera letra como marcador de posición). */
    val avatarUri: String? = null,
) {
    /** ¿Tienes"Pase"Certificación(Iniciar sesión o invitado) — Se utiliza para determinar la ruta startDestination. */
    val isAuthed: Boolean get() = isLoggedIn || isGuestMode

    companion object {
        val GUEST = AccountState()
    }
}
