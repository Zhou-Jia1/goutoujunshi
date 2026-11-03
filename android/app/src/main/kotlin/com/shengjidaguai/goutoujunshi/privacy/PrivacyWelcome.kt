package com.shengjidaguai.goutoujunshi.privacy

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*

@Composable internal fun PrivacyWelcome(start:()->Unit) {
    JunshiColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EmptyState(Illustration.SHIELD,stringResource(R.string.t55_cleared),stringResource(R.string.t55_restart_notice))
        PrimaryButton(stringResource(R.string.t55_restart),start)
    }
}
