package com.aliothmoon.maameow.data.preferences

import com.aliothmoon.maameow.data.model.TaskProfile
import com.aliothmoon.maameow.data.notification.NotificationSettings
import com.aliothmoon.maameow.domain.models.AppSettings
import com.aliothmoon.maameow.schedule.model.ScheduleStrategy
import kotlinx.serialization.Serializable

@Serializable
data class ConfigBackup(
    val version: Int = 1,
    val exportedAt: String = "",
    val appSettings: AppSettings,
    val notificationSettings: NotificationSettings,
    val taskProfiles: List<TaskProfile>,
    val activeProfileId: String,
    val scheduleStrategies: List<ScheduleStrategy>
)
