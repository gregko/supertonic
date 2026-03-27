package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R

@Composable
fun AboutDialog(
    appName: String,
    versionName: String,
    repoUrl: String,
    upstreamRepoLabel: String,
    upstreamRepoUrl: String,
    onDismiss: () -> Unit,
    onOpenLicenses: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.about_version_fmt, versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.about_repo_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(repoUrl) },
                        modifier = Modifier.padding(horizontal = 0.dp)
                    ) {
                        Text(repoUrl)
                    }
                    Text(
                        text = stringResource(R.string.about_forked_from_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(upstreamRepoUrl) },
                        modifier = Modifier.padding(horizontal = 0.dp)
                    ) {
                        Text(
                            text = upstreamRepoLabel,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenLicenses()
                }
            ) {
                Text(stringResource(R.string.action_licenses))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
