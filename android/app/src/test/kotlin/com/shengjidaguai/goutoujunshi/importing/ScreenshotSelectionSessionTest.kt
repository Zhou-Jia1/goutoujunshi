package com.shengjidaguai.goutoujunshi.importing

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest.Fixture
import com.shengjidaguai.goutoujunshi.counsel.CounselViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class ScreenshotSelectionSessionTest {
    private val uris=listOf(Uri.parse("content://synthetic/old-picker"))
    @Test fun noLaunchTicketCannotImportLatePickerResultAfterProcessRecreation() {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        try {
            val vm=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            vm.importImages(uris)
            assertNull(c.state.value)
        } finally { c.cancel();f.close() }
    }
    @Test fun twoActivityViewModelsCannotLetOldPickerOverwriteNewSharedPreview()=runBlocking {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        try {
            val a=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            val b=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            assertTrue(a.launchImagePicker {})
            b.importPaste("合成新分享：保留新预览")
            withTimeout(3000) { while(c.state.value?.busy==true) yield() }
            val fresh=c.state.value!!
            assertFalse(a.launchImagePicker { error("must not reenter") })
            a.importImages(uris)
            assertEquals(fresh.id,c.state.value!!.id)
            assertEquals("保留新预览",c.state.value!!.rows.single().text)
        } finally { c.cancel();f.close() }
    }
    @Test fun ownerClearAndCancelInvalidateButKeepSlotUntilTerminalResult() {
        for(change in listOf("owner","clear","cancel")) {
            val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
            try {
                val vm=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
                assertTrue(vm.launchImagePicker {})
                when(change) { "owner" -> vm.newTemporary(); "clear" -> vm.clearAll(); else -> c.cancel() }
                assertFalse(vm.launchImagePicker { error("old result still outstanding") })
                val epoch=f.controller.importAuthority
                vm.importImages(uris)
                assertNull(c.state.value);assertEquals(epoch,f.controller.importAuthority)
                if(change=="clear") vm.initialize() // clear intentionally returns to an uninitialized welcome state
                assertTrue(vm.launchImagePicker {})
                vm.importImages(emptyList())
                assertNull(c.state.value)
            } finally { c.cancel();f.close() }
        }
    }
    @Test fun cancelAndDuplicateCallbacksDoNotBeginOrCancelAnotherPreview() {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        try {
            val vm=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            assertTrue(vm.launchImagePicker {})
            val epoch=f.controller.importAuthority
            vm.importImages(emptyList())
            assertNull(c.state.value);assertEquals(epoch,f.controller.importAuthority)
            assertTrue(vm.launchImagePicker {})
            vm.importImages(uris)
            val id=c.state.value!!.id;val after=f.controller.importAuthority
            vm.importImages(uris)
            assertEquals(id,c.state.value!!.id);assertEquals(after,f.controller.importAuthority)
        } finally { c.cancel();f.close() }
    }
    @Test fun configurationRetainsSameActivityViewModelTicketWithoutRelaunch() {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        val store=ViewModelStore()
        val factory=object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T =
                CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c) as T
        }
        try {
            val before=ViewModelProvider(store,factory)[CounselViewModel::class.java]
            var launches=0;assertTrue(before.launchImagePicker { launches++ })
            val recreated=ViewModelProvider(store,factory)[CounselViewModel::class.java]
            assertSame(before,recreated)
            assertFalse(recreated.launchImagePicker { launches++ })
            recreated.importImages(uris)
            assertEquals(1,launches);assertEquals(uris,c.state.value!!.images)
        } finally { store.clear();c.cancel();f.close() }
    }
    @Test fun latestActivityLaunchWinsAndWrongPickerKindCannotConsumeSlot() {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        try {
            val a=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            val b=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            assertTrue(a.launchImagePicker {});assertTrue(b.launchImagePicker {})
            b.importFile(null)
            assertFalse(b.launchFilePicker {})
            b.importImages(uris)
            val id=c.state.value!!.id
            a.importImages(uris)
            assertEquals(id,c.state.value!!.id)
        } finally { c.cancel();f.close() }
    }
    @Test fun filePickerUsesSameStaleResultGuard()=runBlocking {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        try {
            val a=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            val b=CounselViewModel(RuntimeEnvironment.getApplication(),f.controller,c)
            assertTrue(a.launchFilePicker {})
            b.importPaste("合成新分享：文件也不可覆盖")
            withTimeout(3000) { while(c.state.value?.busy==true) yield() }
            val fresh=c.state.value!!.id
            a.importFile(Uri.parse("content://synthetic/unreadable-file"))
            assertEquals(fresh,c.state.value!!.id)
        } finally { c.cancel();f.close() }
    }

    @Test fun currentFileTicketReadsThroughTheExistingFileImporter()=runBlocking {
        val f=Fixture();val c=ImportCoordinator(f.controller,f.scope,{true},{})
        try {
            val app=RuntimeEnvironment.getApplication()
            val vm=CounselViewModel(app,f.controller,c)
            val uri=Uri.parse("content://synthetic/current-file")
            shadowOf(app.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream("合成文件：保留文字".toByteArray()))
            assertTrue(vm.launchFilePicker {})
            vm.importFile(uri)
            withTimeout(3000) { while(c.state.value?.busy==true) yield() }
            assertEquals("保留文字",c.state.value!!.rows.single().text)
            assertEquals(com.shengjidaguai.goutoujunshi.domain.ImportSource.FILE,c.state.value!!.source)
            assertTrue(f.persistence.imports.isEmpty())
        } finally { c.cancel();f.close() }
    }

}
