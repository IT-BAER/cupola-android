package com.baer.cupola

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The privacy policy promises that settings never leave the device. Device-to-device transfer is blocked by
 * res/xml/data_extraction_rules.xml, which Robolectric does not parse, so only the backup flag is checked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPolicyTest {
    private val info: ApplicationInfo = RuntimeEnvironment.getApplication().applicationInfo

    @Test
    fun cloudBackupIsOff() {
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
