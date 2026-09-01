package my.cheysoff.desktop.vault

import my.cheysoff.desktop.platform.HostOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class HostOsTest {

    @Test
    fun `windows spellings`() {
        assertEquals(HostOs.WINDOWS, HostOs.from("Windows 11"))
        assertEquals(HostOs.WINDOWS, HostOs.from("Windows Server 2022"))
    }

    /** Apple has shipped both spellings across JDK versions; both must resolve to the same store. */
    @Test
    fun `both macOS spellings`() {
        assertEquals(HostOs.MACOS, HostOs.from("Mac OS X"))
        assertEquals(HostOs.MACOS, HostOs.from("macOS"))
    }

    @Test
    fun `anything else is OTHER rather than a guess`() {
        assertEquals(HostOs.OTHER, HostOs.from("Linux"))
        assertEquals(HostOs.OTHER, HostOs.from("FreeBSD"))
        assertEquals(HostOs.OTHER, HostOs.from(""))
    }
}

class VaultLocationTest {

    /**
     * LOCALAPPDATA, not APPDATA. APPDATA is the roaming profile, which a domain login copies
     * between machines — and the DPAPI blob in this directory cannot be decrypted on another one.
     */
    @Test
    fun `windows uses the local app data directory`() {
        val path = VaultLocation.defaultDirectory(
            hostOs = HostOs.WINDOWS,
            env = { name -> if (name == "LOCALAPPDATA") "C:\\Users\\x\\AppData\\Local" else "C:\\Users\\x\\AppData\\Roaming" },
            userHome = "C:\\Users\\x",
        )
        assertEquals(Paths.get("C:\\Users\\x\\AppData\\Local", "Manana"), path)
    }

    @Test
    fun `windows without LOCALAPPDATA falls back under the home directory`() {
        val path = VaultLocation.defaultDirectory(
            hostOs = HostOs.WINDOWS,
            env = { null },
            userHome = "C:\\Users\\x",
        )
        assertEquals(Paths.get("C:\\Users\\x", "AppData", "Local", "Manana"), path)
    }

    @Test
    fun `macOS uses Application Support`() {
        val path = VaultLocation.defaultDirectory(
            hostOs = HostOs.MACOS,
            env = { null },
            userHome = "/Users/x",
        )
        assertEquals(Paths.get("/Users/x", "Library", "Application Support", "Manana"), path)
    }

    @Test
    fun `linux honours XDG_DATA_HOME`() {
        val path = VaultLocation.defaultDirectory(
            hostOs = HostOs.OTHER,
            env = { name -> if (name == "XDG_DATA_HOME") "/home/x/data" else null },
            userHome = "/home/x",
        )
        assertEquals(Paths.get("/home/x/data", "manana"), path)
    }

    /** The XDG spec says a relative value must be ignored, not resolved against anything. */
    @Test
    fun `a relative XDG_DATA_HOME is ignored`() {
        val path = VaultLocation.defaultDirectory(
            hostOs = HostOs.OTHER,
            env = { name -> if (name == "XDG_DATA_HOME") "relative/path" else null },
            userHome = "/home/x",
        )
        assertEquals(Paths.get("/home/x", ".local", "share", "manana"), path)
    }

    @Test
    fun `linux without XDG_DATA_HOME uses the default share directory`() {
        val path = VaultLocation.defaultDirectory(
            hostOs = HostOs.OTHER,
            env = { null },
            userHome = "/home/x",
        )
        assertEquals(Paths.get("/home/x", ".local", "share", "manana"), path)
    }

    /**
     * The three platforms must not share a directory. They cannot on a real machine, but the
     * function is one `when` away from a copy-paste that makes two branches identical.
     */
    @Test
    fun `each platform gets its own directory`() {
        val paths = HostOs.entries.map {
            VaultLocation.defaultDirectory(it, { null }, "/home/x").toString()
        }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.isNotEmpty() })
    }
}
