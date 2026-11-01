package com.manish.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private val MODELS = listOf(
            "gemini-flash-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )
        private const val PREFS = "jarvis_prefs"
        private const val KEY_API = "gemini_api_key"
        private val SYSTEM_PROMPT =
            "You are Jarvis, a friendly personal voice assistant for a user named Manish, " +
                "a student from India. Your replies are spoken aloud, so keep them short " +
                "(1 to 3 sentences), natural and conversational. Never use markdown, " +
                "asterisks, bullet points, emojis or special symbols. If the user's message " +
                "is in Devanagari Hindi, reply in Devanagari Hindi. If it is in English, " +
                "reply in English. Call the user sir only occasionally. If you do not know " +
                "something or cannot do it, say so honestly. You cannot open apps, set " +
                "alarms or control the phone yet."
    }

    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var replyView: TextView
    private lateinit var talkButton: Button
    private lateinit var langButton: Button
    private lateinit var keyBox: EditText
    private lateinit var saveKeyButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val micRequestCode = 101
    private val handler = Handler(Looper.getMainLooper())

    private var sessionId = 0
    private var requestId = 0
    private var gotResult = false
    private var retryCount = 0
    private var listening = false
    private var englishMode = false

    private val history = ArrayList<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(48, 120, 48, 48)

        statusView = TextView(this)
        statusView.text = "Jarvis ready, sir."
        statusView.textSize = 22f

        heardView = TextView(this)
        heardView.text = ""
        heardView.textSize = 18f
        heardView.setPadding(0, 32, 0, 16)

        replyView = TextView(this)
        replyView.text = ""
        replyView.textSize = 20f
        replyView.setPadding(0, 16, 0, 32)

        talkButton = Button(this)
        talkButton.text = "Bolo (Tap to talk)"
        talkButton.setOnClickListener { onTalkPressed() }

        langButton = Button(this)
        updateLangButton()
        langButton.setOnClickListener {
            if (!listening) {
                englishMode = !englishMode
                updateLangButton()
                applyTtsLanguage()
            }
        }

        keyBox = EditText(this)
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyBox.setSingleLine(true)
        updateKeyHint()

        saveKeyButton = Button(this)
        saveKeyButton.text = "Key save karo"
        saveKeyButton.setOnClickListener {
            val k = keyBox.text.toString().trim()
            keyBox.setText("")
            if (k.isEmpty()) {
                statusView.text = "Pehle key paste karo, sir."
            } else if (!isKeyValid(k)) {
                statusView.text = "Ye asli key nahi lag rahi, isme space ya nayi line hai. " +
                    "Sirf 'Your API key' card ka Copy key button dabake paste karo."
            } else {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_API, k).apply()
                updateKeyHint()
                statusView.text = "Key save ho gayi, sir. Aakhri 4 akshar: " + k.takeLast(4)
            }
        }

        root.addView(statusView)
        root.addView(heardView)
        root.addView(replyView)
        root.addView(talkButton)
        root.addView(langButton)
        root.addView(keyBox)
        root.addView(saveKeyButton)
        scroll.addView(root)
        setContentView(scroll)

        tts = TextToSpeech(this, this)
    }

    private fun getApiKey(): String {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""
    }

    private fun isKeyValid(k: String): Boolean {
        if (k.length < 20 || k.length > 100) return false
        for (c in k) {
            if (c.isWhitespace()) return false
        }
        return true
    }

    private fun updateKeyHint() {
        val k = getApiKey()
        keyBox.hint = when {
            k.isEmpty() -> "Gemini API key yahan paste karo"
            !isKeyValid(k) -> "Saved key galat hai. Sahi key paste karke save karo"
            else -> "Key save hai (..." + k.takeLast(4) + "). Badalni ho to nayi paste karo"
        }
    }

    private fun updateLangButton() {
        langButton.text = if (englishMode) {
            "Bhasha: English (badalne ke liye dabao)"
        } else {
            "Bhasha: Hindi / Hinglish (badalne ke liye dabao)"
        }
    }

    private fun applyTtsLanguage() {
        val locale = if (englishMode) Locale("en", "IN") else Locale("hi", "IN")
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            tts?.setLanguage(Locale.US)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyTtsLanguage()
        } else {
            statusView.text = "Awaaz (TTS) shuru nahi hui."
        }
    }

    private fun onTalkPressed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micRequestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            statusView.text = "Mic ki permission chahiye, sir."
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Is phone par speech recognition nahi mila."
            return
        }
        requestId++
        retryCount = 0
        gotResult = false
        listening = true
        talkButton.isEnabled = false
        heardView.text = ""
        replyView.text = ""
        statusView.text = "Taiyaar ho raha hoon..."
        tts?.stop()
        releaseRecognizer()
        handler.postDelayed({ createAndStart() }, 300)
    }

    private fun createAndStart() {
        if (!listening) return
        sessionId++
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(makeListener(sessionId))

        val langTag = if (englishMode) "en-IN" else "hi-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        r.startListening(intent)
    }

    private fun releaseRecognizer() {
        sessionId++
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun finishListening() {
        listening = false
        talkButton.isEnabled = true
        handler.post { releaseRecognizer() }
    }

    private fun friendlyError(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya, dobara bolo."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya, dobara bolo."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Internet check karo, sir."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic ki permission chahiye, sir."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Service busy hai, thodi der baad try karo."
            SpeechRecognizer.ERROR_AUDIO -> "Mic mein dikkat aayi, dobara try karo."
            11 -> "Speech service band ho gayi. Dobara try karo."
            else -> "Error code: $code. Dobara try karo."
        }
    }

    private fun makeListener(id: Int): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (id != sessionId) return
                statusView.text = "Sun raha hoon..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (id != sessionId || gotResult) return
                statusView.text = "Samajh raha hoon..."
            }

            override fun onError(error: Int) {
                if (id != sessionId) return
                if (gotResult) return
                if (error == 11 && retryCount < 2) {
                    retryCount++
                    statusView.text = "Connection dobara jod raha hoon..."
                    handler.post { releaseRecognizer() }
                    handler.postDelayed({ createAndStart() }, 600)
                    return
                }
                finishListening()
                statusView.text = friendlyError(error)
            }

            override fun onResults(results: Bundle?) {
                if (id != sessionId) return
                gotResult = true
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                finishListening()
                if (text.isNullOrEmpty()) {
                    statusView.text = "Kuch sunai nahi diya."
                } else {
                    val prefix = if (englishMode) "You said" else "Aapne kaha"
                    heardView.text = "$prefix: $text"
                    askGemini(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (id != sessionId || gotResult) return
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    heardView.text = text
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun askGemini(userText: String) {
        val key = getApiKey()
        if (key.isEmpty()) {
            statusView.text = "Pehle neeche Gemini API key save karo, sir."
            return
        }
        if (!isKeyValid(key)) {
            statusView.text = "Saved key galat hai (isme space ya nayi line hai). " +
                "Sahi key dobara paste karke save karo, sir."
            return
        }
        requestId++
        val myRequest = requestId
        statusView.text = "Soch raha hoon..."
        val snapshot: List<Pair<String, String>> = ArrayList(history)

        Thread {
            var reply: String? = null
            var error: String? = null
            try {
                reply = callGemini(key, snapshot, userText)
            } catch (e: Exception) {
                error = e.message ?: "Pata nahi kya galti hui."
            }
            val finalReply = reply
            val finalError = error
            handler.post {
                if (myRequest != requestId) return@post
                if (finalReply != null) {
                    val clean = finalReply.replace("*", "").replace("#", "").trim()
                    history.add(Pair("user", userText))
                    history.add(Pair("model", clean))
                    while (history.size > 12) {
                        history.removeAt(0)
                        history.removeAt(0)
                    }
                    replyView.text = clean
                    statusView.text = "Jarvis ready, sir."
                    val spoken = if (clean.length > 3500) clean.substring(0, 3500) else clean
                    tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
                } else {
                    statusView.text = finalError ?: "Kuch galat hua."
                }
            }
        }.start()
    }

    private fun makeContent(role: String, text: String): JSONObject {
        return JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
    }

    private fun postToGemini(model: String, key: String, bodyText: String): Pair<Int, String> {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseReply(text: String): String {
        val json = JSONObject(text)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw Exception("Gemini ne koi jawab nahi diya.")
        }
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
        }
        val result = sb.toString().trim()
        if (result.isEmpty()) {
            throw Exception("Gemini ka jawab khaali aaya.")
        }
        return result
    }

    private fun callGemini(
        key: String,
        hist: List<Pair<String, String>>,
        userText: String
    ): String {
        val contents = JSONArray()
        for (h in hist) {
            contents.put(makeContent(h.first, h.second))
        }
        contents.put(makeContent("user", userText))

        val body = JSONObject()
        body.put(
            "system_instruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
            )
        )
        body.put("contents", contents)
        val bodyText = body.toString()

        var lastError = "Koi model nahi mila."
        for (model in MODELS) {
            val resp = postToGemini(model, key, bodyText)
            val code = resp.first
            val text = resp.second
            if (code in 200..299) {
                return parseReply(text)
            }
            var msg = text
            try {
                msg = JSONObject(text).getJSONObject("error").getString("message")
            } catch (e: Exception) {
            }
            lastError = "Gemini error $code ($model): ${msg.take(150)}"
            if (code != 404) {
                throw Exception(lastError)
            }
        }
        throw Exception(lastError)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        requestId++
        listening = false
        talkButton.isEnabled = true
        releaseRecognizer()
        tts?.stop()
        statusView.text = "Jarvis ready, sir."
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
