package app.pomoshnik.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.pomoshnik.core.Command
import app.pomoshnik.core.CommandParser
import app.pomoshnik.core.Describe
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
    private lateinit var mic: ImageButton
    private lateinit var title: TextView
    private lateinit var type: Button
    private lateinit var cancel: Button
    private var editing = false
    private lateinit var send: Button
    private var sending = false
    private var command: Command? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && pendingId() == null) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                editing = true
                input.setText(text)
                input.setSelection(input.length())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editing = savedInstanceState?.getBoolean("editing") == true || store.contains("text")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val width = resources.displayMetrics.widthPixels
            val side = if (resources.configuration.isScreenRound) width / 6 else dp(12)
            val vertical = if (resources.configuration.isScreenRound) width / 7 else dp(12)
            setPadding(side, vertical, side, vertical)
            isFocusableInTouchMode = true
        }
        title = TextView(this).apply {
            text = "Чем помочь?"
            textSize = 17f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        content.addView(title)
        mic = ImageButton(this).apply {
            contentDescription = "Продиктовать команду"
            setImageResource(R.drawable.ic_watch_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = capsule(intArrayOf(0xFF3865DA.toInt(), 0xFF7853D8.toInt()))
            setOnClickListener { dictate() }
        }
        content.addView(mic, LinearLayout.LayoutParams(dp(58), dp(58)).apply { topMargin = dp(8) })
        type = commandButton("Ввести текст") {
            editing = true
            updateControls()
            input.requestFocus()
            input.windowInsetsController?.show(WindowInsets.Type.ime())
        }
        content.addView(type, controlLayout())
        input = EditText(this).apply {
            id = R.id.watch_command
            hint = "Команда"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = capsule(intArrayOf(PANEL, PANEL))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            minHeight = dp(48)
            filters = arrayOf(InputFilter.LengthFilter(WatchTaskSync.MAX_TEXT_LENGTH))
            maxLines = 3
            setText(store.getString("text", null))
        }
        content.addView(input, controlLayout())
        send = commandButton("", primary = true) { confirmCommand() }
        content.addView(send, controlLayout())
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = store.getString("status", "")
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            doAfterTextChanged { visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        }
        content.addView(status, controlLayout())
        cancel = commandButton("Назад") {
            editing = false
            store.edit().remove("text").remove("status").apply()
            input.text.clear()
            input.clearFocus()
            input.windowInsetsController?.hide(WindowInsets.Type.ime())
            updateControls()
        }
        content.addView(cancel, controlLayout())
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF222D50.toInt(), 0xFF101522.toInt(), 0xFF101522.toInt()),
            )
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        setContentView(scroll)
        input.doAfterTextChanged {
            if (pendingId() == null) {
                refreshCommand()
                status.text = when (val parsed = command) {
                    null -> ""
                    is Command.CreateTask, is Command.SetAlarm, is Command.SetTimer -> Describe.command(parsed)
                    else -> "Попробуй: «Будильник на 10:30», «Таймер на 5 минут» или «Добавь задачу»."
                }
            }
        }
        refreshCommand()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editing", editing)
        super.onSaveInstanceState(outState)
    }

    private fun controlLayout() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }

    private fun commandButton(label: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            isAllCaps = false
            text = label
            textSize = 13f
            minHeight = dp(48)
            setTextColor(INK)
            background = capsule(
                if (primary) intArrayOf(0xFF335ABF.toInt(), 0xFF5E47B1.toInt())
                else intArrayOf(PANEL, PANEL),
            )
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { action() }
        }

    private fun capsule(colors: IntArray) = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(30).toFloat() },
        null,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

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
            status.text = "Распознавание недоступно. Введи команду текстом."
        }
    }

    private fun confirmCommand() {
        if (sending) return
        if (pendingId() != null) {
            sendToPhone()
            return
        }
        // Recheck after editing and when the confirmation spans a change of date.
        refreshCommand()
        when (val parsed = command) {
            is Command.CreateTask, is Command.SetAlarm -> sendToPhone()
            is Command.SetTimer -> openClock(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, parsed.seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, parsed.label),
            )
            else -> Unit
        }
    }

    private fun openClock(intent: Intent) {
        try {
            startActivity(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            editing = false
            input.text.clear()
            store.edit().remove("text").remove("status").apply()
            status.text = "Команда передана в «Часы»."
        } catch (_: ActivityNotFoundException) {
            status.text = "Приложение «Часы» не принимает эту команду на этой модели."
        } catch (_: SecurityException) {
            status.text = "Часы не разрешили выполнить команду."
        }
    }

    private fun sendToPhone() {
        val existingId = pendingId()
        val text = input.text.toString().trim()
        val id = existingId ?: UUID.randomUUID().toString()
        val enteredAt = store.getString("enteredAt", null) ?: LocalDateTime.now().toString()
        val waiting = "В очереди. Ждём подтверждения телефона."
        if (!store.edit().putString("id", id).putString("text", text)
                .putString("enteredAt", enteredAt).putString("status", waiting).commit()) {
            status.text = "Не удалось сохранить команду для отправки."
            return
        }
        sending = true
        status.text = "Отправляем команду…"
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
        if (success) {
            editing = false
            input.text.clear()
        }
        refreshCommand()
        status.text = message
        requestUri?.let { data.deleteDataItems(it) }
        data.deleteDataItems(item.uri)
    }

    private fun pendingId(): String? = store.getString("id", null)

    private fun refreshCommand() {
        command = input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { CommandParser.parse(it) }
        updateControls()
    }

    private fun updateControls() {
        val pending = pendingId() != null
        val reviewing = editing || pending
        title.visibility = if (reviewing) View.GONE else View.VISIBLE
        mic.visibility = if (reviewing) View.GONE else View.VISIBLE
        type.visibility = if (reviewing) View.GONE else View.VISIBLE
        input.visibility = if (reviewing) View.VISIBLE else View.GONE
        cancel.visibility = if (editing && !pending && !sending) View.VISIBLE else View.GONE
        input.isEnabled = !pending && !sending
        val label = when {
            pending -> "Повторить отправку"
            command is Command.CreateTask -> "Добавить на телефон"
            command is Command.SetAlarm -> "Будильник на телефоне"
            command is Command.SetTimer -> "Запустить таймер"
            else -> null
        }
        send.visibility = if (label != null) android.view.View.VISIBLE else android.view.View.GONE
        send.isEnabled = !sending
        send.text = label.orEmpty()
        send.alpha = if (sending) 0.6f else 1f
    }

    private companion object {
        val INK = Color.rgb(246, 248, 255)
        val MUTED = Color.rgb(189, 202, 228)
        val PANEL = Color.rgb(43, 56, 87)
    }
}
