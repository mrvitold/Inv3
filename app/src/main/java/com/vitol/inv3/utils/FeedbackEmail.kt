package com.vitol.inv3.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.vitol.inv3.BuildConfig
import com.vitol.inv3.R

private const val FEEDBACK_EMAIL = "svarosfrontas@gmail.com"

fun openFeedbackEmail(context: Context) {
    val subject = context.getString(R.string.feedback_email_subject, BuildConfig.VERSION_CODE)
    val body = context.getString(
        R.string.feedback_email_body,
        Build.MANUFACTURER,
        Build.MODEL,
        Build.VERSION.RELEASE,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE
    )
    val uri = Uri.parse(
        "mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
    )
    val intent = Intent(Intent.ACTION_SENDTO).apply { data = uri }
    try {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.feedback_send_chooser_title))
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.feedback_no_email_app), Toast.LENGTH_LONG).show()
    }
}
