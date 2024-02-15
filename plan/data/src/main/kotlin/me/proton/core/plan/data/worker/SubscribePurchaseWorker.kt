/*
 * Copyright (c) 2024 Proton Technologies AG
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

package me.proton.core.plan.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.isRetryable
import me.proton.core.network.domain.session.SessionProvider
import me.proton.core.observability.domain.ObservabilityContext
import me.proton.core.observability.domain.ObservabilityManager
import me.proton.core.payment.domain.MAX_PLAN_QUANTITY
import me.proton.core.payment.domain.entity.PaymentTokenEntity
import me.proton.core.payment.domain.entity.PurchaseState
import me.proton.core.payment.domain.entity.SubscriptionCycle
import me.proton.core.payment.domain.extension.getSubscribeObservabilityData
import me.proton.core.payment.domain.repository.PurchaseRepository
import me.proton.core.payment.domain.usecase.PaymentProvider
import me.proton.core.plan.domain.entity.SubscriptionManagement
import me.proton.core.plan.domain.repository.PlansRepository
import me.proton.core.util.kotlin.CoreLogger
import me.proton.core.util.kotlin.coroutine.withResultContext

@HiltWorker
internal class SubscribePurchaseWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionProvider: SessionProvider,
    private val purchaseRepository: PurchaseRepository,
    private val plansRepository: PlansRepository,
    override val observabilityManager: ObservabilityManager,
) : CoroutineWorker(context, params), ObservabilityContext {

    override suspend fun doWork(): Result = withResultContext {
        onResultEnqueueObservability("createOrUpdateSubscription") {
            getSubscribeObservabilityData(PaymentProvider.GoogleInAppPurchase)
        }

        val planName = requireNotNull(inputData.getString(INPUT_PLAN_NAME))
        val purchase = requireNotNull(purchaseRepository.getPurchase(planName))
        runCatching {
            val userId = requireNotNull(sessionProvider.getUserId(purchase.sessionId))
            require(purchase.paymentProvider == PaymentProvider.GoogleInAppPurchase)
            requireNotNull(purchase.paymentToken)
            plansRepository.createOrUpdateSubscription(
                sessionUserId = userId,
                amount = purchase.paymentAmount,
                currency = purchase.paymentCurrency,
                payment = PaymentTokenEntity(requireNotNull(purchase.paymentToken)),
                codes = null,
                plans = listOf(purchase.planName).associateWith { MAX_PLAN_QUANTITY },
                cycle = SubscriptionCycle.map[purchase.planCycle] ?: SubscriptionCycle.OTHER,
                subscriptionManagement = SubscriptionManagement.GOOGLE_MANAGED
            )
        }.fold(
            onSuccess = {
                purchaseRepository.upsertPurchase(purchase.copy(purchaseState = PurchaseState.Subscribed))
                Result.success()
            },
            onFailure = {
                if (it is ApiException && it.isRetryable()) {
                    Result.retry()
                } else {
                    CoreLogger.e(TAG, it)
                    purchaseRepository.upsertPurchase(
                        purchase.copy(
                            purchaseFailure = it.localizedMessage,
                            purchaseState = PurchaseState.Failed
                        )
                    )
                    Result.failure()
                }
            }
        )
    }

    companion object {
        private const val TAG = "SubscribePurchaseWorker"
        private const val INPUT_PLAN_NAME = "arg.planName"

        fun getOneTimeUniqueWorkName(planName: String) = "$TAG-$planName"

        fun getRequest(planName: String): OneTimeWorkRequest {
            val inputData = workDataOf(INPUT_PLAN_NAME to planName)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<SubscribePurchaseWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()
        }
    }
}
