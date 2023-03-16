package com.piezosaurus.macrosnap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.*


class Tasks(val context: Context, val activity: Activity) {

    fun timer (msg: String, secs: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_LENGTH, secs)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
        Log.i("TASK", "timer")
    }

    fun alarm (msg: String, hour: Int, min: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, min)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
        context.startActivity(intent)
        Log.i("TASK", "alarm")
    }
    
    fun maps (locationStr: String) {
        val location = locationStr.replace(" ", "+")
        val gmmIntentUri = Uri.parse("google.navigation:q=$location")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        context.startActivity(mapIntent)
//        val intent = Intent(Intent.ACTION_VIEW).apply {
//            data = geoLocation
//        }
//        if (intent.resolveActivity(context.packageManager) != null) {
//            context.startActivity(intent)
//        }
        Log.i("TASK", "map navigation $locationStr")
    }

    fun spotify (playlistId: String) {
        val spotifyIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:playlist:$playlistId:play"))
        context.startActivity(spotifyIntent)

        Log.i("TASK", "spotify $playlistId")
    }

    fun spotifyNext () {
        val spotifyIntentNext =
            Intent("com.spotify.mobile.android.ui.widget.NEXT")
        spotifyIntentNext.setPackage("com.spotify.music")
        context.sendBroadcast(spotifyIntentNext)

        Log.i("TASK", "spotify next")
    }

    fun website (url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
        Log.i("PRINT", "website")
    }

    fun call(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            context.startActivity(intent);
        } else {
            requestPermissions(activity, arrayOf<String>(Manifest.permission.CALL_PHONE), 1);
        }
    }

}