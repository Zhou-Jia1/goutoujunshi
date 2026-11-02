package com.shengjidaguai.goutoujunshi.counsel

import android.content.SharedPreferences
import com.shengjidaguai.goutoujunshi.domain.RelationGroup

/** Non-sensitive numeric preferences only; contains no samples, identifiers or consent. */
interface StyleBiasPreferences {
    fun get(group:RelationGroup):Float
    fun set(group:RelationGroup,value:Float)
    fun clear()
}
class MemoryStyleBiasPreferences:StyleBiasPreferences {
    private val values=mutableMapOf<RelationGroup,Float>()
    override fun get(group:RelationGroup)=values[group] ?: 0.5f
    override fun set(group:RelationGroup,value:Float) {values[group]=value}
    override fun clear() {values.clear()}
}
class LocalStyleBiasPreferences(private val preferences:SharedPreferences):StyleBiasPreferences {
    override fun get(group:RelationGroup)=preferences.getFloat(group.name,0.5f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 0.5f
    override fun set(group:RelationGroup,value:Float) {check(preferences.edit().putFloat(group.name,value.coerceIn(0f,1f)).commit())}
    override fun clear() {check(preferences.edit().clear().commit())}
}
