package com.app.canconnection

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Custom [Application] class that owns the single app-scoped [ViewModelStore].
 *
 * All activities resolve [SharedCanViewModel] from this shared store so that
 * connection state, logs, and saved commands survive activity transitions without
 * being re-created each time a new screen is pushed onto the back stack.
 */
class CanApplication : Application(), ViewModelStoreOwner {
    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    override fun onTerminate() {
        super.onTerminate()
        _viewModelStore.clear()
    }
}
