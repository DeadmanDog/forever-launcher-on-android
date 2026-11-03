package app.gamenative.ui.screen.wow

import java.io.File

/**
 * Files an older GameNative container pattern put into a WoW prefix that are not ours to ship (removed from the APK in 1.4.9):
 * Microsoft "Core fonts for the Web" and two CJK bitmap fonts (licence: redistribution only in Microsoft's original packages),
 * and Winlator's closed Windows helpers wfm.exe (file manager) and winhandler.exe with wfm's file icons. WoW never runs them (the
 * launcher starts Wow-ARM64.exe directly) and draws its own fonts; Wine has its own substitutes. Existing containers lose them
 * before the next launch.
 */
object WowLegacyFiles {
    val FONTS: Set<String> = setOf(
        "AriBlk.TTF", "Arial.TTF", "Arialbd.TTF", "Arialbi.TTF", "Ariali.TTF", "Comic.TTF", "Comicbd.TTF", "Georgia.TTF",
        "Georgiab.TTF", "Georgiai.TTF", "Georgiaz.TTF", "Impact.TTF", "Times.TTF", "Timesbd.TTF", "Timesbi.TTF",
        "Timesi.TTF", "Trebucbd.ttf", "Verdana.TTF", "Verdanab.TTF", "Verdanai.TTF", "Verdanaz.TTF", "andalemo.ttf",
        "app932.fon", "app936.fon", "arial.ttf", "arialbd.ttf", "arialbi.ttf", "ariali.ttf", "ariblk.ttf", "comic.ttf",
        "comicbd.ttf", "cour.ttf", "courbd.ttf", "courbi.ttf", "couri.ttf", "georgia.ttf", "georgiab.ttf", "georgiai.ttf",
        "georgiaz.ttf", "impact.ttf", "times.ttf", "timesbd.ttf", "timesbi.ttf", "timesi.ttf", "trebuc.ttf",
        "trebucbd.ttf", "trebucbi.ttf", "trebucit.ttf", "verdana.ttf", "verdanab.ttf", "verdanai.ttf", "verdanaz.ttf",
        "vga932.fon", "vga936.fon", "webdings.ttf")
    val HELPERS = listOf("wfm.exe", "winhandler.exe") +
        listOf("bat", "bmp", "bsp", "dll", "gif", "image", "png", "reg", "txt").map { "system32/$it.ico" }

    /** Deletes the listed files from [windowsDir] (a prefix's drive_c/windows); returns how many were removed. */
    @JvmStatic
    fun removeFrom(windowsDir: File): Int =
        (FONTS.map { "Fonts/$it" } + HELPERS).count { name -> File(windowsDir, name).let { it.isFile && it.delete() } }
}
