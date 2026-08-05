package com.yenaly.han1meviewer.ui.screen.home.homepage

import android.util.Log
import androidx.annotation.StringRes
import com.yenaly.han1meviewer.util.DiagnosticsLog
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.FirebaseDatabase
import com.yenaly.han1meviewer.FIREBASE_REALTIME_DATABASE
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.SAVED_USER_ID
import com.yenaly.han1meviewer.logic.DatabaseRepo
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import com.yenaly.han1meviewer.logic.entity.HKeyframeEntity
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.exception.LoginStateExpiredException
import com.yenaly.han1meviewer.logic.model.Announcement
import com.yenaly.han1meviewer.logic.state.PageState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.logout
import com.yenaly.han1meviewer.ui.viewmodel.AppViewModel
import com.yenaly.yenaly_libs.utils.getSpValue
import com.yenaly.yenaly_libs.utils.putSpValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class HomePageViewModel: ViewModel() {
    data class SessionExpiredMessage(
        val message: String?,
        @param:StringRes val fallbackResId: Int,
    )

    private val _homePageFlow = MutableStateFlow<PageState<HomeData>>(PageState.Loading)
    val homePageFlow = _homePageFlow.asStateFlow()

    private val database = FirebaseDatabase.getInstance(FIREBASE_REALTIME_DATABASE)

    private val _sessionExpiredMessage = MutableSharedFlow<SessionExpiredMessage>()
    val sessionExpiredMessage = _sessionExpiredMessage

    private var homePageJob: Job? = null

    init {
        viewModelScope.launch {
            // 初始化默认已下载分组，防止[FOREIGN KEY constraint failed]
            DatabaseRepo.HanimeDownload.insertDefaultGroup()
        }
    }

    private var retriedAfterEchStart = false

    fun getHomePage(isRefresh: Boolean = false){
        DiagnosticsLog.event("HOME", "getHomePage refresh=$isRefresh; current=${_homePageFlow.value::class.simpleName}")
        homePageJob?.cancel()
        homePageJob = viewModelScope.launch {
            val current = _homePageFlow.value
            if (isRefresh && current is PageState.Success) {
                _homePageFlow.value = current.copy(isRefreshing = true)
            } else if (isRefresh && current is PageState.Error && current.cachedInfo != null) {
                _homePageFlow.value = PageState.Success(info = current.cachedInfo, isRefreshing = true)
            } else if (!isRefresh && current !is PageState.Success){
                _homePageFlow.value = PageState.Loading
            }
            val announcementsDeferred = async(Dispatchers.IO) {
                withTimeoutOrNull(ANNOUNCEMENTS_TIMEOUT_MILLIS.milliseconds) {
                    fetchAnnouncementsFromFirebase()
                }.orEmpty()
            }
            NetworkRepo.getHomePage().collect { networkState ->
                when (networkState){
                    is WebsiteState.Error -> {
                        DiagnosticsLog.event("HOME", "request failed: ${networkState.throwable.javaClass.simpleName}: ${networkState.throwable.message}", networkState.throwable)
                        announcementsDeferred.cancel()
                        if (networkState.throwable is LoginStateExpiredException) {
                            logout()
                            _sessionExpiredMessage.emit(
                                SessionExpiredMessage(
                                    message = networkState.throwable.message,
                                    fallbackResId = R.string.login_state_expired,
                                )
                            )
                        }
                        val previousData = (_homePageFlow.value as? PageState.Success)?.info
                        _homePageFlow.value = PageState.Error(networkState.throwable, cachedInfo = previousData)
                        // ECH 代理刚启动时，首页首请求可能发生在代理就绪之前而直连被墙。
                        // 若代理已就绪（或即将就绪）且尚未重试过，则等待后重试一次走代理。
                        if (!retriedAfterEchStart && EchProxyManager.isRunning) {
                            retriedAfterEchStart = true
                            DiagnosticsLog.event("HOME", "retry after ECH ready")
                            delay(1_000)
                            getHomePage(isRefresh = true)
                            return@collect
                        } else if (!retriedAfterEchStart && EchProxyManager.port > 0) {
                            retriedAfterEchStart = true
                            DiagnosticsLog.event("HOME", "retry waiting for ECH proxy")
                            delay(3_000)
                            getHomePage(isRefresh = true)
                            return@collect
                        }
                    }
                    is WebsiteState.Success -> {
                        DiagnosticsLog.event("HOME", "request parsed successfully")
                        val currentAnnouncements = announcementsDeferred.await()
                        AppViewModel.csrfToken = networkState.info.csrfToken
                        networkState.info.userId.takeIf { it.isNotEmpty() }?.let { userId ->
                            Preferences.preferenceSp.edit { putString(SAVED_USER_ID, userId) }
                        }
                        val homeData = HomeData(page = networkState.info, announcements = currentAnnouncements)
                        _homePageFlow.value = PageState.Success(info = homeData, isRefreshing = false)
                    }
                    is WebsiteState.Loading -> { }
                }
            }
        }
    }
    private suspend fun fetchAnnouncementsFromFirebase(): List<Announcement> =
        suspendCancellableCoroutine { continuation ->
            val lastDismissTime = getSpValue("last_dismiss_time", 0L, "setting_pref")
            val shouldShowAnno = System.currentTimeMillis() - lastDismissTime > 24 * 60 * 60 * 1000L
            if (!shouldShowAnno) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            database.getReference("announcements").get()
                .addOnSuccessListener { snapshot ->
                    val list = mutableListOf<Announcement>()
                    if (snapshot.exists()) {
                        for (announceSnap in snapshot.children) {
                            val announcement = announceSnap.getValue(Announcement::class.java)
                            if (announcement != null && announcement.isActive) {
                                list.add(announcement)
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(list.sortedBy { it.priority })
                        }
                    } else {
                        if (continuation.isActive) {
                            continuation.resume(emptyList())
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e("Announcement", "读取失败: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(emptyList()) // 失败也容错返回空列表
                    }
                }
        }

    private companion object {
        const val ANNOUNCEMENTS_TIMEOUT_MILLIS = 5_000L
    }

    fun dismissAnnouncements(){
        putSpValue("last_dismiss_time", System.currentTimeMillis(), "setting_pref")
        val current = _homePageFlow.value
        if (current is PageState.Success) {
            _homePageFlow.value = current.copy(info = current.info.copy(announcements = emptyList()))
        }
    }

    fun deleteWatchHistory(history: WatchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.delete(history)
            Log.d("delete_watch_hty", "$history DONE!")
        }
    }

    fun deleteAllWatchHistories() {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.deleteAll()
            Log.d("del_all_watch_hty", "DONE!")
        }
    }

    fun loadAllWatchHistories() =
        DatabaseRepo.WatchHistory.loadAll()
            .catch { e -> e.printStackTrace() }
            .flowOn(Dispatchers.IO)
    private val _modifyHKeyframeFlow = MutableSharedFlow<Boolean>()
    fun removeHKeyframe(videoCode: String, hKeyframe: HKeyframeEntity.Keyframe) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.removeKeyframe(videoCode, hKeyframe)
            Log.d("HKeyframe", "removeHKeyframe:$hKeyframe DONE!")
            _modifyHKeyframeFlow.emit(true)
        }
    }
    fun modifyHKeyframe(
        videoCode: String,
        oldKeyframe: HKeyframeEntity.Keyframe, keyframe: HKeyframeEntity.Keyframe,
    ) {
        viewModelScope.launch {
            DatabaseRepo.HKeyframe.modifyKeyframe(videoCode, oldKeyframe, keyframe)
            Log.d("HKeyframe", "modifyHKeyframe:$keyframe DONE!")
            _modifyHKeyframeFlow.emit(true)
        }
    }
    fun deleteHKeyframes(entity: HKeyframeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.delete(entity)
        }
    }

    fun updateHKeyframes(entity: HKeyframeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.update(entity)
        }
    }
}
