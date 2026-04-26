package com.mezze.interpreter.values;

/**
 * Mutable char wrapper for safe local mutation.
 *
 * This is the runtime representation of MutChar - a mutable character.
 */
public final class MutableChar {
    private char value;

    public MutableChar(char value) {
        this.value = value;
    }

    public char get() {
        return value;
    }

    public void set(char value) {
        this.value = value;
    }

    /**
     * Get the Unicode code point of the character.
     */
    public int codePoint() {
        return (int) value;
    }

    /**
     * Set the character from a Unicode code point.
     */
    public void setFromCodePoint(int codePoint) {
        this.value = (char) codePoint;
    }

    /**
     * Convert to uppercase.
     */
    public void toUpper() {
        this.value = Character.toUpperCase(value);
    }

    /**
     * Convert to lowercase.
     */
    public void toLower() {
        this.value = Character.toLowerCase(value);
    }

    /**
     * Check if this is a digit.
     */
    public boolean isDigit() {
        return Character.isDigit(value);
    }

    /**
     * Check if this is a letter.
     */
    public boolean isLetter() {
        return Character.isLetter(value);
    }

    /**
     * Check if this is whitespace.
     */
    public boolean isWhitespace() {
        return Character.isWhitespace(value);
    }

    /**
     * Check if this is uppercase.
     */
    public boolean isUpper() {
        return Character.isUpperCase(value);
    }

    /**
     * Check if this is lowercase.
     */
    public boolean isLower() {
        return Character.isLowerCase(value);
    }
}
