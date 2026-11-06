package io.zer0.muse

import io.zer0.ai.RefImageUrlValidator
import io.zer0.muse.ui.SsrfGuard
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * G4: Figura de referencia Puente de verificación de la SSRF — Pon la capa de la aplicación [SsrfGuard.refImageUrlValidator] Regístrate como
 * ai modulares [RefImageUrlValidator] Koin Tipo,suministro [io.zer0.ai.image.AgnesImageProvider]
 * Inyectar.ai Los módulos no pueden depender de app(Orientación del módulo app→ai),Por tanto, pasa a través de este módulo puente.
 *
 * Ya me uní [allKoinModules](AppKoinModule.kt,Clasificación antes de aiModule).
 */
val SsrfBridgeModule: Module = module {
    single<RefImageUrlValidator> { SsrfGuard.refImageUrlValidator }
}
