package com.aurex.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Apple (SF Symbols style) icon set: 24-unit grid, 1.7 round-capped strokes, no Material shapes.
 * Names match the old Material icons, so `Icons.Default.Foo` became `AppleIcons.Foo`.
 */
private fun circ(cx: Float, cy: Float, r: Float) =
    "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0z"

private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r},$y h${w - 2 * r} a$r,$r 0 0 1 $r,$r v${h - 2 * r} a$r,$r 0 0 1 ${-r},$r " +
        "h${-(w - 2 * r)} a$r,$r 0 0 1 ${-r},${-r} v${-(h - 2 * r)} a$r,$r 0 0 1 $r,${-r}z"

private class IconB(val b: ImageVector.Builder) {
    fun s(vararg d: String) = d.forEach {
        b.addPath(
            PathParser().parsePathString(it).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2.05f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
    }
    fun f(vararg d: String) = d.forEach {
        b.addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black))
    }
}

private fun icon(name: String, build: IconB.() -> Unit): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    IconB(b).build()
    return b.build()
}

object AppleIcons {
    val Download = icon("Download") { s("M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19.5h14") }
    val Refresh = icon("Refresh") { s("M21 4v5h-5", "M19.6 14.5a8 8 0 1 1-1.9-8.2L21 9") }
    val VisibilityOff = icon("VisibilityOff") {
        s("M2.5 12s3.4-6.5 9.5-6.5S21.5 12 21.5 12s-3.4 6.5-9.5 6.5S2.5 12 2.5 12z", circ(12f, 12f, 3f), "M4.5 4.5l15 15")
    }
    val Shield = icon("Shield") { s("M12 3l8 3v6c0 4.8-3.4 7.9-8 9-4.6-1.1-8-4.2-8-9V6z") }
    val Security = icon("Security") { s("M12 3l8 3v6c0 4.8-3.4 7.9-8 9-4.6-1.1-8-4.2-8-9V6z", "M8.8 12l2.4 2.4 4-4.6") }
    val Movie = icon("Movie") { s(rr(3.5f, 5f, 17f, 14f, 2.5f), "M8.5 5v14M15.5 5v14M3.5 12h5M15.5 12h5") }
    val Search = icon("Search") { s(circ(11f, 11f, 6.5f), "M16 16l4.5 4.5") }
    val Delete = icon("Delete") { s("M4 7h16M9 7V4.5h6V7M6.5 7l1 13h9l1-13M10 11v5M14 11v5") }
    val DeleteSweep = Delete
    val Copy = icon("Copy") { s(rr(8.5f, 8.5f, 11.5f, 11.5f, 2.5f), "M15.5 8.5V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7.5a2 2 0 0 0 2 2h2.5") }
    val PlayCircle = icon("PlayCircle") { s(circ(12f, 12f, 9f), "M10 8.5v7l6-3.5z") }
    val PlayArrow = icon("PlayArrow") { f("M7 4.8v14.4a.8.8 0 0 0 1.2.7l11.4-7.2a.8.8 0 0 0 0-1.4L8.2 4.1A.8.8 0 0 0 7 4.8z") }
    val Pause = icon("Pause") { f(rr(6.5f, 4.5f, 4f, 15f, 1.4f), rr(13.5f, 4.5f, 4f, 15f, 1.4f)) }
    val PictureInPictureAlt = icon("Pip") { s(rr(3f, 5f, 18f, 14f, 3f)); f(rr(12f, 12f, 6.5f, 4.5f, 1.2f)) }
    val MoreVert = icon("Ellipsis") { f(circ(5.2f, 12f, 1.95f), circ(12f, 12f, 1.95f), circ(18.8f, 12f, 1.95f)) }
    val Close = icon("Close") { s("M6 6l12 12M18 6L6 18") }
    val Bolt = icon("Bolt") { s("M13 2.5L5 13.5h6l-1 8 8-11h-6z") }
    val Share = icon("Share") { s("M8.5 10H7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2h-1.5", "M12 14.5V3M8.5 6.5L12 3l3.5 3.5") }
    val MusicNote = icon("MusicNote") { s("M9 17.5V6l10-2v11.5"); f(circ(6.5f, 17.5f, 2.6f), circ(16.5f, 15.5f, 2.6f)) }
    val Audiotrack = icon("Speaker") {
        s("M4 9.8v4.4h3.4L12 18V6L7.4 9.8z", "M15.4 9.2a4 4 0 0 1 0 5.6", "M18.2 6.6a8 8 0 0 1 0 10.8")
    }
    val Add = icon("Add") { s("M12 5v14M5 12h14") }
    /** Gear: eight trapezoid teeth joined by arcs, hub circle in the middle. */
    val Settings = icon("Gear") {
        fun pt(deg: Double, r: Double) = "${12 + r * cos(Math.toRadians(deg))},${12 + r * sin(Math.toRadians(deg))}"
        val ro = 9.4
        val ri = 7.3
        val d = StringBuilder()
        for (i in 0 until 8) {
            val c = i * 45.0 - 90.0
            d.append(if (i == 0) "M${pt(c - 13, ri)}" else "A$ri,$ri 0 0 1 ${pt(c - 13, ri)}")
            d.append("L${pt(c - 8, ro)}L${pt(c + 8, ro)}L${pt(c + 13, ri)}")
        }
        d.append("A$ri,$ri 0 0 1 ${pt(-90.0 - 13, ri)}Z")
        s(d.toString(), circ(12f, 12f, 3.1f))
    }
    val History = icon("History") { s("M3.5 12A8.5 8.5 0 1 0 6 6", "M3 3.5v5h5", "M12 7.5V12l3 2") }
    val Star = icon("StarFill") {
        f("M12 3.4l2.6 5.5 6 .8-4.4 4.2 1.1 6-5.3-2.9-5.3 2.9 1.1-6-4.4-4.2 6-.8z")
        s("M12 3.4l2.6 5.5 6 .8-4.4 4.2 1.1 6-5.3-2.9-5.3 2.9 1.1-6-4.4-4.2 6-.8z")
    }
    val Info = icon("Info") { s(circ(12f, 12f, 9f), "M12 11v5.5"); f(circ(12f, 7.7f, 1.3f)) }
    val ErrorOutline = icon("Exclaim") { s(circ(12f, 12f, 9f), "M12 7.5v5.5"); f(circ(12f, 16.5f, 1.3f)) }
    val DesktopWindows = icon("Desktop") { s(rr(3f, 4f, 18f, 12.5f, 2.2f), "M9 20.5h6M12 16.5v4") }
    val DarkMode = icon("Moon") { s("M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z") }
    val Bedtime = icon("Sleep") { s("M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z", "M14.5 3.5h4l-4 4.5h4") }
    val Check = icon("Check") { s("M5 12.5l4.5 4.5L19 7.5") }
    val Article = icon("Article") { s(rr(4.5f, 3f, 15f, 18f, 2.8f), "M8.5 8.5h7M8.5 12h7M8.5 15.5h4") }
    val ArrowBack = icon("ChevronLeft") { s("M15 5l-7 7 7 7") }
    val ArrowForward = icon("ChevronRight") { s("M9 5l7 7-7 7") }
    val ChevronRight = ArrowForward
    val Speed = icon("Gauge") { s("M4.5 18a8.5 8.5 0 1 1 15 0", "M12 14.5l4-4.5"); f(circ(12f, 14.5f, 1.45f)) }
    val Palette = icon("Palette") {
        s("M12 3a9 9 0 0 0 0 18c1.5 0 2.2-1 2.2-2.1 0-1.7-1.8-1.7-1.8-3.2 0-1 .8-1.7 1.9-1.7H18a3 3 0 0 0 3-3C21 6.6 17 3 12 3z")
        f(circ(7.6f, 11f, 1.15f), circ(9.8f, 7.2f, 1.15f), circ(14.6f, 7.2f, 1.15f))
    }
    val Language = icon("Globe") { s(circ(12f, 12f, 9f), "M3 12h18", "M12 3c3 3 3 15 0 18-3-3-3-15 0-18z") }
    val Home = icon("House") { s("M4 11l8-7 8 7", "M6 9.5V20h12V9.5", "M10 20v-5.5h4V20") }
    /** Rotation toggle, shown while the screen is LANDSCAPE: upright phone + arrow = "tap to go portrait". */
    val RotateToPortrait = icon("RotatePortrait") {
        s(rr(8.5f, 10.5f, 7f, 11f, 2.2f), "M11.1 19.1h1.8", "M6 9A6.8 6.8 0 0 1 18.2 7", "M18.9 3.7L18.2 7l-3.3-.7")
    }
    /** Rotation toggle, shown while the screen is PORTRAIT: sideways phone + arrow = "tap to go landscape". */
    val RotateToLandscape = icon("RotateLandscape") {
        s(rr(6f, 12.5f, 11f, 7f, 2.2f), "M15.1 15.1v1.8", "M18 9A6.8 6.8 0 0 0 5.8 7", "M5.1 3.7L5.8 7l3.3-.7")
    }
    val Repeat = icon("Repeat") { s("M17 3.5l3 3-3 3", "M4 11V9.5a3 3 0 0 1 3-3h13", "M7 20.5l-3-3 3-3", "M20 13v1.5a3 3 0 0 1-3 3H4") }
    val Lock = icon("Lock") { s(rr(5f, 10.5f, 14f, 10f, 2.5f), "M8 10.5V8a4 4 0 0 1 8 0v2.5") }
    val LockOpen = icon("LockOpen") { s(rr(5f, 10.5f, 14f, 10f, 2.5f), "M8 10.5V8a4 4 0 0 1 7.6-1.8") }
    val KeyboardArrowDown = icon("ChevronDown") { s("M6 9l6 6 6-6") }
    val Image = icon("Photo") { s(rr(3.5f, 4.5f, 17f, 15f, 3f), "M4 17l5-4.5 3.5 3L15.5 13 20 17"); f(circ(9f, 9.5f, 1.4f)) }
    val HighQuality = icon("HD") { s(rr(3f, 5f, 18f, 14f, 3f), "M7.5 9.5v5M7.5 12h3M10.5 9.5v5", "M14 9.5h1.4a2.5 2.5 0 0 1 0 5H14z") }
    /** Video-quality badges (4K / HD / SD), picked by [qualityIcon] in Panels.kt. */
    val Quality4K = icon("Quality4K") { s(rr(3f, 5f, 18f, 14f, 3f), "M7.5 9.5v3h3M10.5 9.5v5", "M14 9.5v5M17.5 9.5L14.5 12l3 2.5") }
    val QualitySD = icon("QualitySD") { s(rr(3f, 5f, 18f, 14f, 3f), "M10.3 10.4C9.4 9.3 6.9 9.6 7 11c.1 1.3 3.2.9 3.2 2.5 0 1.5-2.7 1.6-3.4.4", "M13 9.5h1.4a2.5 2.5 0 0 1 0 5H13z") }
    val Fullscreen = icon("Fullscreen") {
        s("M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9", "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9", "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15", "M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15")
    }
    val FolderZip = icon("Folder") { s("M3.5 7.5a2 2 0 0 1 2-2H10l2 2.5h6.5a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z") }
    val ContentCopy = icon("Copy") { s(rr(8.5f, 8.5f, 11.5f, 11.5f, 2.5f), "M15.5 8.5v-2a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2") }
    val ClosedCaption = icon("Caption") { s(rr(3f, 5.5f, 18f, 13f, 3f), "M10.6 10.2a2.2 2.2 0 1 0 0 3.6", "M16.6 10.2a2.2 2.2 0 1 0 0 3.6") }
    val Bookmark = icon("Bookmark") { s("M6.5 3.5h11v17L12 16l-5.5 4.5z") }
    /** Private tab: hat and glasses. */
    val Incognito = icon("Incognito") {
        s(
            "M3 11.5h18",
            "M5.4 11.5l1.7-5.2a1.6 1.6 0 0 1 1.5-1.1h6.8a1.6 1.6 0 0 1 1.5 1.1l1.7 5.2",
            circ(8f, 16.6f, 2.8f), circ(16f, 16.6f, 2.8f), "M10.8 16.2a2.4 2.4 0 0 1 2.4 0"
        )
    }
    /** Clear cookies and site data: a cookie with a bite and chips. */
    val Cookie = icon("Cookie") {
        s("M12 3a9 9 0 1 0 9 9 3.6 3.6 0 0 1-4.5-4.5A3.6 3.6 0 0 1 12 3z")
        f(circ(8.6f, 9f, 1f), circ(9.2f, 15f, 1f), circ(15.4f, 14.8f, 1f), circ(12.6f, 11.8f, 0.9f))
    }
    /** Long screenshot: viewfinder corners with a scroll-down arrow. */
    val Screenshot = icon("Screenshot") {
        s(
            "M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9", "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9",
            "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15", "M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15",
            "M12 8.5v7", "M9 12.8l3 3 3-3"
        )
    }
    /** Tabs: a page with another one stacked behind it. */
    val Tabs = icon("Tabs") { s(rr(3f, 7.5f, 18f, 12.5f, 3f), "M6.5 4h11") }
    /** Toolbar settings: a screen with a bar of buttons along the bottom. */
    val ToolbarBar = icon("ToolbarBar") { s(rr(3.5f, 3.5f, 17f, 17f, 3f), "M3.5 15.5h17"); f(circ(8f, 18f, 1.15f), circ(12f, 18f, 1.15f), circ(16f, 18f, 1.15f)) }
    /** Gestures: a touch point swiping to the side. */
    val Swipe = icon("Swipe") {
        s("M13 12h8", "M17.5 8.5L21 12l-3.5 3.5", "M3 9.5a5 5 0 0 0 0 5")
        f(circ(8.3f, 12f, 2.3f))
    }
    /** Installer / disc image (exe, iso, dmg ...). */
    val Installer = icon("Disc") { s(circ(12f, 12f, 9f), circ(12f, 12f, 2.6f), "M5.6 9.2a7 7 0 0 1 3.4-3.6") }
    /** Picture mode "Fit": the whole video inside the screen. */
    val FitScreen = icon("FitScreen") {
        s(
            "M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9", "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9",
            "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15", "M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15",
            rr(8f, 9.5f, 8f, 5f, 1.2f)
        )
    }
    /** Auto-scroll: two chevrons pointing down. */
    val AutoScroll = icon("AutoScroll") { s("M7 6l5 5 5-5", "M7 12.5l5 5 5-5") }
    /** Picture mode "Crop": the crop symbol. */
    val Crop = icon("Crop") { s("M6 2.5v13.5a2 2 0 0 0 2 2h13.5", "M18 21.5V8a2 2 0 0 0-2-2H2.5") }
    val Android = icon("Package") { s("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5", "M12 12v9") }

    // File-type glyphs (used by the iOS-style file tiles)
    val Video = icon("Video") { s(rr(3f, 5.5f, 18f, 13f, 3.5f)); f("M10.2 9.1v5.8l4.9-2.9z") }
    val Doc = icon("Doc") { s("M6.5 3.5h7l4.5 4.5v11a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5z", "M13.5 3.5V8H18") }
    val Sheet = icon("Sheet") { s(rr(4f, 4f, 16f, 16f, 2.5f), "M4 9.3h16M4 14.7h16M10 4v16") }
    val Slides = icon("Slides") { s(rr(3f, 4f, 18f, 12f, 2.2f), "M12 16v4M8.5 20h7", "M8 13v-2.5M12 13V8M16 13v-4") }
    val Archive = icon("Archive") { s(rr(4f, 3.5f, 16f, 17f, 2.5f), "M12 3.5v8", "M10 6.5h4M10 9h4"); f(rr(10f, 12.5f, 4f, 4.5f, 1.2f)) }
    val Code = icon("Code") { s("M8.5 7.5L4 12l4.5 4.5", "M15.5 7.5L20 12l-4.5 4.5", "M13.2 5.5l-2.4 13") }
    val Book = icon("Book") { s("M12 6.5C10 5 7 4.5 4.5 5v13c2.5-.5 5.5 0 7.5 1.5 2-1.5 5-2 7.5-1.5V5C17 4.5 14 5 12 6.5z", "M12 6.5v13") }
    /** Apple SF Symbol reorder grip (three horizontal rounded bars). */
    val DragHandle = icon("DragHandle") { s("M5 8h14", "M5 12h14", "M5 16h14") }
    val ChevronUp = icon("ChevronUp") { s("M6.5 14.5L12 9l5.5 5.5") }
    val ChevronDown = icon("ChevronDown") { s("M6.5 9.5L12 15l5.5-5.5") }
}
