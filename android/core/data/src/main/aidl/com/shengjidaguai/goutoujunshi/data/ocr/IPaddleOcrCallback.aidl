package com.shengjidaguai.goutoujunshi.data.ocr;
oneway interface IPaddleOcrCallback {
    void complete(long requestId, String result);
}
