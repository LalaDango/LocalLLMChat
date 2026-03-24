package com.example.localllmchat.ui.screen.preset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localllmchat.data.local.PresetEntity
import com.example.localllmchat.data.repository.PresetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PresetListUiState(
    val presets: List<PresetEntity> = emptyList(),
    val isLoading: Boolean = true
)

data class PresetEditUiState(
    val emoji: String = "",
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)

class PresetViewModel(
    private val presetRepository: PresetRepository
) : ViewModel() {

    private val _listUiState = MutableStateFlow(PresetListUiState())
    val listUiState: StateFlow<PresetListUiState> = _listUiState.asStateFlow()

    private val _editUiState = MutableStateFlow(PresetEditUiState())
    val editUiState: StateFlow<PresetEditUiState> = _editUiState.asStateFlow()

    private var editingPresetId: Long? = null

    init {
        loadPresets()
    }

    private fun loadPresets() {
        viewModelScope.launch {
            presetRepository.getAllPresets().collect { presets ->
                _listUiState.value = _listUiState.value.copy(
                    presets = presets,
                    isLoading = false
                )
            }
        }
    }

    fun loadPresetForEdit(presetId: Long) {
        viewModelScope.launch {
            val preset = presetRepository.getPresetById(presetId)
            if (preset != null) {
                editingPresetId = preset.id
                _editUiState.value = PresetEditUiState(
                    emoji = preset.emoji,
                    name = preset.name,
                    description = preset.description,
                    systemPrompt = preset.systemPrompt,
                    isEditing = true
                )
            }
        }
    }

    fun updateEmoji(emoji: String) {
        _editUiState.value = _editUiState.value.copy(emoji = emoji, saveSuccess = false)
    }

    fun updateName(name: String) {
        _editUiState.value = _editUiState.value.copy(name = name, saveSuccess = false)
    }

    fun updateDescription(description: String) {
        _editUiState.value = _editUiState.value.copy(description = description, saveSuccess = false)
    }

    fun updateSystemPrompt(systemPrompt: String) {
        _editUiState.value = _editUiState.value.copy(systemPrompt = systemPrompt, saveSuccess = false)
    }

    fun savePreset(onSaved: () -> Unit) {
        val state = _editUiState.value
        if (state.name.isBlank() || state.systemPrompt.isBlank()) return

        viewModelScope.launch {
            _editUiState.value = state.copy(isSaving = true)
            val id = editingPresetId
            if (id != null) {
                val existing = presetRepository.getPresetById(id) ?: return@launch
                presetRepository.updatePreset(
                    existing.copy(
                        emoji = state.emoji,
                        name = state.name,
                        description = state.description,
                        systemPrompt = state.systemPrompt
                    )
                )
            } else {
                presetRepository.createPreset(
                    name = state.name,
                    emoji = state.emoji.ifBlank { "📝" },
                    description = state.description,
                    systemPrompt = state.systemPrompt
                )
            }
            _editUiState.value = _editUiState.value.copy(isSaving = false, saveSuccess = true)
            onSaved()
        }
    }

    fun deletePreset(onDeleted: () -> Unit) {
        val id = editingPresetId ?: return
        viewModelScope.launch {
            presetRepository.deletePreset(id)
            onDeleted()
        }
    }

    class Factory(private val presetRepository: PresetRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PresetViewModel(presetRepository) as T
        }
    }
}
