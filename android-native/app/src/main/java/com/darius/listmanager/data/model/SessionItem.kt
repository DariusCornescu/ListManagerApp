package com.darius.listmanager.data.model

data class SessionItem(
    val id: String,
    val name: String,
    val quantity: Int,
    val distributor: String = "Unknown"
)
