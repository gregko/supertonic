package com.brahmadeo.supertonic.tts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LicenseDocument(
    val title: String,
    val summary: String,
    val assetPath: String
)

private val licenseDocuments = listOf(
    LicenseDocument(
        title = "Third-Party Notices",
        summary = "Overview of the major bundled third-party components and where their license texts are included.",
        assetPath = "licenses/THIRD_PARTY_NOTICES.md"
    ),
    LicenseDocument(
        title = "Supertonic Source Code",
        summary = "Upstream Supertonic source code license from Supertone Inc.",
        assetPath = "licenses/files/SUPERTONIC_SOURCE_CODE_LICENSE.txt"
    ),
    LicenseDocument(
        title = "Supertonic 2 Model Assets",
        summary = "Model asset license and use restrictions for bundled Supertonic 2 voices and ONNX assets.",
        assetPath = "licenses/files/SUPERTONIC_2_MODEL_LICENSE.txt"
    ),
    LicenseDocument(
        title = "ONNX Runtime Android",
        summary = "License text for the Microsoft ONNX Runtime Android binaries bundled with this fork.",
        assetPath = "licenses/files/ONNXRUNTIME_LICENSE.txt"
    ),
    LicenseDocument(
        title = "ort Rust Crate (MIT)",
        summary = "MIT license text for the vendored ort Rust crate.",
        assetPath = "licenses/files/ORT_CRATE_LICENSE_MIT.txt"
    ),
    LicenseDocument(
        title = "ort Rust Crate (Apache-2.0)",
        summary = "Apache-2.0 license text for the vendored ort Rust crate.",
        assetPath = "licenses/files/ORT_CRATE_LICENSE_APACHE_2.0.txt"
    )
)

@Composable
fun LicensesScreen(
    onBackClick: () -> Unit
) {
    var selectedDocument by remember { mutableStateOf<LicenseDocument?>(null) }

    BackHandler(enabled = selectedDocument != null) {
        selectedDocument = null
    }

    if (selectedDocument == null) {
        LicenseListScreen(
            onBackClick = onBackClick,
            onDocumentClick = { selectedDocument = it }
        )
    } else {
        LicenseDetailScreen(
            document = selectedDocument!!,
            onBackClick = { selectedDocument = null }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LicenseListScreen(
    onBackClick: () -> Unit,
    onDocumentClick: (LicenseDocument) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licenses") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Open-source notices and bundled model/runtime licenses included with this Android fork.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(licenseDocuments) { document ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDocumentClick(document) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = document.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = document.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LicenseDetailScreen(
    document: LicenseDocument,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var content by remember(document.assetPath) { mutableStateOf<String?>(null) }
    var loadError by remember(document.assetPath) { mutableStateOf<String?>(null) }

    LaunchedEffect(document.assetPath) {
        loadError = null
        content = null
        try {
            content = withContext(Dispatchers.IO) {
                context.assets.open(document.assetPath).bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Unknown error"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        when {
            loadError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Failed to load license document:\n$loadError",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            content == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                SelectionContainer {
                    Text(
                        text = content.orEmpty(),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
