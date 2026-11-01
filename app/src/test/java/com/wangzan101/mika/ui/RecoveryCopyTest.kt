package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.AutoOnBlock
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun pendingNoteOnlyForDefaultOnesNotYetApplied() {
        val a11y = RecoveryCapability.Accessibility
        val fresh = RecoveryConsent()
        // 还没配好手机操作:开关显示打开,下面说要先完成设置
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = false, autoFailed = emptySet()))
        // 配好了、正在补上:不多说;补上没成功:照实说
        assertNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = emptySet()))
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = setOf(a11y)))
        // 已经生效,或用户亲手关掉:什么都不说
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(applied = setOf(a11y)), paired = false, autoFailed = setOf(a11y)))
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(declined = setOf(a11y)), paired = false, autoFailed = emptySet()))
    }

    /** 助手只用 USB 命令起过:靠无线调试重起助手的两项说清用不上;别的几项照旧。 */
    @Test
    fun wirelessOnlyCapabilitiesSayTheyNeedPairingWhenStartedOverUsb() {
        val usb = HelperRestart.UsbCommand
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb)
            assertNotNull(c.name, note)
            // 用户关掉的不多说;配对过的照旧
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb))
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = HelperRestart.Wireless))
        }
        assertNull(RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(), paired = true, autoFailed = emptySet(), restart = usb))
    }

    /** 用 root 启动:靠无线调试重起助手的两项还没生效时说清用不上(重启后 Mika 自己经 su 再起),不叫人先去设置;生效了不多说。 */
    @Test
    fun wirelessOnlyCapabilitiesSayTheyAreNotNeededWithRoot() {
        val root = HelperRestart.Root
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(), paired = false, autoFailed = emptySet(), restart = root)
            assertNotNull(c.name, note)
            assertNotEquals(c.name, RecoveryCopy.NEED_SETUP, note)
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = root))
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = false, autoFailed = emptySet(), restart = root))
        }
        assertEquals(
            RecoveryCopy.NEED_SETUP,
            RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(), paired = false, autoFailed = emptySet(), restart = root),
        )
    }

    @Test
    fun autoOnNoteOnlyWhenStopped() {
        assertNull(RecoveryCopy.autoOnBlocked(null))
        assertNotNull(RecoveryCopy.autoOnBlocked(AutoOnBlock.NotAllowedHere))
        assertEquals(AutoOnBlock.entries.size, AutoOnBlock.entries.mapNotNull(RecoveryCopy::autoOnBlocked).toSet().size)
    }
}
