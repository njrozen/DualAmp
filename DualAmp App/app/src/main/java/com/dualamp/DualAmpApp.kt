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
        Prefs.init(this)
        PlaylistStore.init(this)
        PresetManager.init(this)
        VizFolders.init(this)
        LibraryRepository.init(this)
        PlayerConnection.connect(this)
    }
}
