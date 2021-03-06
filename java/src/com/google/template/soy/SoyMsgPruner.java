/*
 * Copyright 2013 Google Inc.
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

package com.google.template.soy;

import com.google.inject.Injector;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.SoyMsgBundleHandler;
import com.google.template.soy.msgs.SoyMsgBundleHandler.OutputFileOptions;
import com.google.template.soy.msgs.SoyMsgPlugin;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Option;

/**
 * Executable for pruning messages from extracted msgs files, given a set of Soy files as reference
 * for which messages to keep.
 *
 */
public final class SoyMsgPruner extends AbstractSoyCompiler {
  @Option(
    name = "--allowExternalCalls",
    usage =
        "Whether to allow external calls. New projects should set this to false, and"
            + " existing projects should remove existing external calls and then set this to false."
            + " It will save you a lot of headaches. Currently defaults to true for backward"
            + " compatibility."
  )
  private boolean allowExternalCalls = true;

  @Option(
    name = "--inputMessageFiles",
    usage = "[Required] The list of files to prune",
    handler = SoyCmdLineParser.FileListOptionHandler.class
  )
  private List<File> inputMsgFiles = new ArrayList<>();

  @Option(
    name = "--outputMessageFiles",
    usage =
        "[Required] The names of the files to output.  There should be one of these (in the same "
            + "order) for every inputMessageFile",
    handler = SoyCmdLineParser.FileListOptionHandler.class
  )
  private List<File> outputMsgFiles = new ArrayList<>();

  @Option(
    name = "--messagePlugin",
    usage = "Specifies the full class name of a  BidirectionalSoyMsgPlugin."
  )
  private SoyMsgPlugin messagePlugin;

  @Option(
    name = "--messagePluginModule",
    usage = "Temporary flag for backwards compatibility reasons, please switch to --messagePlugin."
  )
  private String messagePluginModule = null;
  /**
   * Prunes messages from XTB files, given a set of Soy files as reference for which messages to
   * keep.
   *
   * @param args Should contain command-line flags and the list of paths to the Soy files.
   * @throws IOException If there are problems reading the input files or writing the output file.
   * @throws SoySyntaxException If a syntax error is detected.
   */
  public static void main(String[] args) throws IOException {
    new SoyMsgPruner().runMain(args);
  }

  SoyMsgPruner(ClassLoader loader) {
    super(loader);
  }

  SoyMsgPruner() {}

  @Override
  boolean acceptsSourcesAsArguments() {
    return false;
  }

  @Override
  void validateFlags() {
    if (inputMsgFiles.size() != outputMsgFiles.size()) {
      exitWithError("Must provide exactly one input file for every output file.");
    }
    if (messagePlugin == null && messagePluginModule == null) {
      exitWithError("Must specify a --messagePlugin.");
    }
    if (messagePlugin != null && messagePluginModule != null) {
      exitWithError("Cannot specify --messagePluginModule if you also specify --messagePlugin.");
    }
    for (File f : inputMsgFiles) {
      if (!f.exists()) {
        exitWithError("FileNotFound: " + f);
      }
    }
  }

  @Override
  void compile(SoyFileSet.Builder sfsBuilder, Injector injector) throws IOException {
    sfsBuilder.setAllowExternalCalls(allowExternalCalls);
    SoyFileSet sfs = sfsBuilder.build();
    SoyMsgBundleHandler msgBundleHandler =
        new SoyMsgBundleHandler(SoyCmdLineParser.getMsgPlugin(messagePlugin, messagePluginModule));

    // Main loop.
    for (int i = 0; i < inputMsgFiles.size(); i++) {

      // Get the input msg bundle.
      File inputMsgFilePath = inputMsgFiles.get(i);
      SoyMsgBundle origTransMsgBundle = msgBundleHandler.createFromFile(inputMsgFilePath);
      if (origTransMsgBundle.getLocaleString() == null) {
        throw new IOException("Error opening or parsing message file " + inputMsgFilePath);
      }

      // Do the pruning.
      SoyMsgBundle prunedTransSoyMsgBundle = sfs.pruneTranslatedMsgs(origTransMsgBundle);

      // Write out the pruned msg bundle.
      File outputMsgFilePath = outputMsgFiles.get(i);
      msgBundleHandler.writeToTranslatedMsgsFile(
          prunedTransSoyMsgBundle, new OutputFileOptions(), outputMsgFilePath);
    }
  }
}
