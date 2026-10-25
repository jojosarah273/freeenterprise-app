package com.freeenterprise.app

import com.freeenterprise.app.ff4ui.FF4Menu
import com.freeenterprise.ff4.Ff4Row

/**
 * Turns the shared row definitions into this app's menu items.
 *
 * The rows themselves live in `:core` so Android and the desktop read the same
 * labels, values and order. Only the action behind a row is platform-specific.
 */
fun List<Ff4Row>.toMenuItems(): List<FF4Menu.Item> = map {
    FF4Menu.Item(it.label, it.value.ifEmpty { null }, enabled = it.enabled)
}
