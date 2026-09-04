package dev.punit.tidylink.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/** Renders [text] as a 320x320 QR code, black on white regardless of theme. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            320,
            320,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        image.toComposeImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = "Pairing QR code", modifier = modifier)
}
