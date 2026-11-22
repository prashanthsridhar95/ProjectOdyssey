package com.odyssey.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

actual fun qrModules(text: String): List<List<Boolean>> {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    return List(m.height) { y -> List(m.width) { x -> m[x, y] } }
}
