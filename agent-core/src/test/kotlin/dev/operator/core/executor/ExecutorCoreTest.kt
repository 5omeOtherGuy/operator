package dev.operator.core.executor

import dev.operator.core.api.*
import dev.operator.core.policy.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExecutorCoreTest {
    private val key = ElementKey(7)
    private val signature = ScreenSignature("example.app", null, 4)
    private fun node(label: String = "Open", role: Role = Role.BTN, pkg: String = "example.app") =
        UiNode(1, key, 1, pkg, role, label, null, "example:id/$label", null,
            Bounds(0, 0, 40, 40), 0, null, emptyList(),
            setOf(NodeAction.CLICK, NodeAction.LONG_CLICK, NodeAction.SET_TEXT, NodeAction.SCROLL_FORWARD),
            emptySet(), null, null, null)
    private fun screen(n: UiNode = node(), pkg: String = n.packageName) =
        Snapshot(1, 0, pkg, listOf(WindowInfo(1, WindowType.APPLICATION, 0, pkg, null, true, 1)),
            listOf(n), 4, 4, false, null, signature.copy(packageName = pkg))
    private class FakeClock(var now: Long = 100_000) : Clock {
        override fun wallMs() = now
        override fun monotonicMs() = now
    }
    private class Hands(var screen: Snapshot) : HandsPort {
        var acted = 0
        var rows = emptyList<NotificationRecord>()
        override suspend fun snapshot() = screen
        override suspend fun awaitIdle(quietMs: Long, maxMs: Long) = true
        override suspend fun isConnected() = true
        override suspend fun performNodeAction(key: ElementKey, action: NodeAction, text: String?) { acted++ }
        override suspend fun gestureTap(point: Point, durationMs: Long) { acted++ }
        override suspend fun gestureSwipe(from: Point, to: Point, durationMs: Long) { acted++ }
        override suspend fun globalAction(action: GlobalAction) { acted++ }
        override suspend fun notifications() = rows
        override suspend fun takeScreenshot(): ByteArray? = null
    }
    private class Context : PolicyContext {
        override val allowedTools = ToolCatalog.base.keys.mapNotNull { it.simpleName }.map {
            it.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        }.toSet() + setOf("click", "send_sms", "set_text", "read_screen")
        override val allowedPackages = setOf("example.app", "com.test.browser", "com.test.messaging",
            "com.test.email", "com.test.social", "com.android.settings")
        override val ownerUtterance = "please do this"
        override var halted = false
        override var steps = 0
        override fun installed(pkg: String) = true
        override fun knownNumber(number: String) = false
        override fun authentic(token: ApprovalToken) = token.id.startsWith("trusted")
    }
    private class Gate : GatePort {
        override val armed = MutableStateFlow(true)
        var now = 100_000L
        var current: ToolCall? = null
        var signature: ScreenSignature? = null
        var edited = emptyMap<ElementKey, String>()
        var token: ApprovalToken? = null
        var last: GateCard? = null
        override suspend fun request(card: GateCard): GateResult {
            last = card
            val t = ApprovalToken("trusted-${System.nanoTime()}", current!!, signature!!, edited,
                if (card.riskClass == RiskClass.R3) ApprovalMethod.BIOMETRIC_STRONG else ApprovalMethod.VOLUME_HOLD,
                now, now + 30_000)
            token = t
            return GateResult.Approved(t)
        }
        override suspend fun cancelPending() {}
    }
    private class Fixture(s: Snapshot) {
        val clock = FakeClock()
        val hands = Hands(s)
        val ctx = Context()
        val gate = Gate()
        val events = mutableListOf<AuditEvent>()
        var effects = 0
        val executor = ExecutorCore(hands, object : EffectAdapter {
            override suspend fun act(call: ToolCall) { effects++ }
        }, object : EffectVerifier {
            override suspend fun pre(call: ToolCall, snapshot: Snapshot?) = snapshot
            override suspend fun post(call: ToolCall, before: Any?, snapshot: Snapshot?) =
                Verification.Confirmed("read-back")
        }, gate, object : AuditPort {
            override suspend fun append(event: AuditEvent) { events += event }
            override suspend fun head() = ""
        }, clock, ctx)
        suspend fun run(call: ToolCall, approval: ApprovalToken? = null): ExecResult {
            gate.current = call
            gate.signature = hands.screen.screenSignature
            gate.now = clock.now
            return executor.execute(call, approval)
        }
        suspend fun approve(call: ToolCall): ExecResult {
            assertTrue(run(call) is ExecResult.NeedsApproval)
            return run(call, gate.token)
        }
    }
    private fun click(id: Long = 1, label: String = "Open") = ToolCall.Click(id, 1, key, label)

    @Test fun `validation steps refuse in order and audit each decision`() = runBlocking {
        data class Case(val step: String, val call: ToolCall, val change: Fixture.() -> Unit = {})
        val cases = listOf(
            Case("schema", ToolCall.SetAlarm(26, 0, null, emptyList())),
            Case("stale", click(0)),
            Case("element", click(), { hands.screen = screen(node().copy(actions = emptySet())) }),
            Case("owner_channel", click(), { hands.screen = screen(pkg = "dev.operator") }),
            Case("package", click(), { hands.screen = screen(node(pkg = "com.mybank.app")) }),
            Case("number", ToolCall.Call("911")),
            Case("text", ToolCall.SetText(1, 1, key, "Open", "x".repeat(4097))),
            Case("halted", ToolCall.Back, { ctx.halted = true }),
        )
        cases.forEach { (reason, call, change) ->
            val f = Fixture(screen())
            f.change()
            assertEquals(reason, (f.run(call) as ExecResult.Refused).reason)
            assertEquals(0, f.hands.acted)
            assertEquals(0, f.effects)
            assertEquals(AuditDecision.REFUSED, f.events.single().decision)
        }
    }

    @Test fun `context rules gate irreversible targets and allow navigation`() = runBlocking {
        val risky = listOf(
            "com.test.messaging" to "Open", "com.test.email" to "Open",
            "com.test.social" to "Open", "com.android.settings" to "Open",
            "example.app" to "Delete", "example.app" to "OK",
            "example.app" to "Share", "example.app" to "Send",
            "com.test.browser" to "Search",
        )
        risky.forEach { (pkg, label) ->
            val f = Fixture(screen(node(label, pkg = pkg)))
            assertTrue("$pkg $label", f.run(click(label = label)) is ExecResult.NeedsApproval)
            assertEquals(RiskClass.R2, f.gate.last!!.riskClass)
            assertEquals(0, f.hands.acted)
        }
        val f = Fixture(screen(node("Back", pkg = "com.test.messaging")))
        assertEquals(ExecResult.Done("read-back"), f.run(click(label = "Back")))
    }

    @Test fun `taint gates typed text and carries to submit`() = runBlocking {
        val f = Fixture(screen(node("Search", Role.EDIT)))
        f.executor.observe(ObservedText("untrusted.app", "identifier 12345678"))
        val type = ToolCall.SetText(1, 1, key, "Search", "12345678")
        assertTrue(f.run(type) is ExecResult.NeedsApproval)
        assertEquals("untrusted.app", f.gate.last!!.taint!!.sourcePackage)
        assertEquals(ExecResult.Done("read-back"), f.run(type, f.gate.token))
        f.clock.now += 61_000
        assertTrue(f.run(click(label = "Search")) is ExecResult.NeedsApproval)
        assertEquals("untrusted.app", f.gate.last!!.taint!!.sourcePackage)
    }

    @Test fun `tokens cannot be replayed or rebound`() = runBlocking {
        val f = Fixture(screen())
        val sms = ToolCall.SendSms("+15551234567", "hello")
        assertTrue(f.run(sms) is ExecResult.NeedsApproval)
        val token = f.gate.token!!
        assertEquals("approval", (f.run(sms.copy(text = "changed"), token) as ExecResult.Refused).reason)
        assertEquals(ExecResult.Done("read-back"), f.run(sms, token))
        assertTrue(f.run(sms, token) is ExecResult.Refused)
        assertEquals(1, f.effects)
        assertTrue(f.gate.last!!.fromScreen.contains("Unknown number"))
    }

    @Test fun `rate limit rows and ceiling`() = runBlocking {
        data class Row(val call: ToolCall, val allowed: Int, val reason: String, val stepMs: Long = 61_000)
        val rows = listOf(
            Row(ToolCall.Back, 3, "rate_ui_1000", 0),
            Row(ToolCall.SendSms("+15551234567", "a"), 5, "rate_sms_3600000"),
            Row(ToolCall.Call("+15551234567"), 5, "rate_call_3600000"),
            Row(ToolCall.InstallApk("content://owner/a"), 3, "rate_install_86400000"),
            Row(ToolCall.SetPermission("example.app", "test.permission", PermissionGrantState.DENIED),
                10, "rate_device_policy_86400000"),
        )
        rows.forEach { row ->
            val f = Fixture(screen())
            repeat(row.allowed) { i ->
                val call = if (row.call is ToolCall.SendSms) row.call.copy(text = "text-$i") else row.call
                val result = if (row.call is ToolCall.Back) f.run(call) else f.approve(call)
                assertEquals("${row.call} $i", ExecResult.Done("read-back"), result)
                f.clock.now += row.stepMs
            }
            val next = if (row.call is ToolCall.SendSms) row.call.copy(text = "text-next") else row.call
            assertEquals(row.reason, (f.run(next) as ExecResult.Refused).reason)
        }
        val f = Fixture(screen())
        assertEquals(ExecResult.Done("read-back"), f.approve(ToolCall.SendSms("+15551234567", "same")))
        assertEquals("rate_same_60000", (f.run(ToolCall.SendSms("+15551234567", "same")) as ExecResult.Refused).reason)
        f.ctx.steps = 60
        assertEquals("halted", (f.run(ToolCall.Back) as ExecResult.Refused).reason)
    }

    @Test fun `daily SMS cap survives hourly window and dialog buttons require gate`() = runBlocking {
        val f = Fixture(screen())
        repeat(20) { i ->
            val call = ToolCall.SendSms("+15551234567", "message $i")
            assertEquals(ExecResult.Done("read-back"), f.approve(call))
            f.clock.now += if (i % 5 == 4) 3_610_000 else 61_000
        }
        assertEquals("rate_sms_86400000",
            (f.run(ToolCall.SendSms("+15551234567", "message 20")) as ExecResult.Refused).reason)
        val dialog = Fixture(screen(node("Unlabelled").copy(windowTitle = "Confirm dialog")))
        assertTrue(dialog.run(click(label = "Unlabelled")) is ExecResult.NeedsApproval)
    }
}
