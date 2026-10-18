package tech.mmarca.openvitals.healthconnect

import java.time.ZoneId
import tech.mmarca.openvitals.domain.model.HealthConnectAvailability

/**
 * Everything a screen's Health Connect result depends on besides its query: the read
 * grants, whether other apps' records are visible now, whether access is paused, the time
 * zone that cut the days, this app's own record writes, and the [settings] that change
 * what a read returns. A screen snapshot is shown only under the one it was read with,
 * so a revoked grant, a background read or a write of this app's own never stands in
 * for a live one.
 */
data class HealthReadFingerprint(
    val readGrants: Set<String>,
    val readsOtherAppsData: Boolean,
    val accessEnabled: Boolean,
    val zone: ZoneId,
    val writes: Long,
    val settings: Any,
)

suspend fun HealthConnectManager.readFingerprint(accessEnabled: Boolean, settings: Any): HealthReadFingerprint {
    val writes = writeCount
    val available = availability() == HealthConnectAvailability.AVAILABLE
    // A failure throws: the store then draws and saves nothing, rather than trust a guess.
    val granted = if (available) grantedPermissions() else emptySet()
    val readsOtherAppsData = available && readsOtherAppsDataNow()
    // So does a write in flight, or one that began meanwhile: its records may be half there.
    check(writesInFlight == 0 && writeCount == writes) { "A write is in flight" }
    return HealthReadFingerprint(
        readGrants = granted.filterTo(sortedSetOf()) { it.startsWith(ReadPermissionPrefix) },
        readsOtherAppsData = readsOtherAppsData,
        accessEnabled = accessEnabled,
        zone = ZoneId.systemDefault(),
        writes = writes,
        settings = settings,
    )
}

private const val ReadPermissionPrefix = "android.permission.health.READ_"
