package dev.pixelforge.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.pixelforge.core.storage.FileKinds
import java.io.File

object FileOpen {
    fun uriFor(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun open(ctx: Context, f: File) {
        val uri = runCatching { uriFor(ctx, f) }.getOrNull() ?: return ctx.toast("Cannot open this location")
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileKinds.mimeOf(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.launch(Intent.createChooser(i, "Open with"))
    }

    fun share(ctx: Context, files: List<File>) {
        val uris = files.mapNotNull { runCatching { uriFor(ctx, it) }.getOrNull() }
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(FileKinds.mimeOf(files.first().name)).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        ctx.launch(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }
}
