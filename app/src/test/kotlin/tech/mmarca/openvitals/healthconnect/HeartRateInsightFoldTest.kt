package tech.mmarca.openvitals.healthconnect

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.domain.model.HeartRateInsightBucketDuration
import tech.mmarca.openvitals.domain.model.MaxInsightAggregateBuckets

/**
 * The insight series folded from records: the five-minute means `BPM_AVG` returns, or
 * the aggregates themselves when a day is too dense to fold. The fake matches reads on a
 * record's start time, as Health Connect does.
 */
class HeartRateInsightFoldTest {

    @Before
    fun setUp() {
        HealthConnectRateLimitBackoff.resetForTest()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        HealthConnectRateLimitBackoff.resetForTest()
    }

    @Test
    fun `each day's records fold into five-minute means`() = runTest(testDispatcher) {
        val firstDay = date.minusDays(1)
        val rangeStart = startOf(firstDay)
        val midnight = startOf(date)
        val client = seeded(
            // Starts before the range: only its sample inside the range counts.
            heartRateSeries(rangeStart.minusSeconds(600), rangeStart.minusSeconds(300) to 50L, rangeStart.plusSeconds(60) to 65L),
            heartRate(firstDay, 9, 61L),
            // Starts on the first day and carries two samples into the second day's first bucket.
            heartRateSeries(midnight.minusSeconds(60), midnight.minusSeconds(60) to 70L, midnight.plusSeconds(30) to 81L, midnight.plusSeconds(90) to 84L),
            heartRate(date, 15, 88L),
        )

        val samples = reader(client).readHeartRateSamplesForInsights(rangeStart, startOf(date.plusDays(1)))

        assertThat(samples.map { it.time to it.beatsPerMinute }).containsExactly(
            rangeStart to 65L,
            rangeStart.plusSeconds(9 * 3_600) to 61L,
            midnight.minus(HeartRateInsightBucketDuration) to 70L,
            // Truncated like BPM_AVG: (81 + 84) / 2 is 82.5.
            midnight to 82L,
            midnight.plusSeconds(15 * 3_600) to 88L,
        ).inOrder()
        // A heart-rate aggregate costs 0.65 s on a device whatever its size, and they run one at a time.
        assertThat(client.groupByDurationRequestRanges).isEmpty()
    }

    // A writer denser than twice 1 Hz reads its records slower than the aggregates answer.
    @Test
    fun `from a day past the sample budget on, the range reads as aggregates`() = runTest(testDispatcher) {
        val dayStart = startOf(date)
        // 2 Hz for a day: 173,000 samples, past the 172,800 a day may hold.
        val dense = (0 until 173).map { record ->
            val recordStart = dayStart.plusMillis(record * 500_000L)
            heartRateSeries(recordStart, *Array(1_000) { sample -> recordStart.plusMillis(sample * 500L) to 70L })
        }
        val client = seeded(heartRate(date.minusDays(1), 9, 61L), *dense.toTypedArray())

        val samples = reader(client).readHeartRateSamplesForInsights(startOf(date.minusDays(1)), startOf(date.plusDays(1)))

        // The day before keeps its fold; only the dense day asks for aggregates.
        assertThat(samples.first().time to samples.first().beatsPerMinute)
            .isEqualTo(startOf(date.minusDays(1)).plusSeconds(9 * 3_600) to 61L)
        assertThat(samples.drop(1).map { it.beatsPerMinute }.distinct()).containsExactly(70L)
        assertThat(client.groupByDurationRequestRanges.minOf { it.first }).isEqualTo(dayStart)
        // A whole day of buckets in one request hit TransactionTooLargeException.
        assertThat(widestRequest(client)).isAtMost(HeartRateInsightBucketDuration.multipliedBy(MaxInsightAggregateBuckets))
    }

    // Health Connect hands records over at a fixed cost each: one sample per record at 1 Hz
    // took 33 s a day to read, against 1.4 s for the day's aggregates.
    @Test
    fun `from a day past the record budget on, the range reads as aggregates`() = runTest(testDispatcher) {
        val client = seeded(*oneSampleRecords(startOf(date), 4_001))

        val samples = reader(client).readHeartRateSamplesForInsights(startOf(date.minusDays(1)), startOf(date.plusDays(1)))

        assertThat(samples.map { it.beatsPerMinute }.distinct()).containsExactly(70L)
        assertThat(client.groupByDurationRequestRanges.minOf { it.first }).isEqualTo(startOf(date))
    }

    // A record only reaches the day it starts in and later ones, so a dense last day leaves
    // the days before it exact.
    @Test
    fun `a dense last day leaves the days before it folded`() = runTest(testDispatcher) {
        val client = seeded(
            heartRate(date.minusDays(2), 9, 61L),
            heartRate(date.minusDays(1), 9, 62L),
            *oneSampleRecords(startOf(date), 4_001),
        )

        val samples = reader(client).readHeartRateSamplesForInsights(startOf(date.minusDays(2)), startOf(date.plusDays(1)))

        assertThat(samples.take(2).map { it.beatsPerMinute }).containsExactly(61L, 62L).inOrder()
        assertThat(client.groupByDurationRequestRanges.minOf { it.first }).isEqualTo(startOf(date))
    }

    // The fold stands in for the aggregates, so both must return the same series on the same data.
    @Test
    fun `the fold returns what the aggregates return`() = runTest(testDispatcher) {
        val dayStart = startOf(date)
        // Twenty samples every 37 s from 06:30, across two midnights, bpm from 55 to 150.
        val varied = (0 until 160).map { record ->
            val recordStart = dayStart.plusSeconds(6 * 3_600 + 1_800L + record * 1_013L)
            heartRateSeries(
                recordStart,
                *Array(20) { sample -> recordStart.plusSeconds(sample * 37L) to 55L + (record * 31 + sample * 7) % 96 },
            )
        }
        val client = seeded(*varied.toTypedArray())
        // Off midnight at the start, and a two-hour tail short enough to stay raw.
        val start = dayStart.plusSeconds(6 * 3_600 + 1_800L)
        val end = startOf(date.plusDays(2)).plusSeconds(2 * 3_600)
        val reader = reader(client)

        val folded = reader.readHeartRateSamplesForInsights(start, end)
        assertThat(client.groupByDurationRequestRanges).isEmpty()
        val aggregated = reader.readAggregatedSamplesForInsights(
            listOf(
                start to startOf(date.plusDays(1)),
                startOf(date.plusDays(1)) to startOf(date.plusDays(2)),
                startOf(date.plusDays(2)) to end,
            ),
        )

        // Raw in the tail: more samples than its 24 five-minute buckets.
        assertThat(folded.count { !it.time.isBefore(startOf(date.plusDays(2))) }).isGreaterThan(24)
        assertThat(folded.map { it.time to it.beatsPerMinute })
            .containsExactlyElementsIn(aggregated.map { it.time to it.beatsPerMinute })
            .inOrder()
    }

    // Harness.

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : DispatcherProvider {
        override val main = testDispatcher
        override val io = testDispatcher
        override val default = testDispatcher
    }

    /** A fixed past date, well away from any DST transition in any zone. */
    private val date: LocalDate = LocalDate.of(2026, 1, 2)
    private val watch = Device(type = Device.TYPE_WATCH)

    private fun startOf(day: LocalDate): Instant = day.atStartOfDay(ZoneId.systemDefault()).toInstant()

    /** One sample at [bpm] at [hour] o'clock on [day], in a minute-long record. */
    private fun heartRate(day: LocalDate, hour: Long, bpm: Long): HeartRateRecord {
        val time = startOf(day).plusSeconds(hour * 3_600)
        return heartRateSeries(time, time to bpm)
    }

    /** One record from [start] to a minute past its last sample. */
    private fun heartRateSeries(start: Instant, vararg samples: Pair<Instant, Long>) = HeartRateRecord(
        startTime = start,
        startZoneOffset = null,
        endTime = samples.last().first.plusSeconds(60),
        endZoneOffset = null,
        samples = samples.map { (time, bpm) -> HeartRateRecord.Sample(time = time, beatsPerMinute = bpm) },
        metadata = Metadata.autoRecorded(watch),
    )

    /** [count] records of one 70 bpm sample each, seven seconds apart from [start]. */
    private fun oneSampleRecords(start: Instant, count: Int): Array<HeartRateRecord> =
        Array(count) { index -> start.plusSeconds(index * 7L).let { heartRateSeries(it, it to 70L) } }

    private fun widestRequest(client: AggregatingFakeHealthConnectClient): Duration =
        client.groupByDurationRequestRanges.maxOf { (start, end) -> Duration.between(start, end) }

    private suspend fun seeded(vararg records: Record): AggregatingFakeHealthConnectClient {
        val client = FakeHealthConnectClient()
        client.setPackageName(APP_PACKAGE)
        if (records.isNotEmpty()) client.insertRecords(records.toList())
        return AggregatingFakeHealthConnectClient(client, platformTimeRange = true)
    }

    private fun reader(client: HealthConnectClient): HeartHealthReader {
        val diagnostics = mockk<HealthConnectDiagnostics>()
        every { diagnostics.summary() } returns "test"
        val support = HealthConnectReaderSupport(
            clientProvider = { client },
            diagnostics = diagnostics,
            rateLimitMessage = { "rate limited" },
            dispatchers = testDispatchers,
        )
        return HeartHealthReader(support, APP_PACKAGE)
    }

    private companion object {
        const val APP_PACKAGE = "tech.mmarca.openvitals"
    }
}
