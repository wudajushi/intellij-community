package com.jetbrains.python.lexer;

import com.intellij.psi.tree.IElementType;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.LanguageLevel;

/**
 * @author yole
 */
public class PythonHighlightingLexer extends PythonLexer {
  private final LanguageLevel myLanguageLevel;

  public PythonHighlightingLexer(LanguageLevel languageLevel) {
    myLanguageLevel = languageLevel;
  }

  @Override
  public IElementType getTokenType() {
    if (myLanguageLevel.hasWithStatement()) {
      final String tokenText = getTokenText();
      if (tokenText.equals("with")) return PyTokenTypes.WITH_KEYWORD;
      if (tokenText.equals("as")) return PyTokenTypes.AS_KEYWORD;
    }
    if (myLanguageLevel.hasPrintStatement()) {
      final String tokenText = getTokenText();
      if (tokenText.equals("print")) return PyTokenTypes.PRINT_KEYWORD;
    }
    return super.getTokenType();
  }
}
