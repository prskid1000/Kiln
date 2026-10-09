package app.kiln.kit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// ---------------------------------------------------------------- dates in stored data

/**
 * A date you can keep in a `@Serializable` data class (and so in KCollection / KStore): it is a plain
 * java.time.LocalDate, stored as ISO text ("2026-10-08"). Write the type as `KDate`, not `LocalDate`: the name is what
 * carries the serializer — `val date: LocalDate` in a @Serializable class doesn't compile ("Serializer has not been
 * found"). Everywhere else (KDateField, LocalDate.now(), comparisons) it is just a LocalDate.
 * ```
 * @Serializable data class Expense(val amount: Double = 0.0, val date: KDate = LocalDate.now())
 * KFormat.date(expense.date)              // "8 Oct 2026"
 * expense.date.month == LocalDate.now().month
 * ```
 * Don't store dates as String: every screen then has to parse them. A server timestamp (Supabase timestamptz,
 * created_at) is a KDateTime, not a KDate: a KDate keeps a midnight-UTC value as that date (an all-day date).
 */
typealias KDate = @Serializable(with = KDateSerializer::class) LocalDate

/** A time of day for stored data (a reminder at 09:30), stored as "09:30". */
typealias KTime = @Serializable(with = KTimeSerializer::class) LocalTime

/** A date and time for stored data, stored as "2026-10-08T09:30" (sent to Supabase with the device's offset). */
typealias KDateTime = @Serializable(with = KDateTimeSerializer::class) LocalDateTime

// Models write KDate(LocalDate.now()) as if KDate wrapped a date (run 15): it is the date, so these just return it.
/** The date itself (`KDate` is a LocalDate); `val date: KDate = LocalDate.now()` needs no wrapping. */
fun KDate(date: LocalDate): LocalDate = date
/** The time itself (`KTime` is a LocalTime). */
fun KTime(time: LocalTime): LocalTime = time
/** The date-time itself (`KDateTime` is a LocalDateTime). */
fun KDateTime(dateTime: LocalDateTime): LocalDateTime = dateTime

object KDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.kiln.kit.KDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    // A timestamp with an offset is read as its local date ("…T22:30+00:00" is the next day in India), a bare one as written.
    override fun deserialize(decoder: Decoder): LocalDate = decoder.decodeString().let { s ->
        // Midnight UTC is an all-day date ("2026-10-08T00:00:00Z"): kept as that date, not moved a day back west of UTC.
        runCatching { java.time.OffsetDateTime.parse(s).takeIf { it.toLocalTime() != java.time.LocalTime.MIDNIGHT }!!
            .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }
            .getOrElse { LocalDate.parse(s.take(10)) } }
}

object KTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.kiln.kit.KTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

object KDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.kiln.kit.KDateTime", PrimitiveKind.STRING)
    // Stored as the plain local time (an appointment at 10:00 stays at 10:00 in another time zone). Only rows sent to
    // Supabase carry this device's offset, since the server would read a bare time as UTC.
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(
        if ((encoder as? kotlinx.serialization.json.JsonEncoder)?.json.let { it === insertJson || it === updateJson })
            value.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString() else value.toString())
    // Server timestamps carry an offset ("…+00:00", "…Z", Supabase's timestamptz): read them in local time.
    override fun deserialize(decoder: Decoder): LocalDateTime = decoder.decodeString().let { s ->
        runCatching { LocalDateTime.parse(s) }.getOrElse { java.time.OffsetDateTime.parse(s).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() } }
}
