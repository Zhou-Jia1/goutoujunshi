package com.shengjidaguai.goutoujunshi.counsel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T41EncryptedStyleIntegrationTest {
    private class Sandbox(base: Context): ContextWrapper(base) {
        private val nonce=UUID.randomUUID().toString().replace("-", "")
        private val root=File(base.filesDir,"t41-memory-$nonce").apply { check(mkdirs() && isDirectory) { "Unable to create unique memory test sandbox" } }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "${baseContext.packageName}.t41.$nonce"
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = File(root,"cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root,"no-backup").apply { mkdirs() }
        fun preferenceFile(name: String): File = File(baseContext.applicationInfo.dataDir,"shared_prefs/t41-$nonce-$name.xml")
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = baseContext.getSharedPreferences("t41-$nonce-$name",mode)
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences("t41-$nonce-$name")
        override fun getDatabasePath(name: String): File = File(root,"databases/$name").also { it.parentFile!!.mkdirs() }
        override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    @Test fun realCipherConfirmedLearningSourceDeletionAndQuizUseCurrentConsent()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val context=Sandbox(target)
        val store=EncryptedDataStore(context)
        val temporary=TemporarySessionStore()
        try {
            assertNotEquals(target.packageName,context.packageName)
            assertTrue(context.getDatabasePath("junshi.db").canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator))
            val session=store.open()
            val contacts=ContactRepository(session)
            contacts.save(Contact("native-owner","合成朋友",RelationType.FRIEND))
            ConsentRepository(session).update(true)
            val source=ChatImport("native-import","native-owner",ImportSource.PASTE,"native-synthetic-hash")
            val lines=(0..15).map {ChatLine("native-line$it",source.id,seq=it,speaker=Speaker.ME,text="合成本人表达$it，嗯好呀～",speakerConfirmed=true,contactId="native-owner")}
            ImportRepository(session).save(source,lines)
            val styles=StyleRepository(session)
            assertEquals(16,styles.samples(RelationGroup.SOCIAL).size)
            val original=styles.samples(RelationGroup.SOCIAL).first()
            styles.editSample(original.id,"合成用户明确改写的风格示例",styles.snapshot().stamp)
            val edited=styles.samples(RelationGroup.SOCIAL).single {it.id==original.id}
            assertEquals(original.text,edited.text);assertEquals(original.sourceMessageId,edited.sourceMessageId)
            assertEquals("合成用户明确改写的风格示例",edited.effectiveText);assertNotNull(edited.userEdit)
            assertEquals(original.text,ImportRepository(session).lines("native-owner",source.id).single {it.id==original.sourceMessageId}.text)
            val stamp=styles.snapshot().stamp
            styles.editDescription(RelationGroup.SOCIAL,"习惯短句与自然语气词",stamp)
            assertEquals("习惯短句与自然语气词",styles.profile(RelationGroup.SOCIAL)!!.description)
            ConsentRepository(session).update(true,true)
            assertEquals(StyleOutboundContext(),styles.context("native-owner",RelationGroup.SOCIAL))
            ConsentRepository(session).update(true,false)
            assertEquals("习惯短句与自然语气词",styles.profile(RelationGroup.SOCIAL)!!.description)
            assertTrue(runCatching {styles.editDescription(RelationGroup.SOCIAL,"迟到模型结果",stamp)}.isFailure)
            ImportRepository(session).delete("native-owner",source.id)
            assertTrue(styles.samples(RelationGroup.SOCIAL).isEmpty());assertNull(styles.profile(RelationGroup.SOCIAL))
            styles.saveQuiz(List(5) {"合成测验选择$it"},styles.snapshot().stamp)
            assertEquals(5,styles.samples(RelationGroup.DEFAULT).size)
            ConsentRepository(session).update(true,true)
            assertEquals(StyleOutboundContext(),styles.context("native-owner",RelationGroup.SOCIAL))
            assertTrue(runCatching {styles.saveQuiz(List(5) {"暂停后选择$it"},styles.snapshot().stamp)}.isFailure)
        } finally {store.clearAll(temporary)}
    }
}
