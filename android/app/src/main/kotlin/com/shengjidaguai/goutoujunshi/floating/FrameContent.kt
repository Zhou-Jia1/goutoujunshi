package com.shengjidaguai.goutoujunshi.floating
import android.graphics.Bitmap
/** Uniform pixels do not prove capture protection. Preserve dark screens containing glyphs. */
internal fun frameHasContent(bitmap:Bitmap):Boolean {
 val row=IntArray(bitmap.width)
 val first=bitmap.getPixel(0,0) and 0x00ffffff
 for(y in 0 until bitmap.height) {
  bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
  if(row.any { (it and 0x00ffffff)!=first }) return true
 }
 return false
}
