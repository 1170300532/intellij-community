package com.jetbrains.python.codeInsight.imports;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.lang.ImportOptimizer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.jetbrains.python.formatter.PyBlock;
import com.jetbrains.python.inspections.PyUnresolvedReferencesInspection;
import com.jetbrains.python.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author yole
 */
public class PyImportOptimizer implements ImportOptimizer {
  public boolean supports(PsiFile file) {
    return true;
  }

  @NotNull
  public Runnable processFile(@NotNull final PsiFile file) {
    final LocalInspectionToolSession session = new LocalInspectionToolSession(file, 0, file.getTextLength());
    final PyUnresolvedReferencesInspection.Visitor visitor = new PyUnresolvedReferencesInspection.Visitor(null,
                                                                                                          session,
                                                                                                          Collections.<String>emptyList());
    file.accept(new PyRecursiveElementVisitor() {
      @Override
      public void visitPyElement(PyElement node) {
        super.visitPyElement(node);
        node.accept(visitor);
      }
    });
    return new Runnable() {
      public void run() {
        visitor.optimizeImports();
        if (file instanceof PyFile) {
          new ImportSorter((PyFile) file).run();
        }
      }
    };
  }

  private static class ImportSorter {
    private final PyFile myFile;
    private final List<PyImportStatementBase> myBuiltinImports = new ArrayList<PyImportStatementBase>();
    private final List<PyImportStatementBase> myThirdPartyImports = new ArrayList<PyImportStatementBase>();
    private final List<PyImportStatementBase> myProjectImports = new ArrayList<PyImportStatementBase>();
    private final List<PyImportStatementBase> myImportBlock;
    private final PyElementGenerator myGenerator;
    private boolean myMissorted = false;

    private ImportSorter(PyFile file) {
      myFile = file;
      myImportBlock = myFile.getImportBlock();
      myGenerator = PyElementGenerator.getInstance(myFile.getProject());
    }

    public void run() {
      if (myImportBlock.isEmpty()) {
        return;
      }
      LanguageLevel langLevel = LanguageLevel.forElement(myFile);
      for (PyImportStatementBase importStatement : myImportBlock) {
        if (importStatement instanceof PyImportStatement && importStatement.getImportElements().length > 1) {
          for (PyImportElement importElement : importStatement.getImportElements()) {
            myMissorted = true;
            PsiElement toImport = importElement.resolve();
            final PyImportStatement splitImport = myGenerator.createImportStatementFromText(langLevel, "import " + importElement.getText());
            prioritize(splitImport, toImport);
          }
        }
        else {
          PsiElement toImport;
          if (importStatement instanceof PyFromImportStatement) {
            toImport = ((PyFromImportStatement) importStatement).resolveImportSource();
          }
          else {
            toImport = importStatement.getImportElements()[0].resolve();
          }
          prioritize(importStatement, toImport);
        }
      }
      if (myMissorted) {
        applyResults();
      }
    }

    private void prioritize(PyImportStatementBase importStatement, @Nullable PsiElement toImport) {
      if (toImport != null && !(toImport instanceof PsiFileSystemItem)) {
        toImport = toImport.getContainingFile();
      }
      final AddImportHelper.ImportPriority priority = toImport == null
                                                      ? AddImportHelper.ImportPriority.PROJECT
                                                      : AddImportHelper.getImportPriority(myFile, (PsiFileSystemItem)toImport);
      if (priority == AddImportHelper.ImportPriority.BUILTIN) {
        myBuiltinImports.add(importStatement);
        if (!myThirdPartyImports.isEmpty() || !myProjectImports.isEmpty()) {
          myMissorted = true;
        }
      }
      else if (priority == AddImportHelper.ImportPriority.THIRD_PARTY) {
        myThirdPartyImports.add(importStatement);
        if (!myProjectImports.isEmpty()) {
          myMissorted = true;
        }
      }
      else {
        myProjectImports.add(importStatement);
      }
    }

    private void applyResults() {
      markGroupBegin(myThirdPartyImports);
      markGroupBegin(myProjectImports);
      addImports(myBuiltinImports);
      addImports(myThirdPartyImports);
      addImports(myProjectImports);
      PsiElement lastElement = myImportBlock.get(myImportBlock.size()-1);
      myFile.deleteChildRange(myImportBlock.get(0), lastElement);
      for (PyImportStatementBase anImport : myBuiltinImports) {
        anImport.putCopyableUserData(PyBlock.IMPORT_GROUP_BEGIN, null);
      }
    }

    private static void markGroupBegin(List<PyImportStatementBase> imports) {
      if (imports.size() > 0) {
        imports.get(0).putCopyableUserData(PyBlock.IMPORT_GROUP_BEGIN, true);
      }
    }

    private void addImports(final List<PyImportStatementBase> imports) {
      for (PyImportStatementBase newImport: imports) {
        myFile.addBefore(newImport, myImportBlock.get(0));
      }
    }
  }
}
