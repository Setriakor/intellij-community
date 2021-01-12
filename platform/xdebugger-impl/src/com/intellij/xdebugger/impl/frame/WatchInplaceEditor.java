/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.xdebugger.impl.frame;

import com.intellij.ui.AppUIUtil;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.intellij.xdebugger.impl.XDebuggerManagerImpl;
import com.intellij.xdebugger.impl.XDebuggerUtilImpl;
import com.intellij.xdebugger.impl.XDebuggerWatchesManager;
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl;
import com.intellij.xdebugger.impl.inline.InlineWatchNodeImpl;
import com.intellij.xdebugger.impl.inline.XInlineWatchesView;
import com.intellij.xdebugger.impl.ui.tree.ValueMarkup;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreeInplaceEditor;
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchNode;
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchesRootNode;
import com.intellij.xdebugger.impl.ui.tree.nodes.XDebuggerTreeNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;

public class WatchInplaceEditor extends XDebuggerTreeInplaceEditor {
  private final WatchesRootNode myRootNode;
  private final XWatchesView myWatchesView;
  private final WatchNode myOldNode;

  private  XValueMarkers<?, ?> myXValueMarkers;
  private final Set<XValue> myXValues = new HashSet<>();
  private final Map<String, XValue> myMappedMarkedObjects = new HashMap<>();

  public WatchInplaceEditor(@NotNull WatchesRootNode rootNode,
                            XWatchesView watchesView,
                            WatchNode node,
                            @Nullable WatchNode oldNode) {
    super((XDebuggerTreeNode)node, "watch");
    myRootNode = rootNode;
    myWatchesView = watchesView;
    myOldNode = oldNode;
    myExpressionEditor.setExpression(oldNode != null ? oldNode.getExpression() : null);


    trackMarkedObjectsAndVariables();
  }

  @Override
  public void cancelEditing() {
    if (!isShown()) return;
    super.cancelEditing();
    int index = myRootNode.getIndex(myNode);
    if (myOldNode == null && index != -1) {
      myRootNode.removeChildNode(myNode);
    }
    TreeUtil.selectNode(myTree, myNode);
  }

  @Override
  public void doOKAction() {
    XExpression expression = getExpression();
    if (expression.getExpression().contains("#")) {
      expression = createMarkObjectExpression(expression);
    }
    super.doOKAction();
    int index = myRootNode.removeChildNode(myNode);
    if (!XDebuggerUtilImpl.isEmptyExpression(expression) && index != -1) {
      if (myNode instanceof InlineWatchNodeImpl) {
        XDebuggerWatchesManager watchesManager = ((XDebuggerManagerImpl)XDebuggerManager.getInstance(getProject())).getWatchesManager();
        watchesManager.inlineWatchesRemoved(Collections.singletonList(((InlineWatchNodeImpl)myNode).getWatch()),
                                            (XInlineWatchesView)myWatchesView);
        watchesManager.addInlineWatchExpression(expression, index, ((InlineWatchNodeImpl)myNode).getPosition(), false);
      }
      else {
        myWatchesView.addWatchExpression(expression, index, false);
      }
    }
    TreeUtil.selectNode(myTree, myNode);
  }

  @Nullable
  @Override
  protected Rectangle getEditorBounds() {
    Rectangle bounds = super.getEditorBounds();
    if (bounds == null) {
      return null;
    }
    int afterIconX = getAfterIconX();
    bounds.x += afterIconX;
    bounds.width -= afterIconX;
    return bounds;
  }

  private void trackMarkedObjectsAndVariables() {
    retrieveMarkedObjects();
    retrieveStackFrameVariables();
  }

  private XExpression createMarkObjectExpression(XExpression oldExpression) {
    //expressions contain a # symbol so are removed first
    String markedName = oldExpression.getExpression().substring(1);
    if (myMappedMarkedObjects.containsKey(markedName)) {
      String variableName = myMappedMarkedObjects.get(markedName).toString();
      return new XExpressionImpl(variableName, oldExpression.getLanguage(), oldExpression.getCustomInfo(), oldExpression.getMode());
    }
    return oldExpression;
  }

  private void mapMarkedObjectsAndVariables() {
    myXValues.forEach(xValue -> {
      ValueMarkup valueMarkup = myXValueMarkers.getMarkup((XValue)xValue);
      if (valueMarkup != null) {
        myMappedMarkedObjects.put(valueMarkup.getText(), xValue);
      }
    });
  }


  private void retrieveMarkedObjects() {
    XDebugSession session = XDebugView.getSession(myTree);
    if (session != null) {
      myXValueMarkers = ((XDebugSessionImpl)session).getValueMarkers();
    }
  }

  private void retrieveStackFrameVariables() {
    XDebugSession session = XDebugView.getSession(myTree);
    if (session != null) {
      XStackFrame currentStackFrame = session.getCurrentStackFrame();
      Objects.requireNonNull(currentStackFrame).computeChildren(new XCompositeNode() {
        @Override
        public void addChildren(@NotNull XValueChildrenList children, boolean last) {
          for (int c = 0; c < children.size(); c++) {
            XValue childValue = children.getValue(c);
            //keep track of variable
            myXValues.add(childValue);
          }

          mapMarkedObjectsAndVariables();
        }

        @Override
        public void tooManyChildren(int remaining) {

        }

        @Override
        public void tooManyChildren(int remaining, @NotNull Runnable addNextChildren) {

        }

        @Override
        public void setAlreadySorted(boolean alreadySorted) {

        }

        @Override
        public void setErrorMessage(@NotNull String errorMessage) {

        }

        @Override
        public void setErrorMessage(@NotNull String errorMessage, @Nullable XDebuggerTreeNodeHyperlink link) {

        }

        @Override
        public void setMessage(@NotNull String message,
                               @Nullable Icon icon,
                               @NotNull SimpleTextAttributes attributes,
                               @Nullable XDebuggerTreeNodeHyperlink link) {

        }
      });
    }
  }
}
