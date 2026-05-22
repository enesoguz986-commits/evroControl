package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gesture_mappings")
data class GestureMappingEntity(
    @PrimaryKey val gestureId: String, // e.g. "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN", "FIST", "PEACE"
    val gestureNameTr: String, // Turkish name
    val gestureDescription: String, // Description in Turkish
    val mappedAction: String, // Hooked action. e.g. "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "SCROLL_UP", "SCROLL_DOWN", "VOLUME_UP", "VOLUME_DOWN", "NONE"
    val isEnabled: Boolean = true
)
