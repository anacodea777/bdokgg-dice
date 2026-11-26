package com.bdokgg.dice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var diceView: DiceView
    private lateinit var rollButton: MaterialButton
    private lateinit var minusButton: MaterialButton
    private lateinit var plusButton: MaterialButton
    private lateinit var countText: TextView
    private lateinit var totalText: TextView
    private lateinit var breakdownText: TextView
    private lateinit var historyRow: LinearLayout
    private lateinit var historyEmpty: TextView

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }

    private var soundPool: SoundPool? = null
    private val soundIds = mutableListOf<Int>()
    private var lastShake = 0L
    private var lastTick = 0L

    /** Newest first; each entry is the faces of one roll. */
    private val history = mutableListOf<IntArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayShowTitleEnabled(false)

        diceView = findViewById(R.id.diceView)
        rollButton = findViewById(R.id.rollButton)
        minusButton = findViewById(R.id.minusButton)
        plusButton = findViewById(R.id.plusButton)
        countText = findViewById(R.id.countText)
        totalText = findViewById(R.id.totalText)
        breakdownText = findViewById(R.id.breakdownText)
        historyRow = findViewById(R.id.historyRow)
        historyEmpty = findViewById(R.id.historyEmpty)

        diceView.count = prefs.getInt(KEY_COUNT, 2)
        diceView.onFaceChange = ::tick
        loadHistory()
        showIdle()
        updateCount()
        renderHistory()

        rollButton.setOnClickListener { roll() }
        diceView.setOnClickListener { roll() }
        minusButton.setOnClickListener { changeCount(-1) }
        plusButton.setOnClickListener { changeCount(+1) }

        initSound()
    }

    override fun onResume() {
        super.onResume()
        updateShakeListener()
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    private fun updateShakeListener() {
        sensorManager?.unregisterListener(this)
        if (!prefs.getBoolean(KEY_SHAKE, true)) return
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDestroy() {
        soundPool?.release()
        soundPool = null
        super.onDestroy()
    }

    // ---- Rolling -----------------------------------------------------------

    private fun roll() {
        if (diceView.isRolling) return
        val result = IntArray(diceView.count) { Random.nextInt(1, 7) }
        setControlsEnabled(false)
        totalText.text = getString(R.string.dash)
        breakdownText.setText(R.string.rolling)
        playRollSound()
        diceView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        diceView.roll(result) { onRolled(result) }
    }

    private fun onRolled(result: IntArray) {
        setControlsEnabled(true)
        showResult(result)
        history.add(0, result)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        saveHistory()
        renderHistory()
        val done = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS
        diceView.performHapticFeedback(done)
    }

    private fun showResult(faces: IntArray) {
        totalText.text = faces.sum().toString()
        breakdownText.text = if (faces.size == 1) getString(R.string.single_die)
        else faces.joinToString(" + ")
    }

    private fun showIdle() {
        totalText.text = getString(R.string.dash)
        breakdownText.setText(R.string.hint)
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 90) return
        lastTick = now
        diceView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        rollButton.isEnabled = enabled
        rollButton.setText(if (enabled) R.string.roll else R.string.rolling_short)
        minusButton.isEnabled = enabled && diceView.count > 1
        plusButton.isEnabled = enabled && diceView.count < DiceView.MAX_DICE
    }

    private fun changeCount(delta: Int) {
        if (diceView.isRolling) return
        diceView.count += delta
        prefs.edit { putInt(KEY_COUNT, diceView.count) }
        updateCount()
        showIdle()
    }

    private fun updateCount() {
        val n = diceView.count
        countText.text = resources.getQuantityString(R.plurals.dice_count, n, n.toString())
        minusButton.isEnabled = n > 1
        plusButton.isEnabled = n < DiceView.MAX_DICE
    }

    // ---- Sound -------------------------------------------------------------

    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build().also { pool ->
            for (res in intArrayOf(R.raw.dice_roll_1, R.raw.dice_roll_2, R.raw.dice_roll_3)) {
                soundIds += pool.load(this, res, 1)
            }
        }
    }

    private fun playRollSound() {
        if (!prefs.getBoolean(KEY_SOUND, true) || soundIds.isEmpty()) return
        val rate = 0.94f + Random.nextFloat() * 0.12f
        soundPool?.play(soundIds.random(), 1f, 1f, 1, 0, rate)
    }

    // ---- Shake to roll -----------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = SystemClock.uptimeMillis()
        if (g > SHAKE_G && now - lastShake > SHAKE_GAP_MS) {
            lastShake = now
            roll()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- History -----------------------------------------------------------

    private fun loadHistory() {
        history.clear()
        prefs.getString(KEY_HISTORY, null)?.split('|')?.forEach { entry ->
            val faces = entry.split(',').mapNotNull { it.toIntOrNull()?.takeIf { f -> f in 1..6 } }
            if (faces.isNotEmpty()) history += faces.toIntArray()
        }
    }

    private fun saveHistory() {
        prefs.edit { putString(KEY_HISTORY, history.joinToString("|") { it.joinToString(",") }) }
    }

    private fun renderHistory() {
        historyRow.removeAllViews()
        historyEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        val pad = (resources.displayMetrics.density * 12).toInt()
        history.forEachIndexed { i, faces ->
            val chip = layoutInflater.inflate(R.layout.item_history, historyRow, false) as LinearLayout
            chip.findViewById<TextView>(R.id.historyTotal).text = faces.sum().toString()
            chip.findViewById<TextView>(R.id.historyFaces).text = faces.joinToString(" · ")
            if (i == 0) chip.background = ContextCompat.getDrawable(this, R.drawable.bg_history_latest)
            (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = pad / 2
            historyRow.addView(chip)
        }
    }

    // ---- Menu --------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val soundOn = prefs.getBoolean(KEY_SOUND, true)
        menu.findItem(R.id.action_sound)?.apply {
            setIcon(if (soundOn) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
            setTitle(if (soundOn) R.string.sound_off else R.string.sound_on)
        }
        menu.findItem(R.id.action_shake)?.isChecked = prefs.getBoolean(KEY_SHAKE, true)
        val current = AppCompatDelegate.getApplicationLocales()[0]?.language
            ?: resources.configuration.locales[0].language
        menu.findItem(if (current == "bn") R.id.lang_bn else R.id.lang_en)?.isChecked = true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sound -> {
            prefs.edit { putBoolean(KEY_SOUND, !prefs.getBoolean(KEY_SOUND, true)) }
            invalidateOptionsMenu(); true
        }
        R.id.action_shake -> {
            val on = !prefs.getBoolean(KEY_SHAKE, true)
            prefs.edit { putBoolean(KEY_SHAKE, on) }
            updateShakeListener()
            invalidateOptionsMenu(); true
        }
        R.id.action_clear -> {
            history.clear(); saveHistory(); renderHistory(); true
        }
        R.id.lang_en -> { setLanguage("en"); true }
        R.id.lang_bn -> { setLanguage("bn"); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    companion object {
        private const val PREFS = "bdokgg_dice"
        private const val KEY_COUNT = "count"
        private const val KEY_SOUND = "sound"
        private const val KEY_SHAKE = "shake"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 30
        private const val SHAKE_G = 2.6f
        private const val SHAKE_GAP_MS = 1500L
    }
}
