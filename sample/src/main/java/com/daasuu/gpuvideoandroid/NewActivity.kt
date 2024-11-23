package com.daasuu.gpuvideoandroid

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLException
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.daasuu.gpuv.camerarecorder.CameraRecordListener
import com.daasuu.gpuv.camerarecorder.GPUCameraRecorder
import com.daasuu.gpuv.camerarecorder.GPUCameraRecorderBuilder
import com.daasuu.gpuv.camerarecorder.LensFacing
import com.daasuu.gpuv.egl.filter.GlFilterGroup
import com.daasuu.gpuv.egl.filter.GlGammaFilter
import com.daasuu.gpuv.egl.filter.GlWhiteBalanceFilter
import com.daasuu.gpuvideoandroid.databinding.ActivityCameraSquareBinding
import com.daasuu.gpuvideoandroid.widget.SampleCameraGLView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.IntBuffer
import java.text.SimpleDateFormat
import java.util.Date
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

class NewActivity : AppCompatActivity() {
    private var sampleGLView: SampleCameraGLView? = null
    protected var mGPUCameraRecorder: GPUCameraRecorder? = null
    private var filepath: String? = null
    protected var lensFacing: LensFacing = LensFacing.BACK
    protected var cameraWidth: Int = 720
    protected var cameraHeight: Int = 1280
    protected var videoWidth: Int = 2480
    protected var videoHeight: Int = 2480

    private var toggleClick: Boolean = false
    lateinit var mBing: ActivityCameraSquareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBing = ActivityCameraSquareBinding.inflate(LayoutInflater.from(this))
        setContentView(mBing.root)

        onCreateActivity()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        // request camera permission if it has not been grunted.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 666
            )
        }
    }

    private fun onCreateActivity() {
        mBing.btnRecord.setOnClickListener(View.OnClickListener { v: View? ->
            if (mBing.btnRecord.getText() == getString(R.string.app_record)) {
                filepath = videoFilePath
                val file: File = File(filepath)

                Log.d("视频大小", file.exists().toString() + "  " + filepath)
                mGPUCameraRecorder!!.start(filepath)
                mBing.btnRecord.text = "Stop"
//                mBing.filterList.visibility = View.GONE
            } else {
                mGPUCameraRecorder!!.stop()
                mBing.btnRecord.setText(getString(R.string.app_record))
//                mBing.filterList.visibility = View.VISIBLE
            }
        })

        mBing.btnImageCapture.setOnClickListener({ v: View? ->
            captureBitmap(object : BitmapReadyCallbacks {
                override fun onBitmapReady(bitmap: Bitmap?) {
                    bitmap?.let {
                        Handler().post({
                            val imagePath: String = imageFilePath
                            saveAsPngImage(bitmap, imagePath)
                            exportPngToGallery(applicationContext, imagePath)
                        })
                    }

                }

            })

        })


        val filterTypes: List<FilterType> = FilterType.createFilterList()


    }

    override fun onResume() {
        super.onResume()
        setUpCamera()
    }

    override fun onStop() {
        super.onStop()
        releaseCamera()
    }

    private fun releaseCamera() {
        if (sampleGLView != null) {
            sampleGLView!!.onPause()
        }

        if (mGPUCameraRecorder != null) {
            mGPUCameraRecorder!!.stop()
            mGPUCameraRecorder!!.release()
            mGPUCameraRecorder = null
        }

        if (sampleGLView != null) {
            mBing.wrapView.removeView(sampleGLView)
            sampleGLView = null
        }
    }


    private fun setUpCameraView() {
        runOnUiThread({
            val frameLayout: FrameLayout = findViewById(R.id.wrap_view)
            frameLayout.removeAllViews()
            sampleGLView = null
            sampleGLView = SampleCameraGLView(applicationContext)
            sampleGLView!!.setTouchListener({ event: MotionEvent, width: Int, height: Int ->
                if (mGPUCameraRecorder == null) return@setTouchListener
                mGPUCameraRecorder!!.changeManualFocusPoint(event.x, event.y, width, height)
            })
            frameLayout.addView(sampleGLView)
        })
    }


    private fun setUpCamera() {
        setUpCameraView()

        mGPUCameraRecorder = GPUCameraRecorderBuilder(this, sampleGLView) //.recordNoFilter(true)
            .mute(true).lensFacing(LensFacing.BACK).videoSize(videoWidth, videoHeight)
            .cameraSize(cameraWidth, cameraHeight)
            .cameraRecordListener(object : CameraRecordListener {
                override fun onGetFlashSupport(flashSupport: Boolean) {

                }

                override fun onRecordComplete() {
                    Log.d("视频文件", "" + filepath)
                    exportMp4ToGallery(applicationContext, filepath)
                }

                override fun onRecordStart() {
                    runOnUiThread({
//                        mBing.filterList.visibility = View.GONE
                    })
                }

                override fun onError(exception: Exception) {
                    Log.e("GPUCameraRecorder", exception.toString())
                }

                override fun onCameraThreadFinish() {
                    if (toggleClick) {
                        runOnUiThread({
                            setUpCamera()
                        })
                    }
                    toggleClick = false
                }

                override fun onVideoFileReady() {
                }
            })
            .build()


        mBing.compose.setContent {
            val whiteBalanceFilter = GlWhiteBalanceFilter()
            val glGammaFilter = GlGammaFilter()
            val filterGroup = GlFilterGroup(glGammaFilter)
            mGPUCameraRecorder?.setFilter(whiteBalanceFilter)
//            whiteBalanceFilter.setTemperature(2400f)
//            whiteBalanceFilter.setTint(2f)


//            LaunchedEffect(
//                Unit
//            ) {
//                lifecycleScope.launch(Dispatchers.IO) {
//                    delay(1000)
//                    whiteBalanceFilter.setTemperature(3000f)
//
//                }
//            }



//            whiteBalanceFilter.setTint(80f)
//            glGammaFilter.setGamma(1.5f)


            Column {
                val wSlider = remember { mutableFloatStateOf(5000f) }
                Slider(
                    value = wSlider.value,
                    onValueChange = {
                        wSlider.value = it
                        println("temp:${it}")
                        whiteBalanceFilter.setTemperature(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 2000f..7000f,
                )

                val tintSlider = remember { mutableFloatStateOf(0f) }
                Slider(
                    value = tintSlider.value,
                    onValueChange = {
                        tintSlider.value = it
                        whiteBalanceFilter.setTint(it)
                    },
                    modifier = Modifier.fillMaxWidth(), valueRange = 0f..100f,
                )

                val gammaSlider = remember { mutableFloatStateOf(1f) }
                Slider(
                    value = gammaSlider.value,
                    onValueChange = {
                        gammaSlider.value = it
                        glGammaFilter.setGamma(it)
                    },
                    modifier = Modifier.fillMaxWidth(), valueRange = 0f..30f,
                )


            }
        }
    }

    private interface BitmapReadyCallbacks {
        fun onBitmapReady(bitmap: Bitmap?)
    }

    private fun captureBitmap(bitmapReadyCallbacks: BitmapReadyCallbacks) {
        sampleGLView?.let {
            it.queueEvent({
                val egl = EGLContext.getEGL() as EGL10
                val gl = egl.eglGetCurrentContext().gl as GL10
                val snapshotBitmap: Bitmap? = createBitmapFromGLSurface(
                    it.measuredWidth, it.measuredHeight, gl
                )
                runOnUiThread({
                    bitmapReadyCallbacks.onBitmapReady(snapshotBitmap)
                })
            })
        }

    }

    private fun createBitmapFromGLSurface(w: Int, h: Int, gl: GL10): Bitmap? {
        val bitmapBuffer = IntArray(w * h)
        val bitmapSource = IntArray(w * h)
        val intBuffer = IntBuffer.wrap(bitmapBuffer)
        intBuffer.position(0)

        return try {
            gl.glReadPixels(0, 0, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, intBuffer)

            for (i in 0 until h) {
                val offset1 = i * w
                val offset2 = (h - i - 1) * w
                for (j in 0 until w) {
                    val texturePixel = bitmapBuffer[offset1 + j]
                    val blue = (texturePixel shr 16) and 0xff
                    val red = (texturePixel shl 16) and 0x00ff0000
                    val pixel = (texturePixel and 0xff00ff00.toInt()) or red or blue
                    bitmapSource[offset2 + j] = pixel
                }
            }

            Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: GLException) {
            Log.e("CreateBitmap", "createBitmapFromGLSurface: ${e.message}", e)
            null
        }
    }

    fun saveAsPngImage(bitmap: Bitmap, filePath: String) {
        try {
            val file: File = File(filePath)
            val outStream: FileOutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
            outStream.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    companion object {
        fun exportMp4ToGallery(context: Context, filePath: String?) {
            val values: ContentValues = ContentValues(2)
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            values.put(MediaStore.Video.Media.DATA, filePath)
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            context.sendBroadcast(
                Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://$filePath")
                )
            )
        }

        val videoFilePath: String
            get() = androidMoviesFolder.absolutePath + "/" + SimpleDateFormat(
                "yyyyMM_dd-HHmmss"
            ).format(Date()) + "GPUCameraRecorder.mp4"

        val androidMoviesFolder: File
            get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)


        var num = 0
        private fun exportPngToGallery(context: Context, filePath: String) {
            val mediaScanIntent: Intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val f: File = File(filePath)
            if (f.length() < 1024 * 500) {
                Log.d("照片", "照片无效----》" + f.length())
            }
            num += 1
            Log.d("照片", "数量----》$num")
            val contentUri: Uri = Uri.fromFile(f)
            mediaScanIntent.setData(contentUri)
            context.sendBroadcast(mediaScanIntent)
        }

        @JvmStatic
        fun startActivity(activity: CameraSelectActivity) {
            val intent = Intent(activity, NewActivity::class.java)
            activity.startActivity(intent)
        }

        val imageFilePath: String
            get() = androidImageFolder.absolutePath + "/" + SimpleDateFormat(
                "yyyyMM_dd-HHmmss"
            ).format(Date()) + "GPUCameraRecorder.png"

        val androidImageFolder: File
            get() {
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
    }
}
