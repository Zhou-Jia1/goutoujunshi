package com.shengjidaguai.goutoujunshi.floating
import android.content.Intent
internal class ProjectionGrant(val data:Intent,val lockRevision:Long)
/** Never placed in service Intents, bundles or preferences. Consumed once after FGS start. */
internal object ProjectionGrantStore {
 private var entry:Pair<Long,ProjectionGrant>?=null
 @Synchronized fun put(ticket:Long,data:Intent,lockRevision:Long) { entry=ticket to ProjectionGrant(data,lockRevision) }
 @Synchronized fun take(ticket:Long):ProjectionGrant? {
  if(entry?.first!=ticket) return null
  return entry?.second.also { entry=null }
 }
 @Synchronized fun clear(ticket:Long?=null) { if(ticket==null || entry?.first==ticket) entry=null }
}
