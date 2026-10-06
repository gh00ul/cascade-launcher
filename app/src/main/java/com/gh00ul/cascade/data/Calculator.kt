package com.gh00ul.cascade.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Search's answer to arithmetic: [value], shown as [display] (grouped the locale's way, with a real minus sign) and
 * copied as [plain] (no grouping, so it pastes into anything). [expression] is the question as search echoes it
 * ("24 × 7"), and [spoken] the whole answer for TalkBack ("24 times 7 equals 168").
 */
data class Calculation(val value: Double, val display: String, val plain: String, val expression: String, val spoken: String)

/**
 * The answer to [query] when it's arithmetic: + − × ÷ (also * / and x between numbers), ^, parentheses, signs,
 * decimals and percentages ("20% of 85", "85 × 20%", and "50 + 10%" adding 10% of 50, like a phone's calculator). A
 * trailing "=" is ignored, and so are a trailing operator and unclosed parentheses, so the answer stays put while the
 * next number is typed. Null for anything else: words, a lone number (even signed or a percentage), an app name like
 * "2048 game", a division by zero, or a result too large for a double.
 */
fun calculate(query: String, locale: Locale = Locale.getDefault()): Calculation? {
    val text = query.trim().trimEnd { it == '=' || it.isWhitespace() }
    // Cheap outs for what search sees most: words. The cap keeps a pasted wall of parentheses off the stack.
    if (text.length > MAX_LENGTH || text.none { Character.isDigit(it) }) return null
    // A phone number ("555-0100", "+1 206-555-0100") isn't a subtraction: answering it would hide the search.
    if (PhoneNumber.matches(text)) return null
    val symbols = DecimalFormatSymbols.getInstance(locale)
    val tokens = tokenize(text, symbols.decimalSeparator) ?: return null
    // Typing "24*7+" keeps showing 168 until the next number comes, and "(2+3" reads as "(2+3)".
    while (tokens.lastOrNull()?.op in Trailing) tokens.removeAt(tokens.lastIndex)
    var open = 0
    for (t in tokens) if (t.op == '(') open++ else if (t.op == ')') open--
    repeat(open.coerceAtLeast(0)) { tokens += Token(')') }

    val parser = Parser(tokens)
    val node = parser.parse() ?: return null
    if (parser.operators == 0) return null
    val value = node.eval() ?: return null
    val result = format(if (value == 0.0) 0.0 else value, locale, symbols)
    return Calculation(value, result.display, result.plain, node.pretty(), "${node.spoken()} equals ${result.spoken}")
}

private const val MAX_LENGTH = 256

/**
 * How phone numbers are written: groups of digits joined by a single dash or space, maybe after a "+" country code or
 * an area code in parentheses, seven digits or more. A sum is spaced around its minus ("1234 - 567"), so it doesn't
 * match; dotted numbers ("206.555.0100") never parse as arithmetic anyway.
 */
private object PhoneNumber {
    private val shape = Regex("""\+?\s?(\(\d{1,4}\)\s?)?\d{1,4}([-\s]\d{1,4}){1,5}""")
    /** Two numbers and one dash: a local number only as its 3 and 4 digits ("555-0100"); "2024-1987" is a sum. */
    private val dashedPair = Regex("""(\d+)-(\d+)""")
    fun matches(text: String): Boolean {
        if (!shape.matches(text) || text.count { it.isDigit() } < 7) return false
        val pair = dashedPair.matchEntire(text) ?: return true
        return pair.groupValues[1].length == 3 && pair.groupValues[2].length == 4
    }
}
/** Significant digits shown, like a pocket calculator's. */
private const val DIGITS = 10
private const val NUM = '#'
/** Operators that can't end an expression; dropped from the end while typing. */
private val Trailing = setOf('+', '-', '*', '/', '^', 'o', '(')

/** A number as typed, or one of the operators + - * / ^ % ( ) and 'o' for "of". */
private class Token(val op: Char, val number: Double = 0.0, val text: String = "")

private fun tokenize(text: String, decimal: Char): MutableList<Token>? {
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val previous = tokens.lastOrNull()?.op
        when {
            c.isWhitespace() -> i++
            Character.isDigit(c) || c == '.' || c == ',' -> {
                val start = i
                while (i < text.length && (Character.isDigit(text[i]) || text[i] == '.' || text[i] == ',')) i++
                val typed = text.substring(start, i)
                val number = parseNumber(typed, decimal) ?: return null
                tokens += Token(NUM, number, typed)
            }
            c == '+' || c == '-' || c == '^' || c == '%' || c == '(' || c == ')' -> { tokens += Token(c); i++ }
            c == '−' -> { tokens += Token('-'); i++ }
            c == '*' || c == '×' -> { tokens += Token('*'); i++ }
            c == '/' || c == '÷' -> { tokens += Token('/'); i++ }
            // "x" multiplies only between numbers, so "x2" or "2x" stay words.
            (c == 'x' || c == 'X') && (previous == NUM || previous == ')' || previous == '%') &&
                text.drop(i + 1).trimStart().firstOrNull().let { it != null && (Character.isDigit(it) || it in ".(-+−") } -> {
                tokens += Token('*')
                i++
            }
            // "of" only right after a percentage: "20% of 85".
            previous == '%' && text.regionMatches(i, "of", 0, 2, ignoreCase = true) && text.getOrNull(i + 2)?.isLetter() != true -> {
                tokens += Token('o')
                i += 2
            }
            else -> return null
        }
    }
    return tokens
}

/**
 * A number as typed, with "." and the locale's [decimal] mark. A single "." is always a decimal point; a single ","
 * is one where the locale writes it so. With both, the last is the decimal mark. Otherwise "." or "," must group
 * thousands properly ("1,000", "1.234.567"); anything else ("1,5" in English) is too ambiguous to be a number.
 */
private fun parseNumber(typed: String, decimal: Char): Double? {
    val ascii = buildString(typed.length) { for (ch in typed) append(Character.digit(ch, 10).takeIf { it >= 0 }?.let { '0' + it } ?: ch) }
    val dots = ascii.count { it == '.' }
    val commas = ascii.count { it == ',' }
    val (point, grouping) = when {
        dots == 0 && commas == 0 -> null to null
        dots > 0 && commas > 0 -> if (ascii.lastIndexOf('.') > ascii.lastIndexOf(',')) '.' to ',' else ',' to '.'
        dots == 1 -> '.' to null
        commas == 1 && decimal == ',' -> ',' to null
        dots > 0 -> null to '.'
        else -> null to ','
    }
    if (point != null && ascii.count { it == point } > 1) return null
    val whole = if (point != null) ascii.substringBefore(point) else ascii
    val fraction = if (point != null) ascii.substringAfter(point) else ""
    if (whole.isEmpty() && fraction.isEmpty()) return null
    if (grouping != null) {
        val groups = whole.split(grouping)
        if (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 } || grouping in fraction) return null
    }
    val digits = (if (grouping != null) whole.replace(grouping.toString(), "") else whole) + if (point != null) ".$fraction" else ""
    if (digits.any { it != '.' && it !in '0'..'9' }) return null
    return digits.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private sealed interface Node
private class Num(val value: Double, val text: String) : Node
private class Sign(val minus: Boolean, val operand: Node) : Node
private class Percent(val operand: Node) : Node
private class Group(val inner: Node) : Node
private class Op(val op: Char, val left: Node, val right: Node) : Node

/**
 * The usual precedence: + −, then × ÷ "of" (and "2(3)"), then signs, then ^ (right to left, so -2^2 is -4 and
 * 2^-1 is 0.5), then %, then numbers and parentheses.
 */
private class Parser(private val tokens: List<Token>) {
    private var pos = 0
    /** Binary operators read, implied multiplication included. A lone number, signed or not, has none. */
    var operators = 0
        private set

    fun parse(): Node? = expression()?.takeIf { pos == tokens.size }

    private fun peek() = tokens.getOrNull(pos)?.op

    private fun expression(): Node? {
        var left = term() ?: return null
        while (peek() == '+' || peek() == '-') {
            val op = tokens[pos++].op
            left = Op(op, left, term() ?: return null)
            operators++
        }
        return left
    }

    private fun term(): Node? {
        var left = unary() ?: return null
        while (true) {
            val op = when (peek()) {
                '*', '/', 'o' -> tokens[pos++].op
                '(' -> '*'
                else -> return left
            }
            left = Op(op, left, unary() ?: return null)
            operators++
        }
    }

    private fun unary(): Node? = when (peek()) {
        '-', '+' -> {
            val minus = tokens[pos++].op == '-'
            unary()?.let { Sign(minus, it) }
        }
        else -> power()
    }

    private fun power(): Node? {
        val base = postfix() ?: return null
        if (peek() != '^') return base
        pos++
        val exponent = unary() ?: return null
        operators++
        return Op('^', base, exponent)
    }

    private fun postfix(): Node? {
        var node = primary() ?: return null
        while (peek() == '%') {
            pos++
            node = Percent(node)
        }
        return node
    }

    private fun primary(): Node? {
        val token = tokens.getOrNull(pos) ?: return null
        return when (token.op) {
            NUM -> {
                pos++
                Num(token.number, token.text)
            }
            '(' -> {
                pos++
                val inner = expression() ?: return null
                if (peek() != ')') return null
                pos++
                Group(inner)
            }
            else -> null
        }
    }
}

/** The value, or null once any step divides by zero or overflows (1/(1/0) is no answer, though it comes out finite). */
private fun Node.eval(): Double? = when (this) {
    is Num -> value
    is Sign -> operand.eval()?.let { if (minus) -it else it }
    is Percent -> operand.eval()?.let { it / 100 }
    is Group -> inner.eval()
    is Op -> {
        val l = left.eval()
        val r = right.eval()
        if (l == null || r == null) null else when (op) {
            // A percentage added or taken off is of what it's added to: 50 + 10% is 55.
            '+' -> if (right is Percent) l + l * r else l + r
            '-' -> if (right is Percent) l - l * r else l - r
            '*', 'o' -> l * r
            '/' -> l / r
            else -> l.pow(r)
        }.takeIf { it.isFinite() }
    }
}

private fun Node.pretty(): String = when (this) {
    is Num -> text
    is Sign -> (if (minus) "−" else "+") + operand.pretty()
    is Percent -> operand.pretty() + "%"
    is Group -> "(" + inner.pretty() + ")"
    is Op -> if (op == '^') left.pretty() + "^" + right.pretty() else "${left.pretty()} ${symbol(op)} ${right.pretty()}"
}

private fun symbol(op: Char) = when (op) {
    '-' -> "−"
    '*' -> "×"
    '/' -> "÷"
    'o' -> "of"
    else -> op.toString()
}

private fun Node.spoken(): String = when (this) {
    is Num -> text
    is Sign -> if (minus) "minus ${operand.spoken()}" else operand.spoken()
    is Percent -> "${operand.spoken()} percent"
    is Group -> "left parenthesis ${inner.spoken()} right parenthesis"
    is Op -> "${left.spoken()} ${word(op)} ${right.spoken()}"
}

private fun word(op: Char) = when (op) {
    '+' -> "plus"
    '-' -> "minus"
    '*' -> "times"
    '/' -> "divided by"
    'o' -> "of"
    else -> "to the power of"
}

private class Formatted(val display: String, val plain: String, val spoken: String)

/**
 * [value] to [DIGITS] significant digits, trailing zeros dropped. Plain from 0.0001 up to ten digits before the point
 * (or twelve, while rounding made none of them up: 10^11 is "100,000,000,000"); scientific beyond, as "1.2345E15".
 */
private fun format(value: Double, locale: Locale, symbols: DecimalFormatSymbols): Formatted {
    val exact = BigDecimal(abs(value))
    val rounded = exact.round(MathContext(DIGITS, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    if (rounded.signum() == 0) return Formatted("0", "0", "0")
    // Digits before the point; zero or less for a fraction (0.0012 is -2).
    val magnitude = rounded.precision() - rounded.scale()
    val minus = value < 0
    val grouped = decimalFormat(locale, symbols, grouping = true)
    val ungrouped = decimalFormat(locale, symbols, grouping = false)
    fun signed(display: String, plain: String, spoken: String) = Formatted(
        display = (if (minus) "−" else "") + display,
        plain = (if (minus) "-" else "") + plain,
        spoken = (if (minus) "minus " else "") + spoken,
    )
    if (magnitude in -3..10 || (magnitude in 11..12 && rounded.compareTo(exact) == 0)) {
        grouped.maximumFractionDigits = rounded.scale().coerceAtLeast(0)
        ungrouped.maximumFractionDigits = grouped.maximumFractionDigits
        val digits = grouped.format(rounded)
        return signed(digits, ungrouped.format(rounded), digits)
    }
    val exponent = magnitude - 1
    val mantissa = rounded.movePointLeft(exponent)
    ungrouped.maximumFractionDigits = mantissa.scale().coerceAtLeast(0)
    val m = ungrouped.format(mantissa)
    return signed(
        "${m}E${if (exponent < 0) "−" else ""}${abs(exponent)}",
        "${m}E$exponent",
        "$m times 10 to the power of ${if (exponent < 0) "minus " else ""}${abs(exponent)}",
    )
}

private fun decimalFormat(locale: Locale, symbols: DecimalFormatSymbols, grouping: Boolean): DecimalFormat =
    ((NumberFormat.getNumberInstance(locale) as? DecimalFormat) ?: DecimalFormat("#,##0.###", symbols)).apply {
        isGroupingUsed = grouping
        minimumFractionDigits = 0
        roundingMode = RoundingMode.HALF_EVEN
    }
