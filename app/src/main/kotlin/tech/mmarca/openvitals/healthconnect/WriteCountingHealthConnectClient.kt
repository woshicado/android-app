package tech.mmarca.openvitals.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * Counts every record write this app sends Health Connect, whichever reader or importer
 * sends it: [HealthConnectManager] hands every reader this client. A screen snapshot is
 * drawn only under the count it was read at, so no write of this app's own is ever drawn
 * over, nor read while one is in flight. A failed write counts too: it may have landed in
 * part. Medical resources are not counted; no screen that keeps a snapshot reads them.
 */
internal class WriteCountingHealthConnectClient(
    private val inner: HealthConnectClient,
    private val writes: AtomicLong,
    private val inFlight: AtomicInteger,
) : HealthConnectClient by inner {

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse =
        counted { inner.insertRecords(records) }

    override suspend fun updateRecords(records: List<Record>) =
        counted { inner.updateRecords(records) }

    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        recordIdsList: List<String>,
        clientRecordIdsList: List<String>,
    ) = counted { inner.deleteRecords(recordType, recordIdsList, clientRecordIdsList) }

    override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) =
        counted { inner.deleteRecords(recordType, timeRangeFilter) }

    /** Records land while the call runs: no read may claim it before it ends. */
    private inline fun <T> counted(write: () -> T): T {
        inFlight.incrementAndGet()
        try {
            return write()
        } finally {
            writes.incrementAndGet()
            inFlight.decrementAndGet()
        }
    }
}
