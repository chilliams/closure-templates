/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.template.soy.basicdirectives;

import com.google.template.soy.data.ForwardingLoggingAdvisingAppendable;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import com.google.template.soy.data.restricted.SoyString;
import com.google.template.soy.data.restricted.StringData;
import java.io.IOException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Static methods implementing the basic directives in this package. */
public final class BasicDirectivesRuntime {

  private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r\\n|\\r|\\n");

  public static String truncate(String str, int maxLen, boolean doAddEllipsis) {
    if (str.length() <= maxLen) {
      return str; // no need to truncate
    }
    // If doAddEllipsis, either reduce maxLen to compensate, or else if maxLen is too small, just
    // turn off doAddEllipsis.
    if (doAddEllipsis) {
      if (maxLen > 3) {
        maxLen -= 3;
      } else {
        doAddEllipsis = false;
      }
    }

    // Make sure truncating at maxLen doesn't cut up a unicode surrogate pair.
    if (Character.isHighSurrogate(str.charAt(maxLen - 1))
        && Character.isLowSurrogate(str.charAt(maxLen))) {
      maxLen -= 1;
    }

    // Truncate.
    str = str.substring(0, maxLen);

    // Add ellipsis.
    if (doAddEllipsis) {
      str += "...";
    }

    return str;
  }

  public static SoyString changeNewlineToBr(SoyValue value) {
    String result = NEWLINE_PATTERN.matcher(coerceToString(value)).replaceAll("<br>");

    // Make sure to transmit the known direction, if any, to any downstream directive that may need
    // it, e.g. BidiSpanWrapDirective. Since a known direction is carried only by SanitizedContent,
    // and the transformation we make is only valid in HTML, we only transmit the direction when we
    // get HTML SanitizedContent.
    // TODO(user): Consider always returning HTML SanitizedContent.
    if (value instanceof SanitizedContent) {
      SanitizedContent sanitizedContent = (SanitizedContent) value;
      if (sanitizedContent.getContentKind() == ContentKind.HTML) {
        return UnsafeSanitizedContentOrdainer.ordainAsSafe(
            result, ContentKind.HTML, sanitizedContent.getContentDirection());
      }
    }
    return StringData.forValue(result);
  }

  public static LoggingAdvisingAppendable changeNewlineToBrStreaming(
      LoggingAdvisingAppendable appendable) {
    return new ForwardingLoggingAdvisingAppendable(appendable) {
      private boolean lastCharWasCarriageReturn;

      @Override
      public LoggingAdvisingAppendable append(char c) throws IOException {
        switch (c) {
          case '\n':
            if (!lastCharWasCarriageReturn) {
              super.append("<br>");
            }
            lastCharWasCarriageReturn = false;
            break;
          case '\r':
            super.append("<br>");
            lastCharWasCarriageReturn = true;
            break;
          default:
            super.append(c);
            lastCharWasCarriageReturn = false;
            break;
        }
        return this;
      }

      @Override
      public LoggingAdvisingAppendable append(CharSequence csq) throws IOException {
        return append(csq, 0, csq.length());
      }

      @Override
      public LoggingAdvisingAppendable append(CharSequence csq, int start, int end)
          throws IOException {
        int appendedUpTo = start;
        boolean carriageReturn = lastCharWasCarriageReturn;
        for (int i = start; i < end; i++) {
          switch (csq.charAt(i)) {
            case '\n':
              appendUpTo(csq, appendedUpTo, i);
              if (!carriageReturn) {
                super.append("<br>");
              }
              appendedUpTo = i + 1;
              carriageReturn = false;
              break;
            case '\r':
              appendUpTo(csq, appendedUpTo, i);
              super.append("<br>");
              appendedUpTo = i + 1;
              carriageReturn = true;
              break;
            default:
              carriageReturn = false;
              break;
          }
        }
        appendUpTo(csq, appendedUpTo, end);
        lastCharWasCarriageReturn = carriageReturn;
        return this;
      }

      private void appendUpTo(CharSequence csq, int start, int end) throws IOException {
        if (start != end) {
          super.append(csq, start, end);
        }
      }
    };
  }

  public static SoyString insertWordBreaks(SoyValue value, int maxCharsBetweenWordBreaks) {
    String str = coerceToString(value);

    StringBuilder result = new StringBuilder();

    // These variables keep track of important state while looping through the string below.
    boolean isInTag = false; // whether we're inside an HTML tag
    boolean isMaybeInEntity = false; // whether we might be inside an HTML entity
    int numCharsWithoutBreak = 0; // number of characters since the last word break

    for (int codePoint, i = 0, n = str.length(); i < n; i += Character.charCount(codePoint)) {
      codePoint = str.codePointAt(i);

      // If hit maxCharsBetweenWordBreaks, and next char is not a space, then add <wbr>.
      if (numCharsWithoutBreak >= maxCharsBetweenWordBreaks && codePoint != ' ') {
        result.append("<wbr>");
        numCharsWithoutBreak = 0;
      }

      if (isInTag) {
        // If inside an HTML tag and we see '>', it's the end of the tag.
        if (codePoint == '>') {
          isInTag = false;
        }

      } else if (isMaybeInEntity) {
        switch (codePoint) {
            // If maybe inside an entity and we see ';', it's the end of the entity. The entity
            // that just ended counts as one char, so increment numCharsWithoutBreak.
          case ';':
            isMaybeInEntity = false;
            ++numCharsWithoutBreak;
            break;
            // If maybe inside an entity and we see '<', we weren't actually in an entity. But
            // now we're inside an HTML tag.
          case '<':
            isMaybeInEntity = false;
            isInTag = true;
            break;
            // If maybe inside an entity and we see ' ', we weren't actually in an entity. Just
            // correct the state and reset the numCharsWithoutBreak since we just saw a space.
          case ' ':
            isMaybeInEntity = false;
            numCharsWithoutBreak = 0;
            break;
          default: // fall out
        }

      } else { // !isInTag && !isInEntity
        switch (codePoint) {
            // When not within a tag or an entity and we see '<', we're now inside an HTML tag.
          case '<':
            isInTag = true;
            break;
            // When not within a tag or an entity and we see '&', we might be inside an entity.
          case '&':
            isMaybeInEntity = true;
            break;
            // When we see a space, reset the numCharsWithoutBreak count.
          case ' ':
            numCharsWithoutBreak = 0;
            break;
            // When we see a non-space, increment the numCharsWithoutBreak.
          default:
            ++numCharsWithoutBreak;
            break;
        }
      }

      // In addition to adding <wbr>s, we still have to add the original characters.
      result.appendCodePoint(codePoint);
    }

    // Make sure to transmit the known direction, if any, to any downstream directive that may need
    // it, e.g. BidiSpanWrapDirective. Since a known direction is carried only by SanitizedContent,
    // and the transformation we make is only valid in HTML, we only transmit the direction when we
    // get HTML SanitizedContent.
    // TODO(user): Consider always returning HTML SanitizedContent.
    if (value instanceof SanitizedContent) {
      SanitizedContent sanitizedContent = (SanitizedContent) value;
      if (sanitizedContent.getContentKind() == ContentKind.HTML) {
        return UnsafeSanitizedContentOrdainer.ordainAsSafe(
            result.toString(), ContentKind.HTML, sanitizedContent.getContentDirection());
      }
    }

    return StringData.forValue(result.toString());
  }

  private static String coerceToString(@Nullable SoyValue v) {
    return v == null ? "null" : v.coerceToString();
  }
}
