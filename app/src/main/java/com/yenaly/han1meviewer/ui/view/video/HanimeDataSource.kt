package com.yenaly.han1meviewer.ui.view.video

import cn.jzvd.JZDataSource
import com.yenaly.han1meviewer.ResolutionLinkMap

class HanimeDataSource : JZDataSource {

    private val urlsList = mutableListOf<Map.Entry<Any?, Any?>>()

    @Suppress("UNCHECKED_CAST")
    constructor(title: String, resolutionLinkMap: ResolutionLinkMap) : this() {
        this.currentUrlIndex = 0
        urlsList.clear()
        this.urlsMap.also { map ->
            map.clear()
            resolutionLinkMap.mapValuesTo(map) { it.value.link }
            urlsList.addAll(map.entries as Set<Map.Entry<Any?, Any?>>)
        }
        this.title = title
        // 2026-08-06: CDN 防盗链——视频 CDN（如 t33.cdn2020.com）校验 Referer，
        // 浏览器能播是因为带 Referer: https://javchu.com/，系统播放器直连无 header
        // 会被拒（hanime1 的 CDN 不校验所以能播）。这里加 Referer = 当前站点，
        // 让系统播放器/ExoPlayer 直连与浏览器行为一致。
        this.headerMap = hashMapOf(
            "Referer" to com.yenaly.han1meviewer.Preferences.baseUrl.removeSuffix("/"),
            "User-Agent" to com.yenaly.han1meviewer.USER_AGENT,
        )
        this.looping = false
        this.objects = null
    }

    override fun getKeyFromDataSource(index: Int): String? {
        return urlsList.getOrNull(index)?.key?.toString()
    }

    override fun getValueFromLinkedMap(index: Int): Any? {
        return urlsList.getOrNull(index)?.value
    }

    private constructor() : super("")


}