package app.pomoshnik.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.pomoshnik.core.WatchTaskSync
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.util.UUID

class WatchActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val store by lazy { getSharedPreferences("watch-task", MODE_PRIVATE) }
    private val data by lazy { Wearable.getDataClient(this) }
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var send: Button
    private var sending = false

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && pendingId() == null) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                input.setText(text)
                input.setSelection(input.length())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val width = resources.displayMetrics.widthPixels
            val side = if (resources.configuration.isScreenRound) width / 7 else width / 16
            setPadding(side, width / 8, side, width / 8)
        }
        content.addView(TextView(this).apply {
            text = "Секретутка"
            textSize = 18f
            gravity = Gravity.CENTER
        })
        mic = Button(this).apply {
            text = "Продиктовать"
            setOnClickListener { dictate() }
        }
        content.addView(mic)
        input = EditText(this).apply {
            hint = "Добавь задачу…"
            textSize = 15f
            filters = arrayOf(InputFilter.LengthFilter(WatchTaskSync.MAX_TEXT_LENGTH))
            maxLines = 3
            setText(store.getString("text", null))
        }
        content.addView(input, LinearLayout.LayoutParams(-1, -2))
        send = Button(this).apply { setOnClickListener { submit() } }
        content.addView(send)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = store.getString("status", "Задачи сохраняются через телефон")
        }
        content.addView(status)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        setContentView(scroll)
        updateControls()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            try {
                data.addListener(this@WatchActivity).await()
                val items = data.getDataItems().await()
                try {
                    for (item in items) receiveResult(item)
                } finally {
                    items.release()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (pendingId() != null) status.text = "Не удалось проверить статус. Повторите отправку."
            }
        }
    }

    override fun onStop() {
        data.removeListener(this)
        super.onStop()
    }

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED) receiveResult(event.dataItem)
        }
    }

    private fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            status.text = "Распознавание недоступно. Введите задачу текстом."
        }
    }

    private fun submit() {
        if (sending) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            input.error = "Введите задачу"
            return
        }
        val existingId = pendingId()
        val id = existingId ?: UUID.randomUUID().toString()
        val enteredAt = store.getString("enteredAt", null) ?: LocalDateTime.now().toString()
        val waiting = "В очереди. Ждём подтверждения телефона."
        if (!store.edit().putString("id", id).putString("text", text)
                .putString("enteredAt", enteredAt).putString("status", waiting).commit()) {
            status.text = "Не удалось сохранить задачу для отправки."
            return
        }
        sending = true
        status.text = "Отправляем задачу…"
        input.clearFocus()
        input.windowInsetsController?.hide(WindowInsets.Type.ime())
        updateControls()
        lifecycleScope.launch {
            try {
                val request = PutDataMapRequest.create(WatchTaskSync.REQUEST + id).apply {
                    dataMap.putString(WatchTaskSync.TEXT, text)
                    dataMap.putString(WatchTaskSync.ENTERED_AT, enteredAt)
                    dataMap.putString("deliveryAttempt", UUID.randomUUID().toString())
                }.asPutDataRequest().setUrgent()
                val uri = data.putDataItem(request).await().uri
                if (pendingId() == id) {
                    store.edit().putString("requestUri", uri.toString()).apply()
                    status.text = waiting
                } else {
                    data.deleteDataItems(uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (pendingId() == id) status.text = "Не удалось отправить. Попробуйте ещё раз."
            } finally {
                sending = false
                updateControls()
            }
        }
    }

    private fun receiveResult(item: DataItem) {
        val id = pendingId() ?: return
        if (item.uri.path != WatchTaskSync.RESULT + id) return
        val result = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
        val message = result.getString(WatchTaskSync.MESSAGE) ?: return
        val success = result.getBoolean(WatchTaskSync.OK)
        val requestUri = store.getString("requestUri", null)?.let(Uri::parse)
        val update = store.edit().remove("id").remove("enteredAt").remove("requestUri").putString("status", message)
        if (success) update.remove("text")
        if (!update.commit()) return
        status.text = message
        if (success) input.text.clear()
        updateControls()
        requestUri?.let { data.deleteDataItems(it) }
        data.deleteDataItems(item.uri)
    }

    private fun pendingId(): String? = store.getString("id", null)

    private fun updateControls() {
        val pending = pendingId() != null
        mic.isEnabled = !pending && !sending
        input.isEnabled = !pending && !sending
        send.isEnabled = !sending
        send.text = if (pending) "Повторить отправку" else "Добавить задачу"
    }
}
