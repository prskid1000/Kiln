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
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}
