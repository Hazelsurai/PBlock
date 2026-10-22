package com.saworks.pblock.core

/** Pure decisions for [com.saworks.pblock.data.ProtectionMonitor]. */
object MonitorLogic {
    /** Short outages (boot, service restart) are not reported. */
    const val GRACE_MS = 3 * 60_000L

    enum class Action { NONE, START, LOG_OFF, LOG_ON, CLEAR }

    /** Boots in between that PBlock never ran in (safe mode, or a boot without unlocking). */
    fun missedBoots(lastSeen: Int, current: Int): Int =
        if (lastSeen < 0 || current < 0 || current <= lastSeen) 0 else current - lastSeen - 1

    /**
     * @param since start of the current outage in this boot, 0 if none
     * @param logged the "off" event for this outage was already written
     */
    fun step(up: Boolean, since: Long, logged: Boolean, now: Long): Action = when {
        !up && since <= 0 -> Action.START
        !up && !logged && now - since >= GRACE_MS -> Action.LOG_OFF
        !up -> Action.NONE
        since <= 0 -> Action.NONE
        now - since >= GRACE_MS -> Action.LOG_ON
        else -> Action.CLEAR
    }
}
