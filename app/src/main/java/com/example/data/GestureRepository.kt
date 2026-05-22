package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GestureRepository(private val gestureDao: GestureDao) {
    val allMappings: Flow<List<GestureMappingEntity>> = gestureDao.getAllMappings()

    suspend fun updateMapping(mapping: GestureMappingEntity) {
        gestureDao.insertMapping(mapping)
    }

    suspend fun initializeDefaultsIfNeeded() {
        val current = allMappings.first()
        if (current.isEmpty()) {
            val defaults = listOf(
                GestureMappingEntity(
                    gestureId = "SWIPE_LEFT",
                    gestureNameTr = "Sola El Hareketi",
                    gestureDescription = "Elinizi sağdan sola doğru hızlıca hareket ettirin",
                    mappedAction = "BACK"
                ),
                GestureMappingEntity(
                    gestureId = "SWIPE_RIGHT",
                    gestureNameTr = "Sağa El Hareketi",
                    gestureDescription = "Elinizi soldan sağa doğru hızlıca hareket ettirin",
                    mappedAction = "RECENTS"
                ),
                GestureMappingEntity(
                    gestureId = "SWIPE_UP",
                    gestureNameTr = "Yukarı El Hareketi",
                    gestureDescription = "Elinizi aşağıdan yukarıya doğru hızlıca hareket ettirin",
                    mappedAction = "HOME"
                ),
                GestureMappingEntity(
                    gestureId = "SWIPE_DOWN",
                    gestureNameTr = "Aşağı El Hareketi",
                    gestureDescription = "Elinizi yukarıdan aşağıya doğru hızlıca hareket ettirin",
                    mappedAction = "NOTIFICATIONS"
                ),
                GestureMappingEntity(
                    gestureId = "FIST",
                    gestureNameTr = "Yumruk İşareti",
                    gestureDescription = "Elinizi yumruk yaparak tüm parmaklarınızı kapatın",
                    mappedAction = "VOLUME_DOWN"
                ),
                GestureMappingEntity(
                    gestureId = "PEACE",
                    gestureNameTr = "Zafer / İki Parmak",
                    gestureDescription = "İşaret ve orta parmağınızı açarak zafer işareti (V) yapın",
                    mappedAction = "VOLUME_UP"
                )
            )
            gestureDao.insertDefaultMappings(defaults)
        }
    }
}
