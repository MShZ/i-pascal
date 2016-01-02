package com.siberika.idea.pascal.lang;

import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.psi.PsiElement;
import com.intellij.util.SmartList;
import com.siberika.idea.pascal.editor.PascalActionDeclare;
import com.siberika.idea.pascal.editor.PascalRoutineActions;
import com.siberika.idea.pascal.ide.actions.SectionToggle;
import com.siberika.idea.pascal.ide.actions.UsesActions;
import com.siberika.idea.pascal.lang.parser.NamespaceRec;
import com.siberika.idea.pascal.lang.psi.PasEnumType;
import com.siberika.idea.pascal.lang.psi.PasModule;
import com.siberika.idea.pascal.lang.psi.PasNamespaceIdent;
import com.siberika.idea.pascal.lang.psi.PascalNamedElement;
import com.siberika.idea.pascal.lang.psi.PascalStructType;
import com.siberika.idea.pascal.lang.psi.impl.PasExportedRoutineImpl;
import com.siberika.idea.pascal.lang.psi.impl.PasField;
import com.siberika.idea.pascal.lang.psi.impl.PasRoutineImplDeclImpl;
import com.siberika.idea.pascal.lang.psi.impl.PascalRoutineImpl;
import com.siberika.idea.pascal.lang.references.PasReferenceUtil;
import com.siberika.idea.pascal.util.PsiUtil;
import com.siberika.idea.pascal.util.StrUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

import static com.siberika.idea.pascal.PascalBundle.message;

/**
 * Author: George Bakhtadze
 * Date: 12/14/12
 */
public class PascalAnnotator implements Annotator {

    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if ((element instanceof PascalNamedElement) && (PsiUtil.isRoutineName((PascalNamedElement) element))) {
            PsiElement parent = element.getParent();
            if (parent.getClass() == PasExportedRoutineImpl.class) {
                annotateRoutineInInterface((PasExportedRoutineImpl) parent, holder);
            } else if (parent.getClass() == PasRoutineImplDeclImpl.class) {
                annotateRoutineInImplementation((PasRoutineImplDeclImpl) parent, holder);
            }
        }

        //noinspection ConstantConditions
        if (PsiUtil.isEntityName(element) && !PsiUtil.isLastPartOfMethodImplName((PascalNamedElement) element)) {
            //noinspection ConstantConditions
            PascalNamedElement namedElement = (PascalNamedElement) element;
            List<PsiElement> scopes = new SmartList<PsiElement>();
            Collection<PasField> refs = PasReferenceUtil.resolveExpr(scopes, NamespaceRec.fromElement(element), PasField.TYPES_ALL, true, 0);

            if (refs.isEmpty()) {
                PsiElement scope = scopes.isEmpty() ? null : scopes.get(0);
                Annotation ann = holder.createErrorAnnotation(element, message("ann.error.undeclared.identifier"));
                String name = namedElement.getName();
                if (!StrUtil.hasLowerCaseChar(name)) {
                    ann.registerFix(new PascalActionDeclare.ActionCreateConst(message("action.createConst"), namedElement, scope));
                } else {
                    if (scope instanceof PasEnumType) {
                        ann.registerFix(new PascalActionDeclare.ActionCreateEnum(message("action.createEnumConst"), namedElement, scope));
                    } else if (scope instanceof PascalStructType) {
                        ann.registerFix(new PascalActionDeclare.ActionCreateVar(message("action.createField"), namedElement, scope));
                        ann.registerFix(new PascalActionDeclare.ActionCreateProperty(message("action.createProperty"), namedElement, scope));
                    } else {
                        ann.registerFix(new PascalActionDeclare.ActionCreateVar(message("action.createVar"), namedElement, null));
                        ann.registerFix(new PascalActionDeclare.ActionCreateRoutine(message("action.createRoutine"), namedElement, scope, null));
                        if (scope instanceof PascalRoutineImpl) {
                            ann.registerFix(new PascalActionDeclare.ActionCreateVar(message("action.createParameter"), namedElement, scope));
                        }
                    }
                }
                if (name.startsWith("T") || PsiUtil.isTypeName(element)) {
                    ann.registerFix(new PascalActionDeclare.ActionCreateType(message("action.createType"), namedElement, scope));
                }
            }
        }

        if ((element instanceof PascalNamedElement) && PsiUtil.isUsedUnitName(element.getParent())) {
            annotateUnit(holder, (PasNamespaceIdent) element.getParent());
        }
    }

    private void annotateUnit(AnnotationHolder holder, PasNamespaceIdent usedUnitName) {
        if (PascalImportOptimizer.isExcludedFromCheck(usedUnitName)) {
            return;
        }
        Annotation ann = null;
        switch (PascalImportOptimizer.getUsedUnitStatus(usedUnitName)) {
            case UNUSED: {
                ann = holder.createWarningAnnotation(usedUnitName, message("ann.warn.unused.unit"));
                break;
            }
            case USED_IN_IMPL: {
                ann = holder.createWarningAnnotation(usedUnitName, message("ann.warn.unused.unit.interface"));
                ann.registerFix(new UsesActions.MoveUnitAction(message("action.uses.move"), usedUnitName));
                break;
            }
        }
        if (ann != null) {
            ann.registerFix(new UsesActions.RemoveUnitAction(message("action.uses.remove"), usedUnitName));
            ann.registerFix(new UsesActions.ExcludeUnitAction(message("action.uses.exclude"), usedUnitName));
            ann.registerFix(new UsesActions.OptimizeUsesAction(message("action.uses.optimize")));
        }
    }

    /**
     * # unimplemented routine error
     * # unimplemented method  error
     * # filter external/abstract routines/methods
     * # implement routine fix
     * # implement method fix
     * error on class if not all methods implemented
     * implement all methods fix
     */
    private void annotateRoutineInInterface(PasExportedRoutineImpl routine, AnnotationHolder holder) {
        if (!PsiUtil.isFromBuiltinsUnit(routine) && PsiUtil.needImplementation(routine) && (null == SectionToggle.getRoutineTarget(routine))) {
            Annotation ann = holder.createErrorAnnotation(routine, message("ann.error.missing.implementation"));
            ann.registerFix(new PascalRoutineActions.ActionImplement(message("action.implement"), routine));
            ann.registerFix(new PascalRoutineActions.ActionImplementAll(message("action.implement.all"), routine));
        }
    }

    /**
     * error on method in implementation only
     * add method to class declaration fix
     * add to interface section fix for routines in implementation section only
     */
    private void annotateRoutineInImplementation(PasRoutineImplDeclImpl routine, AnnotationHolder holder) {
        if (null == SectionToggle.getRoutineTarget(routine)) {
            if (routine.getContainingScope() instanceof PasModule) {
                if (((PasModule) routine.getContainingScope()).getUnitInterface() != null) {
                    Annotation ann = holder.createWeakWarningAnnotation(routine.getNameIdentifier() != null ? routine.getNameIdentifier() : routine, message("ann.error.missing.routine.declaration"));
                    ann.registerFix(new PascalRoutineActions.ActionDeclare(message("action.declare.routine"), routine));
                    ann.registerFix(new PascalRoutineActions.ActionDeclareAll(message("action.declare.routine.all"), routine));
                }
            } else {
                Annotation ann = holder.createErrorAnnotation(routine.getNameIdentifier() != null ? routine.getNameIdentifier() : routine, message("ann.error.missing.method.declaration"));
                ann.registerFix(new PascalRoutineActions.ActionDeclare(message("action.declare.method"), routine));
                ann.registerFix(new PascalRoutineActions.ActionDeclareAll(message("action.declare.method.all"), routine));
            }
        }
    }

}
