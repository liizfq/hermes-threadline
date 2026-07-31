package com.hermes.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Minimal nav-scope ViewModel that exposes the active (bound) room id as a
 * reactive stream so [MainActivity] can clear pending thread navigation when
 * the user switches rooms from the drawer.
 *
 * Kept deliberately tiny — the session list / drawer logic lives in
 * [com.hermes.android.presentation.sessionlist.SessionListViewModel]; this VM
 * only exists to give the NavHost a process-wide signal without leaking the
 * session list's hilt entry-scoped instance into the top-level graph.
 */
@HiltViewModel
class MainNavViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {

    val boundRoomId: StateFlow<String?> = settingsRepository.observeBoundRoom()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.getBoundRoomId())
}
