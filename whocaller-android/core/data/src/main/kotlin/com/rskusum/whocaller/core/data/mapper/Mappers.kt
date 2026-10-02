package com.rskusum.whocaller.core.data.mapper

import com.rskusum.whocaller.core.database.entity.BlockedCallEntity
import com.rskusum.whocaller.core.database.entity.BlockedNumberEntity
import com.rskusum.whocaller.core.database.entity.BusinessEntity
import com.rskusum.whocaller.core.database.entity.CallHistoryEntity
import com.rskusum.whocaller.core.database.entity.CallerEntity
import com.rskusum.whocaller.core.database.entity.SearchHistoryEntity
import com.rskusum.whocaller.core.database.entity.SpamReportEntity
import com.rskusum.whocaller.core.model.BlockSource
import com.rskusum.whocaller.core.model.BlockedCall
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.Business
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.model.IdentifiedCall
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.model.InfoSource
import com.rskusum.whocaller.core.model.NumberType
import com.rskusum.whocaller.core.model.ReportCategory
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SearchHistoryItem
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.SyncState
import com.rskusum.whocaller.core.network.model.BusinessDto
import com.rskusum.whocaller.core.network.model.NumberInfoDto

internal inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
    value?.let { v -> enumValues<T>().firstOrNull { it.name.equals(v, ignoreCase = true) } }

internal fun encodeVotes(votes: Map<SpamCategory, Int>): String =
    votes.filterValues { it > 0 }.entries.joinToString(",") { "${it.key.name}:${it.value}" }

internal fun decodeVotes(value: String): Map<SpamCategory, Int> =
    value.split(',').mapNotNull { part ->
        val pieces = part.split(':')
        if (pieces.size != 2) return@mapNotNull null
        val category = enumOrNull<SpamCategory>(pieces[0]) ?: return@mapNotNull null
        pieces[1].toIntOrNull()?.let { category to it }
    }.toMap()

fun NumberInfoDto.toCallerInfo(numberKey: String, now: Long): CallerInfo = CallerInfo(
    numberKey = numberKey,
    displayName = name?.trim()?.takeIf { it.isNotEmpty() },
    identityType = enumOrNull<IdentityType>(identityType) ?: IdentityType.UNKNOWN,
    category = SpamCategory.fromWire(category),
    serverScore = spamScore?.coerceIn(0, 100),
    serverConfidence = confidence?.coerceIn(0f, 1f),
    reportCount = reportCount.coerceAtLeast(0),
    reportsLast24h = reportsLast24h.coerceAtLeast(0),
    reportsLast7d = reportsLast7d.coerceAtLeast(0),
    blockCount = blockCount.coerceAtLeast(0),
    lastReportedAt = lastReportedAt,
    categoryVotes = categoryVotes.mapNotNull { (k, v) -> enumOrNull<SpamCategory>(k)?.let { it to v } }.toMap(),
    verified = verified,
    businessId = businessId,
    carrier = carrier,
    lineType = enumOrNull<NumberType>(lineType),
    regionCode = region,
    source = InfoSource.SERVER,
    updatedAt = now,
    listedBy = listedBy?.take(60),
    whoCallerVerified = whoCallerVerified,
)

fun CallerInfo.toEntity(fromSpamList: Boolean = false) = CallerEntity(
    phoneNumber = numberKey,
    displayName = displayName,
    identityType = identityType.name,
    category = category.name,
    spamScore = serverScore ?: -1,
    serverConfidence = serverConfidence,
    reportCount = reportCount,
    reportsLast24h = reportsLast24h,
    reportsLast7d = reportsLast7d,
    blockCount = blockCount,
    lastReportedAt = lastReportedAt,
    categoryVotes = encodeVotes(categoryVotes),
    verified = verified,
    businessId = businessId,
    carrier = carrier,
    lineType = lineType?.name,
    regionCode = regionCode,
    fromSpamList = fromSpamList,
    updatedAt = updatedAt,
    listedBy = listedBy,
    whoCallerVerified = whoCallerVerified,
)

fun CallerEntity.toModel() = CallerInfo(
    numberKey = phoneNumber,
    displayName = displayName,
    identityType = enumOrNull<IdentityType>(identityType) ?: IdentityType.UNKNOWN,
    category = SpamCategory.fromWire(category),
    serverScore = spamScore.takeIf { it >= 0 },
    serverConfidence = serverConfidence,
    reportCount = reportCount,
    reportsLast24h = reportsLast24h,
    reportsLast7d = reportsLast7d,
    blockCount = blockCount,
    lastReportedAt = lastReportedAt,
    categoryVotes = decodeVotes(categoryVotes),
    verified = verified,
    businessId = businessId,
    carrier = carrier,
    lineType = enumOrNull<NumberType>(lineType),
    regionCode = regionCode,
    source = InfoSource.LOCAL_CACHE,
    updatedAt = updatedAt,
    listedBy = listedBy,
    whoCallerVerified = whoCallerVerified,
)

fun SpamReportEntity.toModel() = SpamReport(
    id = id,
    numberKey = numberKey,
    reason = ReportReason.fromWire(reason),
    comment = comment,
    createdAt = createdAt,
    syncState = enumOrNull<SyncState>(syncState) ?: SyncState.PENDING,
    categories = ReportCategory.parseList(categories),
)

fun BlockedNumberEntity.toModel() = BlockedNumber(
    numberKey = numberKey,
    displayNumber = displayNumber,
    label = label,
    category = enumOrNull<SpamCategory>(category),
    blockedAt = blockedAt,
    source = enumOrNull<BlockSource>(source) ?: BlockSource.USER,
)

fun BlockedNumber.toEntity() = BlockedNumberEntity(
    numberKey = numberKey,
    displayNumber = displayNumber,
    label = label,
    category = category?.name,
    blockedAt = blockedAt,
    source = source.name,
)

fun BlockedCallEntity.toModel() = BlockedCall(
    id = id,
    numberKey = numberKey,
    displayNumber = displayNumber,
    reason = enumOrNull<DecisionReason>(reason) ?: DecisionReason.NONE,
    category = SpamCategory.fromWire(category),
    timestamp = timestamp,
)

fun BlockedCall.toEntity() = BlockedCallEntity(
    numberKey = numberKey,
    displayNumber = displayNumber,
    reason = reason.name,
    category = category.name,
    timestamp = timestamp,
)

fun CallHistoryEntity.toModel() = IdentifiedCall(
    id = id,
    numberKey = numberKey,
    displayNumber = displayNumber,
    name = name,
    label = enumOrNull<CallerLabel>(label) ?: CallerLabel.UNKNOWN,
    decision = enumOrNull<CallDecision>(decision) ?: CallDecision.ALLOW,
    spamScore = spamScore,
    timestamp = timestamp,
)

fun IdentifiedCall.toEntity() = CallHistoryEntity(
    numberKey = numberKey,
    displayNumber = displayNumber,
    name = name,
    label = label.name,
    decision = decision.name,
    spamScore = spamScore,
    timestamp = timestamp,
)

fun SearchHistoryEntity.toModel() = SearchHistoryItem(
    id = id,
    query = queryText,
    numberKey = numberKey,
    displayNumber = displayNumber,
    searchedAt = searchedAt,
)

/** "Verified" is only kept when the backend supplied a verification date as well. */
fun BusinessDto.toEntity(now: Long) = BusinessEntity(
    businessId = businessId,
    name = name,
    phoneNumbers = phoneNumbers.joinToString(","),
    category = category,
    address = address,
    website = website?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
    logoUrl = logo?.takeIf { it.startsWith("https://") },
    email = email,
    verified = verified && verificationDate != null,
    verificationDate = verificationDate,
    country = country,
    language = language,
    hours = hours,
    rating = rating?.coerceIn(0f, 5f),
    ratingCount = ratingCount,
    updatedAt = now,
)

fun BusinessEntity.toModel() = Business(
    businessId = businessId,
    name = name,
    phoneNumbers = phoneNumbers.split(',').filter { it.isNotBlank() },
    category = category,
    address = address,
    website = website,
    logoUrl = logoUrl,
    email = email,
    verified = verified,
    verificationDate = verificationDate,
    country = country,
    language = language,
    hours = hours,
    rating = rating,
    ratingCount = ratingCount,
    updatedAt = updatedAt,
)
