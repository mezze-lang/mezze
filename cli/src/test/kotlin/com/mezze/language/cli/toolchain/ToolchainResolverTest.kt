package com.mezze.language.cli.toolchain

import com.mezze.language.cli.toolchain.ToolchainResolver.Resolution
import com.mezze.language.cli.toolchain.ToolchainResolver.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ToolchainResolverTest {

    private fun req(explicit: String? = null, env: String? = null, project: String? = null, default: String? = null) =
        ToolchainResolver.request(explicit, env, project, default)

    // --- precedence ---

    @Test fun `explicit +ver beats everything`() {
        val r = req(explicit = "+0.1.3", env = "0.2.0", project = "0.4", default = "0.5.0")
        assertEquals("0.1.3", r.channel); assertEquals(Source.EXPLICIT, r.source)
    }

    @Test fun `env beats project and default`() {
        val r = req(env = "0.2.0", project = "0.4", default = "0.5.0")
        assertEquals("0.2.0", r.channel); assertEquals(Source.ENV, r.source)
    }

    @Test fun `project pin beats default`() {
        val r = req(project = "0.4", default = "0.5.0")
        assertEquals("0.4", r.channel); assertEquals(Source.PROJECT, r.source)
    }

    @Test fun `default when nothing else`() {
        val r = req(default = "0.5.0")
        assertEquals("0.5.0", r.channel); assertEquals(Source.DEFAULT, r.source)
    }

    @Test fun `latest when no request at all`() {
        val r = req()
        assertEquals(null, r.channel); assertEquals(Source.LATEST, r.source)
    }

    // --- matching against installed ---

    private val installed = listOf("0.1.0", "0.1.3", "0.2.0", "nightly-202609010900", "nightly-202609051200")

    @Test fun `exact pin resolves`() {
        val res = ToolchainResolver.resolve(req(explicit = "0.1.3"), installed)
        assertEquals("0.1.3", (res as Resolution.Ok).version)
    }

    @Test fun `track resolves to newest on that track`() {
        val res = ToolchainResolver.resolve(req(project = "0.1"), installed)
        assertEquals("0.1.3", (res as Resolution.Ok).version, "0.1 -> newest 0.1.x")
    }

    @Test fun `nightly track resolves to newest nightly`() {
        val res = ToolchainResolver.resolve(req(env = "nightly"), installed)
        assertEquals("nightly-202609051200", (res as Resolution.Ok).version)
    }

    @Test fun `latest picks the newest release over nightlies`() {
        val res = ToolchainResolver.resolve(req(), installed)
        assertEquals("0.2.0", (res as Resolution.Ok).version, "releases outrank nightlies for 'latest'")
    }

    @Test fun `unknown channel is NotInstalled, listing what's there`() {
        val res = ToolchainResolver.resolve(req(explicit = "9.9.9"), installed)
        assertTrue(res is Resolution.NotInstalled)
        assertEquals(installed.size, (res as Resolution.NotInstalled).installed.size)
    }

    @Test fun `no toolchains installed`() {
        val res = ToolchainResolver.resolve(req(default = "0.1"), emptyList())
        assertTrue(res is Resolution.None)
    }
}
