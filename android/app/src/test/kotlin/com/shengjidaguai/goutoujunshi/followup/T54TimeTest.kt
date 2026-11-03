package com.shengjidaguai.goutoujunshi.followup
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class T54TimeTest {
 @Test fun originalDueAndClockRollbackNeverAdvanceNotification() {
  val due=Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
  assertEquals(due,FollowupTime.eligible(due,due-3600000,ZoneId.of("UTC")))
 }
 @Test fun currentZoneAndDstAreResolvedAtExecution() {
  val now=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
  val ny=FollowupTime.eligible(now,now,ZoneId.of("America/New_York"))
  assertEquals(Instant.parse("2026-11-01T13:00:00Z").toEpochMilli(),ny)
  assertEquals(now,FollowupTime.eligible(now,now,ZoneId.of("Asia/Shanghai")))
 }
}
