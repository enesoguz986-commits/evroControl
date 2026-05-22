package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.GestureMappingEntity
import com.example.data.GestureRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GestureViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: GestureRepository

    init {
        val db = AppDatabase.getDatabase(application)
        repository = GestureRepository(db.gestureDao())
        
        viewModelScope.launch {
            repository.initializeDefaultsIfNeeded()
        }
    }

    val gestureMappings: StateFlow<List<GestureMappingEntity>> = repository.allMappings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateMapping(mapping: GestureMappingEntity) {
        viewModelScope.launch {
            repository.updateMapping(mapping)
        }
    }
}
