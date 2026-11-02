package com.shengjidaguai.goutoujunshi.floating

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shengjidaguai.goutoujunshi.capture.SyntheticChatContent
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme

/** T33 owns this task; the T32 synthetic renderer remains read-only and unchanged. */
class T33SyntheticCaptureActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JunshiTheme { SyntheticChatContent() } }
    }
}
