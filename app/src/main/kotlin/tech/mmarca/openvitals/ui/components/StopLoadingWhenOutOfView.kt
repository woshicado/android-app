package tech.mmarca.openvitals.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Calls [onStop] when the screen leaves view, so the screen in front gets the Health
 * Connect reads to itself. Not on a rotation: the ViewModel outlives it, and its resume
 * decides whether the load runs again.
 */
@Composable
fun StopLoadingWhenOutOfView(onStop: () -> Unit) {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) onStop()
    }
}
