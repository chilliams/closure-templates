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

package com.google.template.soy.basicfunctions;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;
import com.google.template.soy.data.SoyDict;
import com.google.template.soy.data.SoyMap;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.internal.DictImpl;
import com.google.template.soy.data.restricted.FloatData;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.NumberData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** static functions for implementing the basic functions for java. */
public final class BasicFunctionsRuntime {
  /** Combine the two maps. */
  public static SoyDict augmentMap(SoyDict first, SoyDict second) {
    Map<String, SoyValueProvider> map =
        Maps.newHashMapWithExpectedSize(first.getItemCnt() + second.getItemCnt());
    map.putAll(first.asJavaStringMap());
    map.putAll(second.asJavaStringMap());
    return DictImpl.forProviderMap(map);
  }

  /**
   * Returns the smallest (closest to negative infinity) integer value that is greater than or equal
   * to the argument.
   */
  public static long ceil(SoyValue arg) {
    if (arg instanceof IntegerData) {
      return ((IntegerData) arg).longValue();
    } else {
      return (long) Math.ceil(arg.floatValue());
    }
  }

  /**
   * Returns the largest (closest to positive infinity) integer value that is less than or equal to
   * the argument.
   */
  public static long floor(SoyValue arg) {
    if (arg instanceof IntegerData) {
      return ((IntegerData) arg).longValue();
    } else {
      return (long) Math.floor(arg.floatValue());
    }
  }

  /**
   * Returns a list of all the keys in the given map.
   *
   * <p>Do not inline; required for jbcsrc. Must be mutable list.
   */
  public static List<SoyValue> keys(SoyMap map) {
    List<SoyValue> list = new ArrayList<>(map.getItemCnt());
    Iterables.addAll(list, map.getItemKeys());
    return list;
  }

  /** Returns the numeric maximum of the two arguments. */
  public static NumberData max(SoyValue arg0, SoyValue arg1) {
    if (arg0 instanceof IntegerData && arg1 instanceof IntegerData) {
      return IntegerData.forValue(Math.max(arg0.longValue(), arg1.longValue()));
    } else {
      return FloatData.forValue(Math.max(arg0.numberValue(), arg1.numberValue()));
    }
  }
  /** Returns the numeric minimum of the two arguments. */
  public static NumberData min(SoyValue arg0, SoyValue arg1) {
    if (arg0 instanceof IntegerData && arg1 instanceof IntegerData) {
      return IntegerData.forValue(Math.min(arg0.longValue(), arg1.longValue()));
    } else {
      return FloatData.forValue(Math.min(arg0.numberValue(), arg1.numberValue()));
    }
  }

  public static FloatData parseFloat(String str) {
    Double d = Doubles.tryParse(str);
    return (d == null || d.isNaN()) ? null : FloatData.forValue(d);
  }

  public static IntegerData parseInt(String str) {
    Long l = Longs.tryParse(str);
    return (l == null) ? null : IntegerData.forValue(l);
  }

  /** Returns a random integer between {@code 0} and the provided argument. */
  public static long randomInt(long longValue) {
    return (long) Math.floor(Math.random() * longValue);
  }

  /**
   * Rounds the given value to the closest decimal point left (negative numbers) or right (positive
   * numbers) of the decimal point
   */
  public static NumberData round(SoyValue value, int numDigitsAfterPoint) {
    // NOTE: for more accurate rounding, this should really be using BigDecimal which can do correct
    // decimal arithmetic.  However, for compatibility with js, that probably isn't an option.
    if (numDigitsAfterPoint == 0) {
      return IntegerData.forValue(round(value));
    } else if (numDigitsAfterPoint > 0) {
      double valueDouble = value.numberValue();
      double shift = Math.pow(10, numDigitsAfterPoint);
      return FloatData.forValue(Math.round(valueDouble * shift) / shift);
    } else {
      double valueDouble = value.numberValue();
      double shift = Math.pow(10, -numDigitsAfterPoint);
      return IntegerData.forValue((int) (Math.round(valueDouble / shift) * shift));
    }
  }

  /** Rounds the given value to the closest integer. */
  public static long round(SoyValue value) {
    if (value instanceof IntegerData) {
      return value.longValue();
    } else {
      return Math.round(value.numberValue());
    }
  }
}
