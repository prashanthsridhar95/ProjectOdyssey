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

/**
 * Where a vertical plugs its own pieces into the generic features. For car wash this is the garage: the vehicle form,
 * the vehicle's icon and which price column (hatchback, sedan...) it uses. A vertical with nothing to save returns null.
 */
interface VerticalUi {
    /** What the customer saves ("Vehicle"). */
    val assetNoun: String
    /** Full-screen form that saves a new asset and reports it. [stepText] is shown in the header during onboarding. */
    @Composable fun AssetForm(stepText: String?, onBack: (() -> Unit)?, onSaved: (com.odyssey.domain.Asset) -> Unit)
    fun variantOf(asset: com.odyssey.domain.Asset): String?
    fun iconFor(asset: com.odyssey.domain.Asset): androidx.compose.ui.graphics.vector.ImageVector
    fun iconForVariant(key: String): androidx.compose.ui.graphics.vector.ImageVector
    /** Quick positives shown when rating. */
    val reviewTags: List<String> get() = listOf("On time", "Friendly staff", "Good value")
    /** What can go wrong, as (server category, label). The server accepts QUALITY, DAMAGE, WAIT and CHARGE. */
    val reportCategories: List<Pair<String, String>> get() = listOf("QUALITY" to "Quality", "DAMAGE" to "Damage", "WAIT" to "Long wait", "CHARGE" to "Charged incorrectly")
    /** The noun in headings like "How was your wash?". */
    val serviceNoun: String get() = "service"
}

val LocalVertical = staticCompositionLocalOf<VerticalUi?> { null }
