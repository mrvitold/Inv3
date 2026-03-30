package com.vitol.inv3.ui.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vitol.inv3.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guide_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GuideSection(
                step = 1,
                title = stringResource(R.string.guide_step1_title),
                icon = Icons.Default.Business,
                content = stringResource(R.string.guide_step1_body)
            )

            GuideSection(
                step = 2,
                title = stringResource(R.string.guide_step2_title),
                icon = Icons.Default.CameraAlt,
                content = stringResource(R.string.guide_step2_body)
            )

            GuideSection(
                step = 3,
                title = stringResource(R.string.guide_step3_title),
                icon = Icons.Default.Upload,
                content = stringResource(R.string.guide_step3_body)
            )

            GuideSection(
                step = 4,
                title = stringResource(R.string.guide_step4_title),
                icon = Icons.Default.Edit,
                content = stringResource(R.string.guide_step4_body)
            )

            GuideSection(
                step = 5,
                title = stringResource(R.string.guide_step5_title),
                icon = Icons.Default.FileDownload,
                content = stringResource(R.string.guide_step5_body)
            )

            GuideSection(
                step = 6,
                title = stringResource(R.string.guide_step6_title),
                icon = null,
                content = stringResource(R.string.guide_step6_body),
                imageResId = null
            )
        }
    }
}

@Composable
private fun GuideSection(
    step: Int,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    content: String,
    imageResId: Int? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "$step. $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            imageResId?.let { resId ->
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    painter = painterResource(resId),
                    contentDescription = stringResource(R.string.cd_guide_isaf_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
