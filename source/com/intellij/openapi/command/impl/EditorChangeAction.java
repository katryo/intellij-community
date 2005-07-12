
package com.intellij.openapi.command.impl;

import com.intellij.openapi.command.undo.DocumentReference;
import com.intellij.openapi.command.undo.DocumentReferenceByDocument;
import com.intellij.openapi.command.undo.UndoableAction;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;

class EditorChangeAction implements UndoableAction {
  private final DocumentEx myDocument; // DocumentEx or WeakReference<DocumentEx> or null
  private final VirtualFile myDocumentFile;
  private int myOffset;
  private CharSequence myOldString;
  private CharSequence myNewString;
  private long myTimeStamp;
  private final Project myProject;

  public EditorChangeAction(DocumentEx document, int offset,
                            CharSequence oldString, CharSequence newString,
                            long oldTimeStamp, Project project) {
    myDocumentFile = FileDocumentManager.getInstance().getFile(document);
    if (myDocumentFile != null) {
      myDocument = null;
    }
    else {
      myDocument = document;
    }

    myOffset = offset;
    myOldString = oldString;
    if (myOldString == null) {
      myOldString = "";
    }
    myNewString = newString;
    if (myNewString == null) {
      myNewString = "";
    }
    myTimeStamp = oldTimeStamp;
    myProject = project;
  }

  public void undo() {
    exchangeStrings(myNewString, myOldString);
    getDocument().setModificationStamp(myTimeStamp);
    fileFileStatusChanged();
  }

  private void fileFileStatusChanged() {
    if (myProject == null) return;
    VirtualFile file = FileDocumentManager.getInstance().getFile(getDocument());
    if (file == null) return;
    FileStatusManager.getInstance(myProject).fileStatusChanged(file);
  }

  private void exchangeStrings(CharSequence newString, CharSequence oldString) {
    if (newString.length() > 0 && oldString.length() == 0){
      getDocument().deleteString(myOffset, myOffset + newString.length());
    }
    else if (oldString.length() > 0 && newString.length() == 0){
      getDocument().insertString(myOffset, oldString);
    }
    else if (oldString.length() > 0 && newString.length() > 0){
      getDocument().replaceString(myOffset, myOffset + newString.length(), oldString);
    }
  }

  public void redo() {
    exchangeStrings(myOldString, myNewString);
  }

  public DocumentReference[] getAffectedDocuments() {
    return new DocumentReference[]{DocumentReferenceByDocument.createDocumentReference(getDocument())};
  }

  public boolean isComplex() {
    return false;
  }

  public DocumentEx getDocument() {
    if (myDocument != null) return myDocument;
    return (DocumentEx)FileDocumentManager.getInstance().getDocument(myDocumentFile);
  }
}

