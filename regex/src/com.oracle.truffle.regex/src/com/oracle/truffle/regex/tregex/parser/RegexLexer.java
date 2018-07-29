/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.regex.tregex.parser;

import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.RegexOptions;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.chardata.CodePointRange;
import com.oracle.truffle.regex.chardata.CodePointSet;
import com.oracle.truffle.regex.chardata.Constants;
import com.oracle.truffle.regex.chardata.UnicodeCharacterProperties;
import com.oracle.truffle.regex.tregex.util.DebugUtil;
import com.oracle.truffle.regex.util.CompilationFinalBitSet;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public final class RegexLexer {

    private static final CompilationFinalBitSet PREDEFINED_CHAR_CLASSES = CompilationFinalBitSet.valueOf('s', 'S', 'd', 'D', 'w', 'W');
    private static final CompilationFinalBitSet SYNTAX_CHARS = CompilationFinalBitSet.valueOf(
                    '^', '$', '/', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|');

    private static final CodePointSet ID_START = UnicodeCharacterProperties.getProperty("ID_Start");
    private static final CodePointSet ID_CONTINUE = UnicodeCharacterProperties.getProperty("ID_Continue");

    private final RegexSource source;
    private final String pattern;
    private final RegexFlags flags;
    private final RegexOptions options;
    private Token lastToken;
    private int index = 0;
    private int nGroups = 1;
    private boolean identifiedAllGroups = false;
    private Map<String, Integer> namedCaptureGroups = null;

    public RegexLexer(RegexSource source, RegexOptions options) {
        this.source = source;
        this.pattern = source.getPattern();
        this.flags = source.getFlags();
        this.options = options;
    }

    public boolean hasNext() {
        return !atEnd();
    }

    public Token next() throws RegexSyntaxException {
        int startIndex = index;
        Token t = getNext();
        setSourceSection(t, startIndex, index);
        lastToken = t;
        return t;
    }

    /**
     * Sets the {@link com.oracle.truffle.api.source.SourceSection} of a given {@link Token} in
     * respect of {@link RegexSource#getSource()}.
     * 
     * @param startIndex inclusive start index of the source section in respect of
     *            {@link RegexSource#getPattern()}.
     * @param endIndex exclusive end index of the source section in respect of
     *            {@link RegexSource#getPattern()}.
     */
    private void setSourceSection(Token t, int startIndex, int endIndex) {
        if (DebugUtil.DEBUG) {
            // RegexSource#getSource() prepends a slash ('/') to the pattern, so we have to add an
            // offset of 1 here.
            t.setSourceSection(source.getSource().createSection(startIndex + 1, endIndex - startIndex));
        }
    }

    /* input string access */

    private char curChar() {
        return pattern.charAt(index);
    }

    private char consumeChar() {
        final char c = pattern.charAt(index);
        advance();
        return c;
    }

    private void advance() {
        advance(1);
    }

    private void retreat() {
        advance(-1);
    }

    private void advance(int len) {
        index += len;
    }

    private boolean lookahead(String match) {
        if (pattern.length() - index < match.length()) {
            return false;
        }
        return pattern.regionMatches(index, match, 0, match.length());
    }

    private boolean consumingLookahead(String match) {
        final boolean matches = lookahead(match);
        if (matches) {
            advance(match.length());
        }
        return matches;
    }

    private boolean atEnd() {
        return index >= pattern.length();
    }

    private int numberOfCaptureGroups() throws RegexSyntaxException {
        if (!identifiedAllGroups) {
            identifyCaptureGroups();
            identifiedAllGroups = true;
        }
        return nGroups;
    }

    public Map<String, Integer> getNamedCaptureGroups() throws RegexSyntaxException {
        if (!identifiedAllGroups) {
            identifyCaptureGroups();
            identifiedAllGroups = true;
        }
        return namedCaptureGroups;
    }

    /**
     * Checks whether this regular expression contains any named capture groups.
     * <p>
     * This method is a way to check whether we are parsing the goal symbol Pattern[~U, +N] or
     * Pattern[~U, ~N] (see the ECMAScript RegExp grammar).
     */
    private boolean hasNamedCaptureGroups() throws RegexSyntaxException {
        return getNamedCaptureGroups() != null;
    }

    private void registerCaptureGroup() {
        if (!identifiedAllGroups) {
            nGroups++;
        }
    }

    private void registerNamedCaptureGroup(String name) {
        if (!identifiedAllGroups) {
            if (namedCaptureGroups == null) {
                namedCaptureGroups = new HashMap<>();
            }
            if (namedCaptureGroups.containsKey(name)) {
                throw syntaxError(ErrorMessages.MULTIPLE_GROUPS_SAME_NAME);
            }
            namedCaptureGroups.put(name, nGroups);
        }
        registerCaptureGroup();
    }

    private void identifyCaptureGroups() throws RegexSyntaxException {
        // We are counting capture groups, so we only care about '(' characters and special
        // characters which can cancel the meaning of '(' - those include '\' for escapes, '[' for
        // character classes (where '(' stands for a literal '(') and any characters after the '('
        // which might turn into a non-capturing group or a look-around assertion.
        boolean insideCharClass = false;
        final int restoreIndex = index;
        while (!atEnd()) {
            switch (consumeChar()) {
                case '\\':
                    // skip escaped char
                    advance();
                    break;
                case '[':
                    insideCharClass = true;
                    break;
                case ']':
                    insideCharClass = false;
                    break;
                case '(':
                    if (!insideCharClass) {
                        parseGroupBegin();
                    }
                    break;
                default:
                    break;
            }
        }
        index = restoreIndex;
    }

    private Token charClass(CodePointSet codePointSet) {
        return charClass(codePointSet, false);
    }

    private Token charClass(CodePointSet codePointSet, boolean invert) {
        CodePointSet processedSet = codePointSet;
        processedSet = flags.isIgnoreCase() ? CaseFoldTable.applyCaseFold(processedSet, flags.isUnicode()) : processedSet;
        processedSet = invert ? processedSet.createInverse() : processedSet;
        return Token.createCharClass(processedSet);
    }

    /* lexer */

    private Token getNext() throws RegexSyntaxException {
        final char c = consumeChar();
        switch (c) {
            case '.':
                return charClass(flags.isDotAll() ? Constants.DOT_ALL : Constants.DOT);
            case '^':
                return Token.create(Token.Kind.caret);
            case '$':
                return Token.create(Token.Kind.dollar);
            case '{':
            case '*':
            case '+':
            case '?':
                return parseQuantifier(c);
            case '}':
                if (flags.isUnicode()) {
                    // In ECMAScript regular expressions, syntax characters such as '}' and ']'
                    // cannot be used as atomic patterns. However, Annex B relaxes this condition
                    // and allows the use of unmatched '}' and ']', which then match themselves.
                    // Neverthelesss, in Unicode mode, we should still be strict.
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACE);
                }
                return charClass(CodePointSet.create(c));
            case '|':
                return Token.create(Token.Kind.alternation);
            case '(':
                return parseGroupBegin();
            case ')':
                return Token.create(Token.Kind.groupEnd);
            case '[':
                return parseCharClass();
            case ']':
                if (flags.isUnicode()) {
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACKET);
                }
                return charClass(CodePointSet.create(c));
            case '\\':
                return parseEscape();
            default:
                if (flags.isUnicode() && Character.isHighSurrogate(c)) {
                    return charClass(CodePointSet.create(finishSurrogatePair(c)));
                }
                return charClass(CodePointSet.create(c));
        }
    }

    private Token parseEscape() throws RegexSyntaxException {
        if (atEnd()) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
        }
        final char c = consumeChar();
        if ('1' <= c && c <= '9') {
            final int restoreIndex = index;
            final int backRefNumber = parseInteger(c - '0');
            if (backRefNumber < numberOfCaptureGroups()) {
                return Token.createBackReference(backRefNumber);
            } else if (flags.isUnicode()) {
                throw syntaxError(ErrorMessages.MISSING_GROUP_FOR_BACKREFERENCE);
            }
            index = restoreIndex;
        }
        switch (c) {
            case 'k':
                if (flags.isUnicode() || hasNamedCaptureGroups()) {
                    if (atEnd()) {
                        throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
                    }
                    if (consumeChar() != '<') {
                        throw syntaxError(ErrorMessages.MISSING_GROUP_NAME);
                    }
                    String groupName = parseGroupName();
                    // backward reference
                    if (namedCaptureGroups != null && namedCaptureGroups.containsKey(groupName)) {
                        return Token.createBackReference(namedCaptureGroups.get(groupName));
                    }
                    // possible forward reference
                    Map<String, Integer> allNamedCaptureGroups = getNamedCaptureGroups();
                    if (allNamedCaptureGroups != null && allNamedCaptureGroups.containsKey(groupName)) {
                        return Token.createBackReference(allNamedCaptureGroups.get(groupName));
                    }
                    throw syntaxError(ErrorMessages.MISSING_GROUP_FOR_BACKREFERENCE);
                } else {
                    return charClass(CodePointSet.create(c));
                }
            case 'b':
                return Token.create(Token.Kind.wordBoundary);
            case 'B':
                return Token.create(Token.Kind.nonWordBoundary);
            default:
                // Here we differentiate the case when parsing one of the six basic pre-defined
                // character classes (\w, \W, \d, \D, \s, \S) and Unicode character property
                // escapes. Both result in sets of characters, but in the former case, we can skip
                // the case-folding step in the `charClass` method and call `Token::createCharClass`
                // directly.
                if (isPredefCharClass(c)) {
                    return Token.createCharClass(parsePredefCharClass(c));
                } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
                    return charClass(parseUnicodeCharacterProperty(c == 'P'));
                } else {
                    return charClass(CodePointSet.create(parseEscapeChar(c, false)));
                }
        }
    }

    private Token parseGroupBegin() throws RegexSyntaxException {
        if (consumingLookahead("?=")) {
            return Token.createLookAheadAssertionBegin(false);
        } else if (consumingLookahead("?!")) {
            return Token.createLookAheadAssertionBegin(true);
        } else if (consumingLookahead("?<=")) {
            return Token.createLookBehindAssertionBegin(false);
        } else if (consumingLookahead("?<!")) {
            return Token.createLookBehindAssertionBegin(true);
        } else if (consumingLookahead("?:")) {
            return Token.create(Token.Kind.nonCaptureGroupBegin);
        } else if (consumingLookahead("?<")) {
            String groupName = parseGroupName();
            registerNamedCaptureGroup(groupName);
            return Token.create(Token.Kind.captureGroupBegin);
        } else {
            registerCaptureGroup();
            return Token.create(Token.Kind.captureGroupBegin);
        }
    }

    private int parseCodePointInGroupName() throws RegexSyntaxException {
        if (consumingLookahead("\\u")) {
            final int unicodeEscape = parseUnicodeEscapeChar();
            if (unicodeEscape < 0) {
                throw syntaxError(ErrorMessages.INVALID_UNICODE_ESCAPE);
            } else {
                return unicodeEscape;
            }
        }
        if (atEnd()) {
            throw syntaxError(ErrorMessages.UNTERMINATED_GROUP_NAME);
        }
        if (consumingLookahead(">")) {
            return -1;
        }
        final char c = consumeChar();
        return flags.isUnicode() && Character.isHighSurrogate(c) ? finishSurrogatePair(c) : c;
    }

    /**
     * Parse a {@code GroupName}, i.e. {@code <RegExpIdentifierName>}, assuming that the opening
     * {@code <} bracket was already read.
     * 
     * @return the StringValue of the {@code RegExpIdentifierName}
     */
    private String parseGroupName() throws RegexSyntaxException {
        StringBuilder groupName = new StringBuilder();
        int codePoint = parseCodePointInGroupName();
        if (codePoint == -1) {
            throw syntaxError(ErrorMessages.EMPTY_GROUP_NAME);
        }
        if (!(ID_START.contains(codePoint) || codePoint == '$' || codePoint == '_')) {
            throw syntaxError(ErrorMessages.INVALID_GROUP_NAME_START);
        }
        groupName.appendCodePoint(codePoint);
        while ((codePoint = parseCodePointInGroupName()) != -1) {
            if (!(ID_CONTINUE.contains(codePoint) || codePoint == '$' || codePoint == '\u200c' || codePoint == '\u200d')) {
                throw syntaxError(ErrorMessages.INVALID_GROUP_NAME_PART);
            }
            groupName.appendCodePoint(codePoint);
        }
        return groupName.toString();
    }

    private static final EnumSet<Token.Kind> QUANTIFIER_PREV = EnumSet.of(Token.Kind.charClass, Token.Kind.groupEnd, Token.Kind.backReference);

    private Token parseQuantifier(char c) throws RegexSyntaxException {
        int min;
        int max = -1;
        boolean greedy;
        if (c == '{') {
            final int resetIndex = index;
            BigInteger literalMin = parseDecimal();
            if (literalMin.compareTo(BigInteger.ZERO) < 0) {
                return countedRepetitionSyntaxError(resetIndex);
            }
            min = literalMin.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? literalMin.intValue() : -1;
            if (consumingLookahead(",}")) {
                greedy = !consumingLookahead("?");
            } else if (consumingLookahead("}")) {
                max = min;
                greedy = !consumingLookahead("?");
            } else {
                BigInteger literalMax;
                if (!consumingLookahead(",") || (literalMax = parseDecimal()).compareTo(BigInteger.ZERO) < 0 || !consumingLookahead("}")) {
                    return countedRepetitionSyntaxError(resetIndex);
                }
                max = literalMax.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? literalMax.intValue() : -1;
                greedy = !consumingLookahead("?");
                if (literalMin.compareTo(literalMax) > 0) {
                    throw syntaxError(ErrorMessages.QUANTIFIER_OUT_OF_ORDER);
                }
            }
        } else {
            greedy = !consumingLookahead("?");
            min = c == '+' ? 1 : 0;
            if (c == '?') {
                max = 1;
            }
        }
        if (lastToken == null) {
            throw syntaxError(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        if (lastToken.kind == Token.Kind.quantifier) {
            throw syntaxError(ErrorMessages.QUANTIFIER_ON_QUANTIFIER);
        }
        if (!QUANTIFIER_PREV.contains(lastToken.kind)) {
            throw syntaxError(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        return Token.createQuantifier(min, max, greedy);
    }

    private Token countedRepetitionSyntaxError(int resetIndex) throws RegexSyntaxException {
        if (flags.isUnicode()) {
            throw syntaxError(ErrorMessages.INCOMPLETE_QUANTIFIER);
        }
        index = resetIndex;
        return charClass(CodePointSet.create('{'));
    }

    private Token parseCharClass() throws RegexSyntaxException {
        final boolean invert = consumingLookahead("^");
        CodePointSet curCharClass = CodePointSet.createEmpty();
        while (!atEnd()) {
            final char c = consumeChar();
            if (c == ']') {
                return charClass(curCharClass, invert);
            }
            parseCharClassRange(c, curCharClass);
        }
        throw syntaxError(ErrorMessages.UNMATCHED_LEFT_BRACKET);
    }

    private CodePointSet parseCharClassAtom(char c) throws RegexSyntaxException {
        if (c == '\\') {
            if (atEnd()) {
                throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
            }
            if (isEscapeCharClass(curChar())) {
                return parseEscapeCharClass(consumeChar());
            } else {
                return CodePointSet.create(parseEscapeChar(consumeChar(), true));
            }
        } else if (flags.isUnicode() && Character.isHighSurrogate(c)) {
            return CodePointSet.create(finishSurrogatePair(c));
        } else {
            return CodePointSet.create(c);
        }
    }

    private void parseCharClassRange(char c, CodePointSet curCharClass) throws RegexSyntaxException {
        CodePointSet firstAtom = parseCharClassAtom(c);
        if (consumingLookahead("-")) {
            if (atEnd() || lookahead("]")) {
                curCharClass.addSet(firstAtom);
                curCharClass.addRange(new CodePointRange((int) '-'));
            } else {
                CodePointSet secondAtom = parseCharClassAtom(consumeChar());
                // Runtime Semantics: CharacterRangeOrUnion(firstAtom, secondAtom)
                if (!firstAtom.matchesSingleChar() || !secondAtom.matchesSingleChar()) {
                    if (flags.isUnicode()) {
                        throw syntaxError(ErrorMessages.INVALID_CHARACTER_CLASS);
                    } else {
                        curCharClass.addSet(firstAtom);
                        curCharClass.addSet(secondAtom);
                        curCharClass.addRange(new CodePointRange((int) '-'));
                    }
                } else {
                    int firstChar = firstAtom.getRanges().get(0).lo;
                    int secondChar = secondAtom.getRanges().get(0).lo;
                    if (secondChar < firstChar) {
                        throw syntaxError(ErrorMessages.CHAR_CLASS_RANGE_OUT_OF_ORDER);
                    } else {
                        curCharClass.addRange(new CodePointRange(firstChar, secondChar));
                    }
                }
            }
        } else {
            curCharClass.addSet(firstAtom);
        }
    }

    private CodePointSet parseEscapeCharClass(char c) throws RegexSyntaxException {
        if (isPredefCharClass(c)) {
            return parsePredefCharClass(c);
        } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
            return parseUnicodeCharacterProperty(c == 'P');
        } else {
            throw new IllegalStateException();
        }
    }

    // Note that the CodePointSet returned by this function has already been
    // case-folded and negated.
    private CodePointSet parsePredefCharClass(char c) {
        switch (c) {
            case 's':
                if (options.isU180EWhitespace()) {
                    return Constants.LEGACY_WHITE_SPACE;
                } else {
                    return Constants.WHITE_SPACE;
                }
            case 'S':
                if (options.isU180EWhitespace()) {
                    return Constants.LEGACY_NON_WHITE_SPACE;
                } else {
                    return Constants.NON_WHITE_SPACE;
                }
            case 'd':
                return Constants.DIGITS;
            case 'D':
                return Constants.NON_DIGITS;
            case 'w':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.WORD_CHARS;
                }
            case 'W':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.NON_WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.NON_WORD_CHARS;
                }
            default:
                throw new IllegalStateException();
        }
    }

    private CodePointSet parseUnicodeCharacterProperty(boolean invert) throws RegexSyntaxException {
        if (!consumingLookahead("{")) {
            throw syntaxError(ErrorMessages.INVALID_UNICODE_PROPERTY);
        }
        StringBuilder propSpecBuilder = new StringBuilder();
        while (!atEnd() && curChar() != '}') {
            propSpecBuilder.append(consumeChar());
        }
        if (!consumingLookahead("}")) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_UNICODE_PROPERTY);
        }
        try {
            CodePointSet propertySet = UnicodeCharacterProperties.getProperty(propSpecBuilder.toString());
            return invert ? propertySet.createInverse() : propertySet;
        } catch (IllegalArgumentException e) {
            throw syntaxError(e.getMessage());
        }
    }

    /**
     * Parse a {@code RegExpUnicodeEscapeSequence}, assuming that the prefix '&#92;u' has already
     * been read.
     * 
     * @return the code point of the escaped character, or -1 if the escape was malformed
     */
    private int parseUnicodeEscapeChar() throws RegexSyntaxException {
        if (flags.isUnicode() && consumingLookahead("{")) {
            final int value = parseHex(1, Integer.MAX_VALUE, 0x10ffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
            if (!consumingLookahead("}")) {
                throw syntaxError(ErrorMessages.INVALID_UNICODE_ESCAPE);
            }
            return value;
        } else {
            final int value = parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
            if (flags.isUnicode() && Character.isHighSurrogate((char) value)) {
                final int resetIndex = index;
                if (consumingLookahead("\\u") && !lookahead("{")) {
                    final char lead = (char) value;
                    final char trail = (char) parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                    if (Character.isLowSurrogate(trail)) {
                        return Character.toCodePoint(lead, trail);
                    } else {
                        index = resetIndex;
                    }
                } else {
                    index = resetIndex;
                }
            }
            return value;
        }
    }

    private int parseEscapeChar(char c, boolean inCharClass) throws RegexSyntaxException {
        if (inCharClass && c == 'b') {
            return '\b';
        }
        switch (c) {
            case '0':
                if (flags.isUnicode() && isDecimal(curChar())) {
                    throw syntaxError(ErrorMessages.INVALID_ESCAPE);
                }
                if (!flags.isUnicode() && !atEnd() && isOctal(curChar())) {
                    return parseOctal(0);
                }
                return '\0';
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'v':
                return '\u000B';
            case 'f':
                return '\f';
            case 'r':
                return '\r';
            case 'c':
                if (atEnd()) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                final char controlLetter = curChar();
                if (!flags.isUnicode() && (isDecimal(controlLetter) || controlLetter == '_') && inCharClass) {
                    advance();
                    return controlLetter % 32;
                }
                if (!('a' <= controlLetter && controlLetter <= 'z' || 'A' <= controlLetter && controlLetter <= 'Z')) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                advance();
                return Character.toUpperCase(controlLetter) - ('A' - 1);
            case 'u':
                final int unicodeEscape = parseUnicodeEscapeChar();
                return unicodeEscape < 0 ? c : unicodeEscape;
            case 'x':
                final int value = parseHex(2, 2, 0xff, ErrorMessages.INVALID_ESCAPE);
                return value < 0 ? c : value;
            case '-':
                if (!inCharClass) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
            default:
                if (!flags.isUnicode() && isOctal(c)) {
                    return parseOctal(c - '0');
                }
                if (!SYNTAX_CHARS.get(c)) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
        }
    }

    private int finishSurrogatePair(char c) {
        assert flags.isUnicode() && Character.isHighSurrogate(c);
        if (!atEnd() && Character.isLowSurrogate(curChar())) {
            final char lead = c;
            final char trail = consumeChar();
            return Character.toCodePoint(lead, trail);
        } else {
            return c;
        }
    }

    private char escapeCharSyntaxError(char c, String msg) throws RegexSyntaxException {
        if (flags.isUnicode()) {
            throw syntaxError(msg);
        }
        return c;
    }

    private BigInteger parseDecimal() {
        if (atEnd() || !isDecimal(curChar())) {
            return BigInteger.valueOf(-1);
        }
        return parseDecimal(BigInteger.ZERO);
    }

    private BigInteger parseDecimal(BigInteger firstDigit) {
        BigInteger ret = firstDigit;
        while (!atEnd() && isDecimal(curChar())) {
            ret = ret.multiply(BigInteger.TEN);
            ret = ret.add(BigInteger.valueOf(consumeChar() - '0'));
        }
        return ret;
    }

    /**
     * Parses a non-negative decimal integer. The value of the integer is clamped to
     * {@link Integer#MAX_VALUE}. For all {@code i} in {0,1,..,9}, {@code parseInteger(i)} is
     * equivalent to
     * {@code parseDecimal(BigInteger.valueOf(i)).max(BigInteger.valueOf(Integer.MAX_VALUE)}.
     * {@link #parseInteger(int)} should be faster than {@link #parseDecimal(java.math.BigInteger)}
     * because it does not have to go through {@link BigInteger}s.
     */
    private int parseInteger(int firstDigit) {
        int ret = firstDigit;
        // First, we consume all of the decimal digits that make up the integer.
        final int initialIndex = index;
        while (!atEnd() && isDecimal(curChar())) {
            advance();
        }
        final int terminalIndex = index;
        // Then, we parse the integer, stopping once we reach the limit Integer.MAX_VALUE.
        for (int i = initialIndex; i < terminalIndex; i++) {
            int nextDigit = pattern.charAt(i) - '0';
            if (ret > Integer.MAX_VALUE / 10) {
                return Integer.MAX_VALUE;
            }
            ret *= 10;
            if (ret > Integer.MAX_VALUE - nextDigit) {
                return Integer.MAX_VALUE;
            }
            ret += nextDigit;
        }
        return ret;
    }

    private int parseOctal(int firstDigit) {
        int ret = firstDigit;
        for (int i = 0; !atEnd() && isOctal(curChar()) && i < 2; i++) {
            if (ret * 8 > 255) {
                return ret;
            }
            ret *= 8;
            ret += consumeChar() - '0';
        }
        return ret;
    }

    private int parseHex(int minDigits, int maxDigits, int maxValue, String errorMsg) throws RegexSyntaxException {
        int ret = 0;
        int initialIndex = index;
        for (int i = 0; i < maxDigits; i++) {
            if (atEnd() || !isHex(curChar())) {
                if (i < minDigits) {
                    if (flags.isUnicode()) {
                        throw syntaxError(errorMsg);
                    } else {
                        index = initialIndex;
                        return -1;
                    }
                } else {
                    break;
                }
            }
            final char c = consumeChar();
            ret *= 16;
            if (c >= 'a') {
                ret += c - ('a' - 10);
            } else if (c >= 'A') {
                ret += c - ('A' - 10);
            } else {
                ret += c - '0';
            }
            if (ret > maxValue) {
                throw syntaxError(errorMsg);
            }
        }
        return ret;
    }

    private static boolean isDecimal(char c) {
        return '0' <= c && c <= '9';
    }

    private static boolean isOctal(char c) {
        return '0' <= c && c <= '7';
    }

    private static boolean isHex(char c) {
        return '0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F';
    }

    private static boolean isPredefCharClass(char c) {
        return PREDEFINED_CHAR_CLASSES.get(c);
    }

    private boolean isEscapeCharClass(char c) {
        return isPredefCharClass(c) || (flags.isUnicode() && (c == 'p' || c == 'P'));
    }

    private RegexSyntaxException syntaxError(String msg) {
        return new RegexSyntaxException(pattern, flags, msg);
    }
}
