package com.e2eq.framework.util;

import java.util.ArrayList;
import java.util.Stack;

public class WildCardMatcher {


   private static final int NOT_FOUND = -1;
   static WildCardMatcher instance = new WildCardMatcher();


   public static WildCardMatcher getInstance () {
      return instance;
   }

   /**
    Checks string against wild card string
    wildcardMatch("c.txt", "*.txt")      --> true
    wildcardMatch("c.txt", "*.jpg")      --> false
    wildcardMatch("a/b/c.txt", "a/b/*")  --> true
    wildcardMatch("c.txt", "*.???")      --> true
    wildcardMatch("c.txt", "*.????")     --> false
    @param inputString
    @param wildcardMatcher
    @param caseSensitivity
    @return
    */
   public static boolean wildcardMatch(final String inputString, final String wildcardMatcher, IOCase caseSensitivity) {
      if (inputString == null && wildcardMatcher == null) {
         return true;
      }
      if (inputString == null || wildcardMatcher == null) {
         return false;
      }
      if (caseSensitivity == null) {
         caseSensitivity = IOCase.SENSITIVE;
      }
      final String[] wcs = splitOnTokens(wildcardMatcher);
      boolean anyChars = false;
      int textIdx = 0;
      int wcsIdx = 0;
      final Stack<int[]> backtrack = new Stack<int[]>();

      // loop around a backtrack stack, to handle complex * matching
      do {
         if (backtrack.size() > 0) {
            final int[] array = backtrack.pop();
            wcsIdx = array[0];
            textIdx = array[1];
            anyChars = true;
         }

         // loop whilst tokens and text left to process
         while (wcsIdx < wcs.length) {

            if (wcs[wcsIdx].equals("?")) {
               // ? so move to next text char
               textIdx++;
               if (textIdx > inputString.length()) {
                  break;
               }
               anyChars = false;

            } else if (wcs[wcsIdx].equals("*")) {
               // set any chars status
               anyChars = true;
               if (wcsIdx == wcs.length - 1) {
                  textIdx = inputString.length();
               }

            } else {
               // matching text token
               if (anyChars) {
                  // any chars then try to locate text token
                  textIdx = caseSensitivity.checkIndexOf(inputString, textIdx, wcs[wcsIdx]);
                  if (textIdx == NOT_FOUND) {
                     // token not found
                     break;
                  }
                  final int repeat = caseSensitivity.checkIndexOf(inputString, textIdx + 1, wcs[wcsIdx]);
                  if (repeat >= 0) {
                     backtrack.push(new int[] {wcsIdx, repeat});
                  }
               } else {
                  // matching from current position
                  if (!caseSensitivity.checkRegionMatches(inputString, textIdx, wcs[wcsIdx])) {
                     // couldnt match token
                     break;
                  }
               }

               // matched text token, move text index to end of matched token
               textIdx += wcs[wcsIdx].length();
               anyChars = false;
            }

            wcsIdx++;
         }

         // full match
         if (wcsIdx == wcs.length && textIdx == inputString.length()) {
            return true;
         }

      } while (backtrack.size() > 0);

      return false;
   }

   /**
    * Splits a string into a number of tokens.
    * The text is split by '?' and '*'.
    * Where multiple '*' occur consecutively they are collapsed into a single '*'.
    *
    * @param text  the text to split
    * @return the array of tokens, never null
    */
   static String[] splitOnTokens(final String text) {
      // used by wildcardMatch
      // package level so a unit test may run on this

      if (text.indexOf('?') == NOT_FOUND && text.indexOf('*') == NOT_FOUND) {
         return new String[] { text };
      }

      final char[] array = text.toCharArray();
      final ArrayList<String> list = new ArrayList<String>();
      final StringBuilder buffer = new StringBuilder();
      char prevChar = 0;
      for (final char ch : array) {
         if (ch == '?' || ch == '*') {
            if (buffer.length() != 0) {
               list.add(buffer.toString());
               buffer.setLength(0);
            }
            if (ch == '?') {
               list.add("?");
            } else if (prevChar != '*') {// ch == '*' here; check if previous char was '*'
               list.add("*");
            }
         } else {
            buffer.append(ch);
         }
         prevChar = ch;
      }
      if (buffer.length() != 0) {
         list.add(buffer.toString());
      }

      return list.toArray( new String[ list.size() ] );
   }
}
