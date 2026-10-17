package com.aktv.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppBuildInfoTest {
    @Test fun `device report identifies the installed version code and exact CI artifact run`() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val label = AppBuildInfo.label(context)
        assertTrue(label.contains("/ code ${info.longVersionCode}"))
        assertTrue(label.endsWith("/ build ${context.getString(R.string.build_run_id)}"))
        assertFalse(label.contains("https://"))
    }
}
