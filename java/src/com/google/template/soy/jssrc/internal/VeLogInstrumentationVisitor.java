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

package com.google.template.soy.jssrc.internal;

import com.google.common.base.Strings;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.OperatorNodes.PlusOpNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.logging.LoggingFunction;
import com.google.template.soy.passes.DesugarHtmlNodesPass;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode.Quotes;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.soytree.VeLogNode;
import javax.annotation.Nullable;

/**
 * Instruments {velog} commands and adds necessary data attributes to the top-level DOM node and
 * tags with logging functions.
 */
final class VeLogInstrumentationVisitor extends AbstractSoyNodeVisitor<Void> {
  private final TemplateRegistry templateRegistry;
  /** The node id generator for the parse tree. Retrieved from the root SoyFileSetNode. */
  private IdGenerator nodeIdGen;

  /** This counter is used for creating unique data attribute names for logging functions. */
  private int counter;

  VeLogInstrumentationVisitor(TemplateRegistry templateRegistry) {
    this.templateRegistry = templateRegistry;
  }

  @Override
  protected void visitSoyFileSetNode(SoyFileSetNode node) {
    // Retrieve the node id generator.
    nodeIdGen = node.getNodeIdGenerator();
    visitChildren(node);
    // Run the desugaring pass and combine raw text nodes after we instrument velog node.
    new DesugarHtmlNodesPass().run(node, templateRegistry);
  }

  @Override
  protected void visitSoyFileNode(SoyFileNode node) {
    // only instrument source files, deps haven't had the logging passes run so the logging ids
    // aren't available.  Also, it is pointless.
    if (node.getSoyFileKind() != SoyFileKind.SRC) {
      return;
    }
    super.visitSoyFileNode(node);
  }

  /** Adds data-soylog attribute to the top-level DOM node in this {velog} block. */
  @Override
  protected void visitVeLogNode(VeLogNode node) {
    // VeLogValidationPass enforces that the first child is a open tag. We can safely cast it here.
    HtmlOpenTagNode tag = (HtmlOpenTagNode) node.getChild(0);
    SourceLocation insertionLocation =
        tag.getSourceLocation()
            .getEndPoint()
            .offset(0, tag.isSelfClosing() ? -2 : -1)
            .asLocation(tag.getSourceLocation().getFilePath());
    IfNode ifNode = new IfNode(nodeIdGen.genId(), insertionLocation);
    IfCondNode ifCondNode = createIfCondForLoggingFunction(nodeIdGen.genId(), insertionLocation);
    ifNode.addChild(ifCondNode);
    FunctionNode funcNode = new FunctionNode(VeLogFunction.INSTANCE, insertionLocation);
    funcNode.addChild(new IntegerNode(node.getLoggingId(), insertionLocation));
    funcNode.addChild(
        node.getConfigExpression() == null
            ? new NullNode(insertionLocation)
            : node.getConfigExpression().copy(new CopyState()));
    if (node.getLogonlyExpression() != null) {
      funcNode.addChild(node.getLogonlyExpression().copy(new CopyState()));
    }
    PrintNode attributeValue =
        new PrintNode(
            nodeIdGen.genId(),
            insertionLocation,
            /* isImplicit= */ true,
            /* expr= */ funcNode,
            /* phname= */ null,
            ErrorReporter.exploding());
    HtmlAttributeNode dataAttributeNode =
        createHtmlAttribute("soylog", null, attributeValue, nodeIdGen, insertionLocation);
    ifCondNode.addChild(dataAttributeNode);
    tag.addChild(ifNode);
    visitChildren(node);
  }

  @Override
  protected void visitTemplateNode(TemplateNode node) {
    // Resets the counter whenever we visit a template node.
    counter = 0;
    // Allows the children to insert the nodes as its siblings.
    visitChildrenAllowingConcurrentModification(node);
  }

  @Override
  protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
    // Stores the original counter that might be useful for nested tags.
    int oldCounter = counter;
    counter = 0;
    // Allows the children to insert the nodes as its siblings.
    visitChildrenAllowingConcurrentModification(node);
    counter = oldCounter;
  }

  /**
   * For HtmlAttributeNode that has a logging function as its value, replace the logging function
   * with its place holder, and append a new data attribute that contains all the desired
   * information that are used later by the runtime library.
   */
  @Override
  protected void visitHtmlAttributeNode(HtmlAttributeNode node) {
    // Skip attributes that do not have a value.
    if (!node.hasValue()) {
      return;
    }
    SourceLocation insertionLocation = node.getSourceLocation();
    for (FunctionNode function : SoyTreeUtils.getAllNodesOfType(node, FunctionNode.class)) {
      if (!(function.getSoyFunction() instanceof LoggingFunction)) {
        continue;
      }
      IfNode ifNode = new IfNode(nodeIdGen.genId(), insertionLocation);
      IfCondNode ifCondNode = createIfCondForLoggingFunction(nodeIdGen.genId(), insertionLocation);
      FunctionNode funcNode =
          new FunctionNode(VeLogJsSrcLoggingFunction.INSTANCE, insertionLocation);
      funcNode.addChild(new StringNode(function.getFunctionName(), insertionLocation));
      funcNode.addChild(new ListLiteralNode(function.getChildren(), insertionLocation));
      StandaloneNode attributeName = node.getChild(0);
      if (attributeName instanceof RawTextNode) {
        // If attribute name is a plain text, directly pass it as a function argument.
        funcNode.addChild(
            new StringNode(((RawTextNode) attributeName).getRawText(), insertionLocation));
      } else {
        // Otherwise wrap the print node or call node into a let block, and use the let variable
        // as a function argument.
        String varName = "soy_logging_function_attribute_" + counter;
        LetContentNode letNode =
            LetContentNode.forVariable(
                nodeIdGen.genId(), attributeName.getSourceLocation(), varName, null);
        // Adds a let var which references to the original attribute name, and move the name to
        // the let block.
        node.replaceChild(
            attributeName,
            new PrintNode(
                nodeIdGen.genId(),
                insertionLocation,
                /* isImplicit= */ true,
                /* expr= */ new VarRefNode(varName, insertionLocation, false, letNode.getVar()),
                /* phname= */ null,
                ErrorReporter.exploding()));
        letNode.addChild(attributeName);
        node.getParent().addChild(node.getParent().getChildIndex(node), letNode);
        funcNode.addChild(new VarRefNode(varName, insertionLocation, false, letNode.getVar()));
      }
      HtmlAttributeNode loggingFunctionAttribute =
          createHtmlAttribute(
              "soyloggingfunction-",
              Integer.toString(counter++),
              new PrintNode(
                  nodeIdGen.genId(),
                  insertionLocation,
                  /* isImplicit= */ true,
                  /* expr= */ funcNode,
                  /* phname= */ null,
                  ErrorReporter.exploding()),
              nodeIdGen,
              insertionLocation);
      ifCondNode.addChild(loggingFunctionAttribute);
      ifNode.addChild(ifCondNode);
      // Append the if node at the end of the current tag.
      HtmlOpenTagNode openTag = node.getNearestAncestor(HtmlOpenTagNode.class);
      if (openTag != null) {
        openTag.addChild(ifNode);
      } else {
        // If we cannot find a HTML open tag, we must be in a template with kind="attributes".
        // In this case, append the if condition to the end of this template.
        node.getNearestAncestor(TemplateNode.class).addChild(ifNode);
      }
      // Replace the original attribute value to the placeholder.
      HtmlAttributeValueNode placeHolder =
          new HtmlAttributeValueNode(nodeIdGen.genId(), insertionLocation, Quotes.DOUBLE);
      placeHolder.addChild(
          new RawTextNode(
              nodeIdGen.genId(),
              ((LoggingFunction) function.getSoyFunction()).getPlaceholder(),
              insertionLocation));
      node.replaceChild(node.getChild(1), placeHolder);
      // We can break here since VeLogValidationPass guarantees that there is exactly one
      // logging function in a html attribute value.
      break;
    }
    visitChildren(node);
  }

  private HtmlAttributeNode createHtmlAttribute(
      String attributeName,
      @Nullable String attributeSuffix,
      StandaloneNode attributeValue,
      IdGenerator nodeIdGen,
      SourceLocation insertionLocation) {
    HtmlAttributeNode dataAttributeNode =
        new HtmlAttributeNode(
            nodeIdGen.genId(), insertionLocation, insertionLocation.getBeginPoint());
    PlusOpNode plusOp = new PlusOpNode(insertionLocation);
    plusOp.addChild(new StringNode("data-", insertionLocation));
    FunctionNode xidFunction = new FunctionNode(BuiltinFunction.XID, insertionLocation);
    xidFunction.addChild(new StringNode(attributeName, insertionLocation));
    if (Strings.isNullOrEmpty(attributeSuffix)) {
      plusOp.addChild(xidFunction);
    } else {
      PlusOpNode plusOpSuffix = new PlusOpNode(insertionLocation);
      plusOpSuffix.addChild(xidFunction);
      plusOpSuffix.addChild(new StringNode(attributeSuffix, insertionLocation));
      plusOp.addChild(plusOpSuffix);
    }
    PrintNode attributeNameNode =
        new PrintNode(
            nodeIdGen.genId(),
            insertionLocation,
            /* isImplicit= */ true,
            /* expr= */ plusOp,
            /* phname */ null,
            ErrorReporter.exploding());
    dataAttributeNode.addChild(attributeNameNode);
    HtmlAttributeValueNode attributeValueNode =
        new HtmlAttributeValueNode(
            nodeIdGen.genId(), insertionLocation, HtmlAttributeValueNode.Quotes.NONE);
    attributeValueNode.addChild(attributeValue);
    dataAttributeNode.addChild(attributeValueNode);
    return dataAttributeNode;
  }

  private static IfCondNode createIfCondForLoggingFunction(
      int nodeId, SourceLocation insertionLocation) {
    return new IfCondNode(
        nodeId,
        insertionLocation,
        "if",
        new FunctionNode(HasMetadataFunction.INSTANCE, insertionLocation));
  }

  @Override
  protected void visitSoyNode(SoyNode node) {
    if (node instanceof ParentSoyNode<?>) {
      visitChildren((ParentSoyNode<?>) node);
    }
  }
}
