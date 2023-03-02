package com.piezosaurus.macrosnap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.*


public class Tasks(val context: Context) {

    public fun timer (msg: String, secs: Int) {
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

    public fun alarm (msg: String, hour: Int, min: Int) {
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
    
    public fun maps (geoLocation: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = geoLocation
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
        Log.i("TASK", "map query $geoLocation")
    }

    public fun spotify (playlistId: String) {
        val spotifyIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:playlist:$playlistId:play"))
        context.startActivity(spotifyIntent)

//        val spotifyIntentNext =
//            Intent("com.spotify.mobile.android.ui.widget.PLAY")
//        spotifyIntentNext.putExtra("paused", false)
//        spotifyIntentNext.setPackage("com.spotify.music")
//        context.sendBroadcast(spotifyIntentNext)

        Log.i("TASK", "spotify $playlistId")
    }

}