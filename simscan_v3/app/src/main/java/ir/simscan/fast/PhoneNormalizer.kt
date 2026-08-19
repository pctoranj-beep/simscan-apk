package ir.simscan.fast

object PhoneNormalizer {
    private val fa = "۰۱۲۳۴۵۶۷۸۹"
    private val ar = "٠١٢٣٤٥٦٧٨٩"

    fun normalizeChars(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch in '0'..'9' -> sb.append(ch)
                fa.indexOf(ch) >= 0 -> sb.append(fa.indexOf(ch))
                ar.indexOf(ch) >= 0 -> sb.append(ar.indexOf(ch))
                ch == 'O' || ch == 'o' -> sb.append('0')
                ch == 'I' || ch == 'l' || ch == '|' -> sb.append('1')
                ch == 'S' || ch == 's' -> sb.append('5')
                ch == 'B' -> sb.append('8')
            }
        }
        return sb.toString()
    }

    fun validMobile(input: String): String? {
        val digits = normalizeChars(input)
        return when {
            digits.length == 11 && digits.startsWith("09") -> digits
            digits.length == 10 && digits.startsWith("9") -> "0$digits"
            digits.length == 12 && digits.startsWith("989") -> "0" + digits.substring(2)
            else -> null
        }
    }
}
