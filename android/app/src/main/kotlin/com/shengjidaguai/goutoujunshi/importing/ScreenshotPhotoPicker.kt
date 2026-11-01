package com.shengjidaguai.goutoujunshi.importing

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/** AndroidX prefers the system Photo Picker and supplies OEM/document fallbacks without GMS.
 * Returned URI order is kept in RAM; fallback providers may ignore the selection limit, so the
 * coordinator and decoder also enforce it before any image is read. */
internal object ScreenshotPhotoPicker {
    const val MAX_IMAGES=20
    fun contract()=ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    fun request()=PickVisualMediaRequest(
        mediaType=ActivityResultContracts.PickVisualMedia.ImageOnly,
        maxItems=MAX_IMAGES,
        isOrderedSelection=true,
    )
}
