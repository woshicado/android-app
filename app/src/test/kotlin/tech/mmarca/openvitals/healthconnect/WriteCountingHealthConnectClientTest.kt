package tech.mmarca.openvitals.healthconnect

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WriteCountingHealthConnectClientTest {
    // A screen snapshot is drawn only under the write count it was read at.
    @Test
    fun `every write counts, and a read does not`() = runTest {
        val writes = AtomicLong()
        val client = WriteCountingHealthConnectClient(FakeHealthConnectClient(), writes, AtomicInteger())
        val start = Instant.parse("2026-01-02T08:00:00Z")
        val steps = StepsRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = start.plusSeconds(600),
            endZoneOffset = null,
            count = 900,
            metadata = Metadata.manualEntry(),
        )

        val id = client.insertRecords(listOf(steps)).recordIdsList.single()
        val stored = client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(Instant.EPOCH))).records.single()
        client.updateRecords(listOf(StepsRecord(stored.startTime, null, stored.endTime, null, 950, stored.metadata)))
        client.deleteRecords(StepsRecord::class, listOf(id), emptyList())
        client.deleteRecords(StepsRecord::class, TimeRangeFilter.after(Instant.EPOCH))

        // Each write moves the count as it ends; the read moves nothing.
        assertThat(writes.get()).isEqualTo(4)
    }
}
