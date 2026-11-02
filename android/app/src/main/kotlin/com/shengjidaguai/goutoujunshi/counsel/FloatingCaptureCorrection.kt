package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide
import com.shengjidaguai.goutoujunshi.importing.*

/** Render-only receipt. The capture rows and all scope authority remain process-local below. */
data class FloatingCaptureView(val id:String,val mySide:OcrSide,val busy:Boolean=false,val failed:Boolean=false,val canSwapPhysicalSide:Boolean=true)
internal data class ConfirmedFloatingCapture(
    val id:String,val ticket:Long,val origin:String,val contactId:String?,val contactRevision:Long,
    val conversationRevision:Long,val epoch:Long,val lock:Long,val config:String,val inputRevision:Long,
    val draft:String,val evidence:List<EvidenceBlock>,val imported:ChatImport,val rows:List<ImportRow>,val mySide:OcrSide,
    val binding:FloatingIdentityBinding?=null,val provenance:LiveCaptureProvenance?=null,
)
internal data class FloatingSideOperation(val before:ConfirmedFloatingCapture,val change:CapturedImportReplacement,
    val mySide:OcrSide,val rows:List<ImportRow>,val epoch:Long,val lock:Long,var busy:Boolean=true,var failed:Boolean=false)
internal object FloatingCaptureRows {
    fun canSwap(rows:List<ImportRow>)=rows.any { it.side==OcrSide.LEFT || it.side==OcrSide.RIGHT }
    fun swap(rows:List<ImportRow>,side:OcrSide):List<ImportRow> {
        require(side!=OcrSide.UNKNOWN && canSwap(rows))
        val group=rows.map { it.name }.filter { it.isNotBlank() }.distinct().size>1
        return rows.map { row -> if(row.side==OcrSide.UNKNOWN || row.side==null) row else row.copy(speaker=if(row.side==side) Speaker.ME else if(group && row.name.isNotBlank()) Speaker.OTHER else Speaker.THEM) }
    }
}

/** An explicit continuation of one confirmed capture; never persisted or inferred from nicknames. */
internal data class FloatingCaptureMore(val id:String,val before:ConfirmedFloatingCapture,val epoch:Long,val lock:Long)

/** One RAM handoff from one confirmed preview to its real saved source. */
internal data class CaptureConfirmation(val previewId:String,val ticket:Long,val origin:String,val captureId:String,
    val actualSavedId:String,val epoch:Long,val lock:Long,val contactId:String?,val contactRevision:Long,
    val conversationRevision:Long,val inputRevision:Long,val config:String,val receiver:String)
internal data class CaptureReceiver(val token:String,val previewId:String,val ticket:Long,val epoch:Long,val lock:Long)
