package com.aura.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.R
import com.aura.identity.IdentityManager
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: AuroraSettings,
    private val identityManager: IdentityManager
) : ViewModel() {

    private val _ready = MutableStateFlow(false)
    /** True once the device identity exists — generated while the user reads onboarding. */
    val ready: StateFlow<Boolean> = _ready

    init {
        viewModelScope.launch {
            identityManager.getOrCreate()
            _ready.value = true
        }
    }

    fun markDone() {
        settings.onboardingDone = true
    }
}

private data class OnboardingPage(
    val title: String?,
    val body: String,
    val closing: String? = null,
    val isWelcome: Boolean = false
)

private val onboardingPages = listOf(
    OnboardingPage(
        title = null,
        body = "Private messaging without compromise.",
        isWelcome = true
    ),
    OnboardingPage(
        title = "What is Aurora?",
        body = "Aurora is a private messenger built on one simple belief — some conversations belong only to the people having them.\n\n" +
            "In a world where every message you send passes through a corporation's servers, gets stored in a database, and becomes data to be analyzed, sold, or subpoenaed — Aurora is different.\n\n" +
            "Your messages travel directly from your phone to theirs. No company reads them. No server stores them. No one is listening."
    ),
    OnboardingPage(
        title = "What We Cannot See",
        body = "We want to be precise about this because most apps are not.\n\n" +
            "We cannot read your messages.\n" +
            "We cannot see who you are talking to.\n" +
            "We cannot see what media you share.\n" +
            "We keep no logs of your activity or presence.\n" +
            "We do not know your name, your number, or your face.\n\n" +
            "To deliver messages and calls even when the app is closed, your phone holds a lightweight connection to a relay whose only job is to wake the other person's device. The relay can tell that a device is reachable, but it never sees your messages, your media, or your identity, and it keeps no record of any of it. The conversation itself always travels directly between phones.\n\n" +
            "If someone came to us with a court order demanding your conversations — we could not hand them over. Not because we would refuse. Because they do not exist anywhere but on your phone."
    ),
    OnboardingPage(
        title = "Who Aurora Is For",
        body = "Aurora is for the couple who wants their intimate conversations to stay intimate. The family that wants to share without a corporation watching. The friends who want to talk freely. The person who simply believes that a private conversation should be private.\n\n" +
            "You don't need to be afraid of anything to want privacy. Privacy is not the opposite of having nothing to hide. Privacy is the foundation of every meaningful human relationship.",
        closing = "Aurora. Protecting your loved ones."
    )
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onOpenLegal: (String) -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val ready by viewModel.ready.collectAsState()
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()
    val lastIndex = onboardingPages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp)
    ) {
        // Skip → jump to the final page.
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Spacer(Modifier.weight(1f))
            if (pagerState.currentPage < lastIndex) {
                TextButton(onClick = { scope.launch { pagerState.scrollToPage(lastIndex) } }) {
                    Text("Skip")
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { index ->
            PageContent(onboardingPages[index])
        }

        // Page dots
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(onboardingPages.size) { i ->
                val active = i == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            if (pagerState.currentPage < lastIndex) {
                Button(
                    onClick = { scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Continue") }
            } else {
                if (!ready) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Generating your secure identity…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.markDone(); onDone() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Get started") }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "By tapping Get started, you agree to our",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { onOpenLegal("terms") }) { Text("Terms & Conditions") }
                            Text(
                                "and",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { onOpenLegal("privacy") }) { Text("Privacy Policy") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = if (page.isWelcome) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        if (page.isWelcome) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(140.dp).clip(CircleShape).background(Color(0xFFF8F2F6))
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Aurora",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                page.body,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            page.title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(
                page.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            page.closing?.let {
                Spacer(Modifier.height(28.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
