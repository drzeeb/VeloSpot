package de.velospot.feature.analysis.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.analysis.Achievement
import de.velospot.core.analysis.AchievementId

/** Visuals (icon + colour) for a badge; the title comes from a string resource. */
private data class BadgeVisual(val icon: ImageVector, val color: Color)

private val GOLD = Color(0xFFF6A623)

private fun visualFor(id: AchievementId): BadgeVisual = when (id) {
    AchievementId.HALF_CENTURY, AchievementId.CENTURY ->
        BadgeVisual(Icons.Filled.Route, Color(0xFF1565C0))
    AchievementId.HILL_CLIMBER, AchievementId.SUMMIT_SEEKER ->
        BadgeVisual(Icons.Filled.Terrain, Color(0xFF2E7D32))
    AchievementId.KING_OF_THE_MOUNTAIN ->
        BadgeVisual(Icons.Filled.MilitaryTech, Color(0xFF8E24AA))
    AchievementId.SPEED_DEMON ->
        BadgeVisual(Icons.Filled.Bolt, Color(0xFFEF6C00))
    AchievementId.ENDURANCE ->
        BadgeVisual(Icons.Filled.Timer, Color(0xFF00897B))
    AchievementId.CALORIE_CRUSHER ->
        BadgeVisual(Icons.Filled.LocalFireDepartment, Color(0xFFE53935))
    AchievementId.EARLY_BIRD ->
        BadgeVisual(Icons.Filled.WbTwilight, Color(0xFFF9A825))
    AchievementId.NIGHT_OWL ->
        BadgeVisual(Icons.Filled.DarkMode, Color(0xFF3949AB))
    AchievementId.PR_DISTANCE, AchievementId.PR_CLIMBING,
    AchievementId.PR_PACE, AchievementId.PR_TOP_SPEED ->
        BadgeVisual(Icons.Filled.EmojiEvents, GOLD)
}

private fun titleResFor(id: AchievementId): Int = when (id) {
    AchievementId.HALF_CENTURY -> R.string.ach_half_century
    AchievementId.CENTURY -> R.string.ach_century
    AchievementId.HILL_CLIMBER -> R.string.ach_hill_climber
    AchievementId.SUMMIT_SEEKER -> R.string.ach_summit_seeker
    AchievementId.KING_OF_THE_MOUNTAIN -> R.string.ach_kom
    AchievementId.SPEED_DEMON -> R.string.ach_speed_demon
    AchievementId.ENDURANCE -> R.string.ach_endurance
    AchievementId.CALORIE_CRUSHER -> R.string.ach_calorie_crusher
    AchievementId.EARLY_BIRD -> R.string.ach_early_bird
    AchievementId.NIGHT_OWL -> R.string.ach_night_owl
    AchievementId.PR_DISTANCE -> R.string.ach_pr_distance
    AchievementId.PR_CLIMBING -> R.string.ach_pr_climbing
    AchievementId.PR_PACE -> R.string.ach_pr_pace
    AchievementId.PR_TOP_SPEED -> R.string.ach_pr_top_speed
}

/** A wrap-around grid of earned badge chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AchievementsRow(achievements: List<Achievement>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        achievements.forEach { AchievementBadge(it) }
    }
}

@Composable
private fun AchievementBadge(achievement: Achievement) {
    val visual = visualFor(achievement.id)
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .then(
                if (achievement.isPersonalRecord)
                    Modifier.border(1.5.dp, GOLD, RoundedCornerShape(16.dp))
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(visual.color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(titleResFor(achievement.id)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val subtitle = if (achievement.isPersonalRecord) {
                    stringResource(R.string.ach_personal_record) +
                        (achievement.value?.let { " · $it" } ?: "")
                } else {
                    achievement.value
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (achievement.isPersonalRecord) GOLD
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

