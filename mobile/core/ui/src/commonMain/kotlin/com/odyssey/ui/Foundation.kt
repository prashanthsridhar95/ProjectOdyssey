package com.odyssey.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odyssey.data.AppContainer
import com.odyssey.domain.ApiException
import com.odyssey.domain.PublicConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The wiring every screen reads from. Apps provide it once at the root. */
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** The tenant's words, vehicle types and policies, loaded at start-up. */
val LocalTenant = staticCompositionLocalOf<PublicConfig> { error("Tenant config not provided") }

/** What a screen shows for data that loads. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

fun Throwable.friendly(): String = (this as? ApiException)?.message ?: "Something went wrong. Please try again."

/** Base for view-models: loads data into a [Load] flow and runs one-off actions with error reporting. */
abstract class BaseViewModel : ViewModel() {
    protected fun <T> MutableStateFlow<Load<T>>.load(block: suspend () -> T) {
        if (value !is Load.Ready) value = Load.Loading
        viewModelScope.launch {
            value = try { Load.Ready(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Failed(e.friendly()) }
        }
    }

    protected fun run(onError: (String) -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.friendly()) }
        }
    }
}

/** Creates (and keeps across recompositions and rotation) a view-model for the current screen. */
@Composable
inline fun <reified VM : ViewModel> screenModel(key: String? = null, crossinline create: () -> VM): VM = viewModel(key = key) { create() }

@Composable
fun <T> StateFlow<T>.collectAsStateValue(): T = collectAsState().value

/** A simple back stack. Apps map system back to [pop]. */
class Navigator<R : Any>(start: R) {
    private val stack = mutableStateListOf(start)
    val current: R get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(route: R) { stack.add(route) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun reset(route: R) { stack.clear(); stack.add(route) }
    fun replace(route: R) { stack[stack.lastIndex] = route }
}

/** Where a vertical plugs its own screens into the generic features (for car wash: the garage and vehicle form). */
interface VerticalUi {
    /** Lets the customer choose or add the thing being served (a vehicle). Null when the vertical has none. */
    @Composable fun AssetPicker(selectedId: String?, variantKey: String, onSelected: (assetId: String?, variantKey: String?) -> Unit)
}

val LocalVertical = staticCompositionLocalOf<VerticalUi?> { null }
