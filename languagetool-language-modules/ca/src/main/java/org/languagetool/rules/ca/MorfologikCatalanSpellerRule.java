/* LanguageTool, a natural language style checker 
 * Copyright (C) 2012 Marcin Miłkowski (http://www.languagetool.org)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package org.languagetool.rules.ca;

import org.languagetool.*;
import org.languagetool.rules.SuggestedReplacement;
import org.languagetool.rules.spelling.SpellingCheckRule;
import org.languagetool.rules.spelling.morfologik.MorfologikSpellerRule;
import org.languagetool.tagging.ca.CatalanTagger;
import org.languagetool.tools.StringTools;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MorfologikCatalanSpellerRule extends MorfologikSpellerRule {

  private String dictFilename;
  private static final String SPELLING_FILE = "/ca/spelling.txt";

  @Override
  public List<String> getAdditionalSpellingFileNames() {
    return Arrays.asList("/ca/"+SpellingCheckRule.CUSTOM_SPELLING_FILE, SpellingCheckRule.GLOBAL_SPELLING_FILE,
      "/ca/multiwords.txt", "/ca/spelling-special.txt");
  }
  private static final List<String> PARTICULA_INICIAL = Arrays.asList("no", "en", "a", "el", "els", "al", "als", "pel",
    "pels", "del", "dels", "del", "de", "per", "un", "uns", "una", "unes", "la", "les", "teu", "meu", "seu", "teus", "meus", "seus");
  private static final List<String> PREFIX_AMB_ESPAI = Arrays.asList("pod", "ultra", "eco", "tele", "anti", "re", "des",
    "avant", "auto", "ex", "extra", "macro", "mega", "meta", "micro", "multi", "mono", "mini", "post", "retro", "semi", "super", "trans", "pro", "g", "l", "m");

  private static final Pattern APOSTROF_INICI_VERBS = Pattern.compile("^([lnts])[90]?(h?[aeiouàéèíòóú].*)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern APOSTROF_INICI_VERBS_M = Pattern.compile("^(m)[90]?(h?[aeiouàéèíòóú].*)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern APOSTROF_INICI_NOM_SING = Pattern.compile("^([ld])[90]?(h?[aeiouàéèíòóú]...+)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern APOSTROF_INICI_NOM_PLURAL = Pattern.compile("^(d)[90]?(h?[aeiouàéèíòóú].+)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern APOSTROF_FINAL = Pattern.compile("^(...+[aei])[90]?(l|ls|m|ns|n|t)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern APOSTROF_FINAL_S = Pattern.compile("^(.+e)[90]?(s)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern GUIONET_FINAL = Pattern.compile(
      "^([\\p{L}·]+)[’']?(hi|ho|la|les|li|lo|los|me|ne|nos|se|te|vos)$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern GUIONET_FINAL_GERUNDI = Pattern.compile(
    "^([\\p{L}·]+n)(hi|ho|la|les|li|lo|los|me|ne|nos|se|te|vos)$",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final List<String> SPLIT_DIGITS_AT_END = Arrays.asList("en", "de", "del", "al", "dels", "als", "a", "i", "o", "amb");
  private static final Pattern VERB_INDSUBJ = Pattern.compile("V.[SI].*");
  private static final Pattern VERB_INDSUBJ_M = Pattern.compile("V.[SI].[123]S.*|V.[SI].[23]P.*");
  private static final Pattern NOM_SING = Pattern.compile("V.[NG].*|V.P..S..|N..[SN].*|A...[SN].|PX..S...|DD..S.");
  private static final Pattern NOM_PLURAL = Pattern.compile("V.P..P..|N..[PN].*|A...[PN].|PX..P...|DD..P.");
  private static final Pattern VERB_INFGERIMP = Pattern.compile("V.[NGM].*");
  private static final Pattern VERB_INF = Pattern.compile("V.N.*");
  private static final Pattern VERB_GER = Pattern.compile("V.G.*");

  /* lemma exceptions */
  public static final String[] LemmasToIgnore =  new String[] {"enterar", "sentar", "conseguir", "alcançar"};
  public static final String[] LemmasToAllow =  new String[] {"enter", "sentir"};

  private static final List<String> inalambric = Arrays.asList("inalàmbric", "inalàmbrica", "inalàmbrics", "inalàmbriques", "inalàmbricament", "inalàmbricamente");

  private CatalanTagger tagger;

  public MorfologikCatalanSpellerRule(ResourceBundle messages, Language language, UserConfig userConfig,
      List<Language> altLanguages) throws IOException {
    super(messages, language, userConfig, altLanguages);
    this.setIgnoreTaggedWords();
    if (language.getVariant() != null) {
      tagger = CatalanTagger.INSTANCE_VAL;
    } else {
      tagger = CatalanTagger.INSTANCE_CAT;
    }
    dictFilename = "/ca/" + language.getShortCodeWithCountryAndVariant() + "_spelling" + JLanguageTool.DICTIONARY_FILENAME_EXTENSION;
  }

  @Override
  public String getFileName() {
    return dictFilename;
  }

  @Override
  public String getSpellingFileName() {
    return SPELLING_FILE;
  }

  @Override
  public String getId() {
    return "MORFOLOGIK_RULE_CA_ES";
  }

  @Override
  // Use this rule in LO/OO extension despite being a spelling rule
  public boolean useInOffice() {
    return true;
  }

  @Override
  protected List<SuggestedReplacement> orderSuggestions(List<SuggestedReplacement> suggestions, String word) {
    // move some run-on-words suggestions to the top
    List<SuggestedReplacement> newSuggestions = new ArrayList<>();
    String wordWithouDiacriticsString = StringTools.removeDiacritics(word);
    for (int i = 0; i < suggestions.size(); i++) {
      String replacement = suggestions.get(i).getReplacement();
      if (inalambric.contains(replacement.toLowerCase())) {
        newSuggestions = new ArrayList<>();
        newSuggestions.add(new SuggestedReplacement("sense fils"));
        newSuggestions.add(new SuggestedReplacement("sense fil"));
        newSuggestions.add(new SuggestedReplacement("sense cables"));
        newSuggestions.add(new SuggestedReplacement("autònom"));
        return newSuggestions;
      }
      // remove always
      if (replacement.equalsIgnoreCase("como")) {
        continue;
      }
      boolean ignoreSuggestion = false;
      String[] wordsToAnalyze = replacement.split(" ");
      List<AnalyzedTokenReadings> newatrs = tagger.tag(Arrays.asList(wordsToAnalyze));
      for (AnalyzedTokenReadings newatr : newatrs) {
        if (newatr.hasAnyLemma(LemmasToIgnore) && !newatr.hasAnyLemma(LemmasToAllow)) {
          ignoreSuggestion = true;
        }
      }
      if (ignoreSuggestion) {
        continue;
      }
      // l'_ : remove superfluous space
      if (replacement.contains("' ")) {
        suggestions.get(i).setReplacement(replacement.replace("' ", "'"));
      }
      String[] parts = replacement.split(" ");
      if (parts.length == 2) {
        if (parts[1].toLowerCase().equals("s")) {
          continue;
        }
        // remove wrong split prefixes
        if (PREFIX_AMB_ESPAI.contains(parts[0].toLowerCase())) {
          continue;
        }
      }

      // Don't change first suggestions if they match word without diacritics
      int posNewSugg = 0;
      while (newSuggestions.size() > posNewSugg
          && StringTools.removeDiacritics(newSuggestions.get(posNewSugg).getReplacement())
              .equalsIgnoreCase(wordWithouDiacriticsString)) {
        posNewSugg++;
      }

      // move some split words to first place
      if (parts.length == 2) {
        if (parts[1].length() > 1 && PARTICULA_INICIAL.contains(parts[0].toLowerCase())) {
          String newSuggestion = parts[1];
          List<AnalyzedTokenReadings> atkn = tagger.tag(Arrays.asList(newSuggestion));
          boolean isBalear = atkn.get(0).hasPosTag("VMIP1S0B") && !atkn.get(0).hasPosTagStartingWith("N");
          if (!isBalear) {
            newSuggestions.add(posNewSugg, suggestions.get(i));
            continue;
          }
        }
      }

      String suggWithoutDiacritics = StringTools.removeDiacritics(replacement);
      if (StringTools.removeDiacritics(word).equalsIgnoreCase(suggWithoutDiacritics)) {
        newSuggestions.add(posNewSugg, suggestions.get(i));
        continue;
      }

      // move words with apostrophe or hyphen to second position
      String cleanSuggestion = replacement.replaceAll("'", "").replaceAll("-", "");
      if (i > 1 && suggestions.size() > 2 && cleanSuggestion.equalsIgnoreCase(word)) {
        if (posNewSugg == 0) {
          posNewSugg = 1;
        }
        newSuggestions.add(posNewSugg, suggestions.get(i));
        continue;
      }
      // move "queda'n" to second position
      if (i == 1) {
        if (suggestions.get(0).getReplacement().endsWith("'n") || suggestions.get(0).getReplacement().endsWith("'t")) {
          newSuggestions.add(0, suggestions.get(i));
          continue;
        }
      }
      newSuggestions.add(suggestions.get(i));
    }
    return newSuggestions;
  }

  @Override
  protected List<SuggestedReplacement> getAdditionalTopSuggestions(List<SuggestedReplacement> suggestions, String word)
      throws IOException {
    List<String> suggestionsList = suggestions.stream().map(SuggestedReplacement::getReplacement)
        .collect(Collectors.toList());
    return SuggestedReplacement.convert(getAdditionalTopSuggestionsString(suggestionsList, word));
  }

  private List<String> getAdditionalTopSuggestionsString(List<String> suggestions, String word) throws IOException {
    /*
     * if (word.length() < 5) { return Collections.emptyList(); }
     */
    String[] parts = StringTools.splitCamelCase(word);
    if (parts.length > 1) {
      boolean isTagged = true;
      for(String part: parts) {
        isTagged &= tagger.tag(Arrays.asList(part)).get(0).isTagged();
      }
      if (isTagged) {
        return Collections.singletonList(String.join(" ",parts));
      }
    }
    parts = StringTools.splitDigitsAtEnd(word);
    if (parts.length > 1) {
      if (tagger.tag(Arrays.asList(parts[0])).get(0).isTagged()
      && (parts[0].length() > 2 || SPLIT_DIGITS_AT_END.contains(parts[0].toLowerCase()))) {
        return Collections.singletonList(String.join(" ",parts));
      }
    }
    String suggestion = "";
    suggestion = findSuggestion(suggestion, word, APOSTROF_INICI_VERBS, VERB_INDSUBJ, 2, "'", "");
    suggestion = findSuggestion(suggestion, word, APOSTROF_INICI_VERBS_M, VERB_INDSUBJ_M, 2, "'", "");
    suggestion = findSuggestion(suggestion, word, APOSTROF_INICI_NOM_SING, NOM_SING, 2, "'", "");
    suggestion = findSuggestion(suggestion, word, APOSTROF_INICI_NOM_PLURAL, NOM_PLURAL, 2, "'", "");
    suggestion = findSuggestion(suggestion, word, APOSTROF_FINAL, VERB_INFGERIMP, 1, "'", "");
    suggestion = findSuggestion(suggestion, word, APOSTROF_FINAL_S, VERB_INF, 1, "'", "");
    suggestion = findSuggestion(suggestion, word, GUIONET_FINAL_GERUNDI, VERB_GER, 1, "-", "t");
    suggestion = findSuggestion(suggestion, word, GUIONET_FINAL, VERB_INFGERIMP, 1, "-", "");

    if (!suggestion.isEmpty()) {
      return Collections.singletonList(suggestion);
    }
    return Collections.emptyList();
  }

  private String findSuggestion(String suggestion, String word, Pattern wordPattern, Pattern postagPattern,
      int suggestionPosition, String separator, String addStr) throws IOException {
    if (!suggestion.isEmpty()) {
      return suggestion;
    }
    Matcher matcher = wordPattern.matcher(word);
    if (matcher.matches()) {
      String newSuggestion = matcher.group(suggestionPosition) + addStr;
      AnalyzedTokenReadings newatr = tagger.tag(Arrays.asList(newSuggestion)).get(0);
      if ((!newatr.hasPosTag("VMIP1S0B") || newSuggestion.equalsIgnoreCase("fer") || newSuggestion.equalsIgnoreCase("ajust")
          || newSuggestion.equalsIgnoreCase("gran")) && matchPostagRegexp(newatr, postagPattern)) {
        return matcher.group(1) + addStr + separator + matcher.group(2);
      }
    }
    return "";
  }

  /**
   * Match POS tag with regular expression
   */
  private boolean matchPostagRegexp(AnalyzedTokenReadings aToken, Pattern pattern) {
    for (AnalyzedToken analyzedToken : aToken) {
      String posTag = analyzedToken.getPOSTag();
      if (posTag == null) {
        posTag = "UNKNOWN";
      }
      final Matcher m = pattern.matcher(posTag);
      if (m.matches()) {
        return true;
      }
    }
    return false;
  }

  // Do not tokenize new words from spelling.txt...
  // Multi-token words should be in multiwords.txt
  protected boolean tokenizeNewWords() {
    return false;
  }

}
