package com.example.spoofedaudiodetector

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MainService: Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.START.toString() -> start()
            Actions.STOP.toString() -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start() {
        val notification = NotificationCompat.Builder(this, "main_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ứng dụng đang kiểm tra")
            .setContentText("Micro đang được sử dụng")
            .build()
        startForeground(1, notification)
    }

    enum class Actions {
        START, STOP
    }
}