package app.retrolauncher.club.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaPatchesTest {
    @Test
    fun `the tree listing gives archives by RA game ID, with the base game's folder`() {
        val json = """{"tree":[
            {"path":"SNES/Hacks/Super Mario World/1508-SMW-SMBEnhanced.zip","type":"blob"},
            {"path":"N64/Hacks/LoZ - Ocarina of Time/16504-LoZMissingLink.7z","type":"blob"},
            {"path":"SNES/Hacks/Super Mario World","type":"tree"},
            {"path":"README.md","type":"blob"}]}"""
        val index = RaPatchesRepository.parse(json)
        assertEquals(setOf(1508, 16504), index.keys)
        assertEquals("Super Mario World", index.getValue(1508).baseTitle)
        assertEquals("LoZ - Ocarina of Time", index.getValue(16504).baseTitle)
        assertNull(index[9999])
    }

    @Test
    fun `base ROMs are found by title, abbreviations included`() {
        val files = listOf(
            "Super Mario Advance 2 - Super Mario World (USA, Australia).gba", "Super Mario World (USA).sfc",
            "Legend of Zelda, The - Ocarina of Time (USA).z64", "Pokemon - Emerald Version (USA, Europe).gba",
            "Pokemon - FireRed Version (USA).gba", "Streets of Rage 2 (USA).md", "Kirby's Dream Land (USA, Europe).gb",
        )
        fun best(title: String) = BaseRoms.candidates(title, files) { it }.firstOrNull()
        assertEquals("Super Mario World (USA).sfc", best("Super Mario World"))
        assertEquals("Super Mario World (USA).sfc", best("SMW"))
        assertEquals("Legend of Zelda, The - Ocarina of Time (USA).z64", best("LoZ - Ocarina of Time"))
        assertEquals("Pokemon - Emerald Version (USA, Europe).gba", best("Pokemon Emerald"))
        assertEquals("Streets of Rage 2 (USA).md", best("Streets of Rage 2"))
        assertNull(best("Kirby's Adventure"))
    }

    @Test
    fun `newest version first`() {
        val names = listOf("Odyssey (v4.0.3).bps", "Odyssey (v4.1.1).bps", "Odyssey (v4.0.10).bps", "Odyssey (v4.1.0).bps")
        assertEquals(listOf("Odyssey (v4.1.1).bps", "Odyssey (v4.1.0).bps", "Odyssey (v4.0.10).bps", "Odyssey (v4.0.3).bps"),
            names.sortedWith(NaturalOrder.reversed()))
    }
}
