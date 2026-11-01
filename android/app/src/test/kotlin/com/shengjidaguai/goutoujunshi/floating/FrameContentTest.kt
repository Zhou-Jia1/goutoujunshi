package com.shengjidaguai.goutoujunshi.floating
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class FrameContentTest {
 @Test fun blackOrDarkBlankIsUnavailableWithoutClaimingProtection() {
  val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
  try { bitmap.eraseColor(Color.BLACK);assertFalse(frameHasContent(bitmap))
   bitmap.eraseColor(Color.rgb(20,20,20));assertFalse(frameHasContent(bitmap))
  } finally { bitmap.recycle() }
 }
 @Test fun darkScreenWithTextIsStillContent() {
  val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
  try { bitmap.eraseColor(Color.rgb(20,20,20));bitmap.setPixel(50,50,Color.WHITE);assertTrue(frameHasContent(bitmap)) }
  finally { bitmap.recycle() }
 }
}
