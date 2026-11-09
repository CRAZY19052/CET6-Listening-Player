package com.cet6listening.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var items: List<ListeningItem>
    private lateinit var stats: StatsStore
    private lateinit var root: FrameLayout
    private lateinit var handler: Handler

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItem: ListeningItem? = null
    private var pendingSeekToMs: Long? = null
    private var showingPlayer = false
    private var activeFilterYear: Int? = null

    private var playerItem: ListeningItem? = null
    private var playerSeekBar: SeekBar? = null
    private var playerPositionLabel: TextView? = null
    private var playerDurationLabel: TextView? = null
    private var playerPlayButton: TextView? = null
    private var playerSectionTitle: TextView? = null
    private var playerSectionMeta: TextView? = null
    private var playerSpeeds: MutableList<TextView> = mutableListOf()
    private var playerSectionStats: MutableList<TextView> = mutableListOf()
    private var playerSectionBars: MutableList<ProgressBar> = mutableListOf()
    private var userSeeking = false

    private val uiTick = object : Runnable {
        override fun run() {
            if (showingPlayer) updatePlayerUi()
            handler.postDelayed(this, 500L)
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayerUi()
            updatePlayerButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        items = CatalogRepository.load(this)
        stats = StatsStore(this)
        handler = Handler(Looper.getMainLooper())
        root = FrameLayout(this).apply {
            setBackgroundColor(PAPER)
        }
        setContentView(root)
        applySystemInsets()
        requestNotificationPermission()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingPlayer) {
                    showLibrary()
                } else {
                    finish()
                }
            }
        })
        showLibrary()
        handler.post(uiTick)
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            controller?.removeListener(controllerListener)
            controllerFuture?.let(MediaController::releaseFuture)
            controller = null
            controllerFuture = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiTick)
        super.onDestroy()
    }

    private fun connectController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (future.isCancelled) return@addListener
                val connected = future.get()
                controller = connected
                connected.addListener(controllerListener)
                pendingItem?.let {
                    pendingItem = null
                    val seekToMs = pendingSeekToMs
                    pendingSeekToMs = null
                    startItem(it, seekToMs)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 601)
        }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showLibrary() {
        showingPlayer = false
        playerItem = null
        root.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(44))
        }
        scroll.addView(page, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll)

        page.addView(text("CET6 听力舱", 31f, INK, Typeface.BOLD))
        page.addView(
            text("2020—2026.06 · 27 套真题 · 完全离线", 13f, MUTED).apply {
                setPadding(0, dp(5), 0, dp(22))
            }
        )

        page.addView(statsCard())
        page.addView(sectionHeading("题库", "按年份浏览").apply {
            setPadding(0, dp(26), 0, dp(12))
        })
        page.addView(yearFilterRow())

        val visibleItems = if (activeFilterYear == null) {
            items
        } else {
            items.filter { it.year == activeFilterYear }
        }
        var lastYear = Int.MIN_VALUE
        visibleItems.forEach { item ->
            if (activeFilterYear == null && item.year != lastYear) {
                page.addView(text("${item.year} 年", 18f, INK, Typeface.BOLD).apply {
                    setPadding(0, dp(22), 0, dp(8))
                })
                lastYear = item.year
            }
            page.addView(listeningCard(item).apply {
                setOnClickListener {
                    showPlayer(item)
                    startItem(item)
                }
            })
        }
        if (visibleItems.isEmpty()) {
            page.addView(text("这个年份暂时没有音频。", 15f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(36), 0, dp(36))
            })
        }
    }

    private fun statsCard(): View {
        val card = roundedCard(FOREST)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(18), dp(19), dp(18))
        }
        card.addView(content)
        content.addView(text("学习概览", 13f, ColorUtils.blendARGB(Color.WHITE, FOREST, 0.28f), Typeface.BOLD))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(13), 0, 0)
        }
        val todayMinutes = (stats.todayListeningMs() / 60_000L).coerceAtLeast(0L)
        val totalMinutes = stats.totalListeningMs() / 60_000L
        val completed = stats.totalCompletedSections(items)
        row.addView(statBlock("$todayMinutes", "今日分钟", 1f), weightParams(1f))
        row.addView(statBlock("${totalMinutes / 60}h", "累计时长", 1f), weightParams(1f))
        row.addView(statBlock("$completed", "完成题组", 1f), weightParams(1f))
        content.addView(row)

        val recent = stats.recentItemId()?.let { id -> items.firstOrNull { it.id == id } }
        if (recent != null) {
            val recentRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(FOREST_LIGHT, dp(14))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    showPlayer(recent)
                    startItem(recent)
                }
            }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(text("继续上次学习", 11f, MINT, Typeface.BOLD))
            copy.addView(text(recent.title, 14f, Color.WHITE, Typeface.BOLD).apply {
                setPadding(0, dp(3), 0, 0)
            })
            recentRow.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            recentRow.addView(text("继续  ›", 13f, MINT, Typeface.BOLD))
            content.addView(recentRow, linearParams(matchWidth = true, top = 16))
        }
        return card
    }

    private fun statBlock(value: String, label: String, weight: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(value, 25f, Color.WHITE, Typeface.BOLD))
            addView(text(label, 11f, ColorUtils.blendARGB(Color.WHITE, FOREST, 0.30f)).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun yearFilterRow(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(5))
        }
        val years = listOf<Int?>(null) + items.map { it.year }.distinct().sortedDescending()
        years.forEach { year ->
            val label = year?.toString() ?: "全部"
            val chip = text(label, 13f, if (year == activeFilterYear) Color.WHITE else INK, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(if (year == activeFilterYear) FOREST else CHIP, dp(20))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    activeFilterYear = year
                    showLibrary()
                }
            }
            row.addView(chip, linearParams(right = 8))
        }
        scroll.addView(row)
        return scroll
    }

    private fun listeningCard(item: ListeningItem): View {
        val card = roundedCard(Color.WHITE).apply {
            foreground = selectableItemBackground()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(15))
        }
        card.addView(content)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(text(item.title, 16f, INK, Typeface.BOLD))
        val durationLabel = formatDuration(item.durationMs)
        titleBlock.addView(text("${item.examYm.replace("-", "年")}月 · 第 ${item.setNo} 套 · $durationLabel", 11.5f, MUTED).apply {
            setPadding(0, dp(5), 0, 0)
        })
        top.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val count = stats.itemPlayCount(item)
        top.addView(text(if (count > 0) "完整 $count 遍" else "未开始", 11f, if (count > 0) FOREST else MUTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(if (count > 0) MINT_PALE else CHIP, dp(13))
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        content.addView(top)

        val completed = stats.completedSectionCount(item)
        val totalSections = item.sections.size.coerceAtLeast(1)
        val progress = (completed * 100f / totalSections).roundToInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = progress
            progressTintList = ColorStateList.valueOf(if (progress > 0) FOREST else CHIP)
            progressBackgroundTintList = ColorStateList.valueOf(PAPER_DARK)
        }
        content.addView(bar, linearParams(matchWidth = true, top = 14).apply { height = dp(4) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        bottom.addView(text("题组 $completed / ${item.sections.size}", 11.5f, MUTED), weightParams(1f))
        val pos = stats.lastPositionMs(item.id)
        bottom.addView(
            text(
                if (pos > 0) "上次 ${formatDuration(pos)}  ·  继续 ›" else "开始听力  ›",
                12.5f,
                FOREST,
                Typeface.BOLD
            )
        )
        content.addView(bottom)
        content.addView(View(this), linearParams(matchWidth = true, top = 1))
        card.layoutParams = linearParams(matchWidth = true, top = 10)
        return card
    }

    private fun showPlayer(item: ListeningItem) {
        showingPlayer = true
        playerItem = item
        playerSpeeds.clear()
        playerSectionStats.clear()
        playerSectionBars.clear()
        root.removeAllViews()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scroll.addView(page, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("‹", 34f, INK, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(14), dp(3))
            setOnClickListener { showLibrary() }
        })
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(text(item.title, 18f, INK, Typeface.BOLD))
        titleBlock.addView(text("${item.examYm} · 第 ${item.setNo} 套", 11.5f, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(top)

        val playerCard = roundedCard(INK)
        val playerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(20))
        }
        playerCard.addView(playerContent)
        playerContent.addView(text("NOW LISTENING", 10.5f, MINT, Typeface.BOLD).apply {
            letterSpacing = 0.12f
        })
        playerSectionTitle = text("准备播放", 21f, Color.WHITE, Typeface.BOLD).apply {
            setPadding(0, dp(8), 0, 0)
        }
        playerContent.addView(playerSectionTitle)
        playerSectionMeta = text("选择章节后可单独精听", 12f, ColorUtils.blendARGB(Color.WHITE, INK, 0.32f))
        playerContent.addView(playerSectionMeta)

        playerSeekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(MINT)
            thumbTintList = ColorStateList.valueOf(MINT)
            progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(Color.WHITE, INK, 0.72f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updatePlayerTimesFromSeek(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    val duration = controller?.duration?.takeIf { it > 0 } ?: item.durationMs
                    controller?.seekTo((duration * (seekBar?.progress ?: 0) / 1000L))
                    updatePlayerUi()
                }
            })
        }
        playerContent.addView(playerSeekBar, linearParams(matchWidth = true, top = 18))
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playerPositionLabel = text("00:00", 11f, ColorUtils.blendARGB(Color.WHITE, INK, 0.22f))
        playerDurationLabel = text(formatDuration(item.durationMs), 11f, ColorUtils.blendARGB(Color.WHITE, INK, 0.22f))
        times.addView(playerPositionLabel, weightParams(1f))
        times.addView(playerDurationLabel)
        playerContent.addView(times, linearParams(matchWidth = true, top = 1))

        val mainControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }
        playerPlayButton = controlButton("▶", compact = false).apply {
            setOnClickListener {
                val c = controller ?: return@setOnClickListener
                if (playerItem == null) playerItem = item
                if (c.isPlaying) c.pause() else {
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    c.play()
                }
                updatePlayerButton()
            }
        }
        mainControls.addView(playerPlayButton, LinearLayout.LayoutParams(dp(82), dp(82)))
        playerContent.addView(mainControls)

        playerContent.addView(
            text("精细调节", 10.5f, ColorUtils.blendARGB(Color.WHITE, INK, 0.42f), Typeface.BOLD).apply {
                setPadding(0, dp(10), 0, dp(7))
            }
        )
        val preciseControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            "后退 5 秒" to -5_000L,
            "后退 15 秒" to -15_000L,
            "前进 3 秒" to 3_000L
        ).forEachIndexed { index, (label, deltaMs) ->
            val button = text(label, 11.5f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(ColorUtils.blendARGB(Color.WHITE, INK, 0.80f), dp(14))
                setOnClickListener { skipBy(deltaMs) }
            }
            val params = LinearLayout.LayoutParams(0, dp(42), 1f)
            params.marginStart = dp(if (index == 0) 0 else 4)
            params.marginEnd = dp(if (index == 2) 0 else 4)
            preciseControls.addView(button, params)
        }
        playerContent.addView(preciseControls, linearParams(matchWidth = true))

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
            val button = text(speedLabel(speed), 12f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    controller?.setPlaybackSpeed(speed)
                    stats.setPlaybackSpeed(speed)
                    updateSpeedButtons(speed)
                }
            }
            playerSpeeds.add(button)
            speedRow.addView(button, linearParams(right = 6))
        }
        playerContent.addView(speedRow, linearParams(matchWidth = true, top = 14))
        page.addView(playerCard, linearParams(matchWidth = true, top = 18))

        page.addView(sectionHeading("题组精听", "听到结尾才计 1 遍").apply {
            setPadding(0, dp(26), 0, dp(10))
        })
        item.sections.forEachIndexed { index, section ->
            page.addView(sectionCard(item, index, section))
        }
        updateSpeedButtons(controller?.playbackParameters?.speed ?: stats.playbackSpeed())
        updatePlayerUi()
    }

    private fun sectionCard(item: ListeningItem, index: Int, section: ListeningSection): View {
        val card = roundedCard(Color.WHITE).apply {
            foreground = selectableItemBackground()
            setOnClickListener {
                playerItem = item
                showPlayer(item)
                startItem(item, seekToMs = section.startMs)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(13))
        }
        card.addView(content)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text(section.title, 14.5f, INK, Typeface.BOLD), weightParams(1f))
        val stat = text("", 11f, MUTED, Typeface.BOLD)
        playerSectionStats.add(stat)
        titleRow.addView(stat)
        content.addView(titleRow)
        content.addView(text("${section.questions} · ${formatDuration(section.durationMs)}", 11.5f, MUTED).apply {
            setPadding(0, dp(5), 0, dp(9))
        })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(FOREST)
            progressBackgroundTintList = ColorStateList.valueOf(PAPER_DARK)
        }
        playerSectionBars.add(bar)
        content.addView(bar, linearParams(matchWidth = true).apply { height = dp(4) })
        card.layoutParams = linearParams(matchWidth = true, top = 8)
        return card
    }

    private fun startItem(item: ListeningItem, seekToMs: Long? = null) {
        val c = controller
        if (c == null) {
            pendingItem = item
            pendingSeekToMs = seekToMs
            connectController()
            return
        }
        val alreadySelected = c.currentMediaItem?.mediaId == item.id
        if (!alreadySelected) {
            val resume = resumePositionFor(item)
            val mediaItem = MediaItem.Builder()
                .setMediaId(item.id)
                .setUri("asset:///audio/${item.fileName}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist("CET6 · ${item.examYm}")
                        .build()
                )
                .build()
            c.setMediaItem(mediaItem, resume)
            c.prepare()
            c.setPlaybackSpeed(stats.playbackSpeed())
        } else if (seekToMs == null && c.playbackState == Player.STATE_ENDED) {
            c.seekTo(resumePositionFor(item))
        }
        if (seekToMs != null) {
            c.seekTo(seekToMs.coerceIn(0L, item.durationMs.coerceAtLeast(1L)))
        }
        c.play()
        stats.setRecent(item.id)
        updatePlayerButton()
    }

    private fun resumePositionFor(item: ListeningItem): Long {
        val saved = stats.lastPositionMs(item.id)
        if (saved <= 0L || saved >= item.durationMs - RESUME_END_GUARD_MS) return 0L
        return saved.coerceAtMost(item.durationMs.coerceAtLeast(1L))
    }

    private fun updatePlayerUi() {
        if (!showingPlayer) return
        val item = playerItem ?: return
        val c = controller
        val duration = c?.duration?.takeIf { it > 0L } ?: item.durationMs
        val position = c?.currentPosition?.coerceAtLeast(0L) ?: stats.lastPositionMs(item.id)
        if (!userSeeking) {
            playerSeekBar?.progress = ((position.toDouble() / duration.coerceAtLeast(1L)) * 1000).roundToInt()
        }
        playerPositionLabel?.text = formatDuration(position)
        playerDurationLabel?.text = formatDuration(duration)
        val sectionIndex = item.sectionIndexAt(position).coerceIn(0, item.sections.lastIndex.coerceAtLeast(0))
        val section = item.sections.getOrNull(sectionIndex)
        playerSectionTitle?.text = section?.title ?: "准备播放"
        val count = if (section != null) stats.sectionPlayCount(item.id, sectionIndex) else 0
        playerSectionMeta?.text = section?.let {
            "${it.questions} · 已完整听 $count 遍 · ${sectionProgressPercent(item, sectionIndex)}%"
        } ?: ""

        playerSectionStats.forEachIndexed { index, label ->
            val s = item.sections.getOrNull(index) ?: return@forEachIndexed
            val plays = stats.sectionPlayCount(item.id, index)
            label.text = if (plays > 0) "听过 $plays 遍" else "${sectionProgressPercent(item, index)}%"
            label.setTextColor(if (plays > 0) FOREST else MUTED)
            playerSectionBars.getOrNull(index)?.progress = sectionProgressPercent(item, index) * 10
        }
        updatePlayerButton()
        updateSpeedButtons(c?.playbackParameters?.speed ?: stats.playbackSpeed())
    }

    private fun updatePlayerButton() {
        val isPlaying = controller?.isPlaying == true
        playerPlayButton?.text = if (isPlaying) "Ⅱ" else "▶"
    }

    private fun updateSpeedButtons(activeSpeed: Float) {
        playerSpeeds.forEachIndexed { index, button ->
            val speed = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)[index]
            val selected = kotlin.math.abs(speed - activeSpeed) < 0.01f
            button.background = rounded(if (selected) MINT else ColorUtils.blendARGB(Color.WHITE, INK, 0.80f), dp(16))
            button.setTextColor(if (selected) INK else ColorUtils.blendARGB(Color.WHITE, INK, 0.18f))
        }
    }

    private fun updatePlayerTimesFromSeek(progress: Int) {
        val item = playerItem ?: return
        val duration = controller?.duration?.takeIf { it > 0L } ?: item.durationMs
        playerPositionLabel?.text = formatDuration(duration * progress / 1000L)
    }

    private fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0L } ?: playerItem?.durationMs ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, duration))
        updatePlayerUi()
    }

    private fun sectionProgressPercent(item: ListeningItem, index: Int): Int {
        val section = item.sections.getOrNull(index) ?: return 0
        return (stats.sectionListenedMs(item.id, index) * 100.0 / section.durationMs)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun sectionHeading(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 18f, INK, Typeface.BOLD), weightParams(1f))
            addView(text(subtitle, 11f, MUTED))
        }
    }

    private fun controlButton(label: String, compact: Boolean): TextView {
        return text(label, if (compact) 12f else 23f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = if (compact) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.blendARGB(Color.WHITE, INK, 0.86f))
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MINT)
                }
            }
            if (!compact) setTextColor(INK)
        }
    }

    private fun roundedCard(color: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color, dp(20))
            elevation = dp(1).toFloat()
            clipToOutline = true
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return ContextCompat.getDrawable(this, out.resourceId)
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL): TextView {
        return TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            setTypeface(typeface, style)
            includeFontPadding = false
            letterSpacing = 0f
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return if (h > 0) {
            String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", m, s)
        }
    }

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun linearParams(
        matchWidth: Boolean = false,
        top: Int = 0,
        right: Int = 0
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            marginEnd = dp(right)
        }
    }

    private fun weightParams(weight: Float): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    companion object {
        private const val RESUME_END_GUARD_MS = 4_000L
        private val PAPER = Color.rgb(244, 240, 231)
        private val PAPER_DARK = Color.rgb(229, 224, 213)
        private val INK = Color.rgb(23, 36, 31)
        private val FOREST = Color.rgb(30, 91, 72)
        private val FOREST_LIGHT = Color.rgb(37, 105, 83)
        private val MINT = Color.rgb(153, 222, 192)
        private val MINT_PALE = Color.rgb(223, 242, 232)
        private val MUTED = Color.rgb(105, 116, 109)
        private val CHIP = Color.rgb(234, 229, 218)
    }
}
