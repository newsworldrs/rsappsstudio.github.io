package com.rskusum.whocaller.core.common

import javax.inject.Inject
import javax.inject.Qualifier

/** Injectable time source so time-dependent logic (rate limits, recency) is testable. */
fun interface Clock {
    fun now(): Long
}

class SystemClock @Inject constructor() : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

object TimeUnits {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}
