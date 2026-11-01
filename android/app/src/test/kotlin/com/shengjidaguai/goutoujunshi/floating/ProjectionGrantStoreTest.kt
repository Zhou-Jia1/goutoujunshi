package com.shengjidaguai.goutoujunshi.floating
import android.app.Activity
import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class ProjectionGrantStoreTest {
 @Test fun grantIsOneUseAndOldOwnerCannotClearReplacement() {
  ProjectionGrantStore.put(1,Intent("one"),4);ProjectionGrantStore.put(2,Intent("two"),5)
  ProjectionGrantStore.clear(1)
  assertNull(ProjectionGrantStore.take(1));val accepted=ProjectionGrantStore.take(2)
  assertEquals("two",accepted?.data?.action);assertEquals(5L,accepted?.lockRevision)
  assertNull(ProjectionGrantStore.take(2));ProjectionGrantStore.clear()
 }
}
