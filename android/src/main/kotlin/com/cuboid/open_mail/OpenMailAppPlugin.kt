package com.cuboid.open_mail

import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.net.Uri
import androidx.annotation.NonNull
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class OpenMailAppPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
  private lateinit var channel: MethodChannel
  private lateinit var context: Context

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "open_mail")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
    if (call.method == "openMailApp") {
      val opened = emailAppIntent(call.argument("nativePickerTitle") ?: "")
      result.success(opened)
    } else if (call.method == "openSpecificMailApp" && call.hasArgument("name")) {
      val opened = specificEmailAppIntent(call.argument("name")!!)
      result.success(opened)
    } else if (call.method == "composeNewEmailInMailApp") {
      val opened = composeNewEmailAppIntent(
        call.argument("nativePickerTitle") ?: "", call.argument("emailContent") ?: ""
      )
      result.success(opened)
    } else if (call.method == "composeNewEmailInSpecificMailApp") {
      val opened = composeNewEmailInSpecificEmailAppIntent(
        call.argument("name") ?: "", call.argument("emailContent") ?: ""
      )
      result.success(opened)
    } else if (call.method == "getMainApps") {
      val apps = getInstalledMailApps()
      val appsJson = Gson().toJson(apps)
      result.success(appsJson)
    } else {
      result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    context = binding.activity.applicationContext
  }

  override fun onDetachedFromActivity() {}

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    context = binding.activity.applicationContext
  }

  override fun onDetachedFromActivityForConfigChanges() {}

  private fun emailAppIntent(@NonNull chooserTitle: String): Boolean {
    val emailIntent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))
    val packageManager = context.packageManager

    val excludedPackages = listOf(
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.moneybookers.skrillpayments",
        "com.zellepay.zelle"
    )

    val activitiesHandlingEmails = packageManager.queryIntentActivities(emailIntent, 0)

    Log.d("OpenMailDebug", "Found ${activitiesHandlingEmails.size} apps for mailto:")

    // Filter out excluded packages
    val filteredActivities = activitiesHandlingEmails.filter {
        val pkgName = it.activityInfo.packageName
        val isExcluded = excludedPackages.any { exclude -> exclude.equals(pkgName, ignoreCase = true) }
        if (isExcluded) {
            Log.d("OpenMailDebug", "Excluded: $pkgName")
        } else {
            Log.d("OpenMailDebug", "Accepted: $pkgName")
        }
        !isExcluded
    }

    if (filteredActivities.isNotEmpty()) {
        // Use the first filtered email app
        val firstEmailPackageName = filteredActivities.first().activityInfo.packageName
        val firstEmailInboxIntent = packageManager.getLaunchIntentForPackage(firstEmailPackageName)
        val emailAppChooserIntent = Intent.createChooser(firstEmailInboxIntent, chooserTitle)

        // Build labeled intents for the rest
        val emailInboxIntents = mutableListOf<LabeledIntent>()
        for (i in 1 until filteredActivities.size) {
            val activityHandlingEmail = filteredActivities[i]
            val packageName = activityHandlingEmail.activityInfo.packageName
            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                emailInboxIntents.add(
                    LabeledIntent(
                        intent,
                        packageName,
                        activityHandlingEmail.loadLabel(packageManager),
                        activityHandlingEmail.icon
                    )
                )
            }
        }

        val extraEmailInboxIntents = emailInboxIntents.toTypedArray()
        emailAppChooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraEmailInboxIntents)
        emailAppChooserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        context.startActivity(emailAppChooserIntent)
        return true
    } else {
        Log.d("OpenMailDebug", "No valid email apps found after filtering.")
        return false
    }
}

  private fun composeNewEmailAppIntent(
    @NonNull chooserTitle: String, @NonNull contentJson: String
  ): Boolean {
    val packageManager = context.packageManager
    val emailContent = Gson().fromJson(contentJson, EmailContent::class.java)
    val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))

    val activitiesHandlingEmails = packageManager.queryIntentActivities(emailIntent, 0)
    if (activitiesHandlingEmails.isNotEmpty()) {
      val emailAppChooserIntent = Intent.createChooser(Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        type = "text/plain"
        setClassName(
          activitiesHandlingEmails.first().activityInfo.packageName,
          activitiesHandlingEmails.first().activityInfo.name
        )

        putExtra(Intent.EXTRA_EMAIL, emailContent.to.toTypedArray())
        putExtra(Intent.EXTRA_CC, emailContent.cc.toTypedArray())
        putExtra(Intent.EXTRA_BCC, emailContent.bcc.toTypedArray())
        putExtra(Intent.EXTRA_SUBJECT, emailContent.subject)
        putExtra(Intent.EXTRA_TEXT, emailContent.body)
      }, chooserTitle)

      val emailComposingIntents = mutableListOf<LabeledIntent>()
      for (i in 1 until activitiesHandlingEmails.size) {
        val activityHandlingEmail = activitiesHandlingEmails[i]
        val packageName = activityHandlingEmail.activityInfo.packageName
        emailComposingIntents.add(
          LabeledIntent(
            Intent(Intent.ACTION_SENDTO).apply {
              data = Uri.parse("mailto:")
              type = "text/plain"
              setClassName(
                activityHandlingEmail.activityInfo.packageName,
                activityHandlingEmail.activityInfo.name
              )
              putExtra(Intent.EXTRA_EMAIL, emailContent.to.toTypedArray())
              putExtra(Intent.EXTRA_CC, emailContent.cc.toTypedArray())
              putExtra(Intent.EXTRA_BCC, emailContent.bcc.toTypedArray())
              putExtra(Intent.EXTRA_SUBJECT, emailContent.subject)
              putExtra(Intent.EXTRA_TEXT, emailContent.body)
            },
            packageName,
            activityHandlingEmail.loadLabel(packageManager),
            activityHandlingEmail.icon
          )
        )
      }

      val extraEmailComposingIntents = emailComposingIntents.toTypedArray()
      val finalIntent =
        emailAppChooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraEmailComposingIntents)
      finalIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(finalIntent)
      return true
    } else {
      return false
    }
  }

  private fun specificEmailAppIntent(name: String): Boolean {
    val emailIntent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))
    val packageManager = context.packageManager

    val activitiesHandlingEmails = packageManager.queryIntentActivities(emailIntent, 0)
    val activityHandlingEmail = activitiesHandlingEmails.firstOrNull {
      it.loadLabel(packageManager) == name
    } ?: return false

    val firstEmailPackageName = activityHandlingEmail.activityInfo.packageName
    val emailInboxIntent =
      packageManager.getLaunchIntentForPackage(firstEmailPackageName) ?: return false

    emailInboxIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(emailInboxIntent)
    return true
  }

  private fun composeNewEmailInSpecificEmailAppIntent(
    @NonNull name: String, @NonNull contentJson: String
  ): Boolean {
    val packageManager = context.packageManager
    val emailContent = Gson().fromJson(contentJson, EmailContent::class.java)
    val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))

    val activitiesHandlingEmails = packageManager.queryIntentActivities(emailIntent, 0)
    val specificEmailActivity = activitiesHandlingEmails.firstOrNull {
      it.loadLabel(packageManager) == name
    } ?: return false

    val composeEmailIntent = Intent(Intent.ACTION_SENDTO).apply {
      data = Uri.parse("mailto:")
      type = "text/plain"
      setClassName(
        specificEmailActivity.activityInfo.packageName, specificEmailActivity.activityInfo.name
      )
      putExtra(Intent.EXTRA_EMAIL, emailContent.to.toTypedArray())
      putExtra(Intent.EXTRA_CC, emailContent.cc.toTypedArray())
      putExtra(Intent.EXTRA_BCC, emailContent.bcc.toTypedArray())
      putExtra(Intent.EXTRA_SUBJECT, emailContent.subject)
      putExtra(Intent.EXTRA_TEXT, emailContent.body)
    }

    composeEmailIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(composeEmailIntent)

    return true
  }

  private fun getInstalledMailApps(): List<App> {
    val emailIntent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))
    val packageManager = context.packageManager
    val activitiesHandlingEmails = packageManager.queryIntentActivities(emailIntent, 0)

    val excludedPackages = listOf(
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.moneybookers.skrillpayments",
        "com.zellepay.zelle"
    )

    Log.d("OpenMailDebug", "Found ${activitiesHandlingEmails.size} apps handling mailto:")

    return if (activitiesHandlingEmails.isNotEmpty()) {
        val mailApps = mutableListOf<App>()
        for (activityInfo in activitiesHandlingEmails) {
            val label = activityInfo.loadLabel(packageManager).toString()
            val pkgName = activityInfo.activityInfo.packageName

            Log.d("OpenMailDebug", "Detected app: $label ($pkgName)")

            if (excludedPackages.any { it.equals(pkgName, ignoreCase = true) }) {
                Log.d("OpenMailDebug", "Excluded: $pkgName")
                continue
            }

            mailApps.add(App(label))
        }
        mailApps
    } else {
        Log.d("OpenMailDebug", "No email apps found")
        emptyList()
    }
}

}

data class App(
  @SerializedName("name") val name: String
)

data class EmailContent(

  @SerializedName("to") val to: List<String>,
  @SerializedName("cc") val cc: List<String>,
  @SerializedName("bcc") val bcc: List<String>,
  @SerializedName("subject") val subject: String,
  @SerializedName("body") val body: String
)
