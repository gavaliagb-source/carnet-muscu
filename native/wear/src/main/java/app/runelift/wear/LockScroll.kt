package app.runelift.wear

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

/* Liste qui défile, sauf sur l'écran de série où tout doit rester fixe et centré. */
class LockScroll(ctx: Context) : ScrollView(ctx) {
    var locked = false
        set(v) { field = v; if (v) scrollTo(0, 0) }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = if (locked) false else super.onInterceptTouchEvent(ev)
    override fun onTouchEvent(ev: MotionEvent): Boolean = if (locked) false else super.onTouchEvent(ev)
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean = if (locked) false else super.onGenericMotionEvent(ev)
}
