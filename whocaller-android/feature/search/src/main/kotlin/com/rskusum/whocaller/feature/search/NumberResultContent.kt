package com.rskusum.whocaller.feature.search

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.core.domain.usecase.NumberLookup
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.model.InfoSource
import com.rskusum.whocaller.core.model.NumberType
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.RiskBadge
import com.rskusum.whocaller.core.ui.component.VerifiedBadge
import com.rskusum.whocaller.core.ui.component.WarningBanner
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions
import com.rskusum.whocaller.core.ui.util.labelRes
import com.rskusum.whocaller.core.ui.util.messageRes
import com.rskusum.whocaller.core.ui.util.relativeTime
import java.util.Locale
import com.rskusum.whocaller.core.ui.R as UiR

/**
 * Search result. Every value shown comes from the lookup; when something isn't known the screen
 * says so instead of guessing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NumberResultContent(
    lookup: NumberLookup,
    onReport: () -> Unit,
    onToggleBlock: () -> Unit,
    onOpenBusiness: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val info = lookup.info
    val number = lookup.number
    val dialable = number.e164 ?: number.raw
    val name = lookup.contactName ?: info?.displayName
    val isDemo = name?.startsWith("[Demo]") == true
    val hasWhatsApp = androidx.compose.runtime.remember { TelecomActions.whatsAppPackage(context) != null }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        lookup.remoteError?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.result_offline, stringResource(it.messageRes())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        when (lookup.label) {
            CallerLabel.POSSIBLE_SCAM -> WarningBanner(
                title = stringResource(UiR.string.label_possible_scam),
                message = info?.reportCount?.takeIf { it > 0 }?.let { reportsText(it) },
                severe = true,
            )
            CallerLabel.SUSPECTED_SPAM, CallerLabel.TELEMARKETING -> WarningBanner(
                title = stringResource(if (info?.flaggedOnlyByList == true) UiR.string.label_possible_spam_list else lookup.label.labelRes()),
                message = if (info?.flaggedOnlyByList == true) {
                    stringResource(UiR.string.label_flagged_by_list, info?.listedBy.orEmpty())
                } else {
                    info?.reportCount?.takeIf { it > 0 }?.let { reportsText(it) }
                },
                severe = false,
            )
            else -> Unit
        }
        if (lookup.label == CallerLabel.POSSIBLE_SCAM || lookup.label == CallerLabel.SUSPECTED_SPAM || lookup.label == CallerLabel.TELEMARKETING) {
            Spacer(Modifier.height(12.dp))
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CallerAvatar(name, lookup.label, size = 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name ?: stringResource(R.string.result_no_identity),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                            )
                            if (info?.whoCallerVerified == true && name != null) {
                                Spacer(Modifier.width(6.dp))
                                com.rskusum.whocaller.core.ui.component.VerifiedTick(20.dp, description = stringResource(UiR.string.label_verified_id))
                            }
                        }
                        Text(number.display, style = MaterialTheme.typography.bodyLarge)
                        if (info?.verified == true) VerifiedBadge()
                    }
                }
                if (isDemo) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(UiR.string.source_demo_notice), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (name == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.result_no_identity_desc), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                Field(stringResource(R.string.result_phone_number), number.display)
                Field(
                    stringResource(R.string.result_possible_identity),
                    when {
                        lookup.contactName != null -> stringResource(R.string.result_your_contact)
                        info?.identityType == IdentityType.BUSINESS -> stringResource(R.string.result_identity_business)
                        info?.identityType == IdentityType.PERSON -> stringResource(R.string.result_identity_person)
                        else -> stringResource(R.string.result_identity_unknown)
                    },
                )
                if (lookup.score.category != SpamCategory.UNKNOWN && lookup.score.category != SpamCategory.SAFE) {
                    Field(stringResource(R.string.result_category), stringResource(lookup.score.category.labelRes()))
                }
                if (lookup.contactName == null) {
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.result_spam_risk),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RiskBadge(lookup.score)
                    }
                }
                if (info != null && info.reportCount > 0) {
                    Field(stringResource(R.string.result_community_reports), info.reportCount.toString())
                }
                info?.lastReportedAt?.let { Field(stringResource(R.string.result_last_reported), relativeTime(it)) }
                info?.carrier?.let { Field(stringResource(R.string.result_carrier), it) }
                val type = info?.lineType ?: number.type
                if (type != NumberType.UNKNOWN) Field(stringResource(R.string.result_line_type), stringResource(typeRes(type)))
                (number.regionCode ?: info?.regionCode)?.let { region ->
                    Field(stringResource(R.string.result_country), Locale("", region).displayCountry)
                }
                val source = when {
                    lookup.contactName != null -> R.string.source_contacts
                    info?.source == InfoSource.SERVER -> R.string.source_server
                    info != null -> R.string.source_cache
                    else -> null
                }
                if (source != null && (name != null || lookup.score.hasEvidence)) {
                    Text(
                        stringResource(R.string.result_source, stringResource(source)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        lookup.myReport?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.result_you_reported, stringResource(it.reason.labelRes())), style = MaterialTheme.typography.bodyMedium)
        }
        if (lookup.isBlocked) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.result_blocked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        info?.businessId?.let { id ->
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onOpenBusiness(id) }) {
                Icon(Icons.Outlined.Storefront, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.result_view_business))
            }
        }

        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(Icons.Outlined.Call, stringResource(UiR.string.action_call)) { ActionIntents.dial(context, dialable) }
            Action(Icons.AutoMirrored.Outlined.Message, stringResource(UiR.string.action_message)) { ActionIntents.message(context, dialable) }
            // WHOCALLER VIDEO (experimental)
            val video = com.rskusum.whocaller.core.ui.component.rememberWhoCallerVideo(dialable, lookup.contactName ?: info?.displayName?.takeUnless { isDemo })
            Action(Icons.Outlined.Videocam, stringResource(UiR.string.action_whocaller_video), dimmed = !video.available, onClick = video.onClick)
            if (hasWhatsApp) {
                Action(Icons.AutoMirrored.Outlined.Chat, stringResource(UiR.string.action_whatsapp)) { TelecomActions.openWhatsApp(context, dialable) }
            }
            if (lookup.contactName == null) {
                Action(Icons.Outlined.PersonAdd, stringResource(UiR.string.action_save_contact)) {
                    ActionIntents.saveContact(context, dialable, info?.displayName?.takeUnless { isDemo })
                }
            }
            Action(Icons.Outlined.Block, stringResource(if (lookup.isBlocked) UiR.string.action_unblock else UiR.string.action_block), onClick = onToggleBlock)
            Action(Icons.Outlined.Flag, stringResource(UiR.string.action_report), onClick = onReport)
            Action(Icons.Outlined.Share, stringResource(UiR.string.action_share)) {
                ActionIntents.shareText(context, context.getString(UiR.string.share_number_text, number.display))
            }
        }
    }
}

@Composable
private fun reportsText(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.search_reported_by, count, count)

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, dimmed: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.alpha(if (dimmed) 0.4f else 1f)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

private fun typeRes(type: NumberType): Int = when (type) {
    NumberType.MOBILE -> R.string.type_mobile
    NumberType.FIXED_LINE -> R.string.type_fixed_line
    NumberType.FIXED_LINE_OR_MOBILE -> R.string.type_fixed_or_mobile
    NumberType.TOLL_FREE -> R.string.type_toll_free
    NumberType.PREMIUM_RATE -> R.string.type_premium
    NumberType.VOIP -> R.string.type_voip
    else -> R.string.type_other
}
