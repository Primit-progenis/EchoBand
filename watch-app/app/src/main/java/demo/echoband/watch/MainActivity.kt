package demo.echoband.watch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val permissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    )
    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var codeField: EditText
    private lateinit var statusText: TextView
    private lateinit var streamButton: Button
    private lateinit var ecgButton: Button
    private lateinit var ecgText: TextView
    private lateinit var heartRateText: TextView
    private lateinit var connectionCard: LinearLayout

    private val ink = Color.rgb(22, 44, 61)
    private val muted = Color.rgb(103, 126, 143)
    private val accent = Color.rgb(20, 139, 151)
    private val canvas = Color.rgb(242, 247, 249)

    private var streaming = false
    private var service: HealthTrackingService? = null
    private var heartTracker: HealthTracker? = null
    private var accelerometerTracker: HealthTracker? = null
    private var ecgTracker: HealthTracker? = null
    private var socket: WebSocket? = null
    private var reconnectDelay = 1_000L
    private var reconnectTask: Runnable? = null
    private var ecgTask: Runnable? = null
    private var lastLeadOff = false
    @Volatile private var ax = 0f
    @Volatile private var ay = 0f
    @Volatile private var az = 9.8f
    private var ecgAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        statusText.text = "Enter the laptop's LAN IP, port 8787 and pairing code."
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 14)
            setBackgroundColor(canvas)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply { text = "ECHOBAND"; textSize = 17f; letterSpacing = .12f; setTextColor(ink); gravity = Gravity.CENTER; setPadding(0, 4, 0, 0); setTypeface(null, android.graphics.Typeface.BOLD) })
        content.addView(TextView(this).apply { text = "HOME FOLLOW-UP · WATCH LINK"; textSize = 8f; letterSpacing = .08f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, 1, 0, 10) })

        val vitalsCard = card()
        vitalsCard.addView(TextView(this).apply { text = "HEART RATE"; textSize = 9f; letterSpacing = .08f; setTextColor(muted) })
        heartRateText = TextView(this).apply { text = "—"; textSize = 34f; setTextColor(ink); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 0) }
        vitalsCard.addView(heartRateText)
        vitalsCard.addView(TextView(this).apply { text = "bpm  ·  Watch reading"; textSize = 9f; setTextColor(muted) })
        content.addView(vitalsCard, cardParams(0, 0, 0, 8))

        connectionCard = card()
        connectionCard.addView(sectionTitle("LAPTOP RELAY"))
        ipField = field("Laptop IP address", "192.168.0.193", false); connectionCard.addView(ipField)
        portField = field("Port", "8787", true); connectionCard.addView(portField)
        codeField = field("Pairing code", "6-character code", false); connectionCard.addView(codeField)
        statusText = TextView(this).apply { textSize = 10f; setTextColor(muted); setPadding(2, 7, 2, 6) }; connectionCard.addView(statusText)
        content.addView(connectionCard, cardParams(0, 0, 0, 8))

        streamButton = Button(this).apply { text = "Start streaming"; isAllCaps = false; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(accent); setOnClickListener { if (streaming) stopStreaming() else requestPermissionsAndStart() } }
        content.addView(streamButton, cardParams(0, 0, 0, 8))

        val ecgCard = card()
        ecgCard.addView(sectionTitle("OPTIONAL ECG"))
        ecgText = TextView(this).apply { textSize = 9f; setTextColor(muted); text = "Availability checked when connected."; setPadding(0, 2, 0, 6) }; ecgCard.addView(ecgText)
        ecgButton = Button(this).apply { text = "Measure · 30 sec"; isAllCaps = false; isEnabled = false; setOnClickListener { startEcg() } }; ecgCard.addView(ecgButton)
        content.addView(ecgCard, cardParams(0, 0, 0, 8))
        content.addView(TextView(this).apply { text = "Local Wi‑Fi only · Decision support, not diagnosis"; textSize = 8f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(2, 2, 2, 8) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12, 10, 12, 10)
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f; setStroke(1, Color.rgb(220, 231, 236)) }
    }

    private fun cardParams(left: Int, top: Int, right: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(left, top, right, bottom)
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label; textSize = 9f; letterSpacing = .08f; setTextColor(accent); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 3)
    }

    private fun field(label: String, hint: String, numeric: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(if (label == "Port") "8787" else "")
        textSize = 11f
        setTextColor(ink); setHintTextColor(muted)
        setSingleLine(true)
        if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        else inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        contentDescription = label
        setPadding(8, 4, 8, 4)
    }

    private fun requestPermissionsAndStart() {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 41) else beginStreaming()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) beginStreaming()
            else statusText.text = "Sensor permission is required. Grant it in watch Settings, then tap Start streaming."
        }
    }

    private fun beginStreaming() {
        val host = ipField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
        val code = codeField.text.toString().trim()
        if (host.isBlank() || port == null || port !in 1..65535 || code.isBlank()) {
            statusText.text = "Enter a laptop IP, a valid port and the relay pairing code."
            return
        }
        streaming = true
        streamButton.text = "Stop streaming"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusText.text = "Connecting to relay…"
        connectSocket()
        connectSensors()
    }

    private fun relayUrl(): String {
        val code = URLEncoder.encode(codeField.text.toString().trim(), "UTF-8")
        return "ws://${ipField.text.toString().trim()}:${portField.text.toString().trim()}/ws?role=watch&code=$code"
    }

    private fun connectSocket() {
        if (!streaming) return
        val request = Request.Builder().url(relayUrl()).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!streaming) { webSocket.close(1000, "Stopped by user"); return }
                reconnectDelay = 1_000L
                main.post {
                    statusText.text = "Relay connected · waiting for a valid reading"
                    ipField.visibility = android.view.View.GONE; portField.visibility = android.view.View.GONE; codeField.visibility = android.view.View.GONE
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (streaming) main.post {
                    statusText.text = "Relay unavailable. Check IP, port, code and Wi‑Fi."
                    ipField.visibility = android.view.View.VISIBLE; portField.visibility = android.view.View.VISIBLE; codeField.visibility = android.view.View.VISIBLE
                }
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scheduleReconnect() }
        })
    }

    private fun scheduleReconnect() {
        if (!streaming || reconnectTask != null) return
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(15_000L)
        reconnectTask = Runnable { reconnectTask = null; connectSocket() }.also { main.postDelayed(it, delay) }
    }

    private fun connectSensors() {
        try {
            service = HealthTrackingService(object : ConnectionListener {
                override fun onConnectionSuccess() { if (streaming) main.post { prepareTrackers() } }
                override fun onConnectionEnded() { if (streaming) main.post { statusText.text = "Health Sensor Service disconnected. Reconnect from the watch." } }
                override fun onConnectionFailed(exception: HealthTrackerException) {
                    if (streaming) main.post { statusText.text = "Health Sensor Service unavailable. Enable its developer mode and try again." }
                    if (streaming && exception.hasResolution()) main.post { runCatching { exception.resolve(this@MainActivity) } }
                }
            }, applicationContext)
            service?.connectService()
        } catch (error: Exception) {
            statusText.text = "Samsung Health Sensor SDK is unavailable. Add its AAR to app/libs and enable developer mode."
        }
    }

    private fun prepareTrackers() {
        if (!streaming) return
        val s = service ?: return
        val supported = runCatching { s.trackingCapability.supportHealthTrackerTypes }.getOrElse {
            statusText.text = "Could not read watch sensor capabilities. Check Health Sensor Service."; return
        }
        if (HealthTrackerType.HEART_RATE_CONTINUOUS !in supported || HealthTrackerType.ACCELEROMETER_CONTINUOUS !in supported) {
            statusText.text = "Heart-rate or accelerometer tracking is unavailable on this watch."; return
        }
        ecgAvailable = HealthTrackerType.ECG_ON_DEMAND in supported
        ecgButton.isEnabled = ecgAvailable
        ecgText.text = if (ecgAvailable) "ECG ready. Hold a finger on the watch's Home key during measurement." else "ECG unavailable on this device or region. Heart-rate streaming can continue."
        heartTracker = s.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS).also { it.setEventListener(heartListener) }
        accelerometerTracker = s.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS).also { it.setEventListener(accelListener) }
        statusText.text = "Sensors ready. Keep the watch on your wrist; invalid readings are not streamed."
    }

    private val heartListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: MutableList<DataPoint>) {
            for (point in points) {
                val hr = point.getValue(ValueKey.HeartRateSet.HEART_RATE) as? Int ?: continue
                val hrStatus = point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) as? Int ?: continue
                if (hrStatus != 1 || hr <= 0) {
                    if (hrStatus == -3) main.post { statusText.text = "Watch off wrist. Put it on snugly to resume streaming." }
                    continue
                }
                val rawIbi = point.getValue(ValueKey.HeartRateSet.IBI_LIST) as? List<*> ?: emptyList<Any>()
                val rawStatus = point.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) as? List<*> ?: emptyList<Any>()
                val ibi = JSONArray(); val statuses = JSONArray()
                for (i in 0 until minOf(rawIbi.size, rawStatus.size)) {
                    val value = rawIbi[i] as? Int ?: continue
                    val status = rawStatus[i] as? Int ?: continue
                    if (status == 0 && value > 0) { ibi.put(value); statuses.put(0) }
                }
                val msg = JSONObject().put("type", "vitals").put("t", System.currentTimeMillis()).put("hr", hr).put("hrStatus", hrStatus)
                    .put("ibi", ibi).put("ibiStatus", statuses)
                    .put("acc", JSONObject().put("x", ax.toDouble()).put("y", ay.toDouble()).put("z", az.toDouble()))
                socket?.send(msg.toString())
                main.post { heartRateText.text = hr.toString(); statusText.text = "Streaming · valid on-wrist reading" }
            }
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) { main.post { statusText.text = "Heart-rate sensor error: $error" } }
    }

    private val accelListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: MutableList<DataPoint>) {
            val p = points.lastOrNull() ?: return
            val x = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) as? Number ?: return
            val y = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) as? Number ?: return
            val z = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) as? Number ?: return
            val scale = 9.81f / (16383.75f / 4f)
            ax = x.toFloat() * scale; ay = y.toFloat() * scale; az = z.toFloat() * scale
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) { main.post { statusText.text = "Accelerometer error: $error" } }
    }

    private fun startEcg() {
        if (!streaming || !ecgAvailable) return
        if (ecgTracker != null) ecgTracker?.unsetEventListener()
        val tracker = runCatching { service?.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND) }.getOrNull()
        if (tracker == null) { ecgText.text = "ECG unavailable on this device or region. Streaming continues."; ecgButton.isEnabled = false; return }
        ecgTracker = tracker; lastLeadOff = false
        socket?.send(JSONObject().put("type", "ecg").put("fs", 500).put("samples", JSONArray()).put("leadOff", false).put("done", false).toString())
        ecgText.text = "Recording for 30 seconds. Touch the Home key lightly."
        ecgButton.isEnabled = false
        tracker.setEventListener(ecgListener)
        ecgTask = Runnable {
            tracker.unsetEventListener()
            socket?.send(JSONObject().put("type", "ecg").put("fs", 500).put("samples", JSONArray()).put("leadOff", lastLeadOff).put("done", true).toString())
            ecgText.text = "ECG recording complete at 500 Hz."
            ecgButton.isEnabled = ecgAvailable && streaming
            ecgTask = null
        }.also { main.postDelayed(it, 30_000L) }
    }

    private val ecgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: MutableList<DataPoint>) {
            if (points.isEmpty()) return
            val lead = points.first().getValue(ValueKey.EcgSet.LEAD_OFF) as? Int ?: 5
            val leadOff = lead != 0
            if (leadOff) {
                if (!lastLeadOff) socket?.send(JSONObject().put("type", "ecg").put("fs", 500).put("samples", JSONArray()).put("leadOff", true).put("done", false).toString())
                lastLeadOff = true
                main.post { ecgText.text = "Touch the Home key to restore ECG electrode contact." }
                return
            }
            lastLeadOff = false
            val samples = JSONArray()
            points.forEach { point -> (point.getValue(ValueKey.EcgSet.ECG_MV) as? Number)?.let { samples.put(it.toDouble()) } }
            if (samples.length() > 0) socket?.send(JSONObject().put("type", "ecg").put("fs", 500).put("samples", samples).put("leadOff", false).put("done", false).toString())
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) { main.post { ecgText.text = "ECG unavailable: $error. Heart-rate streaming continues." } }
    }

    private fun stopStreaming() {
        streaming = false
        reconnectTask?.let(main::removeCallbacks); reconnectTask = null
        ecgTask?.let(main::removeCallbacks); ecgTask = null
        heartTracker?.unsetEventListener(); accelerometerTracker?.unsetEventListener(); ecgTracker?.unsetEventListener()
        heartTracker = null; accelerometerTracker = null; ecgTracker = null
        service?.disconnectService(); service = null
        socket?.close(1000, "Stopped by user"); socket = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamButton.text = "Start streaming"; ecgButton.isEnabled = false
        statusText.text = "Streaming stopped."
        ipField.visibility = android.view.View.VISIBLE; portField.visibility = android.view.View.VISIBLE; codeField.visibility = android.view.View.VISIBLE
        heartRateText.text = "—"
    }

    override fun onDestroy() { if (streaming) stopStreaming(); http.dispatcher.cancelAll(); super.onDestroy() }
}
