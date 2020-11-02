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

package me.proton.core.accountmanager.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.account.domain.entity.SessionState
import me.proton.core.account.domain.entity.isReady
import me.proton.core.account.domain.entity.isSecondFactorNeeded
import me.proton.core.account.domain.repository.AccountRepository
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.accountmanager.domain.onSessionStateChanged
import me.proton.core.auth.domain.AccountWorkflowHandler
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.domain.arch.extension.onEntityChanged
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.humanverification.HumanVerificationDetails
import me.proton.core.network.domain.session.Session
import me.proton.core.network.domain.session.SessionId

class AccountManagerImpl constructor(
    product: Product,
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository
) : AccountManager(product), AccountWorkflowHandler {

    private suspend fun removeSession(sessionId: SessionId) {
        authRepository.revokeSession(sessionId)
        accountRepository.deleteSession(sessionId)
    }

    private suspend fun disableAccount(account: Account) {
        accountRepository.updateAccountState(account.userId, AccountState.Disabled)
        account.sessionId?.let { removeSession(it) }
    }

    private suspend fun disableAccount(sessionId: SessionId) {
        accountRepository.getAccountOrNull(sessionId)?.let { disableAccount(it) }
    }

    override suspend fun addAccount(account: Account, session: Session) {
        handleSession(account.copy(state = AccountState.Ready), session)
    }

    override suspend fun removeAccount(userId: UserId) {
        accountRepository.getAccountOrNull(userId)?.let { account ->
            accountRepository.updateAccountState(account.userId, AccountState.Removed)
            account.sessionId?.let { removeSession(it) }
            accountRepository.deleteAccount(account.userId)
        }
    }

    override suspend fun disableAccount(userId: UserId) {
        accountRepository.getAccountOrNull(userId)?.let { disableAccount(it) }
    }

    override fun getAccount(userId: UserId): Flow<Account?> =
        accountRepository.getAccount(userId)

    override fun getAccounts(): Flow<List<Account>> =
        accountRepository.getAccounts()

    override fun getSessions(): Flow<List<Session>> =
        accountRepository.getSessions()

    override fun onAccountStateChanged(): Flow<Account> = getAccounts().onEntityChanged(
        getEntityKey = { it.userId },
        equalPredicate = { previous, current -> previous.state == current.state },
        emitNewEntity = true
    ).distinctUntilChanged()

    override fun onSessionStateChanged(): Flow<Account> = getAccounts().onEntityChanged(
        getEntityKey = { it.sessionId },
        equalPredicate = { previous, current -> previous.sessionState == current.sessionState },
        emitNewEntity = true
    ).filter { it.sessionState != null }.distinctUntilChanged()

    override fun onHumanVerificationNeeded(): Flow<Pair<Account, HumanVerificationDetails?>> =
        onSessionStateChanged(SessionState.HumanVerificationNeeded)
            .map { it to it.sessionId?.let { id -> accountRepository.getHumanVerificationDetails(id) } }

    override fun getPrimaryUserId(): Flow<UserId?> =
        accountRepository.getPrimaryUserId()

    override suspend fun setAsPrimary(userId: UserId) =
        accountRepository.setAsPrimary(userId)

    // region AccountWorkflowHandler

    override suspend fun handleSession(account: Account, session: Session) {
        // Account state must be != Ready if SecondFactorNeeded.
        val state = if (account.isReady() && account.isSecondFactorNeeded()) AccountState.NotReady else account.state
        accountRepository.createOrUpdateAccountSession(account.copy(state = state), session)
    }

    override suspend fun handleTwoPassModeSuccess(sessionId: SessionId) {
        accountRepository.updateAccountState(sessionId, AccountState.TwoPassModeSuccess)
        accountRepository.getAccountOrNull(sessionId)?.let { account ->
            if (account.sessionState == SessionState.Authenticated)
                accountRepository.updateAccountState(sessionId, AccountState.Ready)
        }
    }

    override suspend fun handleTwoPassModeFailed(sessionId: SessionId) {
        accountRepository.updateAccountState(sessionId, AccountState.TwoPassModeFailed)
    }

    override suspend fun handleSecondFactorSuccess(sessionId: SessionId, updatedScopes: List<String>) {
        accountRepository.updateSessionScopes(sessionId, updatedScopes)
        accountRepository.updateSessionState(sessionId, SessionState.SecondFactorSuccess)
        accountRepository.updateSessionState(sessionId, SessionState.Authenticated)
        accountRepository.getAccountOrNull(sessionId)?.let { account ->
            if (account.state != AccountState.TwoPassModeNeeded)
                accountRepository.updateAccountState(sessionId, AccountState.Ready)
        }
    }

    override suspend fun handleSecondFactorFailed(sessionId: SessionId) {
        accountRepository.updateSessionState(sessionId, SessionState.SecondFactorFailed)
        disableAccount(sessionId)
    }

    override suspend fun handleHumanVerificationSuccess(sessionId: SessionId, tokenType: String, tokenCode: String) {
        accountRepository.updateSessionHeaders(sessionId, tokenType, tokenCode)
        accountRepository.updateSessionState(sessionId, SessionState.HumanVerificationSuccess)
        accountRepository.updateSessionState(sessionId, SessionState.Authenticated)
        accountRepository.updateAccountState(sessionId, AccountState.Ready)
    }

    override suspend fun handleHumanVerificationFailed(sessionId: SessionId) {
        accountRepository.updateSessionHeaders(sessionId, null, null)
        accountRepository.updateSessionState(sessionId, SessionState.HumanVerificationFailed)
    }

    // endregion
}
