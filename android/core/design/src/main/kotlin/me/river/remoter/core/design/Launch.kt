package me.river.remoter.core.design

import android.content.Context
import android.content.Intent
import android.net.Uri

fun openWireGuard(context: Context) {
    val i = context.packageManager.getLaunchIntentForPackage("com.wireguard.android")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.wireguard.android"))
    runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
