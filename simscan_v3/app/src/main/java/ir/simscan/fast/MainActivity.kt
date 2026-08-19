package ir.simscan.fast

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ir.simscan.fast.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: SimDb
    private lateinit var adapter: RecordAdapter
    private lateinit var barcodeScanner: BarcodeScanner
    private val ocrEngine = InkOcrEngine()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var torchOn = false

    private enum class ScanMode { BARCODE, PHONE }
    @Volatile private var mode = ScanMode.BARCODE
    @Volatile private var pendingRecordId: Long? = null
    private val barcodeBusy = AtomicBoolean(false)
    private val ocrBusy = AtomicBoolean(false)
    @Volatile private var lastOcrAt = 0L
    @Volatile private var blockedBarcode = ""
    @Volatile private var blockBarcodeUntil = 0L

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "مجوز دوربین لازم است", Toast.LENGTH_LONG).show()
    }

    private val createXlsx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            try {
                XlsxExporter.export(this, uri, db.all())
                Toast.makeText(this, "فایل Excel ذخیره شد", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "خطا در ذخیره Excel: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = SimDb(this)
        adapter = RecordAdapter { showEditDialog(it) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_CODE_128)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        binding.exportButton.setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            createXlsx.launch("SIMSCAN_$stamp.xlsx")
        }
        binding.manualPhoneButton.setOnClickListener { showManualPhoneDialog() }
        binding.flashButton.setOnClickListener { toggleFlash() }

        setupCameraGestures()
        refreshList()
        setReadyStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "خطای دوربین: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        when (mode) {
            ScanMode.BARCODE -> analyzeBarcode(proxy)
            ScanMode.PHONE -> analyzePhone(proxy)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeBarcode(proxy: ImageProxy) {
        if (!barcodeBusy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
            barcodeBusy.set(false)
            proxy.close()
            return
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(media, rotation)
        barcodeScanner.process(image)
            .addOnSuccessListener(cameraExecutor) { results ->
                val value = results.asSequence()
                    .mapNotNull { it.rawValue }
                    .map { cleanBarcode(it) }
                    .firstOrNull { isExpectedBarcode(it) }

                if (value == null) {
                    proxy.close()
                    barcodeBusy.set(false)
                    return@addOnSuccessListener
                }

                val now = System.currentTimeMillis()
                if (value == blockedBarcode && now < blockBarcodeUntil) {
                    proxy.close()
                    barcodeBusy.set(false)
                    return@addOnSuccessListener
                }

                val bitmap: Bitmap? = try { proxy.toBitmap() } catch (_: Exception) { null }
                proxy.close()
                barcodeBusy.set(false)
                onBarcodeDetected(value, bitmap, rotation)
            }
            .addOnFailureListener(cameraExecutor) {
                proxy.close()
                barcodeBusy.set(false)
            }
    }

    private fun analyzePhone(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastOcrAt < 420 || !ocrBusy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastOcrAt = now
        val rotation = proxy.imageInfo.rotationDegrees
        val bitmap = try { proxy.toBitmap() } catch (_: Exception) { null }
        proxy.close()
        if (bitmap == null) {
            ocrBusy.set(false)
            return
        }
        val targetId = pendingRecordId
        if (targetId == null) {
            bitmap.recycle()
            ocrBusy.set(false)
            mode = ScanMode.BARCODE
            return
        }

        ocrEngine.recognize(bitmap, rotation) { phone ->
            ocrBusy.set(false)
            if (phone != null) completePhone(targetId, phone)
        }
    }

    private fun onBarcodeDetected(barcode: String, sameFrame: Bitmap?, rotation: Int) {
        val existing = db.findByBarcode(barcode)
        if (existing != null && existing.phone.isNotBlank()) {
            blockedBarcode = barcode
            blockBarcodeUntil = System.currentTimeMillis() + 2600
            sameFrame?.recycle()
            runOnUiThread {
                binding.statusText.text = "این بارکد قبلاً کامل ثبت شده — کارت بعدی"
            }
            return
        }

        val id = existing?.id ?: db.insertBarcode(barcode).let { inserted ->
            if (inserted > 0) inserted else db.findByBarcode(barcode)?.id ?: -1L
        }
        if (id <= 0) {
            sameFrame?.recycle()
            return
        }

        pendingRecordId = id
        mode = ScanMode.PHONE
        blockedBarcode = barcode
        blockBarcodeUntil = System.currentTimeMillis() + 2600

        runOnUiThread {
            playBarcodeBeep()
            refreshList()
            binding.statusText.text = "بارکد ثبت شد ✓  شماره دست‌نویس را نشان بده — جلو یا پشت کارت"
        }

        if (sameFrame != null && ocrBusy.compareAndSet(false, true)) {
            ocrEngine.recognize(sameFrame, rotation) { phone ->
                ocrBusy.set(false)
                if (phone != null) completePhone(id, phone)
            }
        } else {
            sameFrame?.recycle()
        }
    }

    private fun completePhone(recordId: Long, phone: String) {
        val currentPending = pendingRecordId
        if (currentPending != recordId) return
        val normalized = PhoneNormalizer.validMobile(phone) ?: return
        db.updatePhone(recordId, normalized)
        pendingRecordId = null
        mode = ScanMode.BARCODE
        runOnUiThread {
            playSuccessBeep()
            refreshList()
            binding.statusText.text = "کامل شد ✓  $normalized — کارت بعدی را نشان بده"
        }
    }

    private fun cleanBarcode(raw: String): String = raw.filter { it.isDigit() }

    private fun isExpectedBarcode(value: String): Boolean {
        return value.length in 18..22 && value.startsWith("89")
    }

    private fun refreshList() {
        val list = db.all()
        adapter.submit(list)
        binding.countText.text = "${list.size} ثبت"
    }

    private fun setReadyStatus() {
        binding.statusText.text = "کارت را نشان بده — بارکد سریع آماده است"
    }

    private fun showManualPhoneDialog() {
        val id = pendingRecordId
        if (id == null) {
            Toast.makeText(this, "اول بارکد کارت را اسکن کن", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "مثلاً 09123456789"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("شماره موبایل")
            .setView(box)
            .setPositiveButton("ذخیره", null)
            .setNegativeButton("انصراف", null)
            .setNeutralButton("بدون شماره ادامه", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phone = PhoneNormalizer.validMobile(input.text.toString())
                if (phone == null) Toast.makeText(this, "شماره موبایل معتبر نیست", Toast.LENGTH_SHORT).show()
                else {
                    completePhone(id, phone)
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pendingRecordId = null
                mode = ScanMode.BARCODE
                binding.statusText.text = "شماره خالی ماند — کارت بعدی را نشان بده"
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditDialog(record: SimRecord) {
        val phoneInput = EditText(this).apply {
            hint = "شماره موبایل"
            setText(record.phone)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }
        val barcodeInput = EditText(this).apply {
            hint = "بارکد خطی"
            setText(record.barcode)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(phoneInput)
            addView(barcodeInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("ویرایش ردیف")
            .setView(box)
            .setPositiveButton("ذخیره", null)
            .setNegativeButton("انصراف", null)
            .setNeutralButton("حذف", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phoneRaw = phoneInput.text.toString().trim()
                val phone = if (phoneRaw.isBlank()) "" else PhoneNormalizer.validMobile(phoneRaw)
                val barcode = barcodeInput.text.toString().filter { ch -> ch.isDigit() }
                when {
                    phone == null -> Toast.makeText(this, "شماره موبایل معتبر نیست", Toast.LENGTH_SHORT).show()
                    barcode.length !in 18..22 -> Toast.makeText(this, "بارکد معتبر نیست", Toast.LENGTH_SHORT).show()
                    !db.update(record.id, phone, barcode) -> Toast.makeText(this, "بارکد تکراری یا خطای ذخیره", Toast.LENGTH_SHORT).show()
                    else -> {
                        refreshList()
                        if (pendingRecordId == record.id && phone.isNotBlank()) {
                            pendingRecordId = null
                            mode = ScanMode.BARCODE
                        }
                        dialog.dismiss()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                db.delete(record.id)
                if (pendingRecordId == record.id) {
                    pendingRecordId = null
                    mode = ScanMode.BARCODE
                }
                refreshList()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun toggleFlash() {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "این دوربین فلش ندارد", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
        binding.flashButton.text = if (torchOn) "فلش ✓" else "فلش"
    }

    private fun setupCameraGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val c = camera ?: return false
                val state = c.cameraInfo.zoomState.value ?: return false
                val next = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                c.cameraControl.setZoomRatio(next)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                focusAt(event.x, event.y)
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        val factory: MeteringPointFactory = binding.previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()
        c.cameraControl.startFocusAndMetering(action)
    }

    private fun playBarcodeBeep() {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90).apply {
            startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 110)
            binding.root.postDelayed({ release() }, 180)
        }
    }

    private fun playSuccessBeep() {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 95).apply {
            startTone(android.media.ToneGenerator.TONE_PROP_ACK, 170)
            binding.root.postDelayed({ release() }, 240)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(
                android.os.VibrationEffect.createOneShot(70, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(70)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
        ocrEngine.close()
        db.close()
    }
}
