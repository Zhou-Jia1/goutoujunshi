package com.shengjidaguai.goutoujunshi.floating
internal object FloatingPanelGeometry {
    fun height(screen: Int, requested: Int) = requested.coerceIn(maxOf(1, (screen * .20f).toInt()), maxOf(1, (screen * .55f).toInt()))
}
