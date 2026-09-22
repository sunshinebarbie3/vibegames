@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.app.R
import com.vibe.app.domain.FieldError
import com.vibe.app.ui.theme.VibeCoral
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint

/**
 * Shared VIBE building blocks.
 *
 * Two rules from the design system are enforced here rather than repeated in
 * every screen: an activity icon is never shown without its text label, and a
 * screen has exactly one coral primary action.
 */

@Composable
fun VibeTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.secondary,
        ),
    )
}

@Composable
fun VibeCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VibeCoral,
            contentColor = Color.White,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = { onAction?.invoke() }) {
                Text(text = action, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun GroupRow(
    icon: String,
    name: String,
    detail: String,
    onClick: () -> Unit,
) {
    val bg = when {
        name.contains("Friday") || icon.contains("🎉") -> VibeCoral.copy(alpha = 0.15f)
        name.contains("Girls") || icon.contains("💖") -> VibeLilac.copy(alpha = 0.2f)
        else -> VibeMint.copy(alpha = 0.2f)
    }
    VibeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = icon, fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = "›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An activity card: icon and text always travel together. */
@Composable
fun ActivityRow(
    icon: String,
    title: String,
    subtitle: String,
    participantCount: Int? = null,
    statusLabel: String? = null,
    favourite: Boolean = false,
    onFavouriteToggle: (() -> Unit)? = null,
    eliminated: Boolean = false,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = !eliminated,
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
    ) {
        VibeCard(onClick = onClick) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = icon, fontSize = 22.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (statusLabel != null) {
                        StatusPill(label = statusLabel)
                    }
                }
                if (participantCount != null && participantCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    ) {
                        Text(text = "👤", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = participantCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onFavouriteToggle != null) {
                    IconButton(onClick = onFavouriteToggle) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = stringResource(
                                if (favourite) R.string.activity_unfavourite else R.string.activity_favourite,
                            ),
                            tint = if (favourite) VibeCoral else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusPill(label: String, confirmed: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (confirmed) VibeMint.copy(alpha = 0.22f) else VibeLilac.copy(alpha = 0.22f),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = if (confirmed) VibeMint else VibeLilac,
        )
    }
}

@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) VibeCoral else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, color = VibeLilac)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun AvatarCircle(initials: String, size: Int = 40, highlight: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (highlight) VibeCoral.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials.take(2).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun MemberAvatars(names: List<String>, max: Int = 4) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        names.take(max).forEach { name ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (names.size > max) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(VibeLilac.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+${names.size - max}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VibeCoral)
    }
}

@Composable
fun OfflineBanner(pendingCount: Int, offline: Boolean, modifier: Modifier = Modifier) {
    val visible = offline || pendingCount > 0
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            color = if (offline) VibeLilac.copy(alpha = 0.25f) else VibeMint.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (offline) {
                    stringResource(R.string.state_offline_detail)
                } else {
                    stringResource(R.string.state_saved_offline)
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun ProgressRow(label: String, progress: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = VibeMint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
fun StarRating(rating: Int, onRate: ((Int) -> Unit)? = null) {
    Row {
        (1..5).forEach { star ->
            IconButton(onClick = { onRate?.invoke(star) }, enabled = onRate != null) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.cd_star, star),
                    tint = if (star <= rating) VibeLilac else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
fun NotificationBell(unreadCount: Int, onClick: () -> Unit) {
    if (unreadCount > 0) {
        BadgedBox(badge = { Badge { Text(unreadCount.toString()) } }) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = stringResource(R.string.home_notifications),
                )
            }
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = stringResource(R.string.home_notifications),
            )
        }
    }
}

enum class VibeTab { HOME, ACTIVITIES, ADD, GROUPS, PROFILE, MEMORIES }

fun onSelectTab(
    tab: VibeTab,
    onHome: () -> Unit,
    onActivities: () -> Unit = {},
    onGroups: () -> Unit = {},
    onProfile: () -> Unit = {},
    onMemories: () -> Unit = {},
    onAdd: () -> Unit = {},
) {
    when (tab) {
        VibeTab.HOME -> onHome()
        VibeTab.ACTIVITIES -> onActivities()
        VibeTab.GROUPS -> onGroups()
        VibeTab.PROFILE -> onProfile()
        VibeTab.MEMORIES -> onMemories()
        VibeTab.ADD -> onAdd()
    }
}

@Composable
fun DottedOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.5.dp, VibeCoral.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = VibeCoral,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun VibeBottomBar(
    selected: VibeTab,
    onSelect: (VibeTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        NavigationBarItem(
            selected = selected == VibeTab.HOME,
            onClick = { onSelect(VibeTab.HOME) },
            icon = { Text(text = "🏠", fontSize = 18.sp) },
            label = { Text(stringResource(R.string.cd_nav_home)) },
            colors = vibeNavColors(),
        )
        NavigationBarItem(
            selected = selected == VibeTab.ACTIVITIES,
            onClick = { onSelect(VibeTab.ACTIVITIES) },
            icon = { Text(text = "📋", fontSize = 18.sp) },
            label = { Text(stringResource(R.string.cd_nav_activities)) },
            colors = vibeNavColors(),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable { onSelect(VibeTab.ADD) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = VibeCoral,
                modifier = Modifier.size(46.dp),
                shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.cd_add),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        NavigationBarItem(
            selected = selected == VibeTab.GROUPS,
            onClick = { onSelect(VibeTab.GROUPS) },
            icon = { Text(text = "👥", fontSize = 18.sp) },
            label = { Text(stringResource(R.string.cd_nav_groups)) },
            colors = vibeNavColors(),
        )
        NavigationBarItem(
            selected = selected == VibeTab.PROFILE,
            onClick = { onSelect(VibeTab.PROFILE) },
            icon = { Text(text = "👤", fontSize = 18.sp) },
            label = { Text(stringResource(R.string.cd_nav_profile)) },
            colors = vibeNavColors(),
        )
    }
}

@Composable
private fun vibeNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = VibeCoral,
    selectedTextColor = VibeCoral,
    indicatorColor = VibeCoral.copy(alpha = 0.15f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Maps a domain validation error to the copy the member should read. */
@Composable
fun FieldError.text(): String = when (this) {
    FieldError.NAME_REQUIRED -> stringResource(R.string.error_name_required)
    FieldError.NAME_TOO_LONG -> stringResource(R.string.error_name_required)
    FieldError.EMAIL_INVALID -> stringResource(R.string.error_email_invalid)
    FieldError.PASSWORD_SHORT -> stringResource(R.string.error_password_short)
    FieldError.PASSWORD_DIGIT -> stringResource(R.string.error_password_digit)
    FieldError.PASSWORD_MISMATCH -> stringResource(R.string.error_password_mismatch)
    FieldError.CURRENT_PASSWORD_REQUIRED -> stringResource(R.string.error_current_password)
    FieldError.GROUP_NAME_REQUIRED -> stringResource(R.string.error_group_name)
    FieldError.ACTIVITY_TITLE_REQUIRED -> stringResource(R.string.error_activity_title)
    FieldError.INVITE_CODE_INVALID -> stringResource(R.string.error_group_code)
    FieldError.CAPTION_TOO_LONG -> stringResource(R.string.error_generic)
    FieldError.RATING_OUT_OF_RANGE -> stringResource(R.string.error_generic)
    FieldError.NOT_ENOUGH_ACTIVITIES -> stringResource(R.string.error_activities_needed)
    FieldError.OWNER_ONLY -> stringResource(R.string.error_owner_only)
}

@Composable
fun ErrorText(error: FieldError?) {
    if (error == null) return
    Text(
        text = error.text(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun ErrorText(message: String?) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun LabelValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    VibeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Text(text = "›", fontSize = 24.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(text = "V", style = MaterialTheme.typography.displaySmall, color = VibeCoral)
        Text(text = "I", style = MaterialTheme.typography.displaySmall, color = VibeLilac)
        Text(text = "B", style = MaterialTheme.typography.displaySmall, color = VibeMint)
        Text(text = "E", style = MaterialTheme.typography.displaySmall, color = VibeCoral)
    }
}

@Composable
fun TaglineText(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.tagline),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
fun BoldTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = modifier)
}
