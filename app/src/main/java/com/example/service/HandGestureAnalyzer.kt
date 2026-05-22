package com.example.service

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class GestureResult(
    val detectedGesture: String, // "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN", "FIST", "PEACE", "NONE"
    val centroid: PointF?,
    val boundingBox: RectF?,
    val peaks: List<PointF>,
    val debugBitmap: Bitmap?,
    val skinRatio: Float
)

class HandGestureAnalyzer(
    private val onGestureDetected: (String) -> Unit,
    private val onAnalysisUpdate: (GestureResult) -> Unit
) : ImageAnalysis.Analyzer {

    // Swipe history tracking
    private val centroidHistory = mutableListOf<Centroid>()
    private val historyMaxSize = 12
    private var lastGestureTime = 0L
    private val gestureCooldownMs = 1200L

    class Centroid(val x: Float, val y: Float, val timestamp: Long)

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < 250L) {
            // Drop frames to optimize CPU under intense loads during cooldown
            image.close()
            return
        }

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // Highly optimized subsampling skip factor for << 2ms execution latency
        val skip = 8
        val cols = width / skip
        val rows = height / skip

        var skinPixelCount = 0
        var sumX = 0f
        var sumY = 0f

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        // Buffer binary mask to build a preview Bitmap & count fingers
        val skinMask = BooleanArray(rows * cols)

        for (r in 0 until rows) {
            val imgY = r * skip
            for (c in 0 until cols) {
                val imgX = c * skip

                val yIdx = imgY * yRowStride + imgX * yPixelStride
                val uIdx = (imgY / 2) * uRowStride + (imgX / 2) * uPixelStride
                val vIdx = (imgY / 2) * vRowStride + (imgX / 2) * vPixelStride

                if (yIdx >= yBuffer.capacity() || uIdx >= uBuffer.capacity() || vIdx >= vBuffer.capacity()) {
                    continue
                }

                // Convert signed Java byte to unsigned Int [0, 255]
                val yVal = yBuffer.get(yIdx).toInt() and 0xFF
                val uVal = uBuffer.get(uIdx).toInt() and 0xFF
                val vVal = vBuffer.get(vIdx).toInt() and 0xFF

                // Check skin chrominance (Cb is U, Cr is V)
                // Cb typically in [78, 131], Cr typically in [131..178]. Y levels > 40 ensures good light details
                if (yVal > 45 && uVal in 78..132 && vVal in 131..179) {
                    skinPixelCount++
                    val normX = c.toFloat() / cols
                    val normY = r.toFloat() / rows

                    sumX += normX
                    sumY += normY

                    if (normX < minX) minX = normX
                    if (normX > maxX) maxX = normX
                    if (normY < minY) minY = normY
                    if (normY > maxY) maxY = normY

                    skinMask[r * cols + c] = true
                }
            }
        }

        val totalPixels = rows * cols
        val skinRatio = skinPixelCount.toFloat() / totalPixels

        var detectedGesture = "NONE"
        var centroid: PointF? = null
        var boundingBox: RectF? = null
        val peaksList = mutableListOf<PointF>()
        var debugBitmap: Bitmap? = null

        // Require substantial area size to register hand & ignore back-reflections
        if (skinRatio > 0.02f) {
            val cx = sumX / skinPixelCount
            val cy = sumY / skinPixelCount
            centroid = PointF(cx, cy)
            boundingBox = RectF(minX, minY, maxX, maxY)

            // 1. Swipe Motion Inference kinematics
            synchronized(centroidHistory) {
                centroidHistory.add(Centroid(cx, cy, now))
                if (centroidHistory.size > historyMaxSize) {
                    centroidHistory.removeAt(0)
                }

                // Analyze swift swipes on historical list
                if (now - lastGestureTime > gestureCooldownMs && centroidHistory.size >= 6) {
                    val first = centroidHistory.first()
                    val last = centroidHistory.last()
                    val dx = last.x - first.x
                    val dy = last.y - first.y
                    val dt = last.timestamp - first.timestamp

                    if (dt in 100..650) {
                        val absDx = Math.abs(dx)
                        val absDy = Math.abs(dy)
                        if (absDx > 0.28f && absDx > absDy * 1.5f) {
                            detectedGesture = if (dx < 0) "SWIPE_LEFT" else "SWIPE_RIGHT"
                            lastGestureTime = now
                            centroidHistory.clear()
                            onGestureDetected(detectedGesture)
                        } else if (absDy > 0.28f && absDy > absDx * 1.5f) {
                            detectedGesture = if (dy < 0) "SWIPE_UP" else "SWIPE_DOWN"
                            lastGestureTime = now
                            centroidHistory.clear()
                            onGestureDetected(detectedGesture)
                        }
                    }
                }
            }

            // 2. High density contour profiling & Finger peaks count
            val minCol = (minX * cols).toInt().coerceIn(0, cols - 1)
            val maxCol = (maxX * cols).toInt().coerceIn(0, cols - 1)
            val minRow = (minY * rows).toInt().coerceIn(0, rows - 1)
            val maxRow = (maxY * rows).toInt().coerceIn(0, rows - 1)

            val colWidth = maxCol - minCol + 1
            if (colWidth > 8) {
                // Find highest skin pixel for each column in bounding box
                val upperPoints = IntArray(colWidth) { maxRow }
                for (cIdx in minCol..maxCol) {
                    val arrIndex = cIdx - minCol
                    for (rIdx in minRow..maxRow) {
                        if (skinMask[rIdx * cols + cIdx]) {
                            upperPoints[arrIndex] = rIdx
                            break
                        }
                    }
                }

                // Local minima finder inside moving vertical column ranges
                val window = 3
                val tempPeaksCols = mutableListOf<Int>()
                for (i in window until colWidth - window) {
                    val currentY = upperPoints[i]
                    if (currentY >= maxRow - 4) continue

                    var isMin = true
                    for (w in -window..window) {
                        if (w == 0) continue
                        if (upperPoints[i + w] < currentY) {
                            isMin = false
                            break
                        }
                    }
                    if (isMin) {
                        val leftDepth = upperPoints[i - window] - currentY
                        val rightDepth = upperPoints[i + window] - currentY
                        // Filter details to verify peak depth height
                        if (leftDepth >= 8 && rightDepth >= 8) {
                            tempPeaksCols.add(i + minCol)
                        }
                    }
                }

                // Map results to normalized 0..1 bounding box points
                for (peakCol in tempPeaksCols) {
                    var peakRow = minRow
                    for (rIdx in minRow..maxRow) {
                        if (skinMask[rIdx * cols + peakCol]) {
                            peakRow = rIdx
                            break
                        }
                    }
                    peaksList.add(PointF(peakCol.toFloat() / cols, peakRow.toFloat() / rows))
                }

                // Register static stances like Peace sign or Fist closed
                if (now - lastGestureTime > gestureCooldownMs) {
                    val pCount = peaksList.size
                    if (pCount == 2) {
                        detectedGesture = "PEACE"
                        lastGestureTime = now
                        onGestureDetected(detectedGesture)
                    } else if (pCount == 0 && (maxRow - minRow) < rows * 0.35f) {
                        detectedGesture = "FIST"
                        lastGestureTime = now
                        onGestureDetected(detectedGesture)
                    }
                }
            }
        } else {
            synchronized(centroidHistory) {
                centroidHistory.clear()
            }
        }

        // Build green overlays
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(rows * cols)
        for (i in 0 until rows * cols) {
            pixels[i] = if (skinMask[i]) {
                Color.argb(160, 46, 204, 113) // Transparent emerald skin mask
            } else {
                Color.argb(35, 0, 0, 0) // Shadowed background
            }
        }
        bmp.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        debugBitmap = bmp

        onAnalysisUpdate(
            GestureResult(
                detectedGesture = detectedGesture,
                centroid = centroid,
                boundingBox = boundingBox,
                peaks = peaksList,
                debugBitmap = debugBitmap,
                skinRatio = skinRatio
            )
        )

        image.close()
    }
}
