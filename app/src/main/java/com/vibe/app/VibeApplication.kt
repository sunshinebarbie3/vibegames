package com.vibe.app

import android.app.Application
import coil.Coil
import com.vibe.app.core.VibeContainer
import com.vibe.app.notifications.VibeMessagingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VibeApplication : Application() {
    lateinit var container: VibeContainer private set
    override fun onCreate() {
        super.onCreate()
        container = VibeContainer.provide(this)
        Coil.setImageLoader(container.imageLoader)
        VibeMessagingService.ensureChannel(this)
        container.appScope.launch {
            val settings = container.settings.settings.first()
            com.vibe.app.core.LocaleManager.apply(settings.language)
        }
    }
}
