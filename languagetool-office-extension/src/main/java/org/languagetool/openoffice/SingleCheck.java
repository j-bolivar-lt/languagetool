/* LanguageTool, a natural language style checker
 * Copyright (C) 2011 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.openoffice;

import java.awt.Color;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.gui.Configuration;
import org.languagetool.openoffice.DocumentCache.TextParagraph;
import org.languagetool.openoffice.OfficeTools.DocumentType;
import org.languagetool.openoffice.OfficeTools.LoErrorType;
import org.languagetool.openoffice.ResultCache.CacheEntry;
import org.languagetool.rules.RuleMatch;
import org.languagetool.tools.StringTools;

import com.sun.star.beans.PropertyState;
import com.sun.star.beans.PropertyValue;
import com.sun.star.lang.Locale;
import com.sun.star.lang.XComponent;
import com.sun.star.linguistic2.SingleProofreadingError;
import com.sun.star.text.TextMarkupType;

/**
 * Class for processing one LO/OO check request
 * Note: There can be some parallel requests from background iteration, dialog, right mouse click or LT text level iteration
 * Gives back the matches found by LT
 * Adds matches to result cache
 * @since 5.3
 * @author Fred Kruse, (including some methods developed by Marcin Miłkowski)
 */
class SingleCheck {
  
  /**
   * Full text Check:
   * numParasToCheck: Paragraphs to be checked for full text rules
   * < 0 check full text (time intensive)
   * == 0 check only one paragraph (works like LT Version <= 3.9)
   * > 0 checks numParasToCheck before and after the processed paragraph
   * 
   * Cache:
   * sentencesCache: only used for doResetCheck == true (LO checks again only changed paragraphs by default)
   * paragraphsCache: used to store LT matches for a fast return to LO (numParasToCheck != 0)
   * singleParaCache: used for one paragraph check by default or for special paragraphs like headers, footers, footnotes, etc.
   *  
   */
  
  private static int debugMode;                     //  should be 0 except for testing; 1 = low level; 2 = advanced level

  private final SingleDocument singleDocument;      //  handles one document
  private final MultiDocumentsHandler mDocHandler;  //  handles the different documents loaded in LO/OO
  private final XComponent xComponent;              //  XComponent of the open document
  private final Configuration config;
  private final DocumentCache docCache;             //  cache of paragraphs (only readable by parallel thread)
  private final List<Integer> minToCheckPara;       //  List of minimal to check paragraphs for different classes of text level rules
  private final List<ResultCache> paragraphsCache;  //  Cache for matches of text rules
  private final int numParasToCheck;                //  current number of Paragraphs to be checked
  private final DocumentType docType;               //  save the type of document
  private final boolean isDialogRequest;            //  true: check was initiated by proofreading dialog
  private final boolean isMouseRequest;             //  true: check was initiated by right mouse click
  private final boolean isIntern;                   //  true: check was initiated by intern check dialog
  private final boolean useQueue;                   //  true: use queue to check text level rules (will be overridden by config)
  private final Language docLanguage;               //  docLanguage (usually the Language of the first paragraph)
  private final Language fixedLanguage;             //  fixed language (by configuration); if null: use language of document (given by LO/OO)
//  private final IgnoredMatches ignoredMatches;      //  Map of matches (number of paragraph, number of character) that should be ignored after ignoreOnce was called
//  private final IgnoredMatches permanentIgnoredMatches; //  Map of matches (number of paragraph, number of character) that should be ignored permanent
//  private DocumentCursorTools docCursor;            //  Save document cursor for the single document
//  private FlatParagraphTools flatPara;              //  Save information for flat paragraphs (including iterator and iterator provider) for the single document

  private int changeFrom = 0;                       //  Change result cache from paragraph
  private int changeTo = 0;                         //  Change result cache to paragraph
  private String lastSinglePara = null;             //  stores the last paragraph which is checked as single paragraph

  private List<Integer> changedParas;               //  List of changed paragraphs after editing the document
  
  SingleCheck(SingleDocument singleDocument, List<ResultCache> paragraphsCache,
      Language fixedLanguage, Language docLanguage, 
//      IgnoredMatches ignoredMatches, IgnoredMatches permanentIgnoredMatches, 
      int numParasToCheck, boolean isDialogRequest, boolean isMouseRequest, boolean isIntern) {
    debugMode = OfficeTools.DEBUG_MODE_SC;
    this.singleDocument = singleDocument;
    this.paragraphsCache = paragraphsCache;
//    this.docCursor = docCursor;
//    this.flatPara = flatPara;
    this.numParasToCheck = numParasToCheck;
    this.isDialogRequest = isDialogRequest;
    this.isMouseRequest = isMouseRequest;
    this.isIntern = isIntern;
    this.docLanguage = docLanguage;
    this.fixedLanguage = fixedLanguage;
//    this.ignoredMatches = ignoredMatches;
//    this.permanentIgnoredMatches = permanentIgnoredMatches;
    mDocHandler = singleDocument.getMultiDocumentsHandler();
    xComponent = singleDocument.getXComponent();
    docCache = singleDocument.getDocumentCache();
    docType = singleDocument.getDocumentType();
    config = mDocHandler.getConfiguration();
    useQueue = numParasToCheck != 0 && !isDialogRequest && !mDocHandler.isTestMode() && config.useTextLevelQueue();
    minToCheckPara = mDocHandler.getNumMinToCheckParas();
    changedParas = new ArrayList<>();
  }
  
  /**
   *   get the result for a check of a single document 
   */
  public SingleProofreadingError[] getCheckResults(String paraText, int[] footnotePositions, Locale locale, SwJLanguageTool lt, 
      int paraNum, int startOfSentence, boolean textIsChanged, int changeFrom, int changeTo, String lastSinglePara, 
      int lastChangedPara, LoErrorType errType) {
    if (isDisposed()) {
      return new SingleProofreadingError[0];
    }
    if (docType == DocumentType.WRITER && !isIntern && lastChangedPara >= 0) {
//      if (docCursor == null) {
//        docCursor = new DocumentCursorTools(xComponent);
//      }
      List<Integer> changedParas = singleDocument.getLastChangedParas();
      if (changedParas == null) {
        changedParas = new ArrayList<Integer>();
      } else {
        singleDocument.setLastChangedParas(null);
      }
      if (changedParas.contains(lastChangedPara) )
      changedParas.add(lastChangedPara);
      remarkChangedParagraphs(changedParas, changedParas, lt, true);
    }
    this.lastSinglePara = lastSinglePara;
    if (numParasToCheck != 0 && paraNum >= 0) {
      //  test real flat paragraph rather then the one given by Proofreader - it could be changed meanwhile
      //  Don't use Cache for check in single paragraph mode
      paraText = docCache.getFlatParagraph(paraNum);
    }
    List<SingleProofreadingError[]> pErrors = checkTextRules(paraText, locale, footnotePositions, paraNum, 
                                                                      startOfSentence, lt, textIsChanged, isIntern, errType);
    startOfSentence = paragraphsCache.get(0).getStartSentencePosition(paraNum, startOfSentence);
    SingleProofreadingError[] errors = singleDocument.mergeErrors(pErrors, paraNum);
    if (debugMode > 1) {
      MessageHandler.printToLogFile("SingleCheck: getCheckResults: paRes.aErrors.length: " + errors.length 
          + "; docID: " + singleDocument.getDocID());
    }
    if (!isDisposed() && docType == DocumentType.WRITER && numParasToCheck != 0 && paraNum >= 0 && (textIsChanged || isDialogRequest)) {
//      if (docCursor == null && !isDisposed()) {
//        docCursor = new DocumentCursorTools(xComponent);
//      }
      if (!isIntern && isDialogRequest && !textIsChanged) {
        List<Integer> changedParas = new ArrayList<Integer>();
        changedParas.add(paraNum);
        remarkChangedParagraphs(changedParas, changedParas, lt, true);
      } else if (textIsChanged && (!useQueue || isDialogRequest)) {
        remarkChangedParagraphs(changedParas, changedParas, lt, true);
      }
    }
    return errors;
  }
  
  /**
   *   check for number of Paragraphs > 0, chapter wide or full text
   *   is also called by text level queue
   */
  public void addParaErrorsToCache(int nFPara, SwJLanguageTool lt, int cacheNum, int parasToCheck, 
        boolean checkOnlyParagraph, boolean override, boolean isIntern, boolean hasFootnotes) {
    //  make the method thread save
    MultiDocumentsHandler mDH = mDocHandler;
    if (isDisposed() || docCache == null || nFPara < 0 || nFPara >= docCache.size()) {
      MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: return: isDisposed = " + isDisposed() + ", nFPara = " + nFPara 
          + ", docCache(Size) = " + (docCache == null ? "null" : docCache.size()) );
      return;
    }
    DocumentCache docCache = new DocumentCache(this.docCache);
    if (debugMode > 0 && lt == null && !docCache.isAutomaticGenerated(nFPara)) {
      MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: return: lt is null");
    }
    try {

      ResultCache oldCache = null;
      if (useQueue && !isDialogRequest) {
        oldCache = new ResultCache(paragraphsCache.get(cacheNum));
      }
      
      TextParagraph tPara = docCache.getNumberOfTextParagraph(nFPara);
      if (tPara.type < 0 || tPara.number < 0) {
        MessageHandler.printToLogFile("WARNING: doc cache corrupted (at SingleCheck: addParaErrorsToCache) : refresh doc cache!");
        this.docCache.refresh(singleDocument, LinguisticServices.getLocale(fixedLanguage), 
            LinguisticServices.getLocale(docLanguage), xComponent, 7);
        docCache = new DocumentCache(this.docCache);
        tPara = docCache.getNumberOfTextParagraph(nFPara);
        if (tPara.type < 0 || tPara.number < 0) {
          MessageHandler.printToLogFile("Error: doc cache problem: error cache(" + cacheNum 
              + ") set empty for nFpara = " + nFPara + "!");
          paragraphsCache.get(cacheNum).put(nFPara, null, new SingleProofreadingError[0]);
          oldCache = null;
          return;
        }
      }
      int cursorType = tPara.type;
      
      String textToCheck = docCache.getDocAsString(tPara, parasToCheck, checkOnlyParagraph, useQueue, hasFootnotes);
      List<RuleMatch> paragraphMatches = null;
      List<Integer> nextSentencePositions = null;
      //  NOTE: lt == null if language is not supported by LT
      //        but empty proof reading errors have added to cache to satisfy text level queue
      if (lt != null && mDocHandler.isSortedRuleForIndex(cacheNum)) {
        if (!docCache.isAutomaticGenerated(nFPara)) {
          paragraphMatches = lt.check(textToCheck, true, 
              cacheNum == 0 ? JLanguageTool.ParagraphHandling.NORMAL : JLanguageTool.ParagraphHandling.ONLYPARA);
        }
        if (cacheNum == 0) {
          nextSentencePositions = getNextSentencePositions(textToCheck, lt);
        }
      }
      
      int startPara = docCache.getStartOfParaCheck(tPara, parasToCheck, checkOnlyParagraph, useQueue, false);
      int endPara = docCache.getEndOfParaCheck(tPara, parasToCheck, checkOnlyParagraph, useQueue, false);
      int startPos = docCache.getStartOfParagraph(startPara, tPara, parasToCheck, checkOnlyParagraph, useQueue, hasFootnotes);
      int endPos;
      if (debugMode > 1) {
        MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: tPara.type = " + tPara.type + "; tPara.number = " + tPara.number);
        MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: nFPara = " + nFPara + "; startPara = " + startPara + "; endPara = " + endPara);
      }
      for (int i = startPara; i < endPara; i++) {
        if (isDisposed() || (useQueue && !isDialogRequest && (mDH.getTextLevelCheckQueue() == null || mDH.getTextLevelCheckQueue().isInterrupted()))) {
          MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: return: isDisposed = " + isDisposed() + ", useQueue = " + useQueue
              + ", isDialogRequest = " + isDialogRequest + ", TextLevelCheckQueue(isInterrupted) = " 
              + (mDH.getTextLevelCheckQueue() == null ? "null" : mDH.getTextLevelCheckQueue().isInterrupted()));
          oldCache = null;
          return;
        }
        TextParagraph textPara = docCache.createTextParagraph(cursorType, i);
        int[] footnotePos = docCache.getTextParagraphFootnotes(textPara);
        if (i < endPara - 1) {
          endPos = docCache.getStartOfParagraph(i + 1, tPara, parasToCheck, checkOnlyParagraph, useQueue, hasFootnotes);
        } else {
          endPos = textToCheck.length();
        }
        if (paragraphMatches == null || paragraphMatches.isEmpty()) {
          paragraphsCache.get(cacheNum).put(docCache.getFlatParagraphNumber(textPara), nextSentencePositions, new SingleProofreadingError[0]);
          if (debugMode > 1) {
            MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: Enter to para cache(" + cacheNum + "): Paragraph(" 
                + docCache.getFlatParagraphNumber(textPara) + "): " + docCache.getTextParagraph(textPara) + "; Error number: 0");
          }
        } else {
          List<SingleProofreadingError> errorList = new ArrayList<>();
          int textPos = startPos;
          if (textPos < 0) textPos = 0;
          for (RuleMatch myRuleMatch : paragraphMatches) {
            if (isCorrectRuleMatch(myRuleMatch, textToCheck, lt.getLanguage())) {
              int startErrPos = myRuleMatch.getFromPos();
              if (debugMode > 2) {
                MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: Cache = " + cacheNum 
                    + ", startPos = " + startPos + ", endPos = " + endPos + ", startErrPos = " + startErrPos);
              }
              if (startErrPos >= startPos && startErrPos < endPos) {
                int toPos = docCache.getTextParagraph(textPara).length();
                if (toPos > 0) {
                  errorList.add(correctRuleMatchWithFootnotes(
                      createOOoError(myRuleMatch, -textPos, footnotePos),
                        footnotePos, docCache.getTextParagraphDeletedCharacters(textPara)));
                }
              }
            }
          }
          if (!errorList.isEmpty()) {
            paragraphsCache.get(cacheNum).put(docCache.getFlatParagraphNumber(textPara), nextSentencePositions, errorList.toArray(new SingleProofreadingError[0]));
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: Enter to para cache(" + cacheNum + "): Paragraph(" 
                  + docCache.getFlatParagraphNumber(textPara) + "): " + docCache.getTextParagraph(textPara) 
                  + "; Error number: " + errorList.size());
            }
          } else {
            paragraphsCache.get(cacheNum).put(docCache.getFlatParagraphNumber(textPara), nextSentencePositions, new SingleProofreadingError[0]);
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: Enter to para cache(" + cacheNum + "): Paragraph(" 
                  + docCache.getFlatParagraphNumber(textPara) + "): " + docCache.getTextParagraph(textPara) + "; Error number: 0");
            }
          }
        }
        startPos = endPos;
      }
      if (!isDisposed() && docType == DocumentType.WRITER && useQueue && !isDialogRequest) {
        if (mDH.getTextLevelCheckQueue() == null || mDH.getTextLevelCheckQueue().isInterrupted()) {
          oldCache = null;
          return;
        }
//        flatPara = singleDocument.getFlatParagraphTools();
        
        List<Integer> changedParas = new ArrayList<>();
        List<Integer> toRemarkParas = new ArrayList<>();
        if (cacheNum == 0) {
          changedParas.add(nFPara);
          remarkChangedParagraphs(changedParas, changedParas, lt, true);
        } else if (oldCache != null) {
          for (int nText = startPara; nText < endPara; nText++) {
            int nFlat = docCache.getFlatParagraphNumber(docCache.createTextParagraph(cursorType, nText));
            if (paragraphsCache.get(0).getCacheEntry(nFlat) != null) {
              if (ResultCache.areDifferentEntries(paragraphsCache.get(cacheNum).getSerialCacheEntry(nFlat), oldCache.getSerialCacheEntry(nFlat))) {
                changedParas.add(nFlat);
                if(!ResultCache.isEmptyEntry(oldCache.getSerialCacheEntry(nFlat))) {
                  toRemarkParas.add(nFlat);
                }
              }
            }
          }
          if (!changedParas.isEmpty()) {
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: Cache(" + cacheNum + "): Mark paragraphs from " 
                  + startPara + " to " + endPara + ": " + changedParas.size() 
                  + " changes, tPara.type: " + tPara.type + ", tPara.number: " + tPara.number + ", nFPara: " + nFPara);
              String tmpText = "Changed Paras: ";
              for (int n : changedParas) {
                tmpText += n + " ";
              }
              MessageHandler.printToLogFile(tmpText);
            }
            singleDocument.setLastChangedParas(changedParas);
            remarkChangedParagraphs(changedParas, toRemarkParas, lt, true);
          } else if (debugMode > 1) {
            MessageHandler.printToLogFile("SingleCheck: addParaErrorsToCache: Cache(" + cacheNum + ") Mark paragraphs from " + startPara 
                + " to " + endPara + ": No Paras to Mark, tPara.type: " + tPara.type + ", tPara.number: " + tPara.number + ", nFPara: " + nFPara);
          }
        }
      }
      oldCache = null;
    } catch (Throwable t) {
      MessageHandler.showError(t);
    }
  }
  
  /**
   * remark changed paragraphs
   * override existing marks
   */
  public void remarkChangedParagraphs(List<Integer> changedParas, List<Integer> toRemarkParas, 
                                                  SwJLanguageTool lt, boolean override) {
    if (!isDisposed() && !mDocHandler.isBackgroundCheckOff() && (!isDialogRequest || isIntern)) {
      
      Map <Integer, List<SentenceErrors>> changedParasMap = new HashMap<>();
      List <TextParagraph> toRemarkTextParas = new ArrayList<>();
      for (int i = 0; i < changedParas.size(); i++) {
        List<SentenceErrors> sentencesErrors = getSentencesErrosAsList(changedParas.get(i), lt, LoErrorType.GRAMMAR);
        changedParasMap.put(changedParas.get(i), sentencesErrors);
        if (debugMode > 1) {
          String message = "SingleCheck: remarkChangedParagraphs: Mark errors: Paragraph: " + changedParas.get(i) 
          + "; Number of sentences: " + sentencesErrors.size();
          for (int j = 0; j < sentencesErrors.size(); j++) {
            message += "; Sentence " + j + ": Start = " + sentencesErrors.get(j).sentenceStart + "; End = " + sentencesErrors.get(j).sentenceEnd 
                        + ", Number of Errors = " + sentencesErrors.get(j).sentenceErrors.length;
          }
          MessageHandler.printToLogFile(message);
          message = "SingleCheck: remarkChangedParagraphs: Errors of Sentence 0: ";
          for (int j = 0; j < sentencesErrors.get(0).sentenceErrors.length; j++) {
            message += "Error " + j + ": Start = " + sentencesErrors.get(0).sentenceErrors[j].nErrorStart 
                + ", Length = " + sentencesErrors.get(0).sentenceErrors[j].nErrorLength 
                + "; ErrorID = " + sentencesErrors.get(0).sentenceErrors[j].aRuleIdentifier + "; ";
          }
          MessageHandler.printToLogFile(message);
          for (int j = 0; j < paragraphsCache.size(); j++) {
            MessageHandler.printToLogFile("SingleCheck: remarkChangedParagraphs: Paragraph " + changedParas.get(i) + ": Cache " + j 
                    + ": Number of Errors = " 
                    + (paragraphsCache.get(j).getMatches(changedParas.get(i)) == null ? "null" : paragraphsCache.get(j).getMatches(changedParas.get(i)).length));
          }
        }
      }
      for (int i = 0; i < toRemarkParas.size(); i++) {
        toRemarkTextParas.add(docCache.getNumberOfTextParagraph(toRemarkParas.get(i)));
        if (debugMode > 1) {
          String message = "SingleCheck: remarkChangedParagraphs: Remark: Paragraph: " + toRemarkParas.get(i);
          MessageHandler.printToLogFile(message);
        }
      }
      if (!isDisposed() && !toRemarkTextParas.isEmpty()) {
        DocumentCursorTools docCursor = singleDocument.getDocumentCursorTools();
        if (docCursor != null) {
          docCursor.removeMarks(toRemarkTextParas);
        }
      }
      if (!isDisposed()) {
        FlatParagraphTools flatPara = singleDocument.getFlatParagraphTools();
        if (flatPara != null) {
          flatPara.markParagraphs(changedParasMap);
        }
      }
    }
  }
  
  /**
   *  return last single paragraph (not text paragraph)
   */
  public String getLastSingleParagraph () {
    return lastSinglePara;
  }

  /**
   *  Is document disposed?
   */
  private boolean isDisposed() {
    return singleDocument.isDisposed();
  }

    /**
   * check text rules 
   * different caches are supported for check of different number of paragraphs at once 
   * (for different kinds of text level rules)
   */
  private List<SingleProofreadingError[]> checkTextRules( String paraText, Locale locale, int[] footnotePos, int paraNum, 
      int startSentencePos, SwJLanguageTool lt, boolean textIsChanged, boolean isIntern, LoErrorType errType) {
    List<SingleProofreadingError[]> pErrors = new ArrayList<>();
    if (isDisposed()) {
      return pErrors;
    }
    TextParagraph nTParas = paraNum < 0 ? null : docCache.getNumberOfTextParagraph(paraNum);
    if (nTParas == null || nTParas.type == DocumentCache.CURSOR_TYPE_UNKNOWN || docCache.isSingleParagraph(paraNum)) {
      pErrors.add(checkParaRules(paraText, locale, footnotePos, paraNum, startSentencePos, lt, 0, 0, textIsChanged, isIntern, errType));
    } else {
      //  Real full text check / numParas < 0
      ResultCache oldCache = null;
      List<Integer> tmpChangedParas;
      for (int i = 0; i < minToCheckPara.size(); i++) {
        int parasToCheck = minToCheckPara.get(i);
        if (i == 0 || mDocHandler.isSortedRuleForIndex(i)) {
          mDocHandler.activateTextRulesByIndex(i, lt);
          if (debugMode > 0) {
            MessageHandler.printToLogFile("SingleCheck: checkTextRules: Index: " + i + "/" + minToCheckPara.size() 
            + "; paraNum: " + paraNum + "; numParasToCheck: " + parasToCheck + "; useQueue: " + useQueue);
          }
          if (textIsChanged && !useQueue && parasToCheck != 0 ) {
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: checkTextRules: Copy old cache!");
            }
            oldCache = new ResultCache(paragraphsCache.get(i));
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: checkTextRules: Old cache copied!");
            }
          }
          pErrors.add(checkParaRules(paraText, locale, footnotePos, paraNum, startSentencePos, lt, i, parasToCheck, textIsChanged, isIntern, errType));
          if (debugMode > 1) {
            MessageHandler.printToLogFile("SingleCheck: checkTextRules: Error Cache added!");
          }
          if (!isDisposed() && textIsChanged && !useQueue) {
            if (parasToCheck != 0) {
              tmpChangedParas = paragraphsCache.get(i).differenceInCaches(oldCache);
              for (int chPara : tmpChangedParas) {
                if (!changedParas.contains(chPara)) {
                  changedParas.add(chPara);
                }
              }
              if (!changedParas.contains(paraNum)) {
                changedParas.add(paraNum);
              }
              oldCache = null;
            } else {
              addChangedParas();
            }
          } 
        } else {
          pErrors.add(new SingleProofreadingError[0]);
        }
      }
      mDocHandler.reactivateTextRules(lt);
      if (debugMode > 1) {
        MessageHandler.printToLogFile("SingleCheck: checkTextRules: Text rules reactivated");
      }
    }
    return pErrors;
  }
  
  /**
   * add the numbers of changed paragraphs to list
   */
  private void addChangedParas() {
    int firstPara = changeFrom;
    if (firstPara < 0) {
      firstPara = 0;
    }
    int lastPara = changeTo;
    if (lastPara > docCache.size()) {
      lastPara = docCache.size();
    }
    for (int n = firstPara; n < lastPara; n++) {
      if (!changedParas.contains(n)) {
        changedParas.add(n);
      }
    }
  }

  /**
   * check the text level rules associated with a given cache (cacheNum)
   */
  @Nullable
  public SingleProofreadingError[] checkParaRules(String paraText, Locale locale, int[] footnotePos, int nFPara, int sentencePos, 
          SwJLanguageTool lt, int cacheNum, int parasToCheck, boolean textIsChanged, boolean isIntern, LoErrorType errType) {

    List<RuleMatch> paragraphMatches;
    SingleProofreadingError[] pErrors = null;
    int startSentencePos = 0;
    int endSentencePos = 0;
    try {
      if (isDisposed()) {
        return pErrors;
      }
      boolean isMultiLingual = nFPara >= 0 ? docCache.isMultilingualFlatParagraph(nFPara) : false;
      // use Cache for check in single paragraph mode only after the first call of paragraph
      if (nFPara >= 0 || (sentencePos > 0 && lastSinglePara != null && lastSinglePara.equals(paraText))) {
        if (paragraphsCache.get(0).getCacheEntry(nFPara) != null) {
          startSentencePos = paragraphsCache.get(0).getStartSentencePosition(nFPara, sentencePos);
          endSentencePos = paragraphsCache.get(0).getNextSentencePosition(nFPara, sentencePos);
          pErrors = paragraphsCache.get(cacheNum).getFromPara(nFPara, startSentencePos, endSentencePos, errType);
          if (debugMode > 0 && pErrors != null) {
            String eInfo = ", ";
            for (SingleProofreadingError error : pErrors) {
              eInfo += "(" + error.nErrorStart + "/" + error.nErrorLength + "), ";
            }
            MessageHandler.printToLogFile("SingleCheck: checkParaRules: Para: " + nFPara + "; pErrors from cache(" + cacheNum + "): " + pErrors.length
                + ", start = " + startSentencePos + ", end = " + endSentencePos + eInfo);
          }
        }
      } else if (sentencePos == 0) {
        lastSinglePara = paraText;
      }
      // return Cache result if available / for right mouse click or Dialog only use cache
      boolean isTextParagraph = nFPara >= 0 && docCache != null && docCache.getNumberOfTextParagraph(nFPara).type != DocumentCache.CURSOR_TYPE_UNKNOWN
          && !docCache.isSingleParagraph(nFPara);
      if (nFPara >= 0 && (pErrors != null || isMouseRequest || (useQueue && !isDialogRequest && parasToCheck != 0))) {
        if (useQueue && pErrors == null && parasToCheck != 0 && isTextParagraph) {
          singleDocument.addQueueEntry(nFPara, cacheNum, parasToCheck, singleDocument.getDocID(), textIsChanged);
        }
        return pErrors;
      }
      
      //  One paragraph check
      if (!isTextParagraph || parasToCheck == 0) {
        Locale primaryLocale = isMultiLingual ? docCache.getFlatParagraphLocale(nFPara) : locale;
        SwJLanguageTool mLt;
        if (OfficeTools.isEqualLocale(primaryLocale, locale) || !MultiDocumentsHandler.hasLocale(primaryLocale)) {
          mLt = lt;
        } else {
          mLt = mDocHandler.initLanguageTool(MultiDocumentsHandler.getLanguage(primaryLocale), false);
          mDocHandler.initCheck(mLt);
        }
        List<Integer> nextSentencePositions = getNextSentencePositions(paraText, mLt);
        List<Integer> deletedChars = isTextParagraph ? docCache.getFlatParagraphDeletedCharacters(nFPara): null;
        if (mLt == null || (nFPara >= 0 && docCache != null && docCache.isAutomaticGenerated(nFPara))) {
          paragraphMatches = null;
        } else {
          paraText = removeFootnotes(paraText, footnotePos, deletedChars);
          paragraphMatches = mLt.check(paraText, true, JLanguageTool.ParagraphHandling.NORMAL);
        }
        if (isDisposed()) {
          return null;
        }
        if (paragraphMatches == null || paragraphMatches.isEmpty()) {
          paragraphsCache.get(cacheNum).put(nFPara, nextSentencePositions, new SingleProofreadingError[0]);
          if (debugMode > 1) {
            MessageHandler.printToLogFile("SingleCheck: checkParaRules: Enter " + (isMultiLingual ? "only para " : " ") + "errors to cache(" 
                + cacheNum + "): Paragraph(" + nFPara + "): " + paraText + "; Error number: " + 0);
          }
        } else {
          List<SingleProofreadingError> errorList = new ArrayList<>();
          for (RuleMatch myRuleMatch : paragraphMatches) {
            if (isCorrectRuleMatch(myRuleMatch, paraText, lt.getLanguage())) {
              int toPos = myRuleMatch.getToPos();
              if (toPos > paraText.length()) {
                toPos = paraText.length();
              }
              errorList.add(correctRuleMatchWithFootnotes(
                  createOOoError(myRuleMatch, 0, footnotePos), footnotePos, deletedChars));
            }
          }
          if (!errorList.isEmpty()) {
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: checkParaRules: Enter " + (isMultiLingual ? "only para " : " ") + "errors to cache(" 
                  + cacheNum + "): Paragraph(" + nFPara + "): " + paraText + "; Error number: " + errorList.size());
            }
            paragraphsCache.get(cacheNum).put(nFPara, nextSentencePositions, errorList.toArray(new SingleProofreadingError[0]));
          } else {
            if (debugMode > 1) {
              MessageHandler.printToLogFile("SingleCheck: checkParaRules: Enter " + (isMultiLingual ? "only para " : " ") + "errors to cache(" 
                  + cacheNum + "): Paragraph(" + nFPara + "): " + nFPara + "): " + paraText + "; Error number: " + 0);
            }
            paragraphsCache.get(cacheNum).put(nFPara, nextSentencePositions, new SingleProofreadingError[0]);
          }
        }
        startSentencePos = paragraphsCache.get(cacheNum).getStartSentencePosition(nFPara, sentencePos);
        endSentencePos = paragraphsCache.get(cacheNum).getNextSentencePosition(nFPara, sentencePos);
        return paragraphsCache.get(cacheNum).getFromPara(nFPara, startSentencePos, endSentencePos, errType);
      }

      //  check of numParasToCheck or full text 
      if (isDisposed()) {
        return null;
      }
      addParaErrorsToCache(nFPara, lt, cacheNum, parasToCheck, (cacheNum == 0), textIsChanged, isIntern, (footnotePos != null));
      return paragraphsCache.get(cacheNum).getFromPara(nFPara, startSentencePos, endSentencePos, errType);

    } catch (Throwable t) {
      MessageHandler.showError(t);
    }
    return null;
  }
  
  /**
   * is a grammar error or a correct spell error
   */
  private boolean isCorrectRuleMatch(RuleMatch ruleMatch, String text, Language lang) {
    if (!ruleMatch.getRule().isDictionaryBasedSpellingRule()) {
      return true;
    }
    String word = text.substring(ruleMatch.getFromPos(), ruleMatch.getToPos());
    if (!mDocHandler.getLinguisticServices().isCorrectSpell(word, lang)) {
      if (debugMode > 0) {
        MessageHandler.printToLogFile("SingleCheck: checkParaRules: not correct spelled word: " + word + "; lang: " + lang.toString());
      }
      return true;
    }
    return false;
  }
  
  /**
   * Creates a SingleGrammarError object for use in LO/OO.
   */
  private SingleProofreadingError createOOoError(RuleMatch ruleMatch, int startIndex, int[] footnotes) {
    SingleProofreadingError aError = new SingleProofreadingError();
    if (ruleMatch.getRule().isDictionaryBasedSpellingRule()) {
      aError.nErrorType = TextMarkupType.SPELLCHECK;
    } else {
      aError.nErrorType = TextMarkupType.PROOFREADING;
    }
    // the API currently has no support for formatting text in comments
    String msg = ruleMatch.getMessage();
    if (docLanguage != null) {
      msg = docLanguage.toAdvancedTypography(msg);
    }
    msg = msg.replaceAll("<suggestion>", docLanguage == null ? "\"" : docLanguage.getOpeningDoubleQuote())
        .replaceAll("</suggestion>", docLanguage == null ? "\"" : docLanguage.getClosingDoubleQuote())
        .replaceAll("([\r]*\n)", " "); 
    aError.aFullComment = msg;
    // not all rules have short comments
    if (!StringTools.isEmpty(ruleMatch.getShortMessage())) {
      aError.aShortComment = ruleMatch.getShortMessage();
    } else {
      aError.aShortComment = aError.aFullComment;
    }
    aError.aShortComment = org.languagetool.gui.Tools.shortenComment(aError.aShortComment);
    //  Filter: provide user to delete footnotes by suggestion
    boolean noSuggestions = false;
    if (footnotes != null && footnotes.length > 0 && !ruleMatch.getSuggestedReplacements().isEmpty()) {
      int cor = 0;
      for (int n : footnotes) {
        if (n + cor <= ruleMatch.getFromPos() + startIndex) {
          cor++;
        } else if (n + cor > ruleMatch.getFromPos() + startIndex && n + cor <= ruleMatch.getToPos() + startIndex) {
          noSuggestions = true;
          break;
        }
      }
    }
    int numSuggestions;
    String[] allSuggestions;
    if (noSuggestions) {
      numSuggestions = 0;
      allSuggestions = new String[0];
    } else {
      numSuggestions = ruleMatch.getSuggestedReplacements().size();
      allSuggestions = ruleMatch.getSuggestedReplacements().toArray(new String[numSuggestions]);
    }
    //  Filter: remove suggestions for override dot at the end of sentences
    //  needed because of error in dialog
    /*  since LT 5.2: Filter is commented out because of default use of LT dialog
    if (lastChar == '.' && (ruleMatch.getToPos() + startIndex) == sentencesLength) {
      int i = 0;
      while (i < numSuggestions && i < OfficeTools.MAX_SUGGESTIONS
          && allSuggestions[i].length() > 0 && allSuggestions[i].charAt(allSuggestions[i].length()-1) == '.') {
        i++;
      }
      if (i < numSuggestions && i < OfficeTools.MAX_SUGGESTIONS) {
      numSuggestions = 0;
      allSuggestions = new String[0];
      }
    }
    */
    //  End of Filter
    if (numSuggestions > OfficeTools.MAX_SUGGESTIONS) {
      aError.aSuggestions = Arrays.copyOfRange(allSuggestions, 0, OfficeTools.MAX_SUGGESTIONS);
    } else {
      aError.aSuggestions = allSuggestions;
    }
    aError.nErrorStart = ruleMatch.getFromPos() + startIndex;
    aError.nErrorLength = ruleMatch.getToPos() - ruleMatch.getFromPos();
    aError.aRuleIdentifier = ruleMatch.getRule().getId();
    // LibreOffice since version 3.5 supports an URL that provides more information about the error,
    // LibreOffice since version 6.2 supports the change of underline color (key: "LineColor", value: int (RGB))
    // LibreOffice since version 6.2 supports the change of underline style (key: "LineType", value: short (DASHED = 5))
    // older version will simply ignore the properties
    Color underlineColor = config.getUnderlineColor(ruleMatch.getRule().getCategory().getName(), ruleMatch.getRule().getId());
    short underlineType = config.getUnderlineType(ruleMatch.getRule().getCategory().getName(), ruleMatch.getRule().getId());
    URL url = ruleMatch.getUrl();
    if (url == null) {                      // match URL overrides rule URL 
      url = ruleMatch.getRule().getUrl();
    }
    int nDim = 0;
    if (url != null) {
      nDim++;
    }
    if (underlineColor != Color.blue || ruleMatch.getRule().isDictionaryBasedSpellingRule()) {
      nDim++;
    }
    if (underlineType != Configuration.UNDERLINE_WAVE || (config.markSingleCharBold() && aError.nErrorLength == 1)) {
      nDim++;
    }
    if (nDim > 0) {
      //  HINT: Because of result cache handling:
      //  handle should always be -1
      //  property state should always be PropertyState.DIRECT_VALUE
      //  otherwise result cache handling has to be adapted
      PropertyValue[] propertyValues = new PropertyValue[nDim];
      int n = 0;
      if (url != null) {
        propertyValues[n] = new PropertyValue("FullCommentURL", -1, url.toString(), PropertyState.DIRECT_VALUE);
        n++;
      }
      if (ruleMatch.getRule().isDictionaryBasedSpellingRule()) {
        int ucolor = Color.red.getRGB() & 0xFFFFFF;
        propertyValues[n] = new PropertyValue("LineColor", -1, ucolor, PropertyState.DIRECT_VALUE);
        n++;
      } else {
        if (underlineColor != Color.blue) {
          int ucolor = underlineColor.getRGB() & 0xFFFFFF;
          propertyValues[n] = new PropertyValue("LineColor", -1, ucolor, PropertyState.DIRECT_VALUE);
          n++;
        }
      }
      if (underlineType != Configuration.UNDERLINE_WAVE) {
        propertyValues[n] = new PropertyValue("LineType", -1, underlineType, PropertyState.DIRECT_VALUE);
      } else if (config.markSingleCharBold() && aError.nErrorLength == 1) {
        propertyValues[n] = new PropertyValue("LineType", -1, Configuration.UNDERLINE_BOLDWAVE, PropertyState.DIRECT_VALUE);
      }
      aError.aProperties = propertyValues;
    } else {
        aError.aProperties = new PropertyValue[0];
    }
    return aError;
  }
  
  /**
   * get beginning of next sentence using LanguageTool tokenization
   */
  private List<Integer> getNextSentencePositions (String paraText, SwJLanguageTool lt) {
    List<Integer> nextSentencePositions = new ArrayList<Integer>();
    if (paraText == null || paraText.isEmpty()) {
      nextSentencePositions.add(0);
      return nextSentencePositions;
    }
    if (lt == null || lt.isRemote()) {
      nextSentencePositions.add(paraText.length());
    } else {
      List<String> tokenizedSentences = lt.sentenceTokenize(cleanFootnotes(paraText));
      int position = 0;
      for (String sentence : tokenizedSentences) {
        position += sentence.length();
        nextSentencePositions.add(position);
      }
      if (nextSentencePositions.get(nextSentencePositions.size() - 1) != paraText.length()) {
        nextSentencePositions.set(nextSentencePositions.size() - 1, paraText.length());
      }
    }
    return nextSentencePositions;
  }
  
  /**
   * Fix numbers that are (probably) foot notes.
   * See https://bugs.freedesktop.org/show_bug.cgi?id=69416
   * public for test reasons
   */
  static String cleanFootnotes(String paraText) {
    return paraText.replaceAll("([^\\d][.!?])\\d ", "$1¹ ");
  }
  
  /**
   * Remove footnotes from paraText
   * run cleanFootnotes if information about footnotes are not supported
   */
  static String removeFootnotes(String paraText, int[] footnotes, List<Integer> deletedChars) {
    if (paraText == null) {
      return null;
    }
    if (deletedChars == null || deletedChars.isEmpty()) {
      if (footnotes == null) {
        return cleanFootnotes(paraText);
      }
      for (int i = footnotes.length - 1; i >= 0; i--) {
        if (footnotes[i] < paraText.length()) {
          paraText = paraText.substring(0, footnotes[i]) + paraText.substring(footnotes[i] + 1);
        }
      }
    } else {
      if (footnotes == null || footnotes.length == 0) {
        if (footnotes == null) {
          paraText = cleanFootnotes(paraText);
        }
        for (int i = deletedChars.size() - 1; i >= 0; i--) {
          if (deletedChars.get(i) < paraText.length()) {
            paraText = paraText.substring(0, deletedChars.get(i)) + paraText.substring(deletedChars.get(i) + 1);
          }
        }
      } else {
        int idc = deletedChars.size() - 1;
        int ifn = footnotes.length - 1;
        while (idc >= 0 || ifn >= 0) {
          if (idc >= 0 && (ifn < 0 || deletedChars.get(idc) >= footnotes[ifn])) {
            if (deletedChars.get(idc) < paraText.length()) {
              paraText = paraText.substring(0, deletedChars.get(idc)) + paraText.substring(deletedChars.get(idc) + 1);
            }
            if (ifn >= 0 && deletedChars.get(idc) == footnotes[ifn]) {
              ifn--;
            }
            idc--;
          } else {
            if (footnotes[ifn] < paraText.length()) {
              paraText = paraText.substring(0, footnotes[ifn]) + paraText.substring(footnotes[ifn] + 1);
            }
            ifn--;
          }
        }
      }
    }
    return paraText;
  }
  
  /**
   * Correct SingleProofreadingError by footnote positions
   * footnotes before is the sum of all footnotes before the checked paragraph
   */
  private static SingleProofreadingError correctRuleMatchWithFootnotes(SingleProofreadingError pError, int[] footnotes, List<Integer> deletedChars) {
    if (deletedChars == null || deletedChars.isEmpty()) {
      if (footnotes == null || footnotes.length == 0) {
        return pError;
      }
      for (int i :footnotes) {
        if (i <= pError.nErrorStart) {
          pError.nErrorStart++;
        } else if (i < pError.nErrorStart + pError.nErrorLength) {
          pError.nErrorLength++;
        }
      }
    } else {
      if (footnotes == null || footnotes.length == 0) {
        for (int i : deletedChars) {
          if (i <= pError.nErrorStart) {
            pError.nErrorStart++;
          } else if (i < pError.nErrorStart + pError.nErrorLength) {
            pError.nErrorLength++;
          }
        }
      } else {
        int ifn = 0;
        int idc = 0;
        while (ifn < footnotes.length || idc < deletedChars.size()) {
          if (idc < deletedChars.size() && (ifn >= footnotes.length || deletedChars.get(idc) < footnotes[ifn])) {
            if (deletedChars.get(idc) <= pError.nErrorStart) {
              pError.nErrorStart++;
            } else if (deletedChars.get(idc) < pError.nErrorStart + pError.nErrorLength) {
              pError.nErrorLength++;
            }
            if (ifn < footnotes.length && deletedChars.get(idc) == footnotes[ifn]) {
              ifn++;
            }
            idc++;
          } else {
            if (footnotes[ifn] <= pError.nErrorStart) {
              pError.nErrorStart++;
            } else if (footnotes[ifn] < pError.nErrorStart + pError.nErrorLength) {
              pError.nErrorLength++;
            }
            ifn++;
          }
        }
      }
    }
    return pError;
  }
  
  /**
   * get all errors of a Paragraph as list
   */
  private List<SentenceErrors> getSentencesErrosAsList(int numberOfParagraph, SwJLanguageTool lt, LoErrorType errType) {
    List<SentenceErrors> sentenceErrors = new ArrayList<SentenceErrors>();
    if (!isDisposed()) {
      CacheEntry entry = paragraphsCache.get(0).getCacheEntry(numberOfParagraph);
      List<Integer> nextSentencePositions = null;
      if (entry != null) {
        nextSentencePositions = entry.nextSentencePositions;
      }
      if (nextSentencePositions == null) {
        nextSentencePositions = new ArrayList<Integer>();
      }
      if (nextSentencePositions.size() == 0 && docCache != null 
          && numberOfParagraph >= 0 && numberOfParagraph < docCache.size()) {
        nextSentencePositions = getNextSentencePositions(docCache.getFlatParagraph(numberOfParagraph), lt);
      }
      int startPosition = 0;
      if (nextSentencePositions.size() == 1) {
        List<SingleProofreadingError[]> errorList = new ArrayList<SingleProofreadingError[]>();
        for (ResultCache cache : paragraphsCache) {
          CacheEntry cacheEntry = cache.getCacheEntry(numberOfParagraph);
          errorList.add(cacheEntry == null ? null : cacheEntry.getErrorArray());
        }
        sentenceErrors.add(new SentenceErrors(startPosition, nextSentencePositions.get(0), singleDocument.mergeErrors(errorList, numberOfParagraph)));
      } else {
        for (int nextPosition : nextSentencePositions) {
          List<SingleProofreadingError[]> errorList = new ArrayList<SingleProofreadingError[]>();
          for (ResultCache cache : paragraphsCache) {
            errorList.add(cache.getFromPara(numberOfParagraph, startPosition, nextPosition, errType));
          }
          sentenceErrors.add(new SentenceErrors(startPosition, nextPosition, singleDocument.mergeErrors(errorList, numberOfParagraph)));
          startPosition = nextPosition;
        }
      }
    }
    return sentenceErrors;
  }

  /**
   * Class of proofreading errors of one sentence
   */
  public static class SentenceErrors {
    final int sentenceStart;
    final int sentenceEnd;
    final SingleProofreadingError[] sentenceErrors;
    
    SentenceErrors(int start, int end, SingleProofreadingError[] errors) {
      sentenceStart = start;
      sentenceEnd = end;
      sentenceErrors = errors;
    }
  }

  
}
