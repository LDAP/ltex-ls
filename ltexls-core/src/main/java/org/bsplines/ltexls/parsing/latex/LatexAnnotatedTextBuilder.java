/* Copyright (C) 2020 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.latex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder;
import org.bsplines.ltexls.parsing.DummyGenerator;
import org.bsplines.ltexls.settings.Settings;
import org.bsplines.ltexls.tools.Tools;
import org.checkerframework.checker.nullness.qual.Nullable;

public class LatexAnnotatedTextBuilder extends CodeAnnotatedTextBuilder {
  private enum MathVowelState {
    UNDECIDED,
    STARTS_WITH_VOWEL,
    STARTS_WITH_CONSONANT,
  }

  private enum Mode {
    PARAGRAPH_TEXT,
    INLINE_TEXT,
    HEADING,
    INLINE_MATH,
    DISPLAY_MATH,
    IGNORE_ENVIRONMENT,
    RSWEAVE,
  }

  private static final Pattern commandPattern = Pattern.compile(
      "^\\\\(([^A-Za-z@]|([A-Za-z@]+))\\*?)");
  private static final Pattern argumentPattern = Pattern.compile("^\\{[^\\}]*?\\}");
  private static final Pattern commentPattern = Pattern.compile(
      "^%.*?($|((\n|\r|\r\n)[ \n\r\t]*))");
  private static final Pattern whitespacePattern = Pattern.compile(
      "^[ \n\r\t]+(%.*?($|((\n|\r|\r\n)[ \n\r\t]*)))?");
  private static final Pattern lengthPattern = Pattern.compile(
      "-?[0-9]*(\\.[0-9]+)?(pt|mm|cm|ex|em|bp|dd|pc|in)");
  private static final Pattern lengthInBracePattern = Pattern.compile(
      "^\\{" + lengthPattern.pattern() + "\\}");
  private static final Pattern lengthInBracketPattern = Pattern.compile(
      "^\\[" + lengthPattern.pattern() + "\\]");
  private static final Pattern emDashPattern = Pattern.compile("^---");
  private static final Pattern enDashPattern = Pattern.compile("^--");
  private static final Pattern accentPattern1 = Pattern.compile(
      "^(\\\\[`'\\^~\"=\\.])(([A-Za-z]|\\\\i)|(\\{([A-Za-z]|\\\\i)\\}))");
  private static final Pattern accentPattern2 = Pattern.compile(
      "^(\\\\[cr])( *([A-Za-z])|\\{([A-Za-z])\\})");
  private static final Pattern displayMathPattern = Pattern.compile("^\\$\\$");
  private static final Pattern verbCommandPattern = Pattern.compile("^\\\\verb\\*?(.).*?\\1");
  private static final Pattern rsweaveBeginPattern = Pattern.compile("^<<.*?>>=");
  private static final Pattern rsweaveEndPattern = Pattern.compile("^@");

  private static final List<String> mathEnvironments = Arrays.asList(
      "align", "align*", "alignat", "alignat*",
      "displaymath", "eqnarray", "eqnarray*", "equation", "equation*", "flalign", "flalign*",
      "gather", "gather*", "math", "multline", "multline*");

  private String code = "";
  private int pos;
  private int dummyCounter;
  private String lastSpace = "";
  private String lastPunctuation = "";
  private String dummyLastSpace = "";
  private String dummyLastPunctuation = "";
  private boolean isMathEmpty;
  private MathVowelState mathVowelState = MathVowelState.UNDECIDED;
  private boolean preserveDummyLast;
  private boolean canInsertSpaceBeforeDummy;
  private boolean isMathCharTrivial;
  private Stack<Mode> modeStack = new Stack<>();

  private char curChar;
  private String curString = "";
  private Mode curMode = Mode.PARAGRAPH_TEXT;

  private String language = "en-US";
  private String codeLanguageId = "latex";
  private List<LatexCommandSignature> commandSignatures =
      new ArrayList<>(LatexAnnotatedTextBuilderDefaults.getDefaultLatexCommandSignatures());
  private Map<String, List<LatexCommandSignature>> commandSignatureMap =
      createCommandSignatureMap(this.commandSignatures);
  private List<LatexEnvironmentSignature> environmentSignatures =
      new ArrayList<>(LatexAnnotatedTextBuilderDefaults.getDefaultLatexEnvironmentSignatures());
  private boolean isInStrictMode = false;

  public LatexAnnotatedTextBuilder(String codeLanguageId) {
    this.codeLanguageId = codeLanguageId;
  }

  private static Map<String, List<LatexCommandSignature>> createCommandSignatureMap(
        List<LatexCommandSignature> commandSignatures) {
    Map<String, List<LatexCommandSignature>> map = new HashMap<>();

    for (LatexCommandSignature commandSignature : commandSignatures) {
      String commandName = commandSignature.getName();
      if (!map.containsKey(commandName)) map.put(commandName, new ArrayList<>());
      map.get(commandName).add(commandSignature);
    }

    return map;
  }

  private String matchFromPosition(Pattern pattern) {
    return matchFromPosition(pattern, this.pos);
  }

  private String matchFromPosition(Pattern pattern, int pos) {
    Matcher matcher = pattern.matcher(this.code.substring(pos));
    return (matcher.find() ? matcher.group() : "");
  }

  private String generateDummy() {
    return generateDummy(DummyGenerator.getDefault());
  }

  private String generateDummy(DummyGenerator dummyGenerator) {
    boolean startsWithVowel = (this.mathVowelState == MathVowelState.STARTS_WITH_VOWEL);
    String dummy;

    if (isTextMode(this.curMode)) {
      dummy = dummyGenerator.generate(this.language, this.dummyCounter++, startsWithVowel);
    } else if (this.isMathEmpty) {
      if (this.curMode == Mode.DISPLAY_MATH) {
        dummy = (this.lastSpace.isEmpty() ? " " : "");
      } else {
        dummy = "";
      }
    } else if (this.curMode == Mode.DISPLAY_MATH) {
      dummy = ((this.lastSpace.isEmpty() ? " " : ""))
          + dummyGenerator.generate(this.language, this.dummyCounter++)
          + this.dummyLastPunctuation + ((this.modeStack.peek() == Mode.INLINE_TEXT)
          ? this.dummyLastSpace : " ");
    } else {
      dummy = dummyGenerator.generate(this.language, this.dummyCounter++, startsWithVowel)
          + this.dummyLastPunctuation + this.dummyLastSpace;
    }

    this.dummyLastSpace = "";
    this.dummyLastPunctuation = "";
    this.mathVowelState = MathVowelState.UNDECIDED;
    return dummy;
  }

  /**
   * Add plain text to the builder.
   *
   * @param text plain text
   * @return @c this
   */
  public LatexAnnotatedTextBuilder addText(String text) {
    if (text.isEmpty()) return this;
    super.addText(text);
    this.pos += text.length();
    textAdded(text);
    return this;
  }

  /**
   * Add LaTeX markup to the builder.
   *
   * @param markup LaTeX code
   * @return @c this
   */
  public LatexAnnotatedTextBuilder addMarkup(String markup) {
    if (markup.isEmpty()) return this;
    super.addMarkup(markup);
    this.pos += markup.length();

    if (this.preserveDummyLast) {
      this.preserveDummyLast = false;
    } else {
      this.dummyLastSpace = "";
      this.dummyLastPunctuation = "";
    }

    return this;
  }

  /**
   * Add LaTeX markup to the builder.
   *
   * @param markup LaTeX code
   * @param interpretAs replacement text for the resulting plain text
   * @return @c this
   */
  public LatexAnnotatedTextBuilder addMarkup(String markup, String interpretAs) {
    if (interpretAs.isEmpty()) {
      return addMarkup(markup);
    } else {
      super.addMarkup(markup, interpretAs);
      this.pos += markup.length();
      this.preserveDummyLast = false;
      textAdded(interpretAs);
      return this;
    }
  }

  private void textAdded(String text) {
    if (text.isEmpty()) return;
    char lastChar = text.charAt(text.length() - 1);
    this.lastSpace = (((lastChar == ' ') || (lastChar == '\n') || (lastChar == '\r')) ? " " : "");
    this.lastPunctuation = (isPunctuation(lastChar) ? " " : "");
  }

  private void popMode() {
    this.modeStack.pop();
    if (this.modeStack.isEmpty()) this.modeStack.push(Mode.PARAGRAPH_TEXT);
  }

  private static boolean isPunctuation(char ch) {
    return ((ch == '.') || (ch == ',') || (ch == ':') || (ch == ';'));
  }

  private static boolean isVowel(char ch) {
    ch = Character.toLowerCase(ch);
    return ((ch == 'a') || (ch == 'e') || (ch == 'f') || (ch == 'h') || (ch == 'i') || (ch == 'l')
        || (ch == 'm') || (ch == 'n') || (ch == 'o') || (ch == 'r') || (ch == 's') || (ch == 'x'));
  }

  private static boolean isMathMode(Mode mode) {
    return ((mode == Mode.INLINE_MATH) || (mode == Mode.DISPLAY_MATH));
  }

  private static boolean isIgnoreEnvironmentMode(Mode mode) {
    return (mode == Mode.IGNORE_ENVIRONMENT);
  }

  private static boolean isRsweaveMode(Mode mode) {
    return (mode == Mode.RSWEAVE);
  }

  private static boolean isTextMode(Mode mode) {
    return !isMathMode(mode) && !isIgnoreEnvironmentMode(mode);
  }

  private void enterDisplayMath() {
    this.modeStack.push(Mode.DISPLAY_MATH);
    this.isMathEmpty = true;
    this.mathVowelState = MathVowelState.UNDECIDED;
    this.canInsertSpaceBeforeDummy = true;
  }

  private void enterInlineMath() {
    this.modeStack.push(Mode.INLINE_MATH);
    this.isMathEmpty = true;
    this.mathVowelState = MathVowelState.UNDECIDED;
    this.canInsertSpaceBeforeDummy = true;
    this.isMathCharTrivial = true;
  }

  private String getDebugInformation(String code) {
    String remainingCode = StringEscapeUtils.escapeJava(
        code.substring(this.pos, Math.min(this.pos + 100, code.length())));
    return "Remaining code = \"" + remainingCode
        + "\", pos = " + this.pos
        + ", dummyCounter = " + this.dummyCounter
        + ", lastSpace = \"" + this.lastSpace
        + "\", lastPunctuation = \"" + this.lastPunctuation
        + "\", dummyLastSpace = \"" + this.dummyLastSpace
        + "\", dummyLastPunctuation = \"" + this.dummyLastPunctuation
        + "\", isMathEmpty = " + this.isMathEmpty
        + ", mathVowelState = " + this.mathVowelState
        + ", preserveDummyLast = " + this.preserveDummyLast
        + ", canInsertSpaceBeforeDummy = " + this.canInsertSpaceBeforeDummy
        + ", isMathCharTrivial = " + this.isMathCharTrivial
        + ", modeStack = " + this.modeStack
        + ", curChar = \"" + this.curChar
        + "\", curString = \"" + this.curString
        + "\", curMode = " + this.curMode;
  }

  private static boolean containsTwoEndsOfLine(String text) {
    return (text.contains("\n\n") || text.contains("\r\r") || text.contains("\r\n\r\n"));
  }

  private String convertAccentCommandToUnicode(String accentCommand, String letter) {
    String unicode = "";

    switch (accentCommand.charAt(1)) {
      case '`': {
        if (letter.equals("A")) unicode = "\u00c0";
        else if (letter.equals("E")) unicode = "\u00c8";
        else if (letter.equals("I")) unicode = "\u00cc";
        else if (letter.equals("O")) unicode = "\u00d2";
        else if (letter.equals("U")) unicode = "\u00d9";
        else if (letter.equals("a")) unicode = "\u00e0";
        else if (letter.equals("e")) unicode = "\u00e8";
        else if (letter.equals("i") || letter.equals("\\i")) unicode = "\u00ec";
        else if (letter.equals("o")) unicode = "\u00f2";
        else if (letter.equals("u")) unicode = "\u00f9";
        break;
      }
      case '\'': {
        if (letter.equals("A")) unicode = "\u00c1";
        else if (letter.equals("E")) unicode = "\u00c9";
        else if (letter.equals("I")) unicode = "\u00cd";
        else if (letter.equals("O")) unicode = "\u00d3";
        else if (letter.equals("U")) unicode = "\u00da";
        else if (letter.equals("Y")) unicode = "\u00dd";
        else if (letter.equals("a")) unicode = "\u00e1";
        else if (letter.equals("e")) unicode = "\u00e9";
        else if (letter.equals("i") || letter.equals("\\i")) unicode = "\u00ed";
        else if (letter.equals("o")) unicode = "\u00f3";
        else if (letter.equals("u")) unicode = "\u00fa";
        else if (letter.equals("y")) unicode = "\u00fd";
        break;
      }
      case '^': {
        if (letter.equals("A")) unicode = "\u00c2";
        else if (letter.equals("E")) unicode = "\u00ca";
        else if (letter.equals("I")) unicode = "\u00ce";
        else if (letter.equals("O")) unicode = "\u00d4";
        else if (letter.equals("U")) unicode = "\u00db";
        else if (letter.equals("Y")) unicode = "\u0176";
        else if (letter.equals("a")) unicode = "\u00e2";
        else if (letter.equals("e")) unicode = "\u00ea";
        else if (letter.equals("i") || letter.equals("\\i")) unicode = "\u00ee";
        else if (letter.equals("o")) unicode = "\u00f4";
        else if (letter.equals("u")) unicode = "\u00fb";
        else if (letter.equals("y")) unicode = "\u0177";
        break;
      }
      case '~': {
        if (letter.equals("A")) unicode = "\u00c3";
        else if (letter.equals("E")) unicode = "\u1ebc";
        else if (letter.equals("I")) unicode = "\u0128";
        else if (letter.equals("N")) unicode = "\u00d1";
        else if (letter.equals("O")) unicode = "\u00d5";
        else if (letter.equals("U")) unicode = "\u0168";
        else if (letter.equals("a")) unicode = "\u00e3";
        else if (letter.equals("e")) unicode = "\u1ebd";
        else if (letter.equals("i") || letter.equals("\\i")) unicode = "\u0129";
        else if (letter.equals("n")) unicode = "\u00f1";
        else if (letter.equals("o")) unicode = "\u00f5";
        else if (letter.equals("u")) unicode = "\u0169";
        break;
      }
      case '"': {
        if (letter.equals("A")) unicode = "\u00c4";
        else if (letter.equals("E")) unicode = "\u00cb";
        else if (letter.equals("I")) unicode = "\u00cf";
        else if (letter.equals("O")) unicode = "\u00d6";
        else if (letter.equals("U")) unicode = "\u00dc";
        else if (letter.equals("Y")) unicode = "\u0178";
        else if (letter.equals("a")) unicode = "\u00e4";
        else if (letter.equals("e")) unicode = "\u00eb";
        else if (letter.equals("i") || letter.equals("\\i")) unicode = "\u00ef";
        else if (letter.equals("o")) unicode = "\u00f6";
        else if (letter.equals("u")) unicode = "\u00fc";
        else if (letter.equals("y")) unicode = "\u00ff";
        break;
      }
      case '=': {
        if (letter.equals("A")) unicode = "\u0100";
        else if (letter.equals("E")) unicode = "\u0112";
        else if (letter.equals("I")) unicode = "\u012a";
        else if (letter.equals("O")) unicode = "\u014c";
        else if (letter.equals("U")) unicode = "\u016a";
        else if (letter.equals("Y")) unicode = "\u0232";
        else if (letter.equals("a")) unicode = "\u0101";
        else if (letter.equals("e")) unicode = "\u0113";
        else if (letter.equals("i") || letter.equals("\\i")) unicode = "\u012b";
        else if (letter.equals("o")) unicode = "\u014d";
        else if (letter.equals("u")) unicode = "\u016b";
        else if (letter.equals("y")) unicode = "\u0233";
        break;
      }
      case '.': {
        if (letter.equals("A")) unicode = "\u0226";
        else if (letter.equals("E")) unicode = "\u0116";
        else if (letter.equals("I")) unicode = "\u0130";
        else if (letter.equals("O")) unicode = "\u022e";
        else if (letter.equals("a")) unicode = "\u0227";
        else if (letter.equals("e")) unicode = "\u0117";
        else if (letter.equals("o")) unicode = "\u022f";
        break;
      }
      case 'c': {
        if (letter.equals("C")) unicode = "\u00c7";
        else if (letter.equals("c")) unicode = "\u00e7";
        break;
      }
      case 'r': {
        if (letter.equals("A")) unicode = "\u00c5";
        else if (letter.equals("U")) unicode = "\u016e";
        else if (letter.equals("a")) unicode = "\u00e5";
        else if (letter.equals("u")) unicode = "\u016f";
        break;
      }
      default: {
        break;
      }
    }

    return unicode;
  }

  private void consumeEnvironmentArguments(String environmentName) {
    while (this.pos < this.code.length()) {
      String environmentArgument = LatexCommandSignature.matchArgumentFromPosition(
          this.code, this.pos, LatexCommandSignature.ArgumentType.BRACE);

      if (!environmentArgument.isEmpty()) {
        addMarkup(environmentArgument);
        continue;
      }

      environmentArgument = LatexCommandSignature.matchArgumentFromPosition(
          this.code, this.pos, LatexCommandSignature.ArgumentType.BRACKET);

      if (!environmentArgument.isEmpty()) {
        addMarkup(environmentArgument);
        continue;
      }

      if (environmentName.equals("textblock") || environmentName.equals("textblock*")) {
        environmentArgument = LatexCommandSignature.matchArgumentFromPosition(
            this.code, this.pos, LatexCommandSignature.ArgumentType.PARENTHESIS);

        if (!environmentArgument.isEmpty()) {
          addMarkup(environmentArgument);
          continue;
        }
      }

      break;
    }
  }

  /**
   * Add LaTeX code to the builder, i.e., parse it and call @c addText and @c addMarkup.
   *
   * @param code LaTeX code
   * @return @c this
   */
  public LatexAnnotatedTextBuilder addCode(String code) {
    this.code = code;
    this.pos = 0;
    this.dummyCounter = 0;
    this.lastSpace = "";
    this.lastPunctuation = "";
    this.dummyLastSpace = "";
    this.dummyLastPunctuation = "";
    this.isMathEmpty = true;
    this.mathVowelState = MathVowelState.UNDECIDED;
    this.preserveDummyLast = false;
    this.canInsertSpaceBeforeDummy = false;
    this.isMathCharTrivial = false;

    this.modeStack = new Stack<>();
    this.modeStack.push(Mode.PARAGRAPH_TEXT);

    @Nullable Pattern ignoreEnvironmentEndPattern = null;
    int lastPos = -1;

    while (this.pos < code.length()) {
      this.curChar = code.charAt(this.pos);
      this.curString = String.valueOf(this.curChar);
      this.curMode = this.modeStack.peek();
      this.isMathCharTrivial = false;
      lastPos = this.pos;

      if (isIgnoreEnvironmentMode(this.curMode)) {
        if (ignoreEnvironmentEndPattern != null) {
          String ignoreEnvironmentEnd = matchFromPosition(ignoreEnvironmentEndPattern);

          if (ignoreEnvironmentEnd.isEmpty()) {
            addMarkup(this.curString);
          } else {
            popMode();
            addMarkup(ignoreEnvironmentEnd);
          }
        } else {
          Tools.logger.warning(Tools.i18n("ignoreEnvironmentEndPatternNotSet"));
          popMode();
        }
      } else if (this.codeLanguageId.equals("rsweave") && isRsweaveMode(this.curMode)) {
        String rsweaveEnd = matchFromPosition(rsweaveEndPattern);

        if (rsweaveEnd.isEmpty()) {
          addMarkup(this.curString);
        } else {
          popMode();
          addMarkup(rsweaveEnd);
        }
      } else {
        switch (this.curChar) {
          case '\\': {
            String command = matchFromPosition(commandPattern);

            if (command.equals("\\begin") || command.equals("\\end")) {
              this.preserveDummyLast = true;
              addMarkup(command);

              String argument = matchFromPosition(argumentPattern);
              String environmentName = argument.substring(1, argument.length() - 1);
              String interpretAs = "";

              if (mathEnvironments.contains(environmentName)) {
                if (command.equals("\\begin")) {
                  if (environmentName.equals("math")) {
                    enterInlineMath();
                  } else {
                    enterDisplayMath();
                  }
                } else {
                  popMode();
                  interpretAs = generateDummy();
                }
              } else if (command.equals("\\begin")) {
                @Nullable LatexEnvironmentSignature matchingEnvironment = null;

                for (LatexEnvironmentSignature latexEnvironmentSignature
                      : this.environmentSignatures) {
                  if (latexEnvironmentSignature.getName().equals(environmentName)) {
                    matchingEnvironment = latexEnvironmentSignature;
                  }
                }

                if ((matchingEnvironment != null) && (matchingEnvironment.getAction()
                      == LatexEnvironmentSignature.Action.IGNORE)) {
                  this.modeStack.push(Mode.IGNORE_ENVIRONMENT);
                  ignoreEnvironmentEndPattern = Pattern.compile(
                      "^\\\\end\\{" + Pattern.quote(environmentName) + "\\}");
                } else {
                  this.modeStack.push(this.curMode);
                }
              } else {
                popMode();
              }

              if (!isIgnoreEnvironmentMode(this.modeStack.peek())) {
                this.isMathCharTrivial = true;
                this.preserveDummyLast = true;
                addMarkup(argument, interpretAs);
                if (command.equals("\\begin")) consumeEnvironmentArguments(environmentName);
              }
            } else if (command.equals("\\$") || command.equals("\\%") || command.equals("\\&")) {
              addMarkup(command, command.substring(1));
            } else if (command.equals("\\[")) {
              enterDisplayMath();
              addMarkup(command);
            } else if (command.equals("\\(")) {
              enterInlineMath();
              addMarkup(command);
            } else if (command.equals("\\]") || command.equals("\\)")) {
              popMode();
              addMarkup(command, generateDummy());
            } else if (command.equals("\\AA")) {
              addMarkup(command, "\u00c5");
            } else if (command.equals("\\O")) {
              addMarkup(command, "\u00d8");
            } else if (command.equals("\\aa")) {
              addMarkup(command, "\u00e5");
            } else if (command.equals("\\ss")) {
              addMarkup(command, "\u00df");
            } else if (command.equals("\\o")) {
              addMarkup(command, "\u00f8");
            } else if (command.equals("\\`") || command.equals("\\'") || command.equals("\\^")
                  || command.equals("\\~") || command.equals("\\\"") || command.equals("\\=")
                  || command.equals("\\.")) {
              Matcher matcher = accentPattern1.matcher(code.substring(this.pos));

              if (matcher.find()) {
                @Nullable String accentCommand = matcher.group(1);
                @Nullable String letter = ((matcher.group(3) != null)
                    ? matcher.group(3) : matcher.group(5));
                String interpretAs = (((accentCommand != null) && (letter != null))
                    ? convertAccentCommandToUnicode(accentCommand, letter) : "");
                addMarkup(matcher.group(), interpretAs);
              } else {
                addMarkup(command);
              }
            } else if (command.equals("\\c") || command.equals("\\r")) {
              Matcher matcher = accentPattern2.matcher(code.substring(this.pos));

              if (matcher.find()) {
                @Nullable String accentCommand = matcher.group(1);
                @Nullable String letter = ((matcher.group(3) != null)
                    ? matcher.group(3) : matcher.group(4));
                String interpretAs = (((accentCommand != null) && (letter != null))
                    ? convertAccentCommandToUnicode(accentCommand, letter) : "");
                addMarkup(matcher.group(), interpretAs);
              } else {
                addMarkup(command);
              }
            } else if (command.equals("\\-")) {
              addMarkup(command);
            } else if (command.equals("\\ ") || command.equals("\\,") || command.equals("\\;")
                  || command.equals("\\\\") || command.equals("\\hfill")
                  || command.equals("\\hspace") || command.equals("\\hspace*")
                  || command.equals("\\quad") || command.equals("\\qquad")
                  || command.equals("\\newline")) {
              if (command.equals("\\hspace") || command.equals("\\hspace*")) {
                String argument = matchFromPosition(argumentPattern, this.pos + command.length());
                command += argument;
              }

              if (isMathMode(this.curMode) && this.lastSpace.isEmpty()
                    && this.canInsertSpaceBeforeDummy) {
                addMarkup(command, " ");
              } else {
                this.preserveDummyLast = true;

                if (isMathMode(this.curMode)) {
                  addMarkup(command);
                  this.dummyLastSpace = " ";
                } else {
                  String space = " ";

                  if (!this.lastSpace.isEmpty()) {
                    space = "";
                  } else if (command.equals("\\,")) {
                    space = "\u202f";
                  }

                  addMarkup(command, space);
                }
              }
            } else if (command.equals("\\dots")
                  || command.equals("\\eg") || command.equals("\\egc") || command.equals("\\euro")
                  || command.equals("\\ie") || command.equals("\\iec")) {
              String interpretAs = "";

              if (!isMathMode(this.curMode)) {
                if (command.equals("\\dots")) {
                  interpretAs = "...";
                } else if (command.equals("\\eg")) {
                  interpretAs = "e.g.";
                } else if (command.equals("\\egc")) {
                  interpretAs = "e.g.,";
                } else if (command.equals("\\euro")) {
                  interpretAs = "\u20ac";
                } else if (command.equals("\\ie")) {
                  interpretAs = "i.e.";
                } else if (command.equals("\\iec")) {
                  interpretAs = "i.e.,";
                }
              }

              addMarkup(command, interpretAs);
            } else if (command.equals("\\notag") || command.equals("\\qed")) {
              this.preserveDummyLast = true;
              addMarkup(command);
            } else if (command.equals("\\part") || command.equals("\\chapter")
                  || command.equals("\\section") || command.equals("\\subsection")
                  || command.equals("\\subsubsection") || command.equals("\\paragraph")
                  || command.equals("\\subparagraph")
                  || command.equals("\\part*") || command.equals("\\chapter*")
                  || command.equals("\\section*") || command.equals("\\subsection*")
                  || command.equals("\\subsubsection*") || command.equals("\\paragraph*")
                  || command.equals("\\subparagraph*")) {
              addMarkup(command);
              String headingArgument = LatexCommandSignature.matchArgumentFromPosition(
                  code, this.pos, LatexCommandSignature.ArgumentType.BRACKET);
              if (!headingArgument.isEmpty()) addMarkup(headingArgument);
              this.modeStack.push(Mode.HEADING);
              addMarkup("{");
            } else if (command.equals("\\text") || command.equals("\\intertext")) {
              this.modeStack.push(Mode.INLINE_TEXT);
              String interpretAs = (isMathMode(this.curMode) ? generateDummy() : "");
              addMarkup(command + "{", interpretAs);
            } else if (command.equals("\\verb")) {
              String verbCommand = matchFromPosition(verbCommandPattern);
              addMarkup(verbCommand, generateDummy());
            } else {
              String match = "";
              @Nullable List<LatexCommandSignature> possibleCommandSignatures =
                  this.commandSignatureMap.get(command);
              @Nullable LatexCommandSignature matchingCommand = null;

              if (possibleCommandSignatures == null) {
                possibleCommandSignatures = Collections.emptyList();
              }

              for (LatexCommandSignature latexCommandSignature : possibleCommandSignatures) {
                String curMatch = latexCommandSignature.matchFromPosition(code, this.pos);

                if (!curMatch.isEmpty() && (curMatch.length() >= match.length())) {
                  match = curMatch;
                  matchingCommand = latexCommandSignature;
                }
              }

              if ((matchingCommand == null)
                    || (matchingCommand.getAction() == LatexCommandSignature.Action.DEFAULT)) {
                if (isMathMode(this.curMode) && (this.mathVowelState == MathVowelState.UNDECIDED)) {
                  if (command.equals("\\mathbb") || command.equals("\\mathbf")
                        || command.equals("\\mathcal") || command.equals("\\mathfrak")
                        || command.equals("\\mathit") || command.equals("\\mathnormal")
                        || command.equals("\\mathsf") || command.equals("\\mathtt")) {
                    // leave this.mathVowelState as MathVowelState.UNDECIDED
                  } else if (command.equals("\\ell")) {
                    this.mathVowelState = MathVowelState.STARTS_WITH_VOWEL;
                  } else {
                    this.mathVowelState = MathVowelState.STARTS_WITH_CONSONANT;
                  }
                }

                addMarkup(command);
              } else {
                switch (matchingCommand.getAction()) {
                  case IGNORE: {
                    addMarkup(match);
                    break;
                  }
                  case DUMMY: {
                    addMarkup(match, generateDummy(matchingCommand.getDummyGenerator()));
                    break;
                  }
                  default: {
                    addMarkup(match);
                    break;
                  }
                }
              }
            }

            break;
          }
          case '{': {
            String length = matchFromPosition(lengthInBracePattern);

            if (!length.isEmpty()) {
              addMarkup(length);
            } else {
              this.modeStack.push(this.curMode);
              addMarkup(this.curString);
            }

            break;
          }
          case '}': {
            String interpretAs = "";
            if ((this.curMode == Mode.HEADING) && this.lastPunctuation.isEmpty()) interpretAs = ".";
            popMode();
            addMarkup(this.curString, interpretAs);
            this.canInsertSpaceBeforeDummy = true;

            if (isTextMode(this.curMode) && isMathMode(this.modeStack.peek())) {
              this.isMathEmpty = true;
            }

            this.isMathCharTrivial = true;
            break;
          }
          case '$': {
            String displayMath = matchFromPosition(displayMathPattern);

            if (!displayMath.isEmpty()) {
              if (this.curMode == Mode.DISPLAY_MATH) {
                popMode();
                addMarkup(displayMath, generateDummy());
              } else {
                enterDisplayMath();
                addMarkup(displayMath);
              }
            } else {
              if (this.curMode == Mode.INLINE_MATH) {
                popMode();
                addMarkup(this.curString, generateDummy());
              } else {
                enterInlineMath();
                addMarkup(this.curString);
              }
            }

            break;
          }
          case '%': {
            String comment = matchFromPosition(commentPattern);
            this.preserveDummyLast = true;
            this.isMathCharTrivial = true;
            addMarkup(comment, (containsTwoEndsOfLine(comment) ? "\n\n" : ""));
            break;
          }
          case ' ':
          case '&':
          case '~':
          case '\n':
          case '\r':
          case '\t': {
            String whitespace = (((this.curChar != '~') && (this.curChar != '&'))
                ? matchFromPosition(whitespacePattern) : this.curString);
            this.preserveDummyLast = true;
            this.isMathCharTrivial = true;

            if (isTextMode(this.curMode)) {
              if (containsTwoEndsOfLine(whitespace)) {
                addMarkup(whitespace, "\n\n");
              } else if (this.curChar == '~') {
                addMarkup(whitespace, (this.lastSpace.isEmpty() ? "\u00a0" : ""));
              } else {
                addMarkup(whitespace, (this.lastSpace.isEmpty() ? " " : ""));
              }
            } else {
              addMarkup(whitespace);
            }

            if ((this.curChar == '~') || (this.curChar == '&')) {
              this.dummyLastSpace = " ";
            }

            break;
          }
          case '`':
          case '\'':
          case '"': {
            if (isTextMode(this.curMode)) {
              String quote = "";
              String smartQuote = "";

              if (this.pos + 1 < code.length()) {
                quote = code.substring(this.pos, this.pos + 2);

                if (quote.equals("``") || quote.equals("\"'")) {
                  smartQuote = "\u201c";
                } else if (quote.equals("''")) {
                  smartQuote = "\u201d";
                } else if (quote.equals("\"`")) {
                  smartQuote = "\u201e";
                } else if (quote.equals("\"-") || quote.equals("\"\"") || quote.equals("\"|")) {
                  smartQuote = "";
                } else if (quote.equals("\"=") || quote.equals("\"~")) {
                  smartQuote = "-";
                } else {
                  quote = "";
                }
              }

              if (quote.isEmpty()) addText(this.curString);
              else addMarkup(quote, smartQuote);
            } else {
              addMarkup(this.curString);
            }

            break;
          }
          case '-': {
            String emDash = matchFromPosition(emDashPattern);

            if (isTextMode(this.curMode)) {
              if (!emDash.isEmpty()) {
                addMarkup(emDash, "\u2014");
                break;
              } else {
                String enDash = matchFromPosition(enDashPattern);

                if (!enDash.isEmpty()) {
                  addMarkup(enDash, "\u2013");
                  break;
                }
              }
            }
          }
          // fall through
          case '[': {
            String length = matchFromPosition(lengthInBracketPattern);

            if (!length.isEmpty()) {
              this.isMathCharTrivial = true;
              this.preserveDummyLast = true;
              addMarkup(length);
              break;
            }
          }
          // fall through
          case '<': {
            if (this.codeLanguageId.equals("rsweave")) {
              String rsweaveBegin = matchFromPosition(rsweaveBeginPattern);

              if (!rsweaveBegin.isEmpty()) {
                this.modeStack.push(Mode.RSWEAVE);
                addMarkup(rsweaveBegin);
                break;
              }
            }
          }
          // fall through
          default: {
            if (isTextMode(this.curMode)) {
              addText(this.curString);
              if (isPunctuation(this.curChar)) this.lastPunctuation = this.curString;
            } else {
              addMarkup(this.curString);
              if (isPunctuation(this.curChar)) this.dummyLastPunctuation = this.curString;

              if (this.mathVowelState == MathVowelState.UNDECIDED) {
                this.mathVowelState = (isVowel(this.curChar) ? MathVowelState.STARTS_WITH_VOWEL
                    : MathVowelState.STARTS_WITH_CONSONANT);
              }
            }

            break;
          }
        }
      }

      if (!this.isMathCharTrivial) {
        this.canInsertSpaceBeforeDummy = false;
        this.isMathEmpty = false;
      }

      if (this.pos == lastPos) {
        if (this.isInStrictMode) {
          throw new RuntimeException(Tools.i18n(
              "latexAnnotatedTextBuilderInfiniteLoop", getDebugInformation(code)));
        } else {
          Tools.logger.warning(Tools.i18n(
              "latexAnnotatedTextBuilderPreventedInfiniteLoop", getDebugInformation(code)));
          this.pos++;
        }
      }
    }

    return this;
  }

  @Override
  public void setSettings(Settings settings) {
    this.language = settings.getLanguageShortCode();

    for (Map.Entry<String, String> entry : settings.getLatexCommands().entrySet()) {
      String actionString = entry.getValue();
      LatexCommandSignature.Action action;
      @Nullable DummyGenerator dummyGenerator = null;

      if (actionString.equals("default")) {
        action = LatexCommandSignature.Action.DEFAULT;
      } else if (actionString.equals("ignore")) {
        action = LatexCommandSignature.Action.IGNORE;
      } else if (actionString.equals("dummy")) {
        action = LatexCommandSignature.Action.DUMMY;
      } else if (actionString.equals("pluralDummy")) {
        action = LatexCommandSignature.Action.DUMMY;
        dummyGenerator = DummyGenerator.getDefault(true);
      } else {
        continue;
      }

      if (dummyGenerator == null) dummyGenerator = DummyGenerator.getDefault();
      this.commandSignatures.add(new LatexCommandSignature(entry.getKey(), action, dummyGenerator));
    }

    this.commandSignatureMap = createCommandSignatureMap(this.commandSignatures);

    for (Map.Entry<String, String> entry : settings.getLatexEnvironments().entrySet()) {
      String actionString = entry.getValue();
      LatexEnvironmentSignature.Action action;

      if (actionString.equals("default")) {
        action = LatexEnvironmentSignature.Action.DEFAULT;
      } else if (actionString.equals("ignore")) {
        action = LatexEnvironmentSignature.Action.IGNORE;
      } else {
        continue;
      }

      this.environmentSignatures.add(new LatexEnvironmentSignature(entry.getKey(), action));
    }
  }

  public void setInStrictMode(boolean isInStrictMode) {
    this.isInStrictMode = isInStrictMode;
  }
}
