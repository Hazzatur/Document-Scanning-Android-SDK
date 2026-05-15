/**
Copyright 2020 ZynkSoftware SRL

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.zynksoftware.documentscanner.ui.components.polygon

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.zynksoftware.documentscanner.R
import java.util.ArrayDeque

internal class PolygonPointImageView @JvmOverloads constructor(
    context: Context,
    private val polygonView: PolygonView? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // How far back in time to retain positions for slop analysis.
    // ViewConfiguration.getTapTimeout() is the system-defined duration after which a press is no
    // longer considered a tap (~100 ms), which matches our "recent jitter window" intent exactly.
    private val positionHistoryWindowMs: Long = ViewConfiguration.getTapTimeout().toLong()

    // Pixel distance below which movement is considered a micro-jitter.
    // scaledTouchSlop is the system value for the minimum distance a touch must travel to be
    // treated as a scroll/drag, already scaled for the device's screen density.
    private val touchSlopPx: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private data class TimedPosition(val timestampMs: Long, val point: PointF)

    private var downPoint = PointF()
    private var parentTopLeft = PointF()
    private val positionHistory = ArrayDeque<TimedPosition>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)

        if (polygonView != null) {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val nextX = (event.rawX - parentTopLeft.x - downPoint.x)
                        .coerceIn(0f, (polygonView.width - width).toFloat())
                    val nextY = (event.rawY - parentTopLeft.y - downPoint.y)
                        .coerceIn(0f, (polygonView.height - height).toFloat())

                    x = nextX
                    y = nextY
                    recordPosition(event.eventTime)
                    dispatchCornerTouchEvent(MotionEvent.ACTION_MOVE)
                }

                MotionEvent.ACTION_DOWN -> {
                    downPoint.x = event.x
                    downPoint.y = event.y
                    val parentLocation = IntArray(2)
                    polygonView.getLocationOnScreen(parentLocation)
                    parentTopLeft = PointF(parentLocation[0].toFloat(), parentLocation[1].toFloat())
                    positionHistory.clear()
                    recordPosition(event.eventTime)
                    dispatchCornerTouchEvent(MotionEvent.ACTION_DOWN)
                }

                MotionEvent.ACTION_UP -> {
                    snapToStablePosition()
                    performClick()
                    dispatchCornerTouchEvent(MotionEvent.ACTION_UP)
                    positionHistory.clear()
                }

                MotionEvent.ACTION_CANCEL -> {
                    dispatchCornerTouchEvent(MotionEvent.ACTION_CANCEL)
                    positionHistory.clear()
                }
            }
            polygonView.invalidate()
        }
        return true
    }

    private fun dispatchCornerTouchEvent(action: Int) {
        val polygon = polygonView ?: return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val centerRawX = location[0] + width / 2f
        val centerRawY = location[1] + height / 2f
        polygon.dispatchCornerTouchEvent(action, centerRawX, centerRawY)
    }

    private fun recordPosition(timestampMs: Long) {
        val cutoff = timestampMs - positionHistoryWindowMs
        while (positionHistory.isNotEmpty() && positionHistory.first.timestampMs < cutoff) {
            positionHistory.removeFirst()
        }
        positionHistory.addLast(TimedPosition(timestampMs, PointF(x, y)))
    }

    /**
     * Walk backwards through recent history. As long as each step is smaller than
     * TOUCH_SLOP_PX we treat those samples as jitter and keep going further back.
     * The first sample we find whose movement *into* it was larger than the slop is
     * the last "intentional" position, and we snap to it.
     * If all recorded movement is below slop (the finger barely moved), we keep the
     * current position unchanged.
     */
    private fun snapToStablePosition() {
        val history = positionHistory.toList()
        if (history.size < 2) return

        // Start from the newest sample and walk toward older ones.
        var stableIndex = history.lastIndex
        for (i in history.lastIndex downTo 1) {
            val dx = history[i].point.x - history[i - 1].point.x
            val dy = history[i].point.y - history[i - 1].point.y
            val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist < touchSlopPx) {
                // This step was jitter – look one frame further back.
                stableIndex = i - 1
            } else {
                // Movement was intentional; stop here.
                break
            }
        }

        val stable = history[stableIndex]
        x = stable.point.x
        y = stable.point.y
    }

    // Because we call this from onTouchEvent, this code will be executed for both
    // normal touch events and for when the system calls this using Accessibility
    override fun performClick(): Boolean {
        super.performClick()

        val color = if (polygonView?.isValidShape(polygonView.getPoints()) == true) {
            ContextCompat.getColor(context, android.R.color.white)
        } else {
            ContextCompat.getColor(context, R.color.zdc_red)
        }
        polygonView?.paint?.color = color

        return true
    }

}