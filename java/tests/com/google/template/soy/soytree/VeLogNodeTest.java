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
package com.google.template.soy.soytree;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.LoggableElement;
import com.google.template.soy.logging.LoggingConfig;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.shared.AutoEscapingType;
import com.google.template.soy.types.SoyTypeProvider;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.proto.SoyProtoTypeProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class VeLogNodeTest {

  @Test
  public void testParsing_justName() {
    VeLogNode logNode = parseVeLog("{velog Bar}<div></div>{/velog}");

    assertThat(logNode.toSourceString()).isEqualTo("{velog Bar}<div></div>{/velog}");
    assertThat(logNode.getName().identifier()).isEqualTo("Bar");
    assertThat(logNode.getConfigExpression()).isNull();
    assertThat(logNode.getLogonlyExpression()).isNull();
  }

  @Test
  public void testParsing_configExpression() {
    VeLogNode logNode = parseVeLog("{velog Bar data=\"soy.test.Foo()\"}<div></div>{/velog}");

    assertThat(logNode.toSourceString())
        .isEqualTo("{velog Bar data=\"soy.test.Foo()\"}<div></div>{/velog}");
    assertThat(logNode.getName().identifier()).isEqualTo("Bar");
    assertThat(logNode.getConfigExpression().toSourceString()).isEqualTo("soy.test.Foo()");
    assertThat(logNode.getLogonlyExpression()).isNull();
  }

  @Test
  public void testParsing_logonly() {
    VeLogNode logNode = parseVeLog("{velog Bar logonly=\"false\"}<div></div>{/velog}");

    assertThat(logNode.toSourceString())
        .isEqualTo("{velog Bar logonly=\"false\"}<div></div>{/velog}");
    assertThat(logNode.getName().identifier()).isEqualTo("Bar");
    assertThat(logNode.getConfigExpression()).isNull();
    assertThat(logNode.getLogonlyExpression().toSourceString()).isEqualTo("false");
  }

  @Test
  public void testParsing_configAndLogonly() {
    VeLogNode logNode =
        parseVeLog("{velog Bar data=\"soy.test.Foo()\" logonly=\"false\"}<div></div>{/velog}");

    assertThat(logNode.toSourceString())
        .isEqualTo("{velog Bar data=\"soy.test.Foo()\" logonly=\"false\"}<div></div>{/velog}");
    assertThat(logNode.getName().identifier()).isEqualTo("Bar");
    assertThat(logNode.getConfigExpression().toSourceString()).isEqualTo("soy.test.Foo()");
    assertThat(logNode.getLogonlyExpression().toSourceString()).isEqualTo("false");
  }

  private VeLogNode parseVeLog(String fooLog) {
    return parseVeLog(fooLog, ErrorReporter.exploding());
  }

  private VeLogNode parseVeLog(String fooLog, ErrorReporter reporter) {
    return Iterables.getOnlyElement(
        SoyTreeUtils.getAllNodesOfType(
            SoyFileSetParserBuilder.forTemplateContents(AutoEscapingType.STRICT, true, fooLog)
                .typeRegistry(
                    new SoyTypeRegistry(
                        ImmutableSet.<SoyTypeProvider>of(
                            new SoyProtoTypeProvider.Builder()
                                .addDescriptors(com.google.template.soy.testing.Foo.getDescriptor())
                                .buildNoFiles())))
                .setLoggingConfig(
                    ValidatedLoggingConfig.create(
                        LoggingConfig.newBuilder()
                            .addElement(
                                LoggableElement.newBuilder()
                                    .setName("Bar")
                                    .setId(1L)
                                    .setProtoType("soy.test.Foo")
                                    .build())
                            .build()))
                .errorReporter(reporter)
                .parse()
                .fileSet(),
            VeLogNode.class));
  }
}
