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

package me.proton.core.key.data.repository

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.SourceOfTruth
import com.dropbox.android.external.store4.StoreBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.core.data.arch.buildProtonStore
import me.proton.core.domain.entity.SessionUserId
import me.proton.core.domain.entity.UserId
import me.proton.core.key.data.api.KeyApi
import me.proton.core.key.data.api.response.toPublicAddressInfo
import me.proton.core.key.data.db.PublicAddressDatabase
import me.proton.core.key.data.extension.toEntity
import me.proton.core.key.data.extension.toEntityList
import me.proton.core.key.data.extension.toPublicAddress
import me.proton.core.key.data.extension.toPublicAddressInfo
import me.proton.core.key.data.extension.toPublicSignedKeyList
import me.proton.core.key.domain.entity.key.PublicAddress
import me.proton.core.key.domain.entity.key.PublicAddressInfo
import me.proton.core.key.domain.entity.key.PublicSignedKeyList
import me.proton.core.key.domain.repository.PublicAddressRepository
import me.proton.core.key.domain.repository.Source
import me.proton.core.key.domain.repository.PublicAddressVerifier
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.CacheOverride
import me.proton.core.util.kotlin.CoroutineScopeProvider
import me.proton.core.util.kotlin.toInt
import java.util.Optional
import javax.inject.Inject

class PublicAddressRepositoryImpl @Inject constructor(
    private val db: PublicAddressDatabase,
    private val provider: ApiProvider,
    scopeProvider: CoroutineScopeProvider,
    private val publicAddressVerifier: Optional<PublicAddressVerifier>
) : PublicAddressRepository {

    private val publicAddressDao = db.publicAddressDao()
    private val publicAddressKeyDao = db.publicAddressKeyDao()
    private val publicAddressWithKeysDao = db.publicAddressWithKeysDao()

    private val publicAddressInfoDao = db.publicAddressInfoDao()
    private val publicAddressKeyDataDao = db.publicAddressKeyDataDao()
    private val publicAddressInfoWithKeysDao = db.publicAddressInfoWithKeysDao()

    private data class StoreKey(
        val userId: UserId,
        val email: String,
        val forceNoCache: Boolean,
        val internalOnly: Boolean?
    )

    @Deprecated("Using getPublicAddressKeys is deprecated")
    private val store = StoreBuilder.from(
        fetcher = Fetcher.of { key: StoreKey ->
            provider.get<KeyApi>(key.userId).invoke {
                val publicAddress = getPublicAddressKeys(
                    key.email,
                    if (key.forceNoCache) CacheOverride().noCache() else null
                ).toPublicAddress(key.email)
                publicAddress
            }.valueOrThrow.also { publicAddress ->
                if (publicAddressVerifier.isPresent) {
                    /**
                     *  KT verification happens silently for now (with some logs),
                     *  some UI will be needed later on to communicate the state
                     */
                    publicAddressVerifier
                        .get()
                        .verifyPublicAddress(key.userId, publicAddress)
                }
            }
        },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key -> getPublicAddressLocal(key.email) },
            writer = { _, input -> insertOrUpdate(input) },
            delete = { key -> delete(key.email) },
            deleteAll = { deleteAll() }
        )
    ).buildProtonStore(scopeProvider)

    private val publicAddressInfoStore = StoreBuilder.from(
        fetcher = Fetcher.of { key: StoreKey ->
            provider.get<KeyApi>(key.userId).invoke {
                getAllActivePublicKeys(
                    key.email,
                    key.internalOnly?.toInt(),
                    if (key.forceNoCache) CacheOverride().noCache() else null
                ).toPublicAddressInfo(key.email)
            }.valueOrThrow
            // WARNING: missing KT verification
        },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key -> getPublicAddressInfoLocal(key.email) },
            writer = { _, input -> insertOrUpdate(input) },
            delete = { publicAddressInfoDao.deleteByEmail(it.email) },
            deleteAll = { publicAddressInfoDao.deleteAll() }
        )
    ).disableCache().buildProtonStore(scopeProvider)

    private fun getPublicAddressInfoLocal(email: String): Flow<PublicAddressInfo?> =
        publicAddressInfoWithKeysDao.findWithKeysByEmail(email)
            .map { it?.entity?.toPublicAddressInfo(it.keys) }

    private suspend fun insertOrUpdate(publicAddressInfo: PublicAddressInfo) =
        db.inTransaction {
            val entityWithKeys = publicAddressInfo.toEntity()
            publicAddressInfoDao.insertOrUpdate(entityWithKeys.entity)
            publicAddressKeyDataDao.deleteByEmail(publicAddressInfo.email)
            publicAddressKeyDataDao.insertOrUpdate(*entityWithKeys.keys.toTypedArray())
        }

    private fun getPublicAddressLocal(email: String): Flow<PublicAddress?> =
        publicAddressWithKeysDao.findWithKeysByEmail(email)
            .map { it?.entity?.toPublicAddress(it.keys) }

    private suspend fun insertOrUpdate(publicAddress: PublicAddress) =
        db.inTransaction {
            publicAddressDao.insertOrUpdate(publicAddress.toEntity())
            publicAddressKeyDao.deleteByEmail(publicAddress.email)
            publicAddressKeyDao.insertOrUpdate(*publicAddress.keys.toEntityList().toTypedArray())
        }

    private suspend fun delete(email: String) = publicAddressDao.deleteByEmail(email)

    private suspend fun deleteAll() = publicAddressDao.deleteAll()

    @Deprecated(
        "Deprecated on BE.",
        ReplaceWith("getPublicKeysInfo(sessionUserId, email, internalOnly = TODO(), source)")
    )
    override suspend fun getPublicAddress(
        sessionUserId: SessionUserId,
        email: String,
        source: Source
    ): PublicAddress = StoreKey(sessionUserId, email, source == Source.RemoteNoCache, internalOnly = null)
        .let { if (source == Source.LocalIfAvailable) store.get(it) else store.fresh(it) }

    override suspend fun getPublicAddressInfo(
        sessionUserId: SessionUserId,
        email: String,
        internalOnly: Boolean,
        source: Source
    ): PublicAddressInfo {
        val storeKey =
            StoreKey(sessionUserId, email, forceNoCache = source == Source.RemoteNoCache, internalOnly = internalOnly)
        return if (source == Source.LocalIfAvailable) {
            publicAddressInfoStore.get(storeKey)
        } else {
            publicAddressInfoStore.fresh(storeKey)
        }
    }

    override suspend fun getSKLsAfterEpoch(
        userId: UserId,
        epochId: Int,
        email: String
    ): List<PublicSignedKeyList> = provider.get<KeyApi>(userId).invoke {
        val sklListResponse = getSKLsAfterEpoch(email, epochId)
        sklListResponse.list.map { it.toPublicSignedKeyList() }
    }.valueOrThrow

    override suspend fun getSKLAtEpoch(
        userId: UserId,
        epochId: Int,
        email: String
    ): PublicSignedKeyList = provider.get<KeyApi>(userId).invoke {
        val sklListResponse = getSKLAtEpoch(email, epochId)
        sklListResponse.signedKeyList.toPublicSignedKeyList()
    }.valueOrThrow

    override suspend fun clearAll() {
        publicAddressInfoStore.clearAll()
        store.clearAll()
    }
}
