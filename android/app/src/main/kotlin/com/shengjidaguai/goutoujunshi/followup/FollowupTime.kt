package com.shengjidaguai.goutoujunshi.followup
import java.time.*
object FollowupTime {
    fun eligible(due:Long,now:Long,zone:ZoneId):Long {
        val earliest=Instant.ofEpochMilli(maxOf(due,now)).atZone(zone)
        // Resolve in the current zone on every execution; atZone handles gaps/overlaps.
        return when {
            earliest.hour>=23 -> earliest.toLocalDate().plusDays(1).atTime(8,0).atZone(zone).toInstant().toEpochMilli()
            earliest.hour<8 -> earliest.toLocalDate().atTime(8,0).atZone(zone).toInstant().toEpochMilli()
            else -> earliest.toInstant().toEpochMilli()
        }.coerceAtLeast(maxOf(due,now))
    }
}
