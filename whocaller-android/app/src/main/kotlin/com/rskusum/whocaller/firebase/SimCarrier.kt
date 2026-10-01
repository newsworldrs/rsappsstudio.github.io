package com.rskusum.whocaller.firebase

import android.content.Context
import com.rskusum.whocaller.core.ui.util.SimCards

/**
 * The mobile network the user's own SIM is on right now, as Settings → SIM shows it. After number
 * portability this differs from the network that first issued the number (all a prefix can tell).
 */
object SimCarrier {
    /** Network of the SIM that holds [e164]; null rather than a guess. */
    fun forNumber(context: Context, e164: String): String? = SimCards.forNumber(context, e164)?.network
}
