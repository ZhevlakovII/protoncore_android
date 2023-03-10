/*
 * Copyright (c) 2020 Proton Technologies AG
 * This file is part of Proton Technologies AG and ProtonCore.
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

package me.proton.core.auth.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.auth.domain.AccountWorkflowHandler
import me.proton.core.auth.domain.usecase.AccountAvailability
import me.proton.core.auth.domain.usecase.PostLoginAccountSetup
import me.proton.core.auth.domain.usecase.primaryKeyExists
import me.proton.core.auth.presentation.LogTag
import me.proton.core.crypto.common.keystore.EncryptedString
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.presentation.viewmodel.ProtonViewModel
import me.proton.core.user.domain.entity.Domain
import me.proton.core.usersettings.domain.usecase.SetupUsername
import me.proton.core.util.kotlin.CoreLogger
import me.proton.core.util.kotlin.retryOnceWhen
import javax.inject.Inject

@HiltViewModel
class ChooseAddressViewModel @Inject constructor(
    private val accountWorkflow: AccountWorkflowHandler,
    private val accountAvailability: AccountAvailability,
    private val postLoginAccountSetup: PostLoginAccountSetup,
    private val setupUsername: SetupUsername
) : ProtonViewModel() {

    private val _chooseAddressState = MutableStateFlow<ChooseAddressState>(ChooseAddressState.Idle)

    val chooseAddressState = _chooseAddressState.asStateFlow()

    sealed class ChooseAddressState {
        object Idle : ChooseAddressState()
        object Processing : ChooseAddressState()
        sealed class Data : ChooseAddressState() {
            data class Domains(val domains: List<Domain>) : Data()
            data class UsernameAvailable(val username: String, val domain: String) : Data()
        }
        data class AccountSetupResult(val result: PostLoginAccountSetup.Result) : ChooseAddressState()
        sealed class Error : ChooseAddressState() {
            object DomainsNotAvailable : Error()
            object UsernameNotAvailable : Error()
            data class Message(val error: Throwable) : Error()
        }
    }

    fun setUserId(userId: UserId) = flow {
        emit(ChooseAddressState.Processing)
        val domains = accountAvailability.getDomains()
        if (domains.isEmpty()) {
            emit(ChooseAddressState.Error.DomainsNotAvailable)
            return@flow
        }
        emit(ChooseAddressState.Data.Domains(domains))
        val user = accountAvailability.getUser(userId)
        val username = user.email?.split("@")?.firstOrNull()
        if (username == null) {
            emit(ChooseAddressState.Error.UsernameNotAvailable)
            return@flow
        }
        val domain = domains[0]
        checkUsername(username, domain)
    }.catch { error ->
        emit(ChooseAddressState.Error.Message(error))
    }.onEach {
        _chooseAddressState.tryEmit(it)
    }.launchIn(viewModelScope)

    fun stopChooseAddressWorkflow(
        userId: UserId
    ): Job = viewModelScope.launch {
        accountWorkflow.handleCreateAddressFailed(userId)
    }

    fun checkUsername(username: String, domain: Domain) = flow {
        emit(ChooseAddressState.Processing)
        accountAvailability.checkUsername("$username@$domain")
        emit(ChooseAddressState.Data.UsernameAvailable(username, domain))
    }.catch {
        emit(ChooseAddressState.Error.UsernameNotAvailable)
    }.onEach {
        _chooseAddressState.tryEmit(it)
    }.launchIn(viewModelScope)

    fun setUsernameAndUpgradeToProtonAccount(
        userId: UserId,
        password: EncryptedString,
        username: String,
        domain: String
    ) = flow {
        emit(ChooseAddressState.Processing)

        setupUsername.invoke(userId, username)

        val result = postLoginAccountSetup(
            userId = userId,
            encryptedPassword = password,
            requiredAccountType = AccountType.Internal,
            isSecondFactorNeeded = false,
            isTwoPassModeNeeded = false,
            temporaryPassword = false,
            onSetupSuccess = { accountWorkflow.handleCreateAddressSuccess(userId) },
            internalAddressDomain = domain
        )
        emit(ChooseAddressState.AccountSetupResult(result))
    }.retryOnceWhen(Throwable::primaryKeyExists) {
        CoreLogger.e(LogTag.FLOW_ERROR_RETRY, it, "Retrying to upgrade an account")
    }.catch { error ->
        emit(ChooseAddressState.Error.Message(error))
    }.onEach {
        _chooseAddressState.tryEmit(it)
    }.launchIn(viewModelScope)
}
