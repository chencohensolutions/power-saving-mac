package com.chencohensolution.batterytime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacStateTest {
    /** What App/main.swift companionState() sends (JSONSerialization, sorted keys) */
    private val sample = """
        {"apps":[{"action":"p50","limitedNow":true,"name":"Google Chrome","power":42.3,"reason":"using 42","rule":null},
                 {"action":"none","limitedNow":false,"name":"Music","power":0,"reason":"your rule","rule":"none"}],
         "battery":{"charging":false,"minutes":312,"onAC":false,"percent":76,"powerSource":"Battery","timeText":"5:12 Remaining"},
         "lastRecap":null,"lid":{"closed":true,"closedSince":1795000000000},
         "lowPowerMode":true,"lowPowerWhenLidClosed":true,"mac":{"name":"MacBook Pro"},
         "rules":{"Music":"none"},"runningApps":["Google Chrome","Music","Slack"],
         "saver":{"active":true,"activeMode":"lidClosed","limitedCount":5,"otherAppsOnECores":3,"pausedUntil":null,
                  "preset":"balanced","status":"Saving power · 5 apps limited","statusDetail":"Because the lid is closed"},
         "stayAwake":true,"time":1795000600000,"v":1,"somethingNewer":{"x":1}}
    """.trimIndent()

    @Test
    fun readsTheMacsState() {
        val s = MacState.json.decodeFromString(MacState.serializer(), sample)
        assertEquals(76, s.battery.percent)
        assertEquals(312, s.battery.minutes)
        assertTrue(s.lid.closed)
        assertEquals(Preset.Balanced, s.preset)
        assertEquals(ActiveMode.LidClosed, s.activeMode)
        assertEquals(2, s.apps.size)
        assertNull(s.apps[0].rule)
        assertEquals("none", s.rules["Music"])
        assertEquals("5:12 left", Format.batteryHeadline(s.battery))
        assertEquals("50%", Format.appTag(s.apps[0], s.saver.active))
        assertEquals("Never limited", Format.appTag(s.apps[1], s.saver.active))
        assertEquals("Lid closed for 10m", Format.lid(s.lid, s.time!!))
    }

    @Test
    fun readsAnEmptyState() {
        val s = MacState.json.decodeFromString(MacState.serializer(), "{}")
        assertEquals(Preset.Off, s.preset)
        assertEquals("Calculating time left…", Format.batteryHeadline(s.battery))
    }

    @Test
    fun appliesCommandsOptimistically() {
        val s = MacState.json.decodeFromString(MacState.serializer(), sample)
        assertFalse(s.applying(Command.SetStayAwake(false), 0).stayAwake)
        assertEquals(Preset.Max, s.applying(Command.SetPreset(Preset.Max), 0).preset)
        assertEquals(60_000L * 60, s.applying(Command.PauseLimits(60), 0).saver.pausedUntil)
        assertNull(s.applying(Command.PauseLimits(), 0).applying(Command.ResumeLimits, 0).saver.pausedUntil)

        val ruled = s.applying(Command.SetAppRule("Google Chrome", LimitAction.Pause), 0)
        assertEquals("pause", ruled.rules["Google Chrome"])
        assertEquals("pause", ruled.apps.first { it.name == "Google Chrome" }.rule)
        val cleared = ruled.applying(Command.SetAppRule("Music", null), 0)
        assertNull(cleared.rules["Music"])
    }

    @Test
    fun commandsMatchTheProtocol() {
        assertEquals("""{"type":"setStayAwake","on":true}""", Command.SetStayAwake(true).toJson().toString())
        assertEquals("""{"type":"setAppRule","app":"Slack","action":"auto"}""",
            Command.SetAppRule("Slack", null).toJson().toString())
        assertEquals("""{"type":"pauseLimits","minutes":30}""", Command.PauseLimits(30).toJson().toString())
        assertEquals("""{"type":"setActiveMode","mode":"always"}""", Command.SetActiveMode(ActiveMode.Always).toJson().toString())
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:05", Format.hm(5))
        assertEquals("2h 05m", Format.duration(125 * 60_000L))
        assertEquals("45s", Format.duration(45_000))
    }
}
