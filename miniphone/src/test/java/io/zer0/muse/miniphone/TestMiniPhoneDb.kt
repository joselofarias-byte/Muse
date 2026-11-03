package io.zer0.muse.miniphone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.data.db.MiniPhoneDb

/** Prueba de entrada unificada:Abra una pequeña biblioteca limpia de teléfonos celulares en la memoria cada vez,No te conformes. */
internal fun newInMemoryMiniPhoneDb(): MiniPhoneDb {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, MiniPhoneDb::class.java)
        .allowMainThreadQueries()
        .build()
}
