package com.gh00ul.cascade.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The search calculator: what counts as arithmetic, its answers, and how they're shown, copied and spoken. */
class CalculatorTest {
    private fun answer(query: String, locale: Locale = Locale.US) = calculate(query, locale)?.display

    private fun assertAnswers(expected: String, vararg queries: String) {
        for (q in queries) assertEquals("“$q”", expected, answer(q))
    }

    private fun assertNotArithmetic(vararg queries: String) {
        for (q in queries) assertNull("“$q”", calculate(q, Locale.US))
    }

    @Test fun theFourOperatorsInEveryWayOfTypingThem() {
        assertAnswers("168", "24*7", "24 * 7", "24×7", "24 × 7", "24x7", "24 x 7", "24X7")
        assertAnswers("31", "24+7")
        assertAnswers("17", "24-7", "24 − 7")
        assertAnswers("2.5", "10/4", "10 ÷ 4")
    }

    @Test fun precedenceAndParentheses() {
        assertAnswers("14", "2+3*4", "2*(3+4)", "2(3+4)")
        assertAnswers("20", "(2+3)*4", "((2+3))*4")
        assertAnswers("21", "(1+2)(3+4)")
        // Left to right within a level.
        assertAnswers("5", "10-2-3", "100/10/2")
    }

    @Test fun powersAndSigns() {
        assertAnswers("1,024", "2^10")
        // Right to left, and above the sign: -2^2 is -(2^2).
        assertAnswers("512", "2^3^2")
        assertAnswers("−4", "-2^2")
        assertAnswers("4", "(-2)^2")
        assertAnswers("0.5", "2^-1")
        assertAnswers("−6", "2*-3", "-2*3")
        assertAnswers("5", "3--2", "3 - −2")
        assertAnswers("1", "+3-2")
    }

    @Test fun percentages() {
        assertAnswers("17", "20% of 85", "20 % OF 85", "85*20%", "85 × 20%")
        // Added or taken off, a percentage is of what it's added to, as on a phone's calculator.
        assertAnswers("55", "50+10%")
        assertAnswers("180", "200-10%")
        assertAnswers("1", "50%*2")
        assertAnswers("400", "200/50%")
        assertAnswers("3", "(50+50)%*3")
        // Only a bare percentage is relative; a sum that merely contains one is not.
        assertAnswers("50.2", "50+10%*2")
    }

    @Test fun decimals() {
        assertAnswers("0.3", "0.1+0.2")
        assertAnswers("0.75", ".5+.25")
        assertAnswers("6", "5.+1")
        assertAnswers("3", "1.5*2")
        assertAnswers("0.3333333333", "1/3")
        assertAnswers("0.6666666667", "2/3")
    }

    @Test fun aTrailingEqualsSignAndUnfinishedInput() {
        assertAnswers("4", "2+2=", "2+2 = ", "2+2==")
        // While the next number is being typed, the answer so far stays.
        assertAnswers("168", "24*7+", "24*7 -", "24*7*(")
        assertAnswers("5", "(2+3")
        assertAnswers("14", "2*(3+4")
    }

    @Test fun anythingElseIsNotArithmetic() {
        assertNotArithmetic("", "   ", "hello", "Maps", "C++", "=", "+", "(", ")")
        // A lone number, signed, a percentage or in parentheses: nothing to work out.
        assertNotArithmetic("42", "-42", "+42", "50%", "(5)", "2048", "1,000")
        // App names and other text with numbers in it.
        assertNotArithmetic("2048 game", "2 + two", "1e5", "10:30", "192.168.1.1", "v1.2+1", "5 of 6")
        // "x" only multiplies between numbers.
        assertNotArithmetic("x2", "2x", "2x+")
        // Malformed.
        assertNotArithmetic("5 6", "2+", "2=3", "2+3)", "*5", "5*/2", "()+1", "1..2+1")
    }

    @Test fun divisionByZeroAndOverflowGiveNoAnswer() {
        assertNotArithmetic("1/0", "0/0", "5/(2-2)", "2^1024", "10^308*10", "(-8)^0.5", "0^-1")
        // Every step counts, even when the end comes out finite.
        assertNotArithmetic("1/(1/0)", "(1/0)^0")
        assertAnswers("0", "0/5", "-0*5", "5-5")
    }

    @Test fun tenSignificantDigitsGroupedThenScientific() {
        assertAnswers("1,000,000", "1000*1000")
        assertAnswers("123,456,000", "123456*1000")
        assertAnswers("1,234,567,890", "1234567890*1")
        // Up to twelve digits while they're all real...
        assertAnswers("100,000,000,000", "10^11")
        // ...past that, or when rounding would leave made-up zeros, scientific.
        assertAnswers("1E12", "10^12")
        assertAnswers("1E15", "10^15")
        assertAnswers("−1E15", "-10^15")
        assertAnswers("1.099511628E12", "2^40")
        assertAnswers("9.99998E11", "999999*999999")
        assertAnswers("1.2676506E30", "2^100")
        // Small numbers: plain down to 0.0001.
        assertAnswers("0.001", "1/1000")
        assertAnswers("0.0001", "1/10000")
        assertAnswers("0.0001234567901", "1/8100")
        assertAnswers("1E−5", "1/100000")
        assertAnswers("1E−6", "0.001*0.001")
        assertAnswers("3.333333333E−7", "1/3*10^-6")
    }

    @Test fun theCopyIsPlain() {
        val big = calculate("1000*1000", Locale.US)!!
        assertEquals("1000000", big.plain)
        assertEquals(1_000_000.0, big.value, 0.0)
        val negative = calculate("2-7", Locale.US)!!
        assertEquals("−5", negative.display)
        assertEquals("-5", negative.plain)
        assertEquals("1E-5", calculate("1/100000", Locale.US)!!.plain)
        assertEquals("1234.5", calculate("1234.5*1", Locale.US)!!.plain)
    }

    @Test fun localeDecimalCommaAndGrouping() {
        val german = Locale.GERMANY
        assertEquals("2,5", answer("1,5+1", german))
        assertEquals("0,3", answer("0,1+0,2", german))
        assertEquals("1.500", answer("1000*1,5", german))
        assertEquals("2.469", answer("1.234,5*2", german))
        assertEquals("1500", calculate("1000*1,5", german)!!.plain)
        // A point is a decimal point everywhere.
        assertEquals("2,5", answer("1.5+1", german))
        // In English a comma only groups thousands; "1,5" could be anything.
        assertEquals("1,001", answer("1,000+1"))
        assertEquals("2,469", answer("1,234.5*2"))
        assertEquals("2,000,000", answer("1,000,000*2"))
        assertNotArithmetic("1,5+1", "1,2,3+1", "1,0000+1", "12,34.5+1")
    }

    @Test fun otherScriptsDigits() {
        assertEquals("4", answer("٢+٢"))
        assertEquals("٢ + ٢", calculate("٢+٢", Locale.US)!!.expression)
    }

    @Test fun theQuestionAsShownAndSpoken() {
        fun check(query: String, expression: String, spoken: String) {
            val c = calculate(query, Locale.US)!!
            assertEquals(expression, c.expression)
            assertEquals(spoken, c.spoken)
        }
        check("24*7", "24 × 7", "24 times 7 equals 168")
        check("24x7=", "24 × 7", "24 times 7 equals 168")
        check("20% of 85", "20% of 85", "20 percent of 85 equals 17")
        check("50+10%", "50 + 10%", "50 plus 10 percent equals 55")
        check("(2+3)*4", "(2 + 3) × 4", "left parenthesis 2 plus 3 right parenthesis times 4 equals 20")
        check("2(3+4", "2 × (3 + 4)", "2 times left parenthesis 3 plus 4 right parenthesis equals 14")
        check("-2^2", "−2^2", "minus 2 to the power of 2 equals minus 4")
        check("10/4", "10 ÷ 4", "10 divided by 4 equals 2.5")
        check("1,000-1", "1,000 − 1", "1,000 minus 1 equals 999")
        check("10^15", "10^15", "10 to the power of 15 equals 1 times 10 to the power of 15")
        check("1/100000", "1 ÷ 100000", "1 divided by 100000 equals 1 times 10 to the power of minus 5")
    }

    @Test fun pastedWallsOfTextAreIgnored() {
        assertNotArithmetic("1+".repeat(200) + "1", "(".repeat(300) + "1+1")
    }

    /** Typed to find a contact or search the web, not to subtract. */
    @Test fun phoneNumbersArentSums() {
        assertNotArithmetic("555-0100", "206-555-0100", "+1 206-555-0100", "(206) 555-0100", "+44 20 7946 0958")
        // Short ones and spaced minuses are still sums.
        assertAnswers("455", "555 - 100", "555 −100")
        assertAnswers("17", "24-7")
        assertAnswers("666", "1234 - 568")
    }
}
