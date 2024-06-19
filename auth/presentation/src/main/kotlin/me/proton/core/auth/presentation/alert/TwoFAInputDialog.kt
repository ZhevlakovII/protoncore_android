/*
 * Copyright (c) 2021 Proton Technologies AG
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

package me.proton.core.auth.presentation.alert

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultCaller
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.proton.core.auth.fido.domain.entity.Fido2PublicKeyCredentialRequestOptions
import me.proton.core.auth.fido.domain.entity.SecondFactorFido
import me.proton.core.auth.fido.domain.usecase.PerformTwoFaWithSecurityKey
import me.proton.core.auth.presentation.LogTag
import me.proton.core.auth.presentation.databinding.Dialog2faInputBinding
import me.proton.core.auth.presentation.entity.TwoFAInput
import me.proton.core.auth.presentation.entity.TwoFAMechanisms
import me.proton.core.auth.presentation.entity.TwoFaFido
import me.proton.core.auth.presentation.entity.toParcelable
import me.proton.core.auth.presentation.util.setTextWithAnnotatedLink
import me.proton.core.auth.presentation.viewmodel.TwoFAInputDialogViewModel
import me.proton.core.domain.entity.UserId
import me.proton.core.presentation.R
import me.proton.core.presentation.utils.ProtectScreenConfiguration
import me.proton.core.presentation.utils.ScreenContentProtector
import me.proton.core.presentation.utils.errorSnack
import me.proton.core.presentation.utils.errorToast
import me.proton.core.presentation.utils.onClick
import me.proton.core.presentation.utils.openBrowserLink
import me.proton.core.util.kotlin.CoreLogger
import java.util.Optional
import javax.inject.Inject
import kotlin.jvm.optionals.getOrNull

@AndroidEntryPoint
class TwoFAInputDialog : DialogFragment(), TabLayout.OnTabSelectedListener {

    @Inject
    lateinit var performTwoFaWithSecurityKey: Optional<PerformTwoFaWithSecurityKey<ActivityResultCaller, Activity>>

    private val viewModel by viewModels<TwoFAInputDialogViewModel>()

    companion object {
        private const val ARG_USER_ID = "arg.userId"
        const val KEY_2FA_SET = "key.2fa_set"
        const val BUNDLE_KEY_2FA_DATA = "bundle.2fa_data"

        operator fun invoke(userId: String) = TwoFAInputDialog().apply {
            arguments = bundleOf(
                ARG_USER_ID to userId
            )
        }
    }

    private val screenProtector = ScreenContentProtector(ProtectScreenConfiguration())

    private val userId by lazy {
        UserId(requireNotNull(requireArguments().getString(ARG_USER_ID)))
    }

    private val binding by lazy {
        Dialog2faInputBinding.inflate(LayoutInflater.from(requireContext()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        performTwoFaWithSecurityKey.getOrNull()?.register(requireActivity(), ::onTwoFaWithSecurityKeyResult)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreateDialog(savedInstanceState)
        screenProtector.protect(requireActivity())

        binding.tabLayout.addOnTabSelectedListener(this@TwoFAInputDialog)

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.presentation_authenticate)
            // passing null to the listeners is a workaround to prevent the dialog to auto-dismiss on button click
            .setPositiveButton(R.string.presentation_alert_enter, null)
            .setNegativeButton(R.string.presentation_alert_cancel, null)
            .setView(binding.root)
        val alertDialog = builder.create()

        return alertDialog.apply {
            viewModel.state
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .distinctUntilChanged()
                .onEach {
                    when (it) {
                        is TwoFAInputDialogViewModel.State.Idle -> with(binding){
                            progressLayout.isVisible = false
                            it.showSecurityKey
                            tabLayout.isVisible = it.showSecurityKey
                            if (it.showSecurityKey) {
                                selectSecurityKeyTab()
                            } else {
                                selectOneTimeCodeTab()
                            }
                        }

                        is TwoFAInputDialogViewModel.State.Error.InvalidAccount ->
                            requireContext().errorToast(getString(R.string.presentation_error_general))
                    }
                }.launchIn(lifecycleScope)

            setOnShowListener {
                viewModel.setup(userId)

                // workaround to prevent the dialog to auto-dismiss on button click
                getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isAllCaps = false
                    onClick {
                        with(binding) {
                            when (selectedTwoFAOption()) {
                                TwoFAMechanisms.SECURITY_KEY -> onSecurityKeySubmitted()

                                TwoFAMechanisms.ONE_TIME_CODE -> {
                                    parentFragmentManager.setFragmentResult(
                                        KEY_2FA_SET, bundleOf(
                                            BUNDLE_KEY_2FA_DATA to TwoFAInput(twoFA.text.toString())
                                        )
                                    )
                                    dismissAllowingStateLoss()
                                }
                            }
                        }
                    }
                }
                getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                    isAllCaps = false
                    onClick {
                        parentFragmentManager.setFragmentResult(KEY_2FA_SET, bundleOf())

                        dismissAllowingStateLoss()
                    }
                }
            }
            setCanceledOnTouchOutside(false)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        screenProtector.unprotect(requireActivity())
    }

    override fun onTabSelected(tab: TabLayout.Tab?) {
        when (tab?.position) {
            TwoFAMechanisms.SECURITY_KEY.ordinal -> selectSecurityKeyTab()
            TwoFAMechanisms.ONE_TIME_CODE.ordinal -> selectOneTimeCodeTab()
        }
    }

    override fun onTabUnselected(p0: TabLayout.Tab?) { }

    override fun onTabReselected(p0: TabLayout.Tab?) { }

    private fun onTwoFaWithSecurityKeyResult(
        result: PerformTwoFaWithSecurityKey.Result,
        options: Fido2PublicKeyCredentialRequestOptions
    ) {
        when (result) {
            is PerformTwoFaWithSecurityKey.Result.Success -> {
                val secondFactorFido = SecondFactorFido(
                    publicKeyOptions = options,
                    clientData = result.response.clientDataJSON,
                    authenticatorData = result.response.authenticatorData,
                    signature = result.response.signature,
                    credentialID = result.rawId
                )

                parentFragmentManager.setFragmentResult(
                    KEY_2FA_SET, bundleOf(
                        BUNDLE_KEY_2FA_DATA to TwoFAInput(twoFAFido = TwoFaFido(
                            secondFactorFido.publicKeyOptions.toParcelable(),
                            secondFactorFido.clientData,
                            secondFactorFido.authenticatorData,
                            secondFactorFido.signature,
                            secondFactorFido.credentialID
                        ))
                    )
                )
            }
            is PerformTwoFaWithSecurityKey.Result.Cancelled -> Unit
            is PerformTwoFaWithSecurityKey.Result.EmptyResult -> binding.root.errorSnack(
                getString(me.proton.core.auth.presentation.R.string.auth_login_general_error)
            )

            is PerformTwoFaWithSecurityKey.Result.Error -> binding.root.errorSnack(
                result.error.message ?: getString(me.proton.core.auth.presentation.R.string.auth_login_general_error)
            )

            is PerformTwoFaWithSecurityKey.Result.UnknownResult -> {
                getString(me.proton.core.auth.presentation.R.string.auth_login_general_error)
                CoreLogger.e(LogTag.FLOW_ERROR_2FA, result.toString())
            }
        }
    }

    private fun selectSecurityKeyTab() = with (binding) {
        oneTimeCodeGroup.isVisible = false
        securityKeyGroup.isVisible = true

        securityKeyText.setTextWithAnnotatedLink(me.proton.core.auth.presentation.R.string.auth_2fa_insert_security_key, "more") {
            context?.openBrowserLink(getString(me.proton.core.auth.presentation.R.string.confirm_password_2fa_security_key))
        }
    }

    private fun selectOneTimeCodeTab() = with (binding) {
        oneTimeCodeGroup.isVisible = true
        securityKeyGroup.isVisible = false
    }

    private fun selectedTwoFAOption(): TwoFAMechanisms =
        if (binding.tabLayout.isVisible) {
            TwoFAMechanisms.enumOf(binding.tabLayout.selectedTabPosition)
        }
        else TwoFAMechanisms.ONE_TIME_CODE

    private fun onSecurityKeySubmitted() {
        val requestOptions = requireNotNull(viewModel.fido2Info?.authenticationOptions?.publicKey)

        lifecycleScope.launch {
            when (val launchResult = performTwoFaWithSecurityKey.getOrNull()?.invoke(requireActivity(), requestOptions)) {
                is PerformTwoFaWithSecurityKey.LaunchResult.Failure -> {
                    binding.root.errorSnack(
                        message = launchResult.exception.localizedMessage
                            ?: getString(me.proton.core.auth.presentation.R.string.auth_login_general_error)
                    )
                }

                is PerformTwoFaWithSecurityKey.LaunchResult.Success,
                null -> Unit
            }
        }
    }
}
