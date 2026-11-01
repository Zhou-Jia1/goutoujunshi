package com.shengjidaguai.goutoujunshi.floating

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*

@Composable
internal fun FloatingMenuBall(side: Side, openMenu: () -> Unit) {
    val label=stringResource(R.string.floating_ball_menu)
    FloatingBall(FloatingBallState.IDLE,side,Modifier.clearAndSetSemantics {
        contentDescription=label
        onClick { openMenu(); true }
        onLongClick { openMenu(); true }
    })
}
