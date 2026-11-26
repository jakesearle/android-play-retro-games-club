package app.retrolauncher.club

import app.retrolauncher.club.RaConsoles.Region
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleRegionTest {
    @After
    fun reset() {
        RaConsoles.region = Region.NORTH_AMERICA
    }

    @Test
    fun `device countries pick a region`() {
        assertEquals(Region.NORTH_AMERICA, Region.ofCountry("US"))
        assertEquals(Region.NORTH_AMERICA, Region.ofCountry("ca"))
        assertEquals(Region.JAPAN, Region.ofCountry("JP"))
        assertEquals(Region.EUROPE, Region.ofCountry("GB"))
        assertEquals(Region.EUROPE, Region.ofCountry("FR"))
        assertEquals(Region.EUROPE, Region.ofCountry("AU"))
        assertEquals(Region.EUROPE, Region.ofCountry(""))
    }

    @Test
    fun `names follow the region`() {
        assertEquals("Genesis", RaConsoles.name(RaConsoles.MEGA_DRIVE, Region.NORTH_AMERICA))
        assertEquals("Mega Drive", RaConsoles.name(RaConsoles.MEGA_DRIVE, Region.EUROPE))
        assertEquals("TurboGrafx-16", RaConsoles.name(RaConsoles.PC_ENGINE, Region.NORTH_AMERICA))
        assertEquals("PC Engine", RaConsoles.name(RaConsoles.PC_ENGINE, Region.EUROPE))
        assertEquals("Sega CD", RaConsoles.name(RaConsoles.SEGA_CD, Region.NORTH_AMERICA))
        assertEquals("Mega-CD", RaConsoles.name(RaConsoles.SEGA_CD, Region.JAPAN))
        assertEquals("Super Famicom", RaConsoles.name(RaConsoles.SUPER_NINTENDO, Region.JAPAN))
        assertEquals("SNES", RaConsoles.name(RaConsoles.SUPER_NINTENDO, Region.EUROPE))
        assertEquals("PlayStation", RaConsoles.name(RaConsoles.PLAYSTATION, Region.JAPAN))
    }

    @Test
    fun `a title's console is named for the region, whichever label the club used`() {
        RaConsoles.region = Region.NORTH_AMERICA
        assertEquals("TurboGrafx-16", RaConsoles.fromTitle("Bonk's Adventure (PCE)")?.name)
        assertEquals("Genesis", RaConsoles.fromTitle("Gunstar Heroes (MD)")?.name)
        RaConsoles.region = Region.EUROPE
        assertEquals("PC Engine", RaConsoles.fromTitle("Bonk's Adventure (TG16)")?.name)
        assertEquals("Mega Drive", RaConsoles.fromTitle("Gunstar Heroes (Genesis)")?.name)
        assertEquals("PC Engine CD", RaConsoles.fromTitle("Ys Book I & II (PCECD)")?.name)
    }

    @Test
    fun `unnamed consoles fall back to the label`() {
        assertEquals("Wii U", RaConsoles.fromTitle("Game (Wii U)")?.name)
    }
}
