package com.odyssey.ui

import qrcode.QRCode

actual fun qrModules(text: String): List<List<Boolean>> {
    val code = QRCode.ofSquares().build(text)
    return code.rawData.map { row -> row.map { it.dark } }
}
