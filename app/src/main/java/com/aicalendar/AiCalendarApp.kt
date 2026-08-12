package com.aicalendar

import android.app.Application
import com.aicalendar.di.AppContainer
import com.aicalendar.notify.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AiCalendarApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.ensureChannel(this)

        appScope.launch {
            // Warm the model at startup so the first capture is not the slow one.
            // Loading is several seconds of native work, hence off the main thread.
            val settings = container.settingsRepository.settings.first()
            val installed = container.modelManager.installedModel()
            when {
                !settings.useLlm -> Unit
                installed != null -> container.llmEngine.load(installed.absolutePath)
                settings.modelPath != null -> {
                    // The recorded path is stale; forget it so Settings shows the truth.
                    container.settingsRepository.setModelPath(null)
                }
            }
            if (installed != null && settings.modelPath != installed.absolutePath) {
                container.settingsRepository.setModelPath(installed.absolutePath)
            }
        }
    }
}
