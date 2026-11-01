package com.shengjidaguai.goutoujunshi.importing

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.MediaStore
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest.Fixture
import com.shengjidaguai.goutoujunshi.domain.ocr.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class ScreenshotPhotoPickerTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun supportedApiLaunchesImageOnlyOrderedPhotoPickerWithTwentyLimit() {
        val intent=ScreenshotPhotoPicker.contract().createIntent(context,ScreenshotPhotoPicker.request())
        assertEquals(MediaStore.ACTION_PICK_IMAGES,intent.action)
        assertEquals("image/*",intent.type)
        assertEquals(20,intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,-1))
        assertTrue(intent.getBooleanExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER,false))
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    @Test @Config(sdk=[28]) fun noPickerOrGmsFallsBackToMultipleImageOpenDocument() {
        val intent=ScreenshotPhotoPicker.contract().createIntent(context,ScreenshotPhotoPicker.request())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
        assertEquals("image/*",intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    @Test @Config(sdk=[28]) fun systemOemPhotoPickerIsPreferredOverDocumentFallback() {
        val action="androidx.activity.result.contract.action.PICK_IMAGES"
        val info=ResolveInfo().apply { activityInfo=ActivityInfo().apply {
            name="SyntheticPicker"; packageName="synthetic.picker"
            applicationInfo=ApplicationInfo().apply { packageName="synthetic.picker"; flags=ApplicationInfo.FLAG_SYSTEM }
        } }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(action),info)
        val intent=ScreenshotPhotoPicker.contract().createIntent(context,ScreenshotPhotoPicker.request())
        assertEquals(action,intent.action)
        assertEquals("synthetic.picker",intent.component!!.packageName)
        assertEquals("image/*",intent.type)
        assertEquals(20,intent.getIntExtra("androidx.activity.result.contract.extra.PICK_IMAGES_MAX",-1))
        assertTrue(intent.getBooleanExtra("androidx.activity.result.contract.extra.PICK_IMAGES_IN_ORDER",false))
    }
    @Test fun parserKeepsProviderOrderAndCancellationProducesNoImages() {
        val second=Uri.parse("content://synthetic/second");val first=Uri.parse("content://synthetic/first")
        val clip=ClipData.newRawUri("synthetic",second).apply { addItem(ClipData.Item(first));addItem(ClipData.Item(second)) }
        val result=Intent().apply { clipData=clip }
        assertEquals(listOf(second,first),ScreenshotPhotoPicker.contract().parseResult(Activity.RESULT_OK,result))
        assertTrue(ScreenshotPhotoPicker.contract().parseResult(Activity.RESULT_CANCELED,result).isEmpty())
    }
    @Test fun fallbackOverLimitIsRejectedBeforeReadingWithoutSilentlyTruncating()=runBlocking {
        val f=Fixture();f.controller.initialize();var reads=0
        val importer=ImportCoordinator(f.controller,f.scope,{true},{},{ reads++;emptyList() })
        try {
            val uris=(1..21).map { Uri.parse("content://synthetic/$it") }
            importer.images(uris);importer.recognize()
            assertEquals(0,reads)
            assertNotNull(importer.state.value!!.error)
            assertFalse(importer.state.value!!.canConfirm)
            assertEquals(uris,importer.state.value!!.images)
            assertTrue(f.persistence.imports.isEmpty())
        } finally { importer.cancel();f.close() }
    }
    @Test fun twentyImagesKeepReturnedOrderAndStayUnconfirmedInRam()=runBlocking {
        val f=Fixture();f.controller.initialize();val uris=(20 downTo 1).map { Uri.parse("content://synthetic/$it") }
        val importer=ImportCoordinator(f.controller,f.scope,{true},{},{ received ->
            assertEquals(uris,received)
            received.map { listOf(OcrLine("合成${it.lastPathSegment}",.99f,.1f,.4f,.3f,.45f)) }
        })
        try {
            importer.images(uris);importer.recognize()
            assertEquals(uris,importer.state.value!!.images)
            assertTrue(importer.state.value!!.recognized)
            assertFalse(importer.state.value!!.canConfirm)
            assertTrue(f.persistence.imports.isEmpty())
        } finally { importer.cancel();f.close() }
    }
    @Test fun nonContentUriIsRejectedBeforeAnyRead()=runBlocking {
        val f=Fixture();f.controller.initialize();var reads=0
        val importer=ImportCoordinator(f.controller,f.scope,{true},{},{ reads++;emptyList() })
        try {
            importer.images(listOf(Uri.parse("file:///synthetic.png")));importer.recognize()
            assertEquals(0,reads);assertNotNull(importer.state.value!!.error)
        } finally { importer.cancel();f.close() }
    }
}
