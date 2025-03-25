package com.mmhw.csvtv

data class Video(
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val groupName: String = "Default"
)