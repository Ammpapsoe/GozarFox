package com.gozar

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import androidx.graphics.shapes.Morph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MetricIconViewTest {
    @Test
    fun eachMetricHasAnIndependentMorphWithDifferentContours() {
        val context = RuntimeEnvironment.getApplication()
        val icons = (0..3).map { MetricIconView(context).apply { setAppearance(Color.RED, it) } }
        assertEquals(listOf(0, 1, 2, 3), icons.map { it.shapeIndex })
        icons[0].changeShape()
        val field = MetricIconView::class.java.getDeclaredField("shape").apply { isAccessible = true }
        val morph = field.get(icons[0]) as Morph
        val from = morph.asCubics(0f)
        val to = morph.asCubics(1f)
        assertEquals(from.size, to.size)
        assertTrue(from.zip(to).any { (a, b) ->
            abs(a.anchor0X - b.anchor0X) > 0.001f ||
                abs(a.anchor0Y - b.anchor0Y) > 0.001f ||
                abs(a.control0X - b.control0X) > 0.001f ||
                abs(a.control0Y - b.control0Y) > 0.001f
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(1, icons[0].shapeIndex)
        assertEquals(listOf(1, 2, 3), icons.drop(1).map { it.shapeIndex })
        icons[1].changeShape()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertNotEquals(icons[0].shapeIndex, icons[1].shapeIndex)
        icons.forEach {
            it.layout(0, 0, 60, 60)
            it.draw(Canvas(Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test
    fun reducedMotionCompletesWithoutAnimation() {
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        try {
            setScale.invoke(null, 0f)
            val icon = MetricIconView(RuntimeEnvironment.getApplication())
            icon.setAppearance(Color.BLUE, 2)
            icon.changeShape()
            assertEquals(3, icon.shapeIndex)
            assertEquals(1f, icon.progress, 0f)
        } finally {
            setScale.invoke(null, 1f)
        }
    }
    @Test
    fun changingConnectionValuesTriggersEachCorrespondingMetric() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        Store.setLang(context, "en")
        FoxStore.saveAccount(context, "person@example.test", "test-id", FoxTokens("access", "refresh", 3600), FoxPass("pass", 100, 80, ""))
        Fox.servers = listOf(FoxLocation("NL", "Netherlands", "Amsterdam", "127.0.0.1", 9, false))
        Fox.busy = true
        FoxLink.connected = true
        FoxLink.since = System.currentTimeMillis()
        FoxLink.downRate = 0.0
        FoxLink.upRate = 0.0
        val setScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        setScale.invoke(null, 0f)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val icons = listOf(R.id.foxDownIcon, R.id.foxUpIcon, R.id.foxTotalIcon, R.id.foxSessionIcon)
                .map { activity.findViewById<MetricIconView>(it) }
            val before = icons.map { it.shapeIndex }
            FoxLink.downRate = 2048.0
            FoxLink.upRate = 1024.0
            FoxLink.count(true, 1024)
            FoxLink.since -= 5000
            FoxLink.tick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertEquals(before.map { (it + 1) % 4 }, icons.map { it.shapeIndex })
        } finally {
            FoxLink.connected = false
            FoxLink.downRate = 0.0
            FoxLink.upRate = 0.0
            Fox.busy = false
            Fox.servers = emptyList()
            controller.pause().stop().destroy()
            setScale.invoke(null, 1f)
            context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

}
