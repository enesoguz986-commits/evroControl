package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GestureDao {
    @Query("SELECT * FROM gesture_mappings")
    fun getAllMappings(): Flow<List<GestureMappingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: GestureMappingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDefaultMappings(mappings: List<GestureMappingEntity>)
}
