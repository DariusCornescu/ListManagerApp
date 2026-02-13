package com.darius.listmanager.util

import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.Flow

fun <T> LiveData<T>.asFlow(): Flow<T> = this.asFlow()