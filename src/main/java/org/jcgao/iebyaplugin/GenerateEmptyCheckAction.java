package org.jcgao.iebyaplugin;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;

@SuppressWarnings("checkstyle:NeedBraces")
public class GenerateEmptyCheckAction extends AnAction {
    private static final String OBJECT_UTILS_CLASS = "org.apache.commons.lang3.ObjectUtils";
    private static final String SLF4J_CLASS = "lombok.extern.slf4j.Slf4j";
    private static final String SLF4J_ANNOTATION = "@Slf4j";
    private static final String SLF4J = "Slf4j";
    private static final String LOG_OBJECT = "log";
    private Editor editor;
    private Project project;
    private PsiFile psiFile;

    @Override
    public void actionPerformed(AnActionEvent e) {
        // 0. 初始化类变量
        this.editor = e.getData(CommonDataKeys.EDITOR);
        this.project = e.getProject();
        this.psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || project == null || psiFile == null) {
            Messages.showErrorDialog(project, "Editor, project, or PSI file is not available.", "Error");
            return;
        }

        // 1. 获取光标位置的变量
        PsiElement cursorElement = psiFile.findElementAt(editor.getCaretModel().getOffset());
        if (cursorElement == null) {
            Messages.showErrorDialog(project, "No variable found at cursor position.", "Error");
            return;
        }
        String variableName = cursorElement.getText();
        PsiVariable cursorVar = PsiTreeUtil.getParentOfType(cursorElement, PsiVariable.class);
        if (cursorVar == null) {
            Messages.showErrorDialog(project, "No variable found at cursor position.", "Error");
            return;
        }

        // 2. 获取所在的类
        PsiClass containingClass = PsiTreeUtil.getParentOfType(cursorVar, PsiClass.class);
        if (containingClass == null) {
            Messages.showErrorDialog(project, "No containing class found.", "Error");
            return;
        }
        String className = containingClass.getName();

        // 3. 获取所在的方法
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(cursorVar, PsiMethod.class);
        if (containingMethod == null) {
            Messages.showErrorDialog(project, "No containing method found.", "Error");
            return;
        }
        String methodName = containingMethod.getName();

        // 新增：检查是否已存在相同的空值判断
        PsiStatement statement = getNextStatement(cursorVar);
        if (isEmptyCheckStatement(statement)) {
            modifyExistingLog((PsiIfStatement) statement, cursorElement);
            return;
        }

        // 4. 确定返回语句
        String returnStatement = getReturnStatement(containingMethod);

        // 5. 生成代码
        String code = geneCode(variableName, className, methodName, returnStatement);

        // 6. 插入代码
        insert(code);

        // 7. 检查并导入 ObjectUtils
        if (!isImported(OBJECT_UTILS_CLASS)) {
            addImport(OBJECT_UTILS_CLASS);
        }

        // 8. 检查并添加 @Slf4j 注解
        if (!hasAnnotation(containingClass, SLF4J) && !hasLogField(containingClass, LOG_OBJECT)) {
            addImport(SLF4J_CLASS);
            addAnnotation(containingClass, SLF4J_ANNOTATION);
        }
    }


    /**
     * 检查是否是 ObjectUtils.isEmpty 判断语句
     */
    private boolean isEmptyCheckStatement(PsiStatement statement) {

        if (!(statement instanceof PsiIfStatement)) {
            return false;
        }
        PsiIfStatement ifStatement = (PsiIfStatement) statement;
        PsiExpression condition = ifStatement.getCondition();

        if (condition instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression methodCall = (PsiMethodCallExpression) condition;
            PsiReferenceExpression methodExpression = methodCall.getMethodExpression();

            if ("isEmpty".equals(methodExpression.getReferenceName())) {
                PsiExpression qualifier = methodExpression.getQualifierExpression();
                if (qualifier != null && qualifier.getText().contains("ObjectUtils")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 获取变量后的下一条语句
     */
    private PsiStatement getNextStatement(PsiElement element) {
        PsiStatement currentElement = PsiTreeUtil.getParentOfType(element, PsiStatement.class);
        return PsiTreeUtil.getNextSiblingOfType(currentElement, PsiStatement.class);
    }

    private void modifyExistingLog(PsiIfStatement ifStatement, PsiElement cursorElement) {
        PsiElement resolvedElement = ((PsiReferenceExpression) cursorElement.getParent()).resolve();
        PsiStatement thenBranch = ifStatement.getThenBranch();

        if (resolvedElement instanceof PsiVariable) {
            String variableName = cursorElement.getText();
            handleLogModification(thenBranch, variableName, variableName);
        } else if (resolvedElement instanceof PsiMethod) {
            PsiMethodCallExpression methodCall = findContainingMethodCallExpression(cursorElement);
            String methodName = methodCall.getMethodExpression().getReferenceName();
            String methodCallText = methodCall.getText();
            handleLogModification(thenBranch, methodName, methodCallText);
        }
    }

    /**
     * 查找包含指定元素的完整方法调用表达式。
     */
    private PsiMethodCallExpression findContainingMethodCallExpression(PsiElement element) {
        PsiElement parent = element.getParent();
        while (parent != null) {
            if (parent instanceof PsiMethodCallExpression) {
                return (PsiMethodCallExpression) parent;
            }
            parent = parent.getParent();
        }
        return null; // 如果没有找到方法调用表达式，返回 null
    }


    private void handleLogModification(PsiStatement thenBranch, String key, String paramValue) {
        if (!(thenBranch instanceof PsiBlockStatement)) return;

        PsiCodeBlock codeBlock = ((PsiBlockStatement) thenBranch).getCodeBlock();
        for (PsiStatement stmt : codeBlock.getStatements()) {
            if (!(stmt instanceof PsiExpressionStatement)) continue;

            PsiExpression expr = ((PsiExpressionStatement) stmt).getExpression();
            if (!(expr instanceof PsiMethodCallExpression)) continue;

            PsiMethodCallExpression logCall = (PsiMethodCallExpression) expr;
            if (!"log.error".equals(logCall.getMethodExpression().getText())) continue;

            modifyLogStatement(logCall, key, paramValue);
            return; // 仅处理第一个log.error
        }
    }

    private void modifyLogStatement(PsiMethodCallExpression logCall, String key, String paramValue) {
        PsiExpressionList args = logCall.getArgumentList();
        PsiExpression[] expressions = args.getExpressions();
        if (expressions.length == 0) return;

        String originalMsg = expressions[0].getText();
        String newMsg = String.format("%s, %s: {}\"",
                originalMsg.substring(0, originalMsg.length() - 1), key);

        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 更新日志消息
            PsiExpression newMsgExpr = factory.createExpressionFromText(newMsg, logCall);
            expressions[0].replace(newMsgExpr);

            // 添加新参数
            PsiExpression paramExpr = factory.createExpressionFromText(paramValue, logCall);
            args.add(paramExpr);
        });
    }

    private boolean hasAnnotation(PsiClass psiClass, String annotationName) {
        PsiAnnotation[] annotations = psiClass.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            if (annotationName.equals(annotation.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLogField(PsiClass psiClass, String objName) {
        PsiField[] fields = psiClass.getFields();
        for (PsiField field : fields) {
            if (objName.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    private void addAnnotation(PsiClass psiClass, String annotationName) {
        // 确保文档已提交
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document != null) {
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiAnnotation slf4jAnnotation = factory.createAnnotationFromText(annotationName, psiClass);

        WriteCommandAction.runWriteCommandAction(project, () -> {
            psiClass.getModifierList().addBefore(slf4jAnnotation, psiClass.getModifierList().getFirstChild());
        });
    }

    /**
     * 检查是否导入了指定的类
     */
    private boolean isImported(String className) {
        PsiImportList importList = ((PsiJavaFile) psiFile).getImportList();
        if (importList == null) {
            return false;
        }
        for (PsiImportStatementBase importStatement : importList.getAllImportStatements()) {
            if (importStatement.getText().contains(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加导入语句
     */
    private void addImport(String className) {
        // 确保文档已提交
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document != null) {
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }

        PsiImportList importList = ((PsiJavaFile) psiFile).getImportList();
        if (importList == null) {
            return;
        }
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiImportStatement importStatement = factory.createImportStatement(JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.allScope(project)));
        WriteCommandAction.runWriteCommandAction(project, () -> {
            importList.add(importStatement);
        });
    }

    private String geneCode(String variableName, String className, String methodName, String returnStatement) {
        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(caretOffset);
        if (element == null) {
            return "";
        }

        // 找到光标所在的语句（例如方法、if 语句等）
        PsiElement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class);
        if (statement == null) {
            return "";
        }

        // 获取语句的起始位置和缩进
        int statementStartOffset = statement.getTextRange().getStartOffset();
        Document document = editor.getDocument();
        int statementStartColumn = statementStartOffset - document.getLineStartOffset(document.getLineNumber(statementStartOffset));

        String indent = "    "; // 单级缩进（4 个空格）
        String left = " ".repeat(statementStartColumn); // 基础缩进，与光标位置对齐
        StringBuilder codeBuilder = new StringBuilder();

        // 添加 if 条件（第一级缩进）
        codeBuilder.append(left).append("if (ObjectUtils.isEmpty(").append(variableName).append(")) {\n");

        // 构建 log.error 语句（第二级缩进）
        codeBuilder.append(left).append(indent).append("log.error(\"### ").append(className).append(".").append(methodName)
                .append("：").append(variableName).append(" is empty\");\n");

        // 添加 return 语句（第二级缩进）
        codeBuilder.append(left).append(indent).append(returnStatement).append("\n");

        // 结束 if 块（第一级缩进）
        codeBuilder.append(left).append("}");

        // 返回生成的完整代码
        return codeBuilder.toString();
    }


    private void insert(String code) {
        if (editor == null || project == null || psiFile == null) {
            return;
        }

        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(caretOffset);
        if (element == null) {
            return;
        }

        PsiElement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class);
        if (statement == null) {
            return;
        }

        int statementEndOffset = statement.getTextRange().getEndOffset();
        Document document = editor.getDocument();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.insertString(statementEndOffset, "\n" + code);
        });
    }

    private String getReturnStatement(PsiMethod containingMethod) {
        PsiType returnType = containingMethod.getReturnType();

        // 如果方法没有返回类型（构造函数）或返回类型是 void，返回 "return;"
        if (returnType == null || returnType.equals(PsiTypes.voidType())) {
            return "return;";
        }

        // 检查是否是基本类型并返回对应的默认值
        if (returnType instanceof PsiPrimitiveType) {
            if (returnType.equals(PsiTypes.intType()) || returnType.equals(PsiTypes.shortType())
                    || returnType.equals(PsiTypes.longType()) || returnType.equals(PsiTypes.byteType())) {
                return "return 0;";
            } else if (returnType.equals(PsiTypes.booleanType())) {
                return "return false;";
            } else if (returnType.equals(PsiTypes.charType())) {
                return "return '\\0';"; // 字符的默认值是空字符
            } else if (returnType.equals(PsiTypes.floatType()) || returnType.equals(PsiTypes.doubleType())) {
                return "return 0.0;";
            }
        }

        // 对于引用类型（对象类型），返回 "return null;"
        return "return null;";
    }

    // 仅在编辑器中显示此操作
    @Override
    public void update(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(editor != null && psiFile != null);
    }

    // 明确指定操作在后台线程（BGT）中执行
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}