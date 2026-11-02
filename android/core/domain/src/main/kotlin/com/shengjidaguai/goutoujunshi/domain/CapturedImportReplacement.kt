package com.shengjidaguai.goutoujunshi.domain

/** A user-requested replacement, never an append or a nickname-based owner selection. */
data class CapturedImportReplacement(
    val oldImportId:String, val oldHash:String, val expectedSourceRevision:Long,
    val replacement:ChatImport, val lines:List<ChatLine>,
    val binding:FloatingIdentityBinding?=null, val expectedBindingRevision:Long=0,
    val provenance:LiveCaptureProvenance?=null,
    val reviewedFrames:List<List<ChatLine>> = emptyList(),
)
