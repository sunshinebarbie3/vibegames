package com.vibe.app

import android.app.Application
import com.vibe.app.core.VibeContainer
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Responsibilities:
 *  - own the single [VibeContainer] instance so the local cache, the offline
 *    queue and the sync worker all talk to the same objects;
 *  - start the sync manager, so a change queued while the device was offline is
 *    pushed to the API the moment the connection comes back.
 */
class VibeApplication : Application() {

    lateinit var container: VibeContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = VibeContainer.provide(this)
        container.appScope.launch { container.syncManager.syncNow() }
    }
}
