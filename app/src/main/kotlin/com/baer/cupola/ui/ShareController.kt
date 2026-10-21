package com.baer.cupola.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.baer.cupola.R
import com.baer.cupola.feature.settings.ShareRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders the current view to a PNG in the cache folder and builds the ACTION_SEND intent; needs no permission. */
class ShareController(
    private val context: Context,
    private val renderer: ShareRenderer,
    private val toUri: (Context, File) -> Uri = ::uriFor,
) {
    @Inject constructor(@ApplicationContext context: Context, renderer: ShareRenderer) :
        this(context, renderer, ::uriFor)

    suspend fun createShareIntent(width: Int = WIDTH, height: Int = HEIGHT): Intent = withContext(Dispatchers.Default) {
        val rgba = renderer.renderRgba(width, height)
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "cupola.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" } }
        } finally {
            bitmap.recycle()
        }
        val uri = toUri(context, file)
        Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(null, uri) }
    }

    /** Starts the share chooser through [start]; any failure shows a short message instead of failing silently. */
    suspend fun share(start: (Intent) -> Unit) {
        try {
            start(Intent.createChooser(createShareIntent(), null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Cupola", "share failed", e)
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920

        fun authority(context: Context) = "${context.packageName}.share"

        fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)
    }
}
