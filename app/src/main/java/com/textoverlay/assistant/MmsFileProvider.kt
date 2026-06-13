package com.textoverlay.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves a cached MMS PDU file to the platform MMS service, which reads the
 * content:// uri we pass to SmsManager.sendMultimediaMessage from another
 * process. Only exposes files in our cache dir, read-only.
 */
class MmsFileProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: return null
        val file = File(context!!.cacheDir, name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.textoverlay.assistant.mms"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
