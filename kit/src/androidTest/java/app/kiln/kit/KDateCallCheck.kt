package app.kiln.kit

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

/** Compiles only if KDate(…) / KTime(…) are accepted as models write them (run 15). */
@Serializable
internal data class KDateCallCheck(val date: KDate = KDate(LocalDate.now()), val time: KTime = KTime(LocalTime.now()))
