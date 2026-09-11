package com.aliothmoon.maameow.domain.models

import org.json.JSONObject

enum class GestureRecordStatus { IDLE, RECORDING, DONE, FAILED }

data class GestureRecordResult(val status: GestureRecordStatus, val gesture: UnlockGesture? = null, val errorCode: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("status", status.name).put("errorCode", errorCode)
        .also { if (gesture != null) it.put("gesture", gesture.toJson()) }
    companion object {
        val IDLE = GestureRecordResult(GestureRecordStatus.IDLE)
        val RECORDING = GestureRecordResult(GestureRecordStatus.RECORDING)
        fun failed(errorCode: Int) = GestureRecordResult(GestureRecordStatus.FAILED, errorCode = errorCode)
        fun done(gesture: UnlockGesture) = GestureRecordResult(GestureRecordStatus.DONE, gesture)
    }
}
