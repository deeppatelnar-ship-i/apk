package com.offlineai.touchfree

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.util.Locale

class TouchFreeVoiceAssistantService : AccessibilityService(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TouchFreeVoiceAI"
        private const val SAMPLE_RATE = 16000.0f
        private const val WAKE_WORD = "jarvis" // Customize your offline wake word here

        // Optional local GGUF/Task model path on internal storage for complex natural language parsing
        // Recommended for Galaxy A23 5G (Snapdragon 695): Gemma-2-2B-IT-int4 or Qwen2.5-1.5B-Instruct-int4
        private const val LOCAL_LLM_MODEL_PATH = "/data/local/tmp/llm/gemma-2b-it-cpu-int4.bin"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var llmInference: LlmInference? = null
    private var isAwakeForCommand = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Offline Touch-Free AI Accessibility Service connected.")

        // 1. Initialize Offline Text-to-Speech (TTS)
        tts = TextToSpeech(this, this)

        // 2. Initialize Offline Vosk Speech-to-Text Model from app assets
        // Place unpacked model (e.g., vosk-model-small-en-in-0.4) in app/src/main/assets/model-en
        initOfflineSpeechRecognition()

        // 3. Initialize Optional On-Device LLM (if model file is present on device)
        initOptionalLocalLlm()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            speak("Offline voice assistant ready.")
        }
    }

    private fun initOfflineSpeechRecognition() {
        StorageService.unpack(
            this,
            "model-en",
            "model",
            { model: Model ->
                this.voskModel = model
                startListeningLoop()
            },
            { exception: Exception ->
                Log.e(TAG, "Failed to unpack offline Vosk speech model", exception)
            }
        )
    }

    private fun startListeningLoop() {
        try {
            val recognizer = Recognizer(voskModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            Log.i(TAG, "Continuous offline microphone listening started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Vosk SpeechService", e)
        }
    }

    private fun initOptionalLocalLlm() {
        serviceScope.launch {
            // The app cannot read /data/local/tmp on normal devices, so also check the app's own folder:
            // Android/data/com.offlineai.touchfree/files/llm/gemma-2b-it-cpu-int4.bin
            val modelFile = listOfNotNull(
                File(LOCAL_LLM_MODEL_PATH),
                getExternalFilesDir(null)?.let { File(it, "llm/gemma-2b-it-cpu-int4.bin") }
            ).firstOrNull { it.exists() }
            if (modelFile != null) {
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(128)
                        .setTemperature(0.1f)
                        .build()
                    llmInference = LlmInference.createFromOptions(this@TouchFreeVoiceAssistantService, options)
                    Log.i(TAG, "On-device LLM loaded from ${modelFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Local LLM not initialized; falling back to fast rule router.", e)
                }
            }
        }
    }

    // =========================================================================================
    // VOSK OFFLINE SPEECH RECOGNITION CALLBACKS
    // =========================================================================================

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val spokenText = JSONObject(hypothesis).optString("text", "").trim().lowercase(Locale.ROOT)
        if (spokenText.isEmpty()) return

        Log.d(TAG, "Heard offline: $spokenText")

        // Support either single-shot ("jarvis open whatsapp") or two-step ("jarvis" -> "open whatsapp")
        if (spokenText == WAKE_WORD) {
            isAwakeForCommand = true
            speak("Listening")
            return
        }

        if (spokenText.startsWith("$WAKE_WORD ")) {
            val command = spokenText.removePrefix("$WAKE_WORD ").trim()
            isAwakeForCommand = false
            executeVoiceCommand(command)
        } else if (isAwakeForCommand) {
            isAwakeForCommand = false
            executeVoiceCommand(spokenText)
        }
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error", exception)
    }
    override fun onTimeout() {
        startListeningLoop()
    }

    // =========================================================================================
    // COMMAND ROUTER & LOCAL AI INTENT PARSER
    // =========================================================================================

    private fun executeVoiceCommand(rawCommand: String) {
        val cmd = rawCommand.trim()
        Log.i(TAG, "Executing command: $cmd")

        when {
            // --- 1. SYSTEM NAVIGATION & HARDWARE ACTIONS ---
            cmd == "go back" || cmd == "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            cmd == "go home" || cmd == "home screen" -> performGlobalAction(GLOBAL_ACTION_HOME)
            cmd == "recent apps" || cmd == "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            cmd == "open notifications" || cmd == "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            cmd == "quick settings" || cmd == "control panel" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            cmd == "lock screen" || cmd == "lock phone" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            cmd == "take screenshot" || cmd == "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

            // --- 2. TOUCH-FREE SCROLLING & SWIPING ---
            cmd == "scroll down" || cmd == "swipe up" -> scrollScreen(forward = true)
            cmd == "scroll up" || cmd == "swipe down" -> scrollScreen(forward = false)
            cmd == "swipe left" -> performDirectionalSwipe(direction = "LEFT")
            cmd == "swipe right" -> performDirectionalSwipe(direction = "RIGHT")

            // --- 3. ON-SCREEN UI CLICKING & TYPING ---
            cmd.startsWith("click ") || cmd.startsWith("tap ") -> {
                val targetLabel = cmd.removePrefix("click ").removePrefix("tap ").trim()
                if (!clickNodeByTextOrDescription(targetLabel)) {
                    speak("Could not find $targetLabel on screen")
                }
            }
            cmd.startsWith("type ") || cmd.startsWith("write ") -> {
                val textToType = cmd.removePrefix("type ").removePrefix("write ").trim()
                typeIntoFocusedField(textToType)
            }

            // --- 4. LAUNCH ANY INSTALLED MOBILE APP ---
            cmd.startsWith("open ") || cmd.startsWith("launch ") -> {
                val appName = cmd.removePrefix("open ").removePrefix("launch ").trim()
                launchAppByName(appName)
            }

            // --- 5. PHONE CALLS & CONTACTS ---
            cmd.startsWith("call ") -> {
                val contactOrNumber = cmd.removePrefix("call ").trim()
                placePhoneCall(contactOrNumber)
            }

            // --- 6. FLASHLIGHT / TORCH CONTROL ---
            cmd.contains("flashlight on") || cmd.contains("torch on") -> toggleFlashlight(true)
            cmd.contains("flashlight off") || cmd.contains("torch off") -> toggleFlashlight(false)

            // --- 7. VOLUME CONTROL ---
            cmd == "volume up" || cmd == "increase volume" -> adjustVolume(AudioManager.ADJUST_RAISE)
            cmd == "volume down" || cmd == "decrease volume" -> adjustVolume(AudioManager.ADJUST_LOWER)
            cmd == "mute" || cmd == "silence phone" -> adjustVolume(AudioManager.ADJUST_MUTE)

            // --- 8. ALARMS ---
            cmd.startsWith("set alarm for ") -> {
                val timePart = cmd.removePrefix("set alarm for ").trim()
                setSystemAlarm(timePart)
            }

            // --- 9. FALLBACK TO ON-DEVICE LLM FOR NATURAL LANGUAGE COMMANDS ---
            else -> {
                if (llmInference != null) {
                    routeWithLocalLlm(cmd)
                } else {
                    // Try clicking matching text on screen if user just spoke a button's name
                    if (!clickNodeByTextOrDescription(cmd)) {
                        speak("Command not recognized: $cmd")
                    }
                }
            }
        }
    }

    // =========================================================================================
    // ACCESSIBILITY UI AUTOMATION (CLICK, TYPE, SCROLL, SWIPE)
    // =========================================================================================

    private fun clickNodeByTextOrDescription(query: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val matchedNodes = rootNode.findAccessibilityNodeInfosByText(query)

        for (node in matchedNodes) {
            if (performClickOrParentClick(node)) {
                return true
            }
        }
        // Deep traversal fallback for contentDescription or partial matches
        return searchAndClickNodeRecursive(rootNode, query.lowercase(Locale.ROOT))
    }

    private fun performClickOrParentClick(node: AccessibilityNodeInfo?): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        // If no clickable node in hierarchy, tap the node's screen coordinates directly
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                return true
            }
        }
        return false
    }

    private fun searchAndClickNodeRecursive(node: AccessibilityNodeInfo?, query: String): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

        if (text.contains(query) || desc.contains(query)) {
            if (performClickOrParentClick(node)) return true
        }

        for (i in 0 until node.childCount) {
            if (searchAndClickNodeRecursive(node.getChild(i), query)) return true
        }
        return false
    }

    private fun typeIntoFocusedField(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(rootNode)

        if (focusedNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            speak("Typed $text")
        } else {
            speak("No text box selected on screen")
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun scrollScreen(forward: Boolean) {
        val rootNode = rootInActiveWindow
        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            scrollableNode.performAction(action)
        } else {
            // Fallback to gesture swipe
            performDirectionalSwipe(if (forward) "UP" else "DOWN")
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performDirectionalSwipe(direction: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        val path = Path()
        when (direction) {
            "UP" -> {
                path.moveTo(w * 0.5f, h * 0.75f)
                path.lineTo(w * 0.5f, h * 0.25f)
            }
            "DOWN" -> {
                path.moveTo(w * 0.5f, h * 0.25f)
                path.lineTo(w * 0.5f, h * 0.75f)
            }
            "LEFT" -> {
                path.moveTo(w * 0.85f, h * 0.5f)
                path.lineTo(w * 0.15f, h * 0.5f)
            }
            "RIGHT" -> {
                path.moveTo(w * 0.15f, h * 0.5f)
                path.lineTo(w * 0.85f, h * 0.5f)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // =========================================================================================
    // SYSTEM & PLATFORM CONTROL (APPS, CALLS, TORCH, VOLUME, ALARMS)
    // =========================================================================================

    private fun launchAppByName(spokenAppName: String) {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val target = spokenAppName.lowercase(Locale.ROOT)

        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
            if (label == target || label.contains(target)) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    speak("Opening $label")
                    return
                }
            }
        }
        speak("Could not find app $spokenAppName")
    }

    private fun placePhoneCall(contactOrDigits: String) {
        val digitsOnly = contactOrDigits.replace(" ", "")
        val phoneNumber = if (digitsOnly.all { it.isDigit() || it == '+' }) {
            digitsOnly
        } else {
            lookupContactNumber(contactOrDigits)
        }

        if (phoneNumber != null) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
            speak("Calling $contactOrDigits")
        } else {
            speak("Could not find contact $contactOrDigits")
        }
    }

    private fun lookupContactNumber(nameQuery: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) return cursor.getString(numberIndex)
            }
        }
        return null
    }

    private fun toggleFlashlight(turnOn: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, turnOn)
            speak(if (turnOn) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight", e)
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun setSystemAlarm(timeString: String) {
        // Parses simple spoken format like "7 30" or "7"
        val parts = timeString.split(" ").mapNotNull { it.toIntOrNull() }
        val hour = parts.getOrNull(0) ?: return
        val minute = parts.getOrNull(1) ?: 0

        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(alarmIntent)
        speak("Alarm set for $hour $minute")
    }

    // =========================================================================================
    // OPTIONAL ON-DEVICE LLM ROUTING (FOR COMPLEX NATURAL LANGUAGE)
    // =========================================================================================

    private fun routeWithLocalLlm(userSpeech: String) {
        serviceScope.launch {
            try {
                val prompt = """
                    Map the user request to exactly ONE command format:
                    OPEN <app_name>
                    CLICK <button_text>
                    TYPE <text>
                    CALL <contact_name>
                    SCROLL_DOWN
                    SCROLL_UP
                    BACK
                    HOME
                    User request: "$userSpeech"
                    Command:
                """.trimIndent()

                val response = llmInference?.generateResponse(prompt)?.trim() ?: return@launch
                val normalized = response.lines().firstOrNull()?.trim()?.lowercase(Locale.ROOT) ?: return@launch
                executeVoiceCommand(normalized.replace("_", " "))
            } catch (e: Exception) {
                Log.e(TAG, "Local LLM inference failed", e)
            }
        }
    }

    private fun speak(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "OFFLINE_AI_UTTERANCE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Optional: Read incoming notifications or screen titles aloud if desired
    }

    override fun onInterrupt() {
        speechService?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.shutdown()
        tts?.shutdown()
        llmInference?.close()
        serviceScope.cancel()
    }
}