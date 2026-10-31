package com.shengjidaguai.goutoujunshi.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** V1 baseline only. Add each actual N -> N+1 migration here when a second version exists. */
@RunWith(AndroidJUnit4::class)
class SchemaBaselineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun testVersionOneExportCanBeOpenedAndValidated() {
        val helper = MigrationTestHelper(instrumentation, JunshiDatabase::class.java.canonicalName!!,
            FrameworkSQLiteOpenHelperFactory())
        val name = "schema-baseline.db"
        try {
            helper.createDatabase(name, 1).use { db ->
                db.execSQL("INSERT INTO contacts (id, revision, payload) VALUES ('synthetic', 0, '{}')")
            }
            helper.runMigrationsAndValidate(name, 1, true, *JunshiDatabase.MIGRATIONS).use { db ->
                db.query("SELECT id FROM contacts").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("synthetic", rows.getString(0))
                }
            }
        } finally { instrumentation.targetContext.deleteDatabase(name) }
    }
}
