/* Copyright (C) 2020 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.markdown;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.Escaping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder;
import org.bsplines.ltexls.parsing.DummyGenerator;
import org.bsplines.ltexls.settings.Settings;
import org.checkerframework.checker.nullness.qual.Nullable;

public class MarkdownAnnotatedTextBuilder extends CodeAnnotatedTextBuilder {
  private static final Pattern yamlFrontMatterPattern = Pattern.compile(
      "\\A---\\s*$.*?^---\\s*$", Pattern.MULTILINE | Pattern.DOTALL);

  private Parser parser = Parser.builder().build();

  private String code;
  private int pos;
  private int dummyCounter;
  private Stack<String> nodeTypeStack = new Stack<>();

  private String language = "en-US";
  private List<MarkdownNodeSignature> nodeSignatures =
      new ArrayList<>(MarkdownAnnotatedTextBuilderDefaults.getDefaultMarkdownNodeSignatures());

  public MarkdownAnnotatedTextBuilder() {
    this.code = "";
  }

  private void visitChildren(final Node node) {
    node.getChildren().forEach(this::visit);
  }

  private boolean isInNodeType(String nodeType) {
    return this.nodeTypeStack.contains(nodeType);
  }

  private boolean isInIgnoredNodeType() {
    boolean result = false;

    for (String nodeType : this.nodeTypeStack) {
      for (MarkdownNodeSignature nodeSignature : this.nodeSignatures) {
        if (nodeSignature.getName().equals(nodeType)) {
          result = (nodeSignature.getAction() == MarkdownNodeSignature.Action.IGNORE);
        }
      }
    }

    return result;
  }

  private boolean isDummyNodeType(String nodeType) {
    boolean result = false;

    for (MarkdownNodeSignature nodeSignature : this.nodeSignatures) {
      if (nodeSignature.getName().equals(nodeType)) {
        result = (nodeSignature.getAction() == MarkdownNodeSignature.Action.DUMMY);
      }
    }

    return result;
  }

  private void addMarkup(int newPos) {
    boolean inParagraph = isInNodeType("Paragraph");

    while (true) {
      if ((this.pos >= this.code.length()) || (this.pos >= newPos)) break;
      int curPos = this.code.indexOf('\r', this.pos);

      if ((curPos == -1) || (curPos >= newPos)) {
        curPos = this.code.indexOf('\n', this.pos);
        if ((curPos == -1) || (curPos >= newPos)) break;
      }

      if (curPos > this.pos) super.addMarkup(this.code.substring(this.pos, curPos));
      super.addMarkup(this.code.substring(curPos, curPos + 1), (inParagraph ? " " : "\n"));

      this.pos = curPos + 1;
    }

    if (newPos > this.pos) {
      super.addMarkup(this.code.substring(this.pos, newPos));
      this.pos = newPos;
    }
  }

  private void addMarkup(Node node, String interpretAs) {
    addMarkup(node.getStartOffset());
    int newPos = node.getEndOffset();
    super.addMarkup(this.code.substring(this.pos, newPos), interpretAs);
    this.pos = newPos;
  }

  private void addText(int newPos) {
    if (newPos > this.pos) {
      super.addText(this.code.substring(this.pos, newPos));
      this.pos = newPos;
    }
  }

  private String generateDummy() {
    return DummyGenerator.getDefault().generate(this.language, this.dummyCounter++);
  }

  /**
   * Add Markdown code to the builder, i.e., parse it and call @c addText and @c addMarkup.
   *
   * @param code Markdown code
   * @return @c this
   */
  public MarkdownAnnotatedTextBuilder addCode(String code) {
    int pos = 0;
    Matcher matcher = MarkdownAnnotatedTextBuilder.yamlFrontMatterPattern.matcher(code);

    if (matcher.find()) {
      int newPos = pos + matcher.end();
      super.addMarkup(code.substring(pos, newPos));
      pos += newPos;
    }

    Document document = this.parser.parse(code.substring(pos));
    visit(document);
    return this;
  }

  private void visit(Document document) {
    this.code = document.getChars().toString();
    this.pos = 0;
    this.dummyCounter = 0;
    this.nodeTypeStack.clear();
    visitChildren(document);
    if (this.pos < this.code.length()) addMarkup(this.code.length());
  }

  private void visit(Node node) {
    String nodeType = node.getClass().getSimpleName();

    if (isInIgnoredNodeType()) {
      addMarkup(node.getEndOffset());
    } else if (isDummyNodeType(nodeType)) {
      addMarkup(node, generateDummy());
    } else if (nodeType.equals("Text")) {
      addMarkup(node.getStartOffset());
      addText(node.getEndOffset());
    } else if (nodeType.equals("HtmlEntity")) {
      addMarkup(node, Escaping.unescapeHtml(node.getChars()));
    } else {
      if (nodeType.equals("Paragraph")) addMarkup(node.getStartOffset());
      this.nodeTypeStack.push(nodeType);
      visitChildren(node);
      this.nodeTypeStack.pop();
    }
  }

  @Override
  public void setSettings(Settings settings) {
    this.language = settings.getLanguageShortCode();

    for (Map.Entry<String, String> entry : settings.getMarkdownNodes().entrySet()) {
      String actionString = entry.getValue();
      MarkdownNodeSignature.Action action;
      @Nullable DummyGenerator dummyGenerator = null;

      if (actionString.equals("default")) {
        action = MarkdownNodeSignature.Action.DEFAULT;
      } else if (actionString.equals("ignore")) {
        action = MarkdownNodeSignature.Action.IGNORE;
      } else if (actionString.equals("dummy")) {
        action = MarkdownNodeSignature.Action.DUMMY;
      } else if (actionString.equals("pluralDummy")) {
        action = MarkdownNodeSignature.Action.DUMMY;
        dummyGenerator = DummyGenerator.getDefault(true);
      } else {
        continue;
      }

      if (dummyGenerator == null) dummyGenerator = DummyGenerator.getDefault();
      this.nodeSignatures.add(new MarkdownNodeSignature(entry.getKey(), action, dummyGenerator));
    }
  }
}
