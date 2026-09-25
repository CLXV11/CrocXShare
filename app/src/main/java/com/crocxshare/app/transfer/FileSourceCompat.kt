package com.crocxshare.app.transfer

import android.content.Context
import android.net.Uri

/** Bridges SAF URIs picked in the UI to streaming FileSource objects. */
object FileSourceCompat {
    fun query(context: Context, uri: Uri): FileSource = SafFileSource.query(context, uri)
}
