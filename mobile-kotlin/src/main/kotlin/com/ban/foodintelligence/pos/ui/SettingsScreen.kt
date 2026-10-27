package com.ban.foodintelligence.pos.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ban.foodintelligence.pos.BuildConfig
import com.ban.foodintelligence.pos.R
import com.ban.foodintelligence.pos.net.ApiConfig
import com.ban.foodintelligence.pos.net.HealthCheck
import com.ban.foodintelligence.pos.ui.design.CheckRow
import com.ban.foodintelligence.pos.ui.design.InsetGroup
import com.ban.foodintelligence.pos.ui.design.LargeTitle
import com.ban.foodintelligence.pos.ui.design.ListRow
import com.ban.foodintelligence.pos.ui.design.PosType
import com.ban.foodintelligence.pos.ui.design.Shapes
import com.ban.foodintelligence.pos.ui.design.Space
import com.ban.foodintelligence.pos.ui.design.TextAction
import com.ban.foodintelligence.pos.ui.design.palette
import kotlinx.coroutines.launch

private sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Done(val result: Result<HealthCheck>) : ConnectionTest
}

/**
 * Settings: server address (USB / emulator / LAN) with a connection test, UI language,
 * and the device signing details that used to clutter the sale screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyFingerprint: String,
    onServerChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var savedUrl by remember { mutableStateOf(ApiConfig.baseUrl(context)) }
    var preset by remember { mutableStateOf(ApiConfig.presetFor(savedUrl)) }
    var lanInput by remember {
        mutableStateOf(if (ApiConfig.presetFor(savedUrl) == ApiConfig.Preset.LAN) savedUrl.removePrefix("http://") else "")
    }
    var lanError by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<ConnectionTest>(ConnectionTest.Idle) }
    var showSecurity by remember { mutableStateOf(false) }

    fun choose(p: ApiConfig.Preset) {
        preset = p
        test = ConnectionTest.Idle
        val url = p.url ?: return
        ApiConfig.setBaseUrl(context, url)
        savedUrl = ApiConfig.baseUrl(context)
        onServerChanged()
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xl)) {
        LargeTitle(stringResource(R.string.settings_title))

        InsetGroup(
            header = stringResource(R.string.settings_server_header),
            footer = stringResource(R.string.settings_server_footer)
        ) {
            row {
                CheckRow(
                    title = stringResource(R.string.settings_server_usb),
                    subtitle = stringResource(R.string.settings_server_usb_sub),
                    selected = preset == ApiConfig.Preset.USB
                ) { choose(ApiConfig.Preset.USB) }
            }
            row {
                CheckRow(
                    title = stringResource(R.string.settings_server_emulator),
                    subtitle = "10.0.2.2:8080",
                    selected = preset == ApiConfig.Preset.EMULATOR
                ) { choose(ApiConfig.Preset.EMULATOR) }
            }
            row {
                CheckRow(
                    title = stringResource(R.string.settings_server_lan),
                    subtitle = if (preset == ApiConfig.Preset.LAN) savedUrl.removePrefix("http://") else null,
                    selected = preset == ApiConfig.Preset.LAN
                ) { preset = ApiConfig.Preset.LAN; test = ConnectionTest.Idle }
            }
            if (preset == ApiConfig.Preset.LAN) {
                row {
                    Column(Modifier.padding(horizontal = Space.m, vertical = Space.s)) {
                        OutlinedTextField(
                            value = lanInput,
                            onValueChange = { lanInput = it; lanError = false },
                            singleLine = true,
                            isError = lanError,
                            placeholder = { Text(stringResource(R.string.settings_server_lan_hint), style = PosType.body) },
                            supportingText = if (lanError) {
                                { Text(stringResource(R.string.settings_server_invalid)) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            shape = Shapes.button,
                            textStyle = PosType.body,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = palette.accent,
                                unfocusedBorderColor = palette.separator
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextAction(
                            text = stringResource(R.string.settings_server_save),
                            onClick = {
                                if (ApiConfig.isValid(lanInput)) {
                                    ApiConfig.setBaseUrl(context, lanInput)
                                    savedUrl = ApiConfig.baseUrl(context)
                                    test = ConnectionTest.Idle
                                    onServerChanged()
                                } else {
                                    lanError = true
                                }
                            }
                        )
                    }
                }
            }
            row {
                val current = test
                ListRow(
                    title = stringResource(
                        if (current is ConnectionTest.Running) R.string.settings_testing else R.string.settings_test_connection
                    ),
                    titleColor = palette.accent,
                    subtitle = when (current) {
                        is ConnectionTest.Done -> current.result.fold(
                            onSuccess = {
                                stringResource(
                                    if (it.databaseOk) R.string.settings_test_ok else R.string.settings_test_db_down,
                                    it.latencyMs.toInt()
                                )
                            },
                            onFailure = { stringResource(R.string.error_server_unreachable) + "\n" + (it.message ?: it.javaClass.simpleName) }
                        )
                        else -> null
                    },
                    onClick = if (current is ConnectionTest.Running) null else {
                        {
                            test = ConnectionTest.Running
                            scope.launch { test = ConnectionTest.Done(ApiConfig.testConnection(savedUrl)) }
                        }
                    }
                )
            }
        }

        InsetGroup(header = stringResource(R.string.settings_language_header)) {
            AppLocale.SUPPORTED_NAMES.forEach { (code, name) ->
                row {
                    CheckRow(title = name, selected = AppLocale.current(context) == code) {
                        if (AppLocale.current(context) != code) {
                            AppLocale.set(context, code)
                            (context as? Activity)?.recreate()
                        }
                    }
                }
            }
        }

        InsetGroup(header = stringResource(R.string.settings_security_header)) {
            row(dividerInset = 56.dp) {
                ListRow(
                    title = stringResource(R.string.settings_security_row),
                    icon = Icons.Rounded.Key,
                    chevron = true,
                    onClick = { showSecurity = true }
                )
            }
        }

        InsetGroup(header = stringResource(R.string.settings_about_header)) {
            row(dividerInset = 56.dp) {
                ListRow(
                    title = stringResource(R.string.settings_version),
                    icon = Icons.Rounded.Info,
                    value = BuildConfig.VERSION_NAME
                )
            }
        }
    }

    if (showSecurity) {
        ModalBottomSheet(
            onDismissRequest = { showSecurity = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.background,
            shape = Shapes.sheet
        ) {
            Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(Modifier.padding(horizontal = Space.screen)) {
                    Text(stringResource(R.string.settings_security_header), style = PosType.title, color = palette.label)
                }
                InsetGroup(footer = stringResource(R.string.settings_security_note)) {
                    row { ListRow(title = stringResource(R.string.settings_security_fingerprint), subtitle = keyFingerprint) }
                    row { ListRow(title = stringResource(R.string.settings_security_algorithm), value = "ECDSA P-256") }
                    row { ListRow(title = stringResource(R.string.settings_security_storage), value = stringResource(R.string.settings_security_storage_value)) }
                }
                Spacer(Modifier.height(Space.s))
                TextAction(stringResource(R.string.action_done), onClick = { showSecurity = false }, modifier = Modifier.padding(horizontal = Space.screen))
            }
        }
    }
}
