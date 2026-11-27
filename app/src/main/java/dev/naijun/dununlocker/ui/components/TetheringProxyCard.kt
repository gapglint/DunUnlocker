package dev.naijun.dununlocker.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.naijun.dununlocker.R
import dev.naijun.dununlocker.proxy.ProxyEndpoint
import dev.naijun.dununlocker.proxy.ProxyEndpointKind
import dev.naijun.dununlocker.proxy.TetheringProxyService
import dev.naijun.dununlocker.proxy.TetheringProxyState
import kotlinx.coroutines.delay

@Composable
fun TetheringProxyCard(
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val proxyState by TetheringProxyState.state.collectAsState()
    var portInput by remember {
        mutableStateOf(TetheringProxyState.loadSavedPort(context).toString())
    }

    LaunchedEffect(Unit) {
        while (true) {
            TetheringProxyState.refreshEndpoints()
            delay(3_000L)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                TetheringProxyState.refreshEndpoints()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(proxyState.errorMessage) {
        proxyState.errorMessage?.let { msg ->
            onMessage(msg)
            TetheringProxyState.clearError()
        }
    }

    fun parsePort(): Int? = portInput.trim().toIntOrNull()?.takeIf { it in 1024..65535 }

    val startProxy = {
        val port = parsePort()
        if (port == null) {
            onMessage(context.getString(R.string.proxy_invalid_port))
        } else {
            TetheringProxyService.start(context, port)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        startProxy()
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        onMessage(context.getString(R.string.proxy_copied_message, value))
    }

    val activePort = if (proxyState.isRunning) proxyState.port else (parsePort() ?: proxyState.port)
    val primaryEndpoint = proxyState.endpoints.firstOrNull()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.WifiTethering,
                    contentDescription = null,
                    tint = if (proxyState.isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.proxy_card_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.proxy_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = proxyState.isRunning,
                    onCheckedChange = { enable ->
                        if (enable) {
                            val hasNotifPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasNotifPerm) {
                                startProxy()
                            } else {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            TetheringProxyService.stop(context)
                        }
                    }
                )
            }

            // Always show detected device/tethering IP clearly
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = if (proxyState.isRunning) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (proxyState.isRunning) {
                                    stringResource(R.string.proxy_active_status, proxyState.activeConnections)
                                } else {
                                    stringResource(R.string.proxy_device_ip_label)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (proxyState.isRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (primaryEndpoint != null) {
                                Text(
                                    text = primaryEndpoint.ip,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.proxy_ip_interface_format,
                                        stringResource(primaryEndpoint.kind.labelRes),
                                        primaryEndpoint.interfaceName
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.proxy_ip_not_detected),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (primaryEndpoint != null) {
                            IconButton(onClick = { copyToClipboard("device_ip", primaryEndpoint.ip) }) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.proxy_device_ip_label),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(onClick = { TetheringProxyState.refreshEndpoints() }) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.proxy_refresh_ip)
                            )
                        }
                    }

                    if (proxyState.endpoints.size > 1) {
                        Text(
                            text = stringResource(
                                R.string.proxy_extra_ips,
                                proxyState.endpoints.drop(1).joinToString(", ") {
                                    "${it.ip} (${it.interfaceName})"
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = proxyState.isRunning && primaryEndpoint != null) {
                        primaryEndpoint?.let { ep ->
                            val portalUrl = "http://${ep.ip}:$activePort"
                            val pacUrl = "http://${ep.ip}:$activePort/proxy.pac"
                            val rawAddr = "${ep.ip}:$activePort"
                            Column(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                                ProxyEndpointRow(
                                    label = stringResource(R.string.proxy_portal_url_label),
                                    value = portalUrl,
                                    onCopy = { copyToClipboard("portal_url", portalUrl) }
                                )
                                ProxyEndpointRow(
                                    label = stringResource(R.string.proxy_pac_url_label),
                                    value = pacUrl,
                                    onCopy = { copyToClipboard("pac_url", pacUrl) }
                                )
                                ProxyEndpointRow(
                                    label = stringResource(R.string.proxy_manual_addr_label),
                                    value = rawAddr,
                                    onCopy = { copyToClipboard("proxy_addr", rawAddr) }
                                )
                                Text(
                                    text = stringResource(R.string.proxy_setup_guide),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (proxyState.isRunning && primaryEndpoint == null) {
                        Text(
                            text = stringResource(R.string.proxy_no_hotspot_ip, activePort),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedTextField(
                value = portInput,
                onValueChange = { input ->
                    if (input.length <= 5 && input.all(Char::isDigit)) {
                        portInput = input
                        input.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                            TetheringProxyState.savePort(context, it)
                        }
                    }
                },
                enabled = !proxyState.isRunning,
                label = { Text(stringResource(R.string.proxy_port_label)) },
                supportingText = { Text(stringResource(R.string.proxy_port_hint, TetheringProxyState.DEFAULT_PORT)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private val ProxyEndpointKind.labelRes: Int
    @StringRes get() = when (this) {
        ProxyEndpointKind.HOTSPOT -> R.string.proxy_kind_hotspot
        ProxyEndpointKind.USB_TETHER -> R.string.proxy_kind_usb
        ProxyEndpointKind.BLUETOOTH_TETHER -> R.string.proxy_kind_bluetooth
        ProxyEndpointKind.WIFI -> R.string.proxy_kind_wifi
        ProxyEndpointKind.OTHER -> R.string.proxy_kind_other
    }

@Composable
private fun ProxyEndpointRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = label,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
