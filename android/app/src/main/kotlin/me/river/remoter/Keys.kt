package me.river.remoter

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import me.river.remoter.feature.onboarding.PairAgainReason

@Serializable data class Onboarding(val pairAgain: PairAgainReason? = null) : NavKey
/** Retired with app lock; kept so a saved back stack that holds it still restores. */
@Serializable data object Lock : NavKey
@Serializable data object Home : NavKey
@Serializable data class Browser(val path: String, val focusSearch: Boolean = false) : NavKey
@Serializable data class Session(val id: String) : NavKey
@Serializable data object Settings : NavKey
@Serializable data object Audit : NavKey
