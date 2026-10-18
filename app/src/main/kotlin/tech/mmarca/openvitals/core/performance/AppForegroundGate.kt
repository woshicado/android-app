package tech.mmarca.openvitals.core.performance

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForegroundGate @Inject constructor() {

    private val foregroundState = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Observable [isForeground]. Garmin watches defer online errands until the companion is active. */
    val foregroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = foregroundState

    private var foreground: Boolean
        get() = foregroundState.value
        set(value) { foregroundState.value = value }

    val isForeground: Boolean
        get() = foregroundState.value

    /** How often the app has left the foreground: a read across one may hold this app's records only. */
    @Volatile
    var timesBackgrounded: Long = 0L
        private set

    fun registerProcessLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    timesBackgrounded++
                }
            },
        )
    }
}
