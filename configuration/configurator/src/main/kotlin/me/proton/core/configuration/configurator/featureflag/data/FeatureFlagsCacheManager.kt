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

package me.proton.core.configuration.configurator.featureflag.data

import io.github.reactivecircus.cache4k.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.configuration.configurator.featureflag.data.api.Feature
import me.proton.core.configuration.configurator.network.RetrofitInstance
import me.proton.core.network.data.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
class FeatureFlagsCacheManager @Inject constructor() {

    private val cache = Cache.Builder().expireAfterWrite(1.minutes).build<String, List<Feature>>()

    suspend fun getFeatureFlags(): List<Feature> {
        return withContext(Dispatchers.IO) {
            cache.get(String()) {
                fetchFeatureFlagsFromApi()
            }
        }
    }

    private suspend fun fetchFeatureFlagsFromApi(): List<Feature> {
        return try {
            val apiToken = BuildConfig.UNLEASH_API_TOKEN
            if (apiToken.isNotEmpty()) {
                val response = RetrofitInstance.api.getFeatureFlags(apiToken)
                response.features
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
