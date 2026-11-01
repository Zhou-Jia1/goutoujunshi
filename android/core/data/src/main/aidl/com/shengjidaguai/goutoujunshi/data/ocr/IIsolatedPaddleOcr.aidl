package com.shengjidaguai.goutoujunshi.data.ocr;
import android.graphics.Bitmap;
import com.shengjidaguai.goutoujunshi.data.ocr.IPaddleOcrCallback;
interface IIsolatedPaddleOcr {
    String isolationStatus();
    void recognize(long requestId, in Bitmap bitmap, IPaddleOcrCallback callback);
    void cancel(long requestId);
}
