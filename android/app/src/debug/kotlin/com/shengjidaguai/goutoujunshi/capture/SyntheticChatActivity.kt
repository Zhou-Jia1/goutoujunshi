package com.shengjidaguai.goutoujunshi.capture
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*
/** Fictional local target. No grants or capture bypasses. */
class SyntheticChatActivity:ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  @Suppress("DEPRECATION")
  setTaskDescription(android.app.ActivityManager.TaskDescription(getString(R.string.capture_synthetic_title)))
  setContent { JunshiTheme { SyntheticChatContent() } }
 }
}
@Composable internal fun SyntheticChatContent() {
 Surface(Modifier.fillMaxSize()) {
  var earlier by remember { mutableStateOf(false) }
  JunshiColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
   JunshiTopBar(stringResource(R.string.capture_synthetic_title),actions={ BodyText(stringResource(R.string.capture_synthetic_peer)) })
   if(earlier) SyntheticBubble("earlier",R.string.capture_synthetic_prior,false)
   SyntheticBubble("one",R.string.capture_synthetic_one,false)
   if(!earlier) SyntheticBubble("two",R.string.capture_synthetic_two,true)
   if(!earlier) SyntheticBubble("three",R.string.capture_synthetic_three,false)
   Spacer(Modifier.weight(1f))
   SecondaryButton(stringResource(R.string.capture_synthetic_earlier),{ earlier=true })
  }
 }
}
@Composable private fun SyntheticBubble(id:String,text:Int,me:Boolean) {
 Box(Modifier.fillMaxWidth(),contentAlignment=if(me) androidx.compose.ui.Alignment.CenterEnd else androidx.compose.ui.Alignment.CenterStart) {
  ImportPreviewBubble(ChatLineModel(id,stringResource(text),if(me) "" else stringResource(R.string.capture_synthetic_peer),me),{})
 }
}
