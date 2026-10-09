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
 * java.time.LocalDate, stored as ISO text ("2026-10-08").
 * ```
 * @Serializable data class Expense(val amount: Double = 0.0, val date: KDate = LocalDate.now())
 * KFormat.date(expense.date)              // "8 Oct 2026"
 * expense.date.month == LocalDate.now().month
 * ```
 * Don't store dates as String: every screen then has to parse them.
 */
typealias KDate = @Serializable(with = KDateSerializer::class) LocalDate

/** A time of day for stored data (a reminder at 09:30), stored as "09:30". */
typealias KTime = @Serializable(with = KTimeSerializer::class) LocalTime

/** A date and time for stored data, stored as "2026-10-08T09:30". */
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
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString().take(10))
}

object KTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.kiln.kit.KTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

object KDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.kiln.kit.KDateTime", PrimitiveKind.STRING)
    // Written with this device's offset, so a server reads the same moment (a bare local time was taken as UTC and
    // came back shifted by the offset on every save).
    override fun serialize(encoder: Encoder, value: LocalDateTime) =
        encoder.encodeString(value.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString())
    // Server timestamps carry an offset ("…+00:00", "…Z", Supabase's timestamptz): read them in local time.
    override fun deserialize(decoder: Decoder): LocalDateTime = decoder.decodeString().let { s ->
        runCatching { LocalDateTime.parse(s) }.getOrElse { java.time.OffsetDateTime.parse(s).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() } }
}
