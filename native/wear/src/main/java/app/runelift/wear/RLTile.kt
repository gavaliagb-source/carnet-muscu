package app.runelift.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/* Tuile Runelift : glisse à gauche depuis le cadran.
   Hors séance : routine du jour + « Commencer ». Pendant la séance : séries faites + « Ouvrir ». */
class RLTile : TileService() {
    companion object {
        fun refresh(ctx: Context) { try { getUpdater(ctx).requestUpdate(RLTile::class.java) } catch (e: Exception) {} }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 2) =
        LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(lines)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build())
            .build()

    private fun button(label: String, bg: Int, fg: Int, extra: String?): LayoutElementBuilders.LayoutElement {
        val act = ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName("app.runelift.wear.WatchActivity")
        if (extra != null) act.addKeyToExtraMapping("start", ActionBuilders.AndroidStringExtra.Builder().setValue(extra).build())
        val click = ModifiersBuilders.Clickable.Builder().setId("go").setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(act.build()).build()).build()
        return LayoutElementBuilders.Box.Builder().setWidth(wrap()).setHeight(dp(44f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click)
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(bg)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build()).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(22f)).setEnd(dp(22f)).build()).build())
            .addContent(text(label, 15f, fg, true, 1)).build()
    }

    private fun space(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        WS.init(this)
        val acc = WS.acc(); val onAcc = WS.onAcc()
        val white = 0xFFFFFFFF.toInt(); val muted = 0xFF8A93A0.toInt()
        val col = LayoutElementBuilders.Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        col.addContent(text("Runelift", 13f, acc, true, 1)).addContent(space(6f))
        if (WS.on()) {
            val (d, n) = WS.doneCount()
            col.addContent(text(WS.st?.optString("n") ?: "", 16f, white, true))
            col.addContent(space(4f))
            col.addContent(text(WS.t("$d/$n séries", "$d/$n sets"), 14f, muted, false, 1))
            col.addContent(space(10f))
            col.addContent(button(WS.t("Ouvrir", "Open"), acc, onAcc, null))
        } else {
            val rts = WS.routines()
            val r = rts?.optJSONObject(0)
            if (r == null) {
                col.addContent(text(WS.t("Ouvre Runelift sur ton téléphone", "Open Runelift on your phone"), 14f, white))
                col.addContent(space(10f)).addContent(button(WS.t("Ouvrir", "Open"), acc, onAcc, null))
            } else {
                val today = r.optInt("td", 0) == 1
                col.addContent(text(if (today) WS.t("Aujourd'hui", "Today") else WS.t("Prochaine routine", "Next routine"), 12f, muted, false, 1))
                col.addContent(text(r.optString("n"), 17f, white, true))
                col.addContent(text(WS.t("${r.optJSONArray("ex")?.length() ?: 0} exercices", "${r.optJSONArray("ex")?.length() ?: 0} exercises"), 12f, muted, false, 1))
                col.addContent(space(10f))
                col.addContent(button(WS.t("Commencer", "Start"), acc, onAcc, r.optString("id")))
            }
        }
        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col.build()).build()
        val tile = TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setFreshnessIntervalMillis(if (WS.on()) 60_000L else 30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}
