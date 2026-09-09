package me.river.remoter.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object Space {
    val s4 = 4.dp
    val s8 = 8.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val s32 = 32.dp
    val s48 = 48.dp
    val s64 = 64.dp

    val gutter = s16
    val section = s24
    val cardPadding = s16
    val sheetPadding = s24
}

object Touch {
    /** min hit area, even when the visual is smaller */
    val min = 48.dp
    val row = 64.dp
    val primaryButton = 56.dp
    val searchPill = 56.dp
}

object Shapes {
    val pill = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val card = RoundedCornerShape(20.dp)
    val technical = RoundedCornerShape(4.dp)
}
