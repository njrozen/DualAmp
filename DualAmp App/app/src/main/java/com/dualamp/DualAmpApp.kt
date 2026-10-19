package com.dualamp

import android.app.Application
import com.dualamp.library.LibraryRepository
import com.dualamp.library.PlaylistStore
import com.dualamp.playback.PlayerConnection
import com.dualamp.settings.Prefs
import com.dualamp.viz.PresetManager
import com.dualamp.viz.VizFolders

class DualAmpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        com.dualamp.ui.AppTheme.load()
        com.dualamp.settings.applyLaunchViews()
        PlaylistStore.init(this)
        PresetManager.init(this)
        VizFolders.init(this)
        LibraryRepository.init(this)
        PlayerConnection.connect(this)
        com.dualamp.viz.VizStream.appContext = this
        com.dualamp.viz.VizStream.apply()
    }

    companion object {
        /** For code without a Context of its own (settings text, for example). */
        lateinit var instance: DualAmpApp
            private set
    }
}
