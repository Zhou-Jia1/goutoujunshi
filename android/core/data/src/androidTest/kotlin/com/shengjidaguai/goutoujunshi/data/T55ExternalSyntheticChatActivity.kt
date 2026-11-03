package com.shengjidaguai.goutoujunshi.data

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

/** Fixed synthetic pixels in the existing Data test APK, under its separate Linux UID.
 * No production dependency, credential, private data, permission or launcher is added. */
class T55ExternalSyntheticChatActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(intent.action==FINISH) {finish();return}
        val column=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE);setPadding(48,72,48,48)
        }
        listOf("T55 SYNTHETIC EXTERNAL CHAT","ALICE","Would you like to meet on Saturday?","ME","That works. Shall we meet at two?","ALICE","Yes, two o'clock is good.").forEachIndexed {index,text ->
            column.addView(TextView(this).apply {
                this.text=text;textSize=if(index==0)24f else 26f;setTextColor(Color.BLACK)
                setPadding(0,24,0,24)
                contentDescription=if(index==0) "T55_EXTERNAL_SYNTHETIC_CHAT" else null
            })
        }
        setContentView(column)
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);if(intent.action==FINISH) finish()}
    companion object {const val FINISH="T55_FINISH_EXTERNAL_SYNTHETIC_CHAT"}
}
