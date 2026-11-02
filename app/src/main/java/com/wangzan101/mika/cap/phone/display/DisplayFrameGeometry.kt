package com.wangzan101.mika.cap.phone.display

/** ROTATION_0/90/180/270 分别为 0/1/2/3。物理缓冲不变,逻辑屏在四分之一圈时交换宽高。 */
internal data class DisplayFrameGeometry(val bufferWidth: Int, val bufferHeight: Int, val rotation: Int) {
    private val sideways get() = rotation == 1 || rotation == 3
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight

    fun previewSize(maxWidth: Int): Pair<Int, Int> {
        val w = minOf(maxWidth, width)
        return w to maxOf(1, (height.toLong() * w / width).toInt())
    }
}
