package com.mmhw.csvtv

data class Video(
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val groupName: String = "Default",
    var isChecking: Boolean = false,
    var isValid: Boolean? = null,
    var pingMs: Long? = null,
    var resolution: String? = null,
    var videoFormat: String? = null,
    var isAudioOnly: Boolean? = null,
    var audioChannels: String? = null
)