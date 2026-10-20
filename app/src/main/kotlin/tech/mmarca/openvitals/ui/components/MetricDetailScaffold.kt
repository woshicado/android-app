package tech.mmarca.openvitals.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import tech.mmarca.openvitals.R
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.mmarca.openvitals.core.period.DatePeriod
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.core.period.WeekPeriodMode
import tech.mmarca.openvitals.core.period.displayPeriodFor
import java.time.LocalDate
import tech.mmarca.openvitals.core.presentation.ScreenError
import tech.mmarca.openvitals.core.presentation.resolve
import tech.mmarca.openvitals.domain.preferences.MetricDetailSectionId
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScaffold(
    isLoading: Boolean,
    selectedRange: TimeRange,
    selectedDate: LocalDate,
    screenError: ScreenError? = null,
    error: String? = null,
    onRefresh: () -> Unit,
    onSelectRange: (TimeRange) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectDay: ((LocalDate) -> Unit)? = null,
    primaryAction: MetricAction? = null,
    weekPeriodMode: WeekPeriodMode = WeekPeriodMode.MONDAY_TO_SUNDAY,
    periodOverride: (DatePeriod) -> DatePeriod = { it },
    periodTitle: @Composable ((DatePeriod) -> String)? = null,
    showTimeRangeSelector: Boolean = true,
    syncPaused: Boolean = false,
    sectionListState: MetricDetailSectionListState? = null,
    /** A still label once a load passes 300 ms, not while a pull's spinner shows; see [MetricLoadingLabel]. */
    showLoadingLabel: Boolean = false,
    headerItems: LazyListScope.() -> Unit = {},
    content: LazyListScope.(period: DatePeriod) -> Unit,
) {
    val period = periodOverride(displayPeriodFor(selectedRange, selectedDate, weekPeriodMode = weekPeriodMode))
    val today = LocalDate.now()
    val resolvedError = screenError.resolve() ?: error
    var showDatePicker by remember { mutableStateOf(false) }
    val defaultListState = rememberLazyListState()
    val lazyListState = sectionListState?.lazyListState ?: defaultListState
    val reorderableSectionState = sectionListState?.let { listState ->
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val fromId = from.key as? MetricDetailSectionId
            val toId = to.key as? MetricDetailSectionId
            if (fromId != null && toId != null && fromId != toId) {
                listState.onMoveSectionToTarget?.invoke(fromId, toId)
            }
        }
    }
    SideEffect {
        sectionListState?.reorderableState = reorderableSectionState
    }
    val isSectionDragActive = reorderableSectionState?.isAnyItemDragging == true
    // Remembered, or a fresh lambda would invalidate every chart that reads it.
    val openDay = remember(onSelectDay, onSelectRange, onSelectDate) {
        onSelectDay ?: { date: LocalDate ->
            // Screens without an atomic selectDay still land on the right day.
            onSelectRange(TimeRange.DAY)
            onSelectDate(date)
        }
    }

    // One reload, one indicator: the spinner answers the pull only.
    var pullRefreshRequested by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (!isLoading) pullRefreshRequested = false
    }

    CompositionLocalProvider(
        LocalMetricDayOpener provides openDay,
        LocalPeriodWeekMode provides weekPeriodMode,
    ) {
    PullToRefreshBox(
        isRefreshing = pullRefreshRequested && isLoading,
        onRefresh = {
            pullRefreshRequested = true
            onRefresh()
        },
        enabled = !isSectionDragActive,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = lazyListState,
                userScrollEnabled = !isSectionDragActive,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 920.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                headerItems()
                if (syncPaused) {
                    item {
                        HealthConnectSyncStatusBanner(
                            syncPaused = syncPaused,
                            syncInProgress = false,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (showTimeRangeSelector) {
                    item {
                        TimeRangeSelector(
                            selected = selectedRange,
                            onSelect = onSelectRange,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                item {
                    PeriodNavigator(
                        selectedRange = selectedRange,
                        period = period,
                        title = periodTitle?.invoke(period),
                        canGoForward = period.end.isBefore(today),
                        onPreviousPeriod = onPreviousPeriod,
                        onNextPeriod = onNextPeriod,
                        onOpenCalendar = { showDatePicker = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        weekPeriodMode = weekPeriodMode,
                    )
                }
                primaryAction?.let { action ->
                    item {
                        CompactMetricActionButton(
                            action = action,
                            expanded = true,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (screenError == ScreenError.PermissionDenied) {
                    item { ScreenErrorContent(screenError) }
                } else {
                    resolvedError?.let { err ->
                        item { ErrorMessage(err) }
                    }
                }
                content(period)
                item { Spacer(Modifier.height(if (showLoadingLabel) MetricLoadingLabelRoom else 16.dp)) }
            }
            if (showLoadingLabel && isLoading && !pullRefreshRequested) MetricLoadingLabel()
        }
    }
    }

    if (showDatePicker) {
        HealthDatePickerDialog(
            selectedDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                showDatePicker = false
                onSelectDate(date)
            },
        )
    }
}

/**
 * Room under a list's last row that the loading label can cover, there whether or not it shows: the
 * label's 16 dp offset and 8 dp padding around a labelMedium line, with space for a large font scale.
 */
private val MetricLoadingLabelRoom = 56.dp

/**
 * "Loading..." at the bottom, over the list, so nothing moves when it comes and goes; not during a
 * pull, which shows its spinner. It waits [MetricLoadingLabelDelayMs] so a quick load does not flash
 * it. Still, not animated: an animation draws frames for the whole read, and on a year of workouts
 * that CPU slows Health Connect's own reads. A Text, not a Surface, so taps and drags reach the list.
 */
@Composable
private fun BoxScope.MetricLoadingLabel() {
    val shown by produceState(false) {
        delay(MetricLoadingLabelDelayMs)
        value = true
    }
    if (!shown) return
    Text(
        text = stringResource(R.string.loading),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    )
}

private const val MetricLoadingLabelDelayMs = 300L
