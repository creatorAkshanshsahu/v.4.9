package com.aktv.app

/** Single-shot preparation gate shared by surface-created/changed callbacks. No stale player may start. */
internal class SurfaceStartGate(
    private val active: () -> Boolean,
    private val ready: () -> Boolean,
    private val prepare: () -> Unit
) {
    private var started = false
    fun signal() {
        if (started || !active() || !ready()) return
        started = true
        prepare()
    }
}
