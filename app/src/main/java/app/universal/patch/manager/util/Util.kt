package app.universal.patch.manager.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.universal.patch.manager.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

typealias PatchSelection = Map<Int, Set<String>>
typealias Options = Map<Int, Map<String, Map<String, Any?>>>

fun PatchSelection.mergeWith(other: PatchSelection): PatchSelection {
    if (isEmpty()) {
        return other.mapValues { (_, patches) ->
            patches.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.filterValues { it.isNotEmpty() }
    }
    if (other.isEmpty()) {
        return mapValues { (_, patches) ->
            patches.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.filterValues { it.isNotEmpty() }
    }

    return buildMap {
        (keys + other.keys).forEach { bundleUid ->
            val merged = this@mergeWith[bundleUid].orEmpty() + other[bundleUid].orEmpty()
            val normalized = merged.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (normalized.isNotEmpty()) {
                put(bundleUid, normalized)
            }
        }
    }
}

val Context.isDebuggable get() = 0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE

fun Context.openUrl(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}

fun Context.toastHandle(string: String, duration: Int = Toast.LENGTH_SHORT): Toast =
    Toast.makeText(this, string, duration).apply { show() }

fun Context.toast(string: String, duration: Int = Toast.LENGTH_SHORT) {
    toastHandle(string, duration)
}

suspend fun <T> Context.withRepeatingToast(@StringRes messageRes: Int, block: suspend () -> T): T =
    withContext(Dispatchers.Main.immediate) {
        coroutineScope {
            val message = getString(messageRes)
            var progressToast = toastHandle(message)
            val repeater = launch {
                while (isActive) {
                    delay(2_500L)
                    progressToast.cancel()
                    progressToast = toastHandle(message)
                }
            }
            try {
                block()
            } finally {
                repeater.cancel()
                progressToast.cancel()
            }
        }
    }

/**
 * Safely perform an operation that may fail to avoid crashing the app.
 * If [block] fails, the error will be logged and a toast will be shown to the user to inform them that the action failed.
 *
 * @param context The android [Context].
 * @param toastMsg The toast message to show if [block] throws.
 * @param logMsg The log message.
 * @param block The code to execute.
 */
@OptIn(DelicateCoroutinesApi::class)
inline fun uiSafe(context: Context, @StringRes toastMsg: Int, logMsg: String, block: () -> Unit) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // You can only toast on the main thread.
        GlobalScope.launch(Dispatchers.Main) {
            context.toast(
                context.getString(
                    toastMsg,
                    error.simpleMessage()
                )
            )
        }

        Log.e(tag, logMsg, error)
    }
}

fun Throwable.simpleMessage() = this.message ?: this.cause?.message ?: this::class.simpleName

inline fun LifecycleOwner.launchAndRepeatWithViewLifecycle(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline block: suspend CoroutineScope.() -> Unit
) {
    lifecycleScope.launch {
        lifecycle.repeatOnLifecycle(minActiveState) {
            block()
        }
    }
}

/**
 * Run [transformer] on the [Iterable] and then [combine] the result using [combiner].
 * This is used to transform collections that contain [Flow]s into something that is easier to work with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T, reified R, C> Flow<Iterable<T>>.flatMapLatestAndCombine(
    crossinline combiner: suspend (Array<R>) -> C,
    crossinline transformer: suspend (T) -> Flow<R>,
): Flow<C> = flatMapLatest { iterable ->
    combine(iterable.map { transformer(it) }) {
        combiner(it)
    }
}

val Color.hexCode: String
    inline get() {
        val a: Int = (alpha * 255).toInt()
        val r: Int = (red * 255).toInt()
        val g: Int = (green * 255).toInt()
        val b: Int = (blue * 255).toInt()
        return java.lang.String.format(Locale.getDefault(), "%02X%02X%02X%02X", r, g, b, a)
    }

suspend fun <T> Flow<Iterable<T>>.collectEach(block: suspend (T) -> Unit) {
    this.collect { iterable ->
        iterable.forEach {
            block(it)
        }
    }
}

fun LocalDateTime.relativeTime(context: Context): String {
    try {
        val now = Clock.System.now()
        val duration = now - this.toInstant(TimeZone.UTC)

        return when {
            duration.inWholeMinutes < 1 -> context.getString(R.string.just_now)
            duration.inWholeMinutes < 60 -> context.getString(
                R.string.minutes_ago,
                duration.inWholeMinutes.toString()
            )

            duration.inWholeHours < 24 -> context.getString(
                R.string.hours_ago,
                duration.inWholeHours.toString()
            )

            duration.inWholeHours < 30 -> context.getString(
                R.string.days_ago,
                duration.inWholeDays.toString()
            )

            else -> LocalDateTime.Format {
                monthName(MonthNames.ENGLISH_ABBREVIATED)
                char(' ')
                dayOfMonth()
                if (now.toLocalDateTime(TimeZone.UTC).year != this@relativeTime.year) {
                    chars(", ")
                    year()
                }
            }.format(this)
        }
    } catch (e: IllegalArgumentException) {
        return context.getString(R.string.invalid_date)
    }
}

fun Instant.relativeTime(context: Context): String {
    try {
        val now = Clock.System.now()
        val duration = now - this

        return when {
            duration.inWholeMinutes < 1 -> context.getString(R.string.just_now)
            duration.inWholeMinutes < 60 -> context.getString(
                R.string.minutes_ago,
                duration.inWholeMinutes.toString()
            )

            duration.inWholeHours < 24 -> context.getString(
                R.string.hours_ago,
                duration.inWholeHours.toString()
            )

            duration.inWholeHours < 30 -> context.getString(
                R.string.days_ago,
                duration.inWholeDays.toString()
            )

            else -> {
                val localDateTime = this.toLocalDateTime(TimeZone.UTC)
                LocalDateTime.Format {
                    monthName(MonthNames.ENGLISH_ABBREVIATED)
                    char(' ')
                    dayOfMonth()
                    if (now.toLocalDateTime(TimeZone.UTC).year != localDateTime.year) {
                        chars(", ")
                        year()
                    }
                }.format(localDateTime)
            }
        }
    } catch (e: IllegalArgumentException) {
        return context.getString(R.string.invalid_date)
    }
}

fun Long.relativeTime(context: Context): String {
    if (this <= 0L) return context.getString(R.string.invalid_date)
    return DateUtils.getRelativeTimeSpanString(
        this,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

/**
 * The default ListItem colors, but with [ListItemColors.containerColor] set to [Color.Transparent].
 */
val transparentListItemColors
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun <T> EventEffect(flow: Flow<T>, vararg keys: Any?, state: Lifecycle.State = Lifecycle.State.STARTED, block: suspend (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)

    LaunchedEffect(flow, state, *keys) {
        lifecycleOwner.repeatOnLifecycle(state) {
            flow.collect {
                currentBlock(it)
            }
        }
    }
}

@Composable
fun LazyListState.isScrollingUp(): State<Boolean> {
    val tracker = remember(this) {
        ScrollDirectionTracker(initialIndex = firstVisibleItemIndex,
            initialOffset = firstVisibleItemScrollOffset)
    }
    val scrollingUp = remember(this) { mutableStateOf(true) }
    LaunchedEffect(this) {
        snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }
            .collect { (index, offset) -> scrollingUp.value = tracker.update(index, offset) }
    }
    return scrollingUp
}

@Composable
fun ScrollState.isScrollingUp(): State<Boolean> {
    val tracker = remember(this) { ScrollDirectionTracker(initialOffset = value) }
    val scrollingUp = remember(this) { mutableStateOf(true) }
    LaunchedEffect(this) {
        snapshotFlow { value }.collect { scrollingUp.value = tracker.update(0, it) }
    }
    return scrollingUp
}

val LazyListState.isScrollingUp: Boolean @Composable get() = this.isScrollingUp().value
val ScrollState.isScrollingUp: Boolean @Composable get() = this.isScrollingUp().value

@Composable
@ReadOnlyComposable
fun <R> (() -> R).withHapticFeedback(constant: Int): () -> R {
    val view = LocalView.current
    return {
        view.performHapticFeedback(constant)
        this()
    }
}

@Composable
@ReadOnlyComposable
fun <T, R> ((T) -> R).withHapticFeedback(constant: Int): (T) -> R {
    val view = LocalView.current
    return {
        view.performHapticFeedback(constant)
        this(it)
    }
}

fun Modifier.enabled(condition: Boolean) = if (condition) this else alpha(0.5f)

@Composable
fun Modifier.longPressOnly(
    label: String,
    onLongPress: () -> Unit
): Modifier {
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { currentOnLongPress() })
        }
        .semantics {
            onLongClick(label = label) {
                currentOnLongPress()
                true
            }
        }
}

@MainThread
fun <T : Any> SavedStateHandle.saveableVar(init: () -> T): PropertyDelegateProvider<Any?, ReadWriteProperty<Any?, T>> =
    PropertyDelegateProvider { _: Any?, property ->
        val name = property.name
        if (name !in this) this[name] = init()
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = get(name)!!
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) =
                set(name, value)
        }
    }

fun <T : Any> SavedStateHandle.saveableVar(): ReadWriteProperty<Any?, T?> =
    object : ReadWriteProperty<Any?, T?> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T? = get(property.name)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) =
            set(property.name, value)
    }
