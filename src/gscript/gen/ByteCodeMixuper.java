package gscript.gen;

import gscript.node.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 字节码混淆器
 */
public class ByteCodeMixuper implements Visitor {

    // 您的混淆字符集
    private static final String MIXUP_CHAR = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final Set<String> KEYWORDS = new HashSet<>();

    {
        // 内置打印
        KEYWORDS.add("println");
        // 匿名函数关键字
        KEYWORDS.add("anonymous");
        KEYWORDS.add("player");
        KEYWORDS.add("uiFrame");
        KEYWORDS.add("gameEnv");
        KEYWORDS.add("GAME_VARS");
    }

    // 当前计数值，用于生成下一个字符串
    private static int counter = 0;

    // 生成混淆字符串的方法
    public String generateMixup() {
        return generateBase62String(counter++);
    }

    // 将数字转换为基于MIXUP_CHAR字符集的字符串
    private String generateBase62String(int num) {
        if (num == 0) {
            return String.valueOf(MIXUP_CHAR.charAt(0));
        }

        StringBuilder result = new StringBuilder();
        int base = MIXUP_CHAR.length();

        while (num > 0) {
            int remainder = num % base;
            result.insert(0, MIXUP_CHAR.charAt(remainder));
            num = num / base;
        }

        return result.toString();
    }

    /**
     * 检测标识符是否需要混淆
     *
     * @return
     */
    public boolean isKeyMixup(String word) {
        // 首先检测是不是_开头的，下划线开头的不混淆
        if ("_".equals(word.substring(0, 1))) {
            return false;
        }
        return !KEYWORDS.contains(word);
    }

    private final Set<String> RETAIN_VARS = new HashSet<>();

    {
        RETAIN_VARS.add("player");
        RETAIN_VARS.add("uiFrame");
        RETAIN_VARS.add("gameEnv");
        RETAIN_VARS.add("GAME_VARS");
    }

    /**
     * 变量声明是否混淆
     * @param word
     * @return
     */
    public boolean isVariableDeclMixup(String word) {
        return !RETAIN_VARS.contains(word);
    }

    private final Set<String> RETAIN_FUNCTIONS = new HashSet<>();

    {
        RETAIN_FUNCTIONS.add("start");
        RETAIN_FUNCTIONS.add("entryGame");
        RETAIN_FUNCTIONS.add("beforePlayerTick");
        RETAIN_FUNCTIONS.add("drawHangUI");
        RETAIN_FUNCTIONS.add("keyHangUI");
        RETAIN_FUNCTIONS.add("keyInHangMenuOk");
    }

    /**
     * 函数直接调用是否混淆
     * @param word
     * @return
     */
     public boolean isFunctionMixup(String word) {
        return !RETAIN_FUNCTIONS.contains(word);
    }

    /**
     * 混淆字典表
     */
    public HashMap<String, String> mixupTable = new HashMap();

    @Override
    public void visit(VariableStatement node) {
        node.args.accept(this);
    }

    @Override
    public void visit(FunctionStatement node) {
        // 检测当前函数名是否保留
        if(isFunctionMixup(node.identifier.name)){
            node.identifier.accept(this);
        }
        for (Identifier ident : node.params) {
            ident.accept(this);
        }
        node.body.accept(this);
    }

    @Override
    public void visit(BlockStatement node) {
        for (Node stmt : node.stmt) {
            stmt.accept(this);
        }
    }

    @Override
    public void visit(ProgramNode program) {
        for (Node stmt : program.stmt) {
            stmt.accept(this);
        }
    }

    @Override
    public void visit(Expression node) {
        node.left.accept(this);
        if (node.operator != null) {
            node.operator.accept(this);
        }
        if (node.right != null) {
            node.right.accept(this);
        }
    }

    /**
     * 可选表达式
     * @param node
     */
    @Override
    public void visit(ConditionalExpression node) {
        if(node.condition instanceof Identifier){
            Identifier ident =  (Identifier)node.condition;
            // 检测表达式右侧是否需要混淆
            if(isFunctionMixup(ident.name) && isVariableDeclMixup(ident.name)){
                node.condition.accept(this);
            }
        }else{
            node.condition.accept(this);
        }

        if (node.thenExpr != null) {
            node.thenExpr.accept(this);
        }
        if (node.elseExpr != null) {
            node.elseExpr.accept(this);
        }
    }

    /**
     * 访问标识符，在这里进行混淆。将标识符映射成另外的标识符
     *
     * @param node
     */
    @Override
    public void visit(Identifier node) {
        // 检测是否需要混淆
        if (!isKeyMixup(node.name)) {
            return;
        }
        // 首先获取当前标识符名称是否已经在mixupTable里面
        if (mixupTable.containsKey(node.name)) {
            node.name = mixupTable.get(node.name);
        } else {
            String newName = generateMixup();
            mixupTable.put(node.name, newName);
            node.name = newName;
        }
    }

    @Override
    public void visit(Literal node) {
    }

    @Override
    public void visit(AdditiveExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(ExpressionStatement node) {
        node.expr.accept(this);
    }

    /**
     * 变量声明语句
     * @param node
     */
    @Override
    public void visit(VariableDecl node) {
        // 如果是不需要混淆的，变量名不混淆
        if(isVariableDeclMixup(node.identifier.name)) {
            node.identifier.accept(this);
        }
        node.value.accept(this);
    }

    @Override
    public void visit(PropertyAccess node) {
        if(!(node.object instanceof Identifier)) {
            node.object.accept(this);
        }
        // 如果属性访问是一个标识符，不需要混淆
        if(!(node.property instanceof Identifier)) {
            node.property.accept(this);
        }
    }

    @Override
    public void visit(ComputedMemberNode node) {
        node.object.accept(this);
        node.expression.accept(this);
    }

    /**
     * 函数调用
     * @param node
     */
    @Override
    public void visit(FunctionCallNode node) {
        // 如果是一级调用
        if(node.callee instanceof Identifier) {
            Identifier ident = (Identifier) node.callee;
            // 检测当前是否保留
            if(isFunctionMixup(ident.name)) {
                node.callee.accept(this);
            }
        }else{
            node.callee.accept(this);
        }
        for (Node arg : node.args) {
            arg.accept(this);
        }
    }

    @Override
    public void visit(ReturnStatement node) {
        if (node.expression != null) {
            node.expression.accept(this);
        }
    }

    @Override
    public void visit(UnaryExpression node) {
        node.operator.accept(this);
        node.operand.accept(this);
    }

    @Override
    public void visit(IfStatement node) {
        node.condition.accept(this);
        if (node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    @Override
    public void visit(EqualityExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(DoWhileStatement node) {
        node.body.accept(this);
        node.condition.accept(this);
    }

    @Override
    public void visit(ForStatement node) {
        node.init.accept(this);
        node.condition.accept(this);
        node.update.accept(this);
        node.body.accept(this);
    }

    @Override
    public void visit(VariableDeclList node) {
        for (VariableDecl decl : node.decls) {
            decl.accept(this);
        }
    }

    @Override
    public void visit(RelationalExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(WhileStatement node) {
        node.condition.accept(this);
        node.body.accept(this);
    }

    @Override
    public void visit(ContinueStatement node) {

    }

    @Override
    public void visit(BreakStatement node) {

    }

    @Override
    public void visit(PostfixExpression node) {
        node.operand.accept(this);
        node.operator.accept(this);
    }

    @Override
    public void visit(LogicalORExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(LogicalANDExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(BitwiseORExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(BitwiseXORExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(BitwiseANDExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(ObjectLiteral node) {
        node.members.forEach((key, value) -> {
            // 如果是标识符，不混淆
            if (!(key instanceof Identifier)) {
                key.accept(this);
            }
            value.accept(this);
        });
    }

    @Override
    public void visit(ArrayLiteral node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(MultiplicativeExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(ShiftExpression node) {
        node.left.accept(this);
        node.operator.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(MemberFunctionStatement expr) {
        expr.identifier.accept(this);
        for (Identifier param : expr.params) {
            param.accept(this);
        }
        expr.body.accept(this);
    }
}
