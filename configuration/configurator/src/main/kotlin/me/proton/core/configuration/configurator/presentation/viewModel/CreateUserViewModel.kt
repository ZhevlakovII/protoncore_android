/*
 * Copyright (c) 2024 Proton Technologies AG
 * This file is part of Proton AG and ProtonCore.
 *
 * ProtonCore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonCore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonCore.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.proton.core.configuration.configurator.presentation.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.configuration.configurator.domain.ConfigurationUseCase
import me.proton.core.test.quark.data.Plan
import me.proton.core.test.quark.data.User
import me.proton.core.test.quark.response.CreateUserQuarkResponse
import me.proton.core.test.quark.v2.QuarkCommand
import me.proton.core.test.quark.v2.command.enableEarlyAccess
import me.proton.core.test.quark.v2.command.seedNewSubscriber
import me.proton.core.test.quark.v2.command.userCreate
import javax.inject.Inject

@HiltViewModel
class CreateUserViewModel @Inject constructor(
    private val quarkCommand: QuarkCommand,
    private val configurationUseCase: ConfigurationUseCase
) : ViewModel() {

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _userResponse = MutableStateFlow<CreateUserQuarkResponse?>(null)
    val userResponse: StateFlow<CreateUserQuarkResponse?> = _userResponse.asStateFlow()

    val selectedDomain: StateFlow<String> = configurationUseCase.configState.map { set ->
        val hostField = set.first { field -> field.name == "host" }
        hostField.value as String
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), initialValue = "")

    fun createUser(username: String, password: String, plan: Plan, isEnableEarlyAccess: Boolean) =

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val user = User(name = username, password = password, plan = plan)
                quarkCommand.baseUrl("https://${selectedDomain.value}/api/internal")

                val response = quarkCommand.userCreate(user)
                quarkCommand.seedNewSubscriber(user)

                if (isEnableEarlyAccess)
                    quarkCommand.enableEarlyAccess(user.name)
                _userResponse.value = response
                _errorState.value = null
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage
                _userResponse.value = null
            }
        }
}
