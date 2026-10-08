package com.rskusum.whocaller.core.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import com.rskusum.whocaller.core.ui.R

/**
 * System intents for call/message/contact actions. Calling uses ACTION_DIAL so WhoCaller does not
 * need the CALL_PHONE permission: the user always confirms in their dialer.
 */
object ActionIntents {
    fun dial(context: Context, number: String) =
        start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    fun message(context: Context, number: String) =
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    fun saveContact(context: Context, number: String, name: String? = null) = start(
        context,
        Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (number.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, number)
            if (name != null) putExtra(ContactsContract.Intents.Insert.NAME, name)
        },
    )

    fun viewContact(context: Context, contactId: Long) = start(
        context,
        Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())),
    )

    fun editContact(context: Context, contactId: Long) = start(
        context,
        Intent(Intent.ACTION_EDIT, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()))
            .putExtra("finishActivityOnSaveCompleted", true),
    )

    fun shareText(context: Context, text: String) = start(
        context,
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
    )

    fun openUrl(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http" && uri.scheme != "mailto") return
        start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
        }
    }
}
