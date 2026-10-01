package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import com.rskusum.whocaller.core.ui.util.TelecomActions
import java.util.Locale

/** Everything the contact details card shows. Read on-device from the Contacts Provider only. */
data class ContactCard(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val photoUri: String?,
    val starred: Boolean,
    val phones: List<LabeledValue>,
    val emails: List<LabeledValue>,
    val company: String?,
    val jobTitle: String?,
    val address: String?,
    val city: String?,
)

data class LabeledValue(val value: String, val label: String?)

/** A SIM / calling account the user can pick for an outgoing call. */
data class SimOption(
    val handle: PhoneAccountHandle,
    val label: String,
    val color: Int?,
    /** 1-based SIM slot, when known. */
    val slot: Int? = null,
    /** Network provider of this SIM ("Jio", "Airtel"…), as SIM settings show it. */
    val network: String? = null,
)

/** Offline facts about a number from libphonenumber's location and operator data. */
data class NumberFacts(val location: String?, val carrier: String?, val international: String?)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Contact details, photos and WhatsApp call shortcuts, straight from the Contacts Provider. */
object ContactLookup {

    fun canRead(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    /** The saved contact for a number (platform fuzzy phone matching), or null. Call off the main thread. */
    fun byNumber(context: Context, number: String): ContactCard? {
        if (!canRead(context) || number.count(Char::isDigit) < 3) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val id = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull() ?: return null
        return details(context, id)
    }

    /** Full details of one contact. Call off the main thread. */
    fun details(context: Context, contactId: Long): ContactCard? {
        if (!canRead(context)) return null
        val resolver = context.contentResolver
        return runCatching {
            val base = resolver.query(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                arrayOf(
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_URI,
                    ContactsContract.Contacts.STARRED,
                    ContactsContract.Contacts.LOOKUP_KEY,
                ),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) listOf(c.getString(0), c.getString(1), c.getInt(2).toString(), c.getString(3)) else null }
                ?: return null

            val phones = mutableListOf<LabeledValue>()
            val emails = mutableListOf<LabeledValue>()
            var company: String? = null
            var title: String? = null
            var address: String? = null
            var city: String? = null
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3,
                    ContactsContract.Data.DATA4,
                    ContactsContract.Data.DATA7,
                ),
                "${ContactsContract.Data.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(1)
                    when (c.getString(0)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (!type.isNullOrBlank() && phones.none { same(it.value, type) }) {
                            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                            phones += LabeledValue(type, label)
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (!type.isNullOrBlank() && emails.none { it.value == type }) {
                            val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                            emails += LabeledValue(type, label)
                        }
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                            if (company == null) company = type?.takeIf { it.isNotBlank() }
                            if (title == null) title = c.getString(4)?.takeIf { it.isNotBlank() }
                        }
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                            if (address == null) address = type?.takeIf { it.isNotBlank() }
                            if (city == null) city = c.getString(5)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            ContactCard(
                contactId = contactId,
                lookupKey = base[3],
                name = base[0] ?: phones.firstOrNull()?.value.orEmpty(),
                photoUri = base[1],
                starred = base[2] == "1",
                phones = phones,
                emails = emails,
                company = company,
                jobTitle = title,
                address = address,
                city = city,
            )
        }.getOrNull()
    }

    private fun same(a: String, b: String) = a.filter(Char::isDigit).takeLast(10) == b.filter(Char::isDigit).takeLast(10)

    private val photos = LruCache<String, ImageBitmap>(60)

    /** Decodes a contact photo, downscaled. Call off the main thread. */
    fun photo(context: Context, uri: String?, maxPx: Int = 256): ImageBitmap? {
        if (uri.isNullOrBlank()) return null
        val key = "$uri@$maxPx"
        photos.get(key)?.let { return it }
        return runCatching {
            val resolver = context.contentResolver
            val parsed = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            resolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }?.asImageBitmap()
        }.getOrNull()?.also { photos.put(key, it) }
    }

    private const val WHATSAPP_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

    /**
     * WhatsApp adds a "Video call" entry to contacts that use WhatsApp. If this number has one we
     * can start a WhatsApp video call directly. Call off the main thread.
     */
    fun whatsAppVideoEntry(context: Context, number: String): Long? {
        if (!canRead(context)) return null
        val tail = number.filter(Char::isDigit).takeLast(10).takeIf { it.length >= 7 } ?: return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(WHATSAPP_VIDEO),
                null,
            )?.use { c ->
                var found: Long? = null
                while (found == null && c.moveToNext()) {
                    val jid = c.getString(1)?.substringBefore('@')?.filter(Char::isDigit).orEmpty()
                    if (jid.endsWith(tail)) found = c.getLong(0)
                }
                found
            }
        }.getOrNull()
    }

    fun startWhatsAppVideo(context: Context, dataId: Long): Boolean {
        val pkg = TelecomActions.whatsAppPackage(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId), WHATSAPP_VIDEO)
            .setPackage(pkg)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

/** SIM / calling-account selection. */
object Sims {

    /** Call-capable accounts. Empty without READ_PHONE_STATE. */
    fun list(context: Context): List<SimOption> {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
        val sims = com.rskusum.whocaller.core.ui.util.SimCards.list(context)
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        return try {
            val accounts = telecom.callCapablePhoneAccounts
            accounts.mapIndexedNotNull { i, handle ->
                val account = telecom.getPhoneAccount(handle) ?: return@mapIndexedNotNull null
                // Which SIM this calling account is: exact on Android 11+, else by id or order.
                val subId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { tm?.getSubscriptionId(handle) }.getOrNull()
                } else {
                    handle.id.toIntOrNull()
                }
                val sim = sims.firstOrNull { it.subscriptionId == subId }
                    ?: sims.takeIf { it.size == accounts.size }?.getOrNull(i)
                SimOption(
                    handle = handle,
                    label = account.label?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${i + 1}",
                    color = account.highlightColor.takeIf { it != PhoneAccount.NO_HIGHLIGHT_COLOR },
                    slot = sim?.slot ?: (i + 1),
                    network = sim?.network ?: account.label?.toString()?.takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.slot ?: 0 }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** The user's default SIM for calls, or null for "ask every time". */
    fun default(context: Context): PhoneAccountHandle? {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            context.getSystemService(TelecomManager::class.java)?.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
        } catch (_: SecurityException) {
            null
        }
    }
}

object NumberTools {

    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
            ?: "US"
    }

    /** Pretty-prints a number for the region (e.g. "+91 94616 93096"); returns it unchanged if it can't. */
    fun format(number: String, countryIso: String): String =
        if (number.length < 4 || number.any { it in "*#," }) number else PhoneNumberUtils.formatNumber(number, countryIso) ?: number

    /**
     * Location (state/city where the data has it, else country), operator and the international
     * format. Uses libphonenumber's offline data; loads it on first use, so call off the main thread.
     */
    fun facts(number: String, countryIso: String): NumberFacts? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(number, countryIso)
        if (!util.isPossibleNumber(parsed)) return null
        val locale = Locale.getDefault()
        val country = util.getRegionCodeForNumber(parsed)?.let { Locale("", it).getDisplayCountry(locale) }?.takeIf { it.isNotBlank() }
        val place = PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(parsed, locale)?.takeIf { it.isNotBlank() }
        val location = when {
            place == null -> country
            country == null || place == country -> place
            else -> "$place, $country"
        }
        val carrier = PhoneNumberToCarrierMapper.getInstance().getNameForNumber(parsed, Locale.ENGLISH)?.takeIf { it.isNotBlank() }
        val international = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        NumberFacts(location, carrier, international)
    }.getOrNull()

    /** "+91 94616 93096" style international format, without loading location data. */
    fun international(number: String, countryIso: String): String? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(number, countryIso)
        if (util.isPossibleNumber(parsed)) util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL) else null
    }.getOrNull()

    fun isEmergency(context: Context, number: String): Boolean {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.length !in 2..6) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(digits) == true
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(digits)
            }
        }.getOrDefault(false)
    }

    /** Hands an emergency number to the system phone app, which may always place emergency calls. */
    fun dialEmergencyWithSystem(context: Context, number: String) {
        val systemDialer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(TelecomManager::class.java)?.systemDialerPackage
        } else {
            null
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        if (systemDialer != null && systemDialer != context.packageName) intent.setPackage(systemDialer)
        runCatching { context.startActivity(intent) }
    }
}

/** The installed WhatsApp's own launcher icon (so the button shows WhatsApp's real logo). */
object WhatsAppBrand {
    @Volatile private var cached: ImageBitmap? = null

    fun icon(context: Context, sizePx: Int): ImageBitmap? {
        cached?.let { return it }
        val pkg = TelecomActions.whatsAppPackage(context) ?: return null
        return runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()?.also { cached = it }
    }
}

/** Opens a screen of the main WhoCaller app through its whocaller:// links. */
fun openWhoCaller(context: Context, path: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("whocaller://$path")).setPackage(context.packageName)
    runCatching { context.startActivity(intent) }
}
