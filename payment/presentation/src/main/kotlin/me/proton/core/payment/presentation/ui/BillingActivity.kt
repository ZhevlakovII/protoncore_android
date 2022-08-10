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

package me.proton.core.payment.presentation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.proton.core.payment.presentation.LogTag
import me.proton.core.payment.presentation.R
import me.proton.core.payment.presentation.databinding.ActivityBillingBinding
import me.proton.core.payment.presentation.entity.BillingResult
import me.proton.core.paymentcommon.domain.entity.Currency
import me.proton.core.paymentcommon.domain.entity.SubscriptionCycle
import me.proton.core.paymentcommon.domain.usecase.PaymentProvider
import me.proton.core.paymentcommon.presentation.entity.BillingInput
import me.proton.core.paymentcommon.presentation.viewmodel.BillingCommonViewModel
import me.proton.core.paymentcommon.presentation.viewmodel.BillingViewModel
import me.proton.core.presentation.utils.errorSnack
import me.proton.core.presentation.utils.formatCentsPriceDefaultLocale
import me.proton.core.presentation.utils.getUserMessage
import me.proton.core.presentation.utils.onClick
import me.proton.core.util.kotlin.CoreLogger
import me.proton.core.util.kotlin.exhaustive

/**
 * Activity responsible for taking a Credit Card input from a user for the purpose of paying a subscription.
 * It processes the payment request as well.
 * Note, that this one only works with a new Credit Card and is not responsible for displaying existing payment methods.
 */
@AndroidEntryPoint
class BillingActivity : PaymentsActivity<ActivityBillingBinding>(ActivityBillingBinding::inflate) {

    private val viewModel by viewModels<BillingViewModel>()

    private val input: BillingInput by lazy {
        requireNotNull(intent?.extras?.getParcelable(ARG_BILLING_INPUT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.apply {
            findOutPlan()
            toolbar.apply {
                if (input.userId != null) {
                    navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_proton_arrow_back)
                }
                setNavigationOnClickListener {
                    onBackPressed()
                }
            }
            payButton.onClick(::onPayClicked)
            nextPaymentProviderButton.onClick(::onNextPaymentProviderClicked)
        }
        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.plansValidationState
            .flowWithLifecycle(lifecycle)
            .distinctUntilChanged()
            .onEach {
                when (it) {
                    is BillingCommonViewModel.PlansValidationState.Success -> {
                        val amountDue = it.subscription.amountDue
                        val plan = input.plan.copy(amount = amountDue)
                        viewModel.setPlan(plan)
                        binding.payButton.text = String.format(
                            getString(R.string.payments_pay),
                            plan.amount?.toDouble()?.formatCentsPriceDefaultLocale(plan.currency.name) ?: ""
                        )
                    }
                    is BillingCommonViewModel.PlansValidationState.Error.Message -> showError(it.message)
                    else -> Unit
                }.exhaustive
            }.launchIn(lifecycleScope)

        viewModel.subscriptionResult
            .flowWithLifecycle(lifecycle)
            .distinctUntilChanged()
            .onEach {
                when (it) {
                    is BillingCommonViewModel.State.Processing -> showLoading(true)
                    is BillingCommonViewModel.State.Success.SignUpTokenReady -> onBillingSuccess(
                        it.paymentToken,
                        it.amount,
                        it.currency,
                        it.cycle
                    )
                    is BillingCommonViewModel.State.Success.SubscriptionCreated -> onBillingSuccess(
                        amount = it.amount,
                        currency = it.currency,
                        cycle = it.cycle
                    )
                    is BillingCommonViewModel.State.Incomplete.TokenApprovalNeeded ->
                        onTokenApprovalNeeded(input.userId, it.paymentToken, it.amount)
                    is BillingCommonViewModel.State.Error.General -> showError(it.error.getUserMessage(resources))
                    is BillingCommonViewModel.State.Error.SignUpWithPaymentMethodUnsupported ->
                        showError(getString(R.string.payments_error_signup_paymentmethod))
                    else -> {
                        // no operation, not interested in other events
                    }
                }
            }.launchIn(lifecycleScope)

        viewModel.paymentProvidersResult
            .flowWithLifecycle(lifecycle)
            .distinctUntilChanged()
            .onEach {
                when (it) {
                    is BillingViewModel.PaymentProvidersState.Error.Message -> showError(it.error)
                    is BillingViewModel.PaymentProvidersState.Success -> {
                        with(binding) {
                            val currentProvider = it.activeProvider
                            when (currentProvider) {
                                PaymentProvider.GoogleInAppPurchase -> {
                                    supportFragmentManager.showBillingIAPFragment(R.id.fragment_container)
                                    nextPaymentProviderButton.visibility = View.VISIBLE
                                }
                                PaymentProvider.ProtonPayment -> {
                                    supportFragmentManager.showBillingFragment(R.id.fragment_container)
                                }
                            }.exhaustive

                            it.nextPaymentProviderTextResource?.let { textResource ->
                                nextPaymentProviderButton.text = getString(textResource)
                            } ?: run {
                                nextPaymentProviderButton.visibility = View.GONE
                            }
                        }
                    }
                    BillingViewModel.PaymentProvidersState.PaymentProvidersEmpty -> {
                        val message = getString(R.string.payments_no_payment_provider)
                        CoreLogger.i(LogTag.NO_ACTIVE_PAYMENT_PROVIDER, message)
                        setResult(RESULT_CANCELED, intent)
                        finish()
                    }
                    is BillingViewModel.PaymentProvidersState.Idle,
                    is BillingViewModel.PaymentProvidersState.Processing -> {
                        // do nothing
                    }
                }.exhaustive
            }.launchIn(lifecycleScope)
    }

    private fun onBillingSuccess(token: String? = null, amount: Long, currency: Currency, cycle: SubscriptionCycle) {
        val intent = Intent()
            .putExtra(
                ARG_BILLING_RESULT,
                BillingResult(
                    paySuccess = true,
                    token = token,
                    subscriptionCreated = token == null,
                    amount = amount,
                    currency = currency,
                    cycle = cycle
                )
            )
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun findOutPlan() = with(input) {
        if (plan.amount == null) {
            viewModel.validatePlan(user, listOf(plan.name), codes, plan.currency, plan.subscriptionCycle)
        }
        viewModel.setPlan(plan)
    }

    override fun onThreeDSApprovalResult(amount: Long, token: String, success: Boolean) {
        if (!success) {
            binding.payButton.setIdle()
            return
        }
        with(input) {
            val plans = listOf(plan.name)
            viewModel.onThreeDSTokenApproved(
                user, plans, codes, amount, plan.currency, plan.subscriptionCycle, token
            )
        }
    }

    private fun onPayClicked() {
        viewModel.onPay(input)
    }

    private fun onNextPaymentProviderClicked() {
        viewModel.switchNextPaymentProvider()
    }

    override fun showLoading(loading: Boolean) {
        if (loading) {
            binding.payButton.setLoading()
        } else {
            binding.payButton.setIdle()
        }
        viewModel.onLoadingStateChange(loading)
    }

    override fun showError(message: String?) {
        showLoading(false)
        binding.root.errorSnack(message = message ?: getString(R.string.payments_general_error))
    }

    companion object {
        const val ARG_BILLING_INPUT = "arg.billingInput"
        const val ARG_BILLING_RESULT = "arg.billingResult"
        const val EXP_DATE_SEPARATOR = "/"
    }
}
