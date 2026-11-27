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
import app.pomoshnik.core.CommandState
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
    private val history by lazy { WatchCommandHistory(this) }
    private val data by lazy { Wearable.getDataClient(this) }
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var mic: ImageButton
    private lateinit var title: TextView
    private lateinit var type: Button
    private lateinit var cancel: Button
    private lateinit var quickButton: Button
    private lateinit var historyButton: Button
    private lateinit var quickCommands: LinearLayout
    private lateinit var historyPage: LinearLayout
    private lateinit var scroll: ScrollView
    private enum class Page { ASSISTANT, QUICK, HISTORY }
    private var page = Page.ASSISTANT
    private var dictatingTask = false
    private var editing = false
    private lateinit var send: Button
    private var sending = false
    private var command: Command? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && pendingId() == null) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                editing = true
                page = Page.ASSISTANT
                val commandText = if (dictatingTask && CommandParser.parse(text) !is Command.CreateTask) {
                    "Добавь задачу $text"
                } else text
                input.setText(commandText)
                input.setSelection(input.length())
            }
        }
        dictatingTask = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editing = savedInstanceState?.getBoolean("editing") == true || store.contains("text")
        dictatingTask = savedInstanceState?.getBoolean("dictatingTask") == true
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
        quickButton = commandButton("Быстрые команды") { showPage(Page.QUICK) }
        content.addView(quickButton, controlLayout())
        historyButton = commandButton("История") { showPage(Page.HISTORY) }
        content.addView(historyButton, controlLayout())
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
            doAfterTextChanged {
                visibility = if (page == Page.ASSISTANT && !it.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
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
        quickCommands = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(commandButton("Таймер 5 мин") {
                page = Page.ASSISTANT
                editing = true
                input.setText("Таймер на 5 минут")
                showPage(Page.ASSISTANT)
            }, controlLayout())
            addView(commandButton("Записать задачу") { dictate(task = true) }, controlLayout())
            addView(commandButton("Назад") { showPage(Page.ASSISTANT) }, controlLayout())
        }
        content.addView(quickCommands, controlLayout())
        historyPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyPage, controlLayout())
        scroll = ScrollView(this).apply {
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
        outState.putBoolean("dictatingTask", dictatingTask)
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

    private fun dictate(task: Boolean = false) {
        dictatingTask = task
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            dictatingTask = false
            showPage(Page.ASSISTANT)
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
                input.text.toString(),
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, parsed.seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, parsed.label),
            )
            else -> Unit
        }
    }

    private fun openClock(text: String, intent: Intent) {
        try {
            startActivity(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            editing = false
            input.text.clear()
            store.edit().remove("text").remove("status").apply()
            recordTimer(text, "Таймер передан в «Часы».", CommandState.SENT)
        } catch (_: ActivityNotFoundException) {
            recordTimer(text, "Приложение «Часы» не принимает эту команду на этой модели.", CommandState.FAILED)
        } catch (_: SecurityException) {
            recordTimer(text, "Часы не разрешили выполнить команду.", CommandState.FAILED)
        }
    }

    private fun recordTimer(text: String, message: String, state: CommandState) {
        history.put(WatchCommandHistory.Record(UUID.randomUUID().toString(), text, message, state))
        status.text = message
    }

    private fun sendToPhone() {
        val existingId = pendingId()
        val text = input.text.toString().trim()
        val id = existingId ?: UUID.randomUUID().toString()
        val enteredAt = store.getString("enteredAt", null) ?: LocalDateTime.now().toString()
        val waiting = "В очереди. Ждём подтверждения телефона."
        if (!store.edit().putString("id", id).putString("text", text)
                .putString("enteredAt", enteredAt).putString("status", waiting).putString("statusId", id).commit()) {
            status.text = "Не удалось сохранить команду для отправки."
            return
        }
        history.put(WatchCommandHistory.Record(id, text, waiting, CommandState.QUEUED))
        sending = true
        status.text = "Отправляем команду…"
        input.clearFocus()
        input.windowInsetsController?.hide(WindowInsets.Type.ime())
        updateControls()
        lifecycleScope.launch {
            try {
                val request = PutDataMapRequest.create(WatchTaskSync.REQUEST + id).apply {
                    dataMap.putString(WatchTaskSync.TEXT, text)
                    dataMap.putBoolean(WatchTaskSync.TRACK_FINAL, true)
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
                if (pendingId() == id) {
                    val message = "Не удалось отправить. Попробуйте ещё раз."
                    history.put(WatchCommandHistory.Record(id, text, message, CommandState.FAILED))
                    status.text = message
                }
            } finally {
                sending = false
                updateControls()
            }
        }
    }

    private fun receiveResult(item: DataItem) {
        val path = item.uri.path ?: return
        val final = path.startsWith(WatchTaskSync.FINAL)
        if (!final && !path.startsWith(WatchTaskSync.RESULT)) return
        val id = path.removePrefix(if (final) WatchTaskSync.FINAL else WatchTaskSync.RESULT)
        val pending = pendingId() == id
        val prior = history.records().firstOrNull { it.id == id }
        if (!pending && (!final || prior?.state != CommandState.QUEUED)) {
            if (prior != null) data.deleteDataItems(item.uri)
            return
        }
        val result = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
        val message = result.getString(WatchTaskSync.MESSAGE) ?: return
        val success = result.getBoolean(WatchTaskSync.OK)
        val state = result.getString(WatchTaskSync.STATE)?.let { value ->
            CommandState.entries.firstOrNull { it.name == value }
        } ?: if (success) CommandState.DONE else CommandState.FAILED
        val text = prior?.text ?: store.getString("text", null).orEmpty()
        if (!history.put(WatchCommandHistory.Record(id, text, message, state))) return
        if (page == Page.HISTORY) renderHistory()

        if (pending) {
            val requestUri = store.getString("requestUri", null)?.let(Uri::parse)
            val update = store.edit().remove("id").remove("enteredAt").remove("requestUri").putString("status", message)
            if (success) update.remove("text")
            if (!update.commit()) return
            if (success) {
                editing = false
                input.text.clear()
            }
            refreshCommand()
            requestUri?.let { data.deleteDataItems(it) }
        }
        if (pending || store.getString("statusId", null) == id) {
            store.edit().putString("status", message).apply()
            status.text = message
        }
        data.deleteDataItems(item.uri)
    }

    private fun showPage(target: Page) {
        page = target
        if (target == Page.HISTORY) renderHistory()
        updateControls()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun renderHistory() {
        historyPage.removeAllViews()
        historyPage.addView(commandButton("Назад") { showPage(Page.ASSISTANT) }, controlLayout())
        val records = history.records()
        if (records.isEmpty()) historyPage.addView(historyLine("Пока нет команд"), controlLayout())
        for (record in records) {
            historyPage.addView(historyLine("${record.state.title} · ${record.text}\n${record.message}"), controlLayout())
        }
    }

    private fun historyLine(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(INK)
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(5), dp(5), dp(5))
    }

    private fun pendingId(): String? = store.getString("id", null)

    private fun refreshCommand() {
        command = input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { CommandParser.parse(it) }
        updateControls()
    }

    private fun updateControls() {
        val pending = pendingId() != null
        val home = page == Page.ASSISTANT
        val reviewing = editing || pending
        val idle = home && !reviewing
        title.visibility = if (idle) View.VISIBLE else View.GONE
        mic.visibility = if (idle) View.VISIBLE else View.GONE
        type.visibility = if (idle) View.VISIBLE else View.GONE
        quickButton.visibility = if (idle) View.VISIBLE else View.GONE
        historyButton.visibility = if (idle) View.VISIBLE else View.GONE
        quickCommands.visibility = if (page == Page.QUICK) View.VISIBLE else View.GONE
        historyPage.visibility = if (page == Page.HISTORY) View.VISIBLE else View.GONE
        input.visibility = if (home && reviewing) View.VISIBLE else View.GONE
        status.visibility = if (home && status.text.isNotEmpty()) View.VISIBLE else View.GONE
        cancel.visibility = if (home && editing && !pending && !sending) View.VISIBLE else View.GONE
        input.isEnabled = !pending && !sending
        val label = when {
            pending -> "Повторить отправку"
            command is Command.CreateTask -> "Добавить на телефон"
            command is Command.SetAlarm -> "Будильник на телефоне"
            command is Command.SetTimer -> "Запустить таймер"
            else -> null
        }
        send.visibility = if (home && label != null) android.view.View.VISIBLE else android.view.View.GONE
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
