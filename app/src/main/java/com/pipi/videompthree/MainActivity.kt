package com.pipi.videompthree

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.pipi.videompthree.adapter.Mp3Adapter
import com.pipi.videompthree.model.Mp3Item
import com.pipi.videompthree.util.LocalVideoExtractor
import com.pipi.videompthree.util.Mp3StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PlaybackMode {
    NORMAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvHeaderStatus: TextView

    // 상단 4단 추출 조작부 (♠ 장식, 파일, 파일명 표시창, ⚡ 추출)
    private lateinit var btnRecord: TextView
    private lateinit var btnFile: TextView
    private lateinit var btnLinkPaste: TextView
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnStartExtract: TextView

    // 전용 플레이어 UI
    private lateinit var layoutPlayer: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var sbPlayerProgress: SeekBar
    private lateinit var tvPlayerTime: TextView

    // 플레이어 좌측 모드 3버튼
    private lateinit var btnModeRepeatOne: ImageButton
    private lateinit var btnModeRepeatAll: ImageButton
    private lateinit var btnModeShuffle: ImageButton

    // 플레이어 우측 제어 5버튼
    private lateinit var btnPlayerPrev: ImageButton
    private lateinit var btnPlayerRewind5: ImageButton
    private lateinit var btnPlayerPlayStop: ImageButton
    private lateinit var btnPlayerForward5: ImageButton
    private lateinit var btnPlayerNext: ImageButton

    // 리스트 헤더 및 선택 모드
    private lateinit var layoutNormalHeader: LinearLayout
    private lateinit var btnPlayAll: TextView
    private lateinit var btnEnterSelectMode: TextView

    private lateinit var layoutSelectionHeader: LinearLayout
    private lateinit var cbSelectAll: CheckBox
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnDeleteSelected: TextView
    private lateinit var btnAddToPlaylist: TextView
    private lateinit var btnCancelSelection: TextView

    // 리스트 & 어댑터
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvMp3List: RecyclerView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var mp3Adapter: Mp3Adapter

    // 재생 및 상태 제어
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingItem: Mp3Item? = null
    private var playbackMode: PlaybackMode = PlaybackMode.NORMAL
    private var isUserTrackingSeekBar = false
    private var playerUpdateJob: Job? = null
    private val playlistItemIds = mutableSetOf<Long>()

    private var isExtracting = false
    private var selectedLocalVideoUri: Uri? = null
    private var selectedLocalVideoName: String = ""

    // 비디오 파일 선택 런처 (컬렉션/앨범 '이 기기에서' 우선 호출)
    private val selectVideoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                onVideoSelected(uri)
            }
        }
    }

    // 시스템 기본 파일 탐색기 백업 런처
    private val selectVideoFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onVideoSelected(uri)
        }
    }

    // 파일 읽기/저장 권한 요청 런처
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        loadMp3Files()
    }

    // 안드로이드 10+ 미디어 삭제 권한 요청 런처
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            exitSelectionMode()
            loadMp3Files()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        setupBackPressHandler()
        checkStoragePermissions()
        loadMp3Files()
    }

    override fun onResume() {
        super.onResume()
        loadMp3Files()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    private fun initViews() {
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)

        btnRecord = findViewById(R.id.btnRecord)
        btnFile = findViewById(R.id.btnFile)
        btnLinkPaste = findViewById(R.id.btnLinkPaste)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnStartExtract = findViewById(R.id.btnStartExtract)

        layoutPlayer = findViewById(R.id.layoutPlayer)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        sbPlayerProgress = findViewById(R.id.sbPlayerProgress)
        tvPlayerTime = findViewById(R.id.tvPlayerTime)

        btnModeRepeatOne = findViewById(R.id.btnModeRepeatOne)
        btnModeRepeatAll = findViewById(R.id.btnModeRepeatAll)
        btnModeShuffle = findViewById(R.id.btnModeShuffle)

        btnPlayerPrev = findViewById(R.id.btnPlayerPrev)
        btnPlayerRewind5 = findViewById(R.id.btnPlayerRewind5)
        btnPlayerPlayStop = findViewById(R.id.btnPlayerPlayStop)
        btnPlayerForward5 = findViewById(R.id.btnPlayerForward5)
        btnPlayerNext = findViewById(R.id.btnPlayerNext)

        layoutNormalHeader = findViewById(R.id.layoutNormalHeader)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        btnEnterSelectMode = findViewById(R.id.btnEnterSelectMode)

        layoutSelectionHeader = findViewById(R.id.layoutSelectionHeader)
        cbSelectAll = findViewById(R.id.cbSelectAll)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        btnAddToPlaylist = findViewById(R.id.btnAddToPlaylist)
        btnCancelSelection = findViewById(R.id.btnCancelSelection)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        rvMp3List = findViewById(R.id.rvMp3List)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)

        mp3Adapter = Mp3Adapter(
            context = this,
            onItemClicked = { item ->
                playTrack(item)
            },
            onSelectionChanged = { selectedCount, totalCount ->
                updateSelectionUi(selectedCount, totalCount)
            },
            onStartSelectionMode = { initialItem ->
                enterSelectionMode(initialItem)
            }
        )

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter

        updateModeButtonsUi()
    }

    private fun setupListeners() {
        // [♠] 장식 버튼: 클릭 시 아무런 동작도 하지 않음 (기능 없는 순수 외형 장식)
        btnRecord.setOnClickListener {
            // No-op (장식용)
        }

        // [📁 파일] 버튼 (갤러리 비디오 선택기 호출)
        btnFile.setOnClickListener {
            val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
                type = "video/*"
            }
            try {
                selectVideoPickerLauncher.launch(pickIntent)
            } catch (e: Exception) {
                selectVideoFallbackLauncher.launch("video/*")
            }
        }

        // 🎬 아이콘 클릭 시에도 파일 선택기 호출
        btnLinkPaste.setOnClickListener {
            btnFile.performClick()
        }

        // 파일명 지우기 (✕)
        btnClearUrl.setOnClickListener {
            etYoutubeUrl.setText("")
            selectedLocalVideoUri = null
            selectedLocalVideoName = ""
            updateStatusText()
        }

        // 파일명 텍스트 변경 감지 -> 지우기 버튼 노출 제어
        etYoutubeUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                updateStatusText()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // [⚡ 추출] 버튼 클릭
        btnStartExtract.setOnClickListener {
            startExtractionFlow()
        }

        // [선택] 버튼 클릭 -> 다중 선택 모드 진입
        btnEnterSelectMode.setOnClickListener {
            enterSelectionMode()
        }

        // [취소] 버튼 클릭 -> 다중 선택 모드 해제
        btnCancelSelection.setOnClickListener {
            exitSelectionMode()
        }

        // [전체] 체크박스 클릭
        cbSelectAll.setOnClickListener {
            mp3Adapter.selectAll(cbSelectAll.isChecked)
        }

        // [삭제] 버튼 클릭
        btnDeleteSelected.setOnClickListener {
            requestDeleteSelectedMp3s()
        }

        // [재생목록 추가] 버튼 클릭
        btnAddToPlaylist.setOnClickListener {
            val selected = mp3Adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                playlistItemIds.addAll(selected.map { it.id })
                mp3Adapter.setPlaylistIds(playlistItemIds)
                exitSelectionMode()
            }
        }

        // [전체 재생] 버튼 클릭
        btnPlayAll.setOnClickListener {
            val all = mp3Adapter.getAllItems()
            if (all.isNotEmpty()) {
                playTrack(all[0])
            }
        }

        // 당겨서 새로고침
        swipeRefreshLayout.setOnRefreshListener {
            loadMp3Files()
        }

        // 1곡 반복 버튼 (🔂)
        btnModeRepeatOne.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.REPEAT_ONE) PlaybackMode.NORMAL else PlaybackMode.REPEAT_ONE
            updateModeButtonsUi()
        }

        // 전체 반복 버튼 (🔁)
        btnModeRepeatAll.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.REPEAT_ALL) PlaybackMode.NORMAL else PlaybackMode.REPEAT_ALL
            updateModeButtonsUi()
        }

        // 랜덤 재생 버튼 (🔀)
        btnModeShuffle.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.SHUFFLE) PlaybackMode.NORMAL else PlaybackMode.SHUFFLE
            updateModeButtonsUi()
        }

        // 이전곡 (⏮)
        btnPlayerPrev.setOnClickListener {
            playPreviousTrack()
        }

        // 5초 뒤로 (⏪5s)
        btnPlayerRewind5.setOnClickListener {
            mediaPlayer?.let { player ->
                val newPos = max(0, player.currentPosition - 5000)
                player.seekTo(newPos)
                sbPlayerProgress.progress = newPos
                updatePlayerTimeDisplay(newPos, player.duration)
            }
        }

        // 재생 / 정지 토글 (▶ / ⏹)
        btnPlayerPlayStop.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                stopPlayback()
            } else {
                resumeOrPlayCurrentTrack()
            }
        }

        // 5초 앞으로 (5s⏩)
        btnPlayerForward5.setOnClickListener {
            mediaPlayer?.let { player ->
                val newPos = min(player.duration, player.currentPosition + 5000)
                player.seekTo(newPos)
                sbPlayerProgress.progress = newPos
                updatePlayerTimeDisplay(newPos, player.duration)
            }
        }

        // 다음곡 (⏭)
        btnPlayerNext.setOnClickListener {
            playNextTrack()
        }

        // 탐색바 드래그 리스너
        sbPlayerProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { player ->
                        updatePlayerTimeDisplay(progress, player.duration)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = false
                seekBar?.let {
                    mediaPlayer?.seekTo(it.progress)
                }
            }
        })
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mp3Adapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // 동영상 파일 선택 시 처리: 파일명 규칙 '영상_yyMMdd_HHmm' (초 제외) 적용
    private fun onVideoSelected(uri: Uri) {
        selectedLocalVideoUri = uri
        val timeStamp = SimpleDateFormat("yyMMdd_HHmm", Locale.KOREA).format(Date())
        val defaultTargetName = "영상_$timeStamp"
        selectedLocalVideoName = "$defaultTargetName.mp4"
        etYoutubeUrl.setText(defaultTargetName)
        etYoutubeUrl.setSelection(etYoutubeUrl.text.length)
        updateStatusText()
    }

    // 전용 플레이어 재생 로직
    private fun playTrack(item: Mp3Item) {
        stopPlayback()
        currentPlayingItem = item
        mp3Adapter.setPlayingItemId(item.id)

        tvPlayerTitle.text = item.title
        tvPlayerTitle.isSelected = true

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, item.uri)
                prepare()
                start()
            }

            btnPlayerPlayStop.setImageResource(R.drawable.ic_stop)
            btnPlayerPlayStop.setColorFilter(Color.parseColor("#4FC3F7"))
            sbPlayerProgress.max = mediaPlayer?.duration ?: 0
            sbPlayerProgress.progress = 0
            updatePlayerTimeDisplay(0, mediaPlayer?.duration ?: 0)

            mediaPlayer?.setOnCompletionListener {
                onTrackCompleted()
            }

            startProgressUpdater()

        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback()
        }
    }

    private fun resumeOrPlayCurrentTrack() {
        if (mediaPlayer?.isPlaying == true) {
            return
        } else if (mediaPlayer != null) {
            mediaPlayer?.start()
            btnPlayerPlayStop.setImageResource(R.drawable.ic_stop)
            btnPlayerPlayStop.setColorFilter(Color.parseColor("#4FC3F7"))
        } else if (currentPlayingItem != null) {
            playTrack(currentPlayingItem!!)
        } else {
            val activeList = getActivePlaylist()
            if (activeList.isNotEmpty()) {
                playTrack(activeList[0])
            }
        }
    }

    private fun playPreviousTrack() {
        val activeList = getActivePlaylist()
        if (activeList.isEmpty()) return
        val currentIndex = activeList.indexOfFirst { it.id == currentPlayingItem?.id }
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else activeList.size - 1
        playTrack(activeList[prevIndex])
    }

    private fun playNextTrack() {
        val activeList = getActivePlaylist()
        if (activeList.isEmpty()) return
        if (playbackMode == PlaybackMode.SHUFFLE && activeList.size > 1) {
            val nextItem = activeList.filter { it.id != currentPlayingItem?.id }.random()
            playTrack(nextItem)
        } else {
            val currentIndex = activeList.indexOfFirst { it.id == currentPlayingItem?.id }
            val nextIndex = if (currentIndex != -1) (currentIndex + 1) % activeList.size else 0
            playTrack(activeList[nextIndex])
        }
    }

    private fun onTrackCompleted() {
        when (playbackMode) {
            PlaybackMode.REPEAT_ONE -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
            }
            PlaybackMode.REPEAT_ALL -> {
                val activeList = getActivePlaylist()
                if (activeList.isNotEmpty()) {
                    val currentIndex = activeList.indexOfFirst { it.id == currentPlayingItem?.id }
                    val nextIndex = if (currentIndex != -1) (currentIndex + 1) % activeList.size else 0
                    playTrack(activeList[nextIndex])
                } else {
                    stopPlayback()
                }
            }
            PlaybackMode.SHUFFLE -> {
                val activeList = getActivePlaylist()
                if (activeList.isNotEmpty()) {
                    val nextItem = if (activeList.size > 1) {
                        activeList.filter { it.id != currentPlayingItem?.id }.random()
                    } else {
                        activeList.first()
                    }
                    playTrack(nextItem)
                } else {
                    stopPlayback()
                }
            }
            PlaybackMode.NORMAL -> {
                val activeList = getActivePlaylist()
                val currentIndex = activeList.indexOfFirst { it.id == currentPlayingItem?.id }
                if (currentIndex != -1 && currentIndex + 1 < activeList.size) {
                    playTrack(activeList[currentIndex + 1])
                } else {
                    stopPlayback()
                }
            }
        }
    }

    private fun getActivePlaylist(): List<Mp3Item> {
        val all = mp3Adapter.getAllItems()
        return if (playlistItemIds.isNotEmpty()) {
            all.filter { playlistItemIds.contains(it.id) }
        } else {
            all
        }
    }

    private fun startProgressUpdater() {
        playerUpdateJob?.cancel()
        playerUpdateJob = lifecycleScope.launch {
            while (true) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying && !isUserTrackingSeekBar) {
                        val current = player.currentPosition
                        val total = player.duration
                        sbPlayerProgress.max = total
                        sbPlayerProgress.progress = current
                        updatePlayerTimeDisplay(current, total)
                    }
                }
                delay(300)
            }
        }
    }

    private fun updatePlayerTimeDisplay(currentMs: Int, totalMs: Int) {
        val currentStr = formatMs(currentMs.toLong())
        val totalStr = formatMs(totalMs.toLong())
        tvPlayerTime.text = "$currentStr / $totalStr"
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    private fun stopPlayback() {
        playerUpdateJob?.cancel()
        playerUpdateJob = null
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        btnPlayerPlayStop.setImageResource(R.drawable.ic_play_arrow)
        btnPlayerPlayStop.setColorFilter(Color.WHITE)
        sbPlayerProgress.progress = 0
        mp3Adapter.setPlayingItemId(null)
    }

    private fun updateModeButtonsUi() {
        val activeColor = Color.parseColor("#4FC3F7")
        val inactiveColor = ContextCompat.getColor(this, R.color.on_surface_secondary)

        btnModeRepeatOne.setColorFilter(if (playbackMode == PlaybackMode.REPEAT_ONE) activeColor else inactiveColor)
        btnModeRepeatAll.setColorFilter(if (playbackMode == PlaybackMode.REPEAT_ALL) activeColor else inactiveColor)
        btnModeShuffle.setColorFilter(if (playbackMode == PlaybackMode.SHUFFLE) activeColor else inactiveColor)
    }

    private fun enterSelectionMode(initialItem: Mp3Item? = null) {
        layoutNormalHeader.visibility = View.GONE
        layoutSelectionHeader.visibility = View.VISIBLE
        mp3Adapter.enterSelectionMode(initialItem)
    }

    private fun exitSelectionMode() {
        layoutSelectionHeader.visibility = View.GONE
        layoutNormalHeader.visibility = View.VISIBLE
        cbSelectAll.isChecked = false
        mp3Adapter.exitSelectionMode()
    }

    private fun updateSelectionUi(selectedCount: Int, totalCount: Int) {
        tvSelectedCount.text = "${selectedCount}개 선택됨"
        cbSelectAll.isChecked = (selectedCount > 0 && selectedCount == totalCount)
        btnDeleteSelected.isEnabled = (selectedCount > 0)
        btnDeleteSelected.alpha = if (selectedCount > 0) 1.0f else 0.4f
        btnAddToPlaylist.isEnabled = (selectedCount > 0)
        btnAddToPlaylist.alpha = if (selectedCount > 0) 1.0f else 0.4f
    }

    private fun startExtractionFlow() {
        if (isExtracting) return

        if (selectedLocalVideoUri != null) {
            val userTitle = etYoutubeUrl.text.toString().trim()
            val finalName = if (userTitle.isNotBlank()) {
                if (userTitle.endsWith(".mp4", ignoreCase = true) || userTitle.endsWith(".m4a", ignoreCase = true)) {
                    userTitle
                } else {
                    "$userTitle.m4a"
                }
            } else {
                val timeStamp = SimpleDateFormat("yyMMdd_HHmm", Locale.KOREA).format(Date())
                "영상_$timeStamp.m4a"
            }
            startLocalVideoExtraction(finalName)
            return
        }

        // 파일이 아직 선택되지 않았으면 파일 선택기 자동 호출
        btnFile.performClick()
    }

    private fun startLocalVideoExtraction(fileNameToUse: String) {
        val uri = selectedLocalVideoUri ?: return

        lifecycleScope.launch {
            setExtractingState(true)

            val result = LocalVideoExtractor.extractAudioFromLocalVideo(
                context = this@MainActivity,
                videoUri = uri,
                fileName = fileNameToUse,
                onProgress = { percent, statusText ->
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[$statusText]   [${count}곡]"
                    tvHeaderStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary))
                }
            )

            setExtractingState(false)

            if (result.isSuccess) {
                selectedLocalVideoUri = null
                selectedLocalVideoName = ""
                etYoutubeUrl.setText("")
                loadMp3Files(scrollToTop = true)
            }
            updateStatusText()
        }
    }

    private fun setExtractingState(extracting: Boolean) {
        isExtracting = extracting
        btnFile.isEnabled = !extracting
        btnFile.alpha = if (extracting) 0.5f else 1.0f
        btnStartExtract.isEnabled = !extracting
        btnStartExtract.alpha = if (extracting) 0.5f else 1.0f
        if (!extracting) {
            updateStatusText()
        }
    }

    private fun loadMp3Files(scrollToTop: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = Mp3StorageHelper.getAllExtractedMp3Files(this@MainActivity)
            withContext(Dispatchers.Main) {
                mp3Adapter.submitList(list)
                updateStatusText()
                swipeRefreshLayout.isRefreshing = false
                tvEmptyMessage.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                btnEnterSelectMode.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                if (scrollToTop && list.isNotEmpty()) {
                    rvMp3List.post {
                        rvMp3List.scrollToPosition(0)
                    }
                }
                if (list.isEmpty() && mp3Adapter.isSelectionMode) {
                    exitSelectionMode()
                }
            }
        }
    }

    private fun updateStatusText() {
        val count = mp3Adapter.itemCount
        tvHeaderStatus.setTextColor(ContextCompat.getColor(this, R.color.secondary))

        if (isExtracting) {
            return
        }

        if (selectedLocalVideoUri != null) {
            tvHeaderStatus.text = "[동영상 선택됨]   [${count}곡]"
        } else {
            tvHeaderStatus.text = "[완료]   [${count}곡]"
        }
    }

    private fun checkStoragePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestStoragePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestDeleteSelectedMp3s() {
        val selectedItems = mp3Adapter.getSelectedItems()
        if (selectedItems.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val uris = selectedItems.map { it.uri }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                    withContext(Dispatchers.Main) {
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                    return@launch
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var securityException: SecurityException? = null
            for (item in selectedItems) {
                try {
                    contentResolver.delete(item.uri, null, null)
                } catch (se: SecurityException) {
                    securityException = se
                }
            }

            if (securityException != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = securityException as? RecoverableSecurityException
                if (recoverable != null) {
                    withContext(Dispatchers.Main) {
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                    }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                exitSelectionMode()
                loadMp3Files()
            }
        }
    }
}
