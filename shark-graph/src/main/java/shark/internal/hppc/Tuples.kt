package shark.internal.hppc

/** Alternative to Pair<Long, Object> that doesn't box long.*/
data class LongObjectPair<out B>(
        val first: Long,
        val second: B
)

/** Alternative to Pair<Int, Object> that doesn't box int.*/
data class IntObjectPair<out B>(
        val first: Int,
        val second: B
)

/** Alternative to Pair<Long, Long> that doesn't box longs. */
data class LongLongPair(
        val first: Long,
        val second: Long
)

infix fun <B> Long.to(that: B): LongObjectPair<B> = LongObjectPair(this, that)

infix fun <B> Int.to(that: B): IntObjectPair<B> = IntObjectPair(this, that)

infix fun Long.to(that: Long): LongLongPair = LongLongPair(this, that)
