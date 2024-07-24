package com.example.spoofedaudiodetector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.record.AndroidAudioRecorder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.LinkedList
import java.util.Locale
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {

    // recorder
    private val recorder by lazy {
        AndroidAudioRecorder(applicationContext)
    }

    // recorded files' queue
    private val fileUriQueue = LinkedList<Uri>()
    private var fileCounter = 0

    // predicted result
    private var predict: Int by Delegates.observable(1) { property, oldValue, newValue ->
        if (newValue == 0) {
            findViewById<TextView>(R.id.tvResult).text = "Dự đoán: Phát hiện giọng giả"
        }
    }

    // detector's status
    private var running = false

    // server info
    //private val serverHost = "192.168.1.9"
    //private val serverHost = "192.168.1.32"
    private val serverHost = "192.168.185.163"
    private val serverPort = 9999

    // for sending whole file
    private var pickedFileUri: Uri? = null

    // communication codes
    private var newFileCode = "<MNY>"
    private var endFileCode = "<END>"
    private var closeConnCode = "<DNE>"

    // request codes
    private val permissionRequestCode = 0
    private val pickFileRequestCode = 1

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 34) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
                ),
                permissionRequestCode
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                permissionRequestCode
            )
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET
            ),
            permissionRequestCode
        )

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            showFilePicker()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener() {

            // init new task
            fileUriQueue.clear()
            fileCounter = 0

            predict = 1
            findViewById<TextView>(R.id.tvResult).text = "Dự đoán: Giọng thật"
            running = true
            findViewById<TextView>(R.id.tvStatus).text = "Đang kiểm tra..."

            Intent(applicationContext, MainService::class.java).also {
                it.action = MainService.Actions.START.toString()
                startService(it)
            }

            GlobalScope.launch {

                // prepare socket
                val socket = Socket(serverHost, serverPort)
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()

                // stuffs for communicating
                val inputBuffer = ByteArray(1)
                var readBytes = 0
                var sentFiles = 0
                var receivedResults = 0

                // for calculating spoof percent
                var spoofedAudioNum = 0

                if (pickedFileUri == null) {
                    val recordingJob = launch {
                        while (running) {
                            File(cacheDir, "audio$fileCounter.m4a").also {
                                fileCounter++
                                recorder.start(it)
                                delay(4000)
                                recorder.stop()
                                fileUriQueue.add(it.toUri())
                            }
                        }
                    }
                } else {
                    running = false
                    newFileCode = "<SGL>"
                    fileCounter++
                    fileUriQueue.add(pickedFileUri!!)
                }

                val sendingJob = launch {
                    while (running || sentFiles < fileCounter) {
                        val fileUri = fileUriQueue.poll()
                        if (fileUri != null) {
                            sentFiles++
                            sendFileViaSocket(fileUri, socket, outputStream)
                        }
                    }
                }

                val receivingJob = launch {
                    while (running || receivedResults < fileCounter || newFileCode == "<SGL>") {
                        readBytes = inputStream.read(inputBuffer)
                        if (readBytes > 0) {
                            receivedResults++
                            if (String(inputBuffer).toInt() == 2) break
                            runOnUiThread {
                                predict = String(inputBuffer).toInt()
                                if (predict == 0) spoofedAudioNum++
                                findViewById<TextView>(R.id.spoofPercent).text =
                                    String.format(
                                        Locale("vi", "VN"),
                                        "Tỉ lệ giọng giả: %.2f %%",
                                        spoofedAudioNum.toFloat() / receivedResults.toFloat() * 100
                                    )
                            }
                        }
                    }
                }

                receivingJob.join()

                // cleaning task
                outputStream.write(closeConnCode.toByteArray())
                outputStream.flush()
                outputStream.close()
                inputStream.close()
                socket.close()
                pickedFileUri = null
                newFileCode = "<MNY>"
                runOnUiThread {
                    //findViewById<TextView>(R.id.tvFileCounter).text = "File Counter: $fileCounter"
                    findViewById<TextView>(R.id.tvStatus).text = "Kiểm tra xong"
                    findViewById<TextView>(R.id.tvChoosenFile).text = "Đã chọn:\nKhông có"
                }

                Intent(applicationContext, MainService::class.java).also {
                    it.action = MainService.Actions.STOP.toString()
                    startService(it)
                }
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            running = false
        }
    }

    private fun showFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, pickFileRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFileRequestCode && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val fileName = getFileName(uri)
                if (!isM4aFile(fileName)) return
                pickedFileUri = uri
                findViewById<TextView>(R.id.tvChoosenFile).text = "Đã chọn:\n$fileName"
            }
        }
    }

    private fun isM4aFile(fileName: String?): Boolean {
        if (fileName == null) return false
        if (fileName.lowercase().endsWith(".m4a")) {
            Toast.makeText(this, "Thành công!", Toast.LENGTH_LONG).show()
            return true
        } else {
            Toast.makeText(this, "Thất bại!\nHãy chọn tệp m4a!", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun sendFileViaSocket(uri: Uri, socket: Socket, outputStream: OutputStream) {
        val contentResolver = contentResolver
        try {
            contentResolver.openInputStream(uri)?.use { fileInputStream ->

                val buffer = ByteArray(1024)
                var bytesRead: Int

                outputStream.write(newFileCode.toByteArray())
                while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.write(endFileCode.toByteArray())

                outputStream.flush()
                fileInputStream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme.equals("content")) {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }
}