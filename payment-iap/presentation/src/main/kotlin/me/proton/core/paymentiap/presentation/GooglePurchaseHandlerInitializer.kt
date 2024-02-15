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

package me.proton.core.paymentiap.presentation

import android.content.Context
import androidx.startup.Initializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import me.proton.core.paymentiap.data.GooglePurchaseStateHandler
import me.proton.core.util.kotlin.annotation.ExcludeFromCoverage

@ExcludeFromCoverage
public class GooglePurchaseHandlerInitializer : Initializer<Unit> {
    public override fun create(context: Context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PurchaseHandlerInitializerEntryPoint::class.java
        ).googlePurchaseStateHandler().start()
    }

    public override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface PurchaseHandlerInitializerEntryPoint {
        fun googlePurchaseStateHandler(): GooglePurchaseStateHandler
    }
}
