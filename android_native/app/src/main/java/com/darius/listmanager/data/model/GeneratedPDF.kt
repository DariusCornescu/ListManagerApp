package com.darius.listmanager.data.model

data class GeneratedPDF(
    val id: String,
    val timestamp: Long,
    val distributorGroups: Map<String, List<SessionItem>>
)
