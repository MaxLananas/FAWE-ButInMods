package com.maxlananas.fawebim.core.expression;

import com.maxlananas.fawebim.core.util.noise.Noise;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * The expression engine behind {@code //calc}, {@code //deform},
 * {@code //generate} and the {@code =}-prefixed masks/patterns.
 *
 * <p>Supports WorldEdit's operators ({@code + - * / % ^ == != &lt; &lt;= &gt; &gt;= && || !})
 * and its function set (trigonometry, rounding, {@code min/max/abs/pow/random},
 * noise helpers, {@code if/while/for} statements and variable assignment).</p>
 */
public final class Expression {

    private final Node root;

    private Expression(Node root) {
        this.root = root;
    }

    /**
     * An expression that cannot be read or cannot be evaluated: a syntax error,
     * an unknown function, a loop that does not end. It is the player's input
     * that is wrong, so the commands answer it as such. It stays an
     * {@link IllegalArgumentException} for the callers that catch that.
     */
    public static final class ExpressionException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public ExpressionException(String message) {
            super(message);
        }
    }

    public static Expression compile(String input) {
        Parser parser = new Parser(input);
        Node node = parser.parseStatements();
        parser.expectEnd();
        return new Expression(node);
    }

    public double evaluate(Variables variables) {
        return root.eval(variables);
    }

    public int evaluateInt(Variables variables) {
        return (int) Math.floor(evaluate(variables));
    }

    /**
     * Variables are case-insensitive.
     *
     * <p>An expression is evaluated for every block of an edit and names a
     * handful of variables: a linear search over them costs less than hashing,
     * and the values stay unboxed. The map of boxed doubles this replaced
     * allocated on every assignment of every block.</p>
     */
    public static final class Variables {

        private String[] names = new String[8];
        private double[] values = new double[8];
        private int size;

        public Variables set(String name, double value) {
            String key = name.toLowerCase(Locale.ROOT);
            int index = indexOf(key);
            if (index < 0) {
                if (size == names.length) {
                    names = Arrays.copyOf(names, size * 2);
                    values = Arrays.copyOf(values, size * 2);
                }
                index = size++;
                names[index] = key;
            }
            values[index] = value;
            return this;
        }

        public double get(String name) {
            int index = indexOf(name.toLowerCase(Locale.ROOT));
            return index < 0 ? 0 : values[index];
        }

        /** Null when unset, so constants can take over. */
        public Double getOrNull(String name) {
            int index = indexOf(name.toLowerCase(Locale.ROOT));
            return index < 0 ? null : values[index];
        }

        public boolean has(String name) {
            return indexOf(name.toLowerCase(Locale.ROOT)) >= 0;
        }

        public Variables copy() {
            Variables copy = new Variables();
            copy.names = names.clone();
            copy.values = values.clone();
            copy.size = size;
            return copy;
        }

        /** The slot of a lowercase name, or -1. */
        private int indexOf(String key) {
            for (int i = 0; i < size; i++) {
                String name = names[i];
                if (name == key || name.equals(key)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ---------------------------------------------------------------- nodes

    private interface Node {
        double eval(Variables vars);
    }

    private record Constant(double value) implements Node {
        @Override
        public double eval(Variables vars) {
            return value;
        }
    }

    private static final class Variable implements Node {

        private final String name;
        /** What the name reads as when unset: bare constants, so "pi" and "e" work like in FAWE's expressions. */
        private final double fallback;

        Variable(String name) {
            this.name = name.toLowerCase(Locale.ROOT);
            this.fallback = switch (this.name) {
                case "pi" -> Math.PI;
                case "e" -> Math.E;
                case "true" -> 1;
                default -> 0;
            };
        }

        @Override
        public double eval(Variables vars) {
            int index = vars.indexOf(name);
            return index < 0 ? fallback : vars.values[index];
        }
    }

    private record Unary(String op, Node operand) implements Node {
        @Override
        public double eval(Variables vars) {
            double v = operand.eval(vars);
            return switch (op) {
                case "-" -> -v;
                case "+" -> v;
                case "!" -> v == 0 ? 1 : 0;
                default -> throw new IllegalStateException(op);
            };
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        @Override
        public double eval(Variables vars) {
            // Short-circuit for the logical operators.
            if (op.equals("&&")) {
                return left.eval(vars) != 0 && right.eval(vars) != 0 ? 1 : 0;
            }
            if (op.equals("||")) {
                return left.eval(vars) != 0 || right.eval(vars) != 0 ? 1 : 0;
            }
            double a = left.eval(vars);
            double b = right.eval(vars);
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                case "%" -> a % b;
                case "^" -> Math.pow(a, b);
                case "==" -> a == b ? 1 : 0;
                case "!=" -> a != b ? 1 : 0;
                case "<" -> a < b ? 1 : 0;
                case "<=" -> a <= b ? 1 : 0;
                case ">" -> a > b ? 1 : 0;
                case ">=" -> a >= b ? 1 : 0;
                default -> throw new IllegalStateException(op);
            };
        }
    }

    private record Sequence(java.util.List<Node> statements) implements Node {
        @Override
        public double eval(Variables vars) {
            double result = 0;
            for (Node statement : statements) {
                result = statement.eval(vars);
            }
            return result;
        }
    }

    private record IfNode(Node condition, Node thenBranch, Node elseBranch) implements Node {
        @Override
        public double eval(Variables vars) {
            if (condition.eval(vars) != 0) {
                return thenBranch.eval(vars);
            }
            return elseBranch == null ? 0 : elseBranch.eval(vars);
        }
    }

    private record WhileNode(Node condition, Node body) implements Node {
        @Override
        public double eval(Variables vars) {
            int guard = 0;
            while (condition.eval(vars) != 0) {
                body.eval(vars);
                if (++guard > 100_000) {
                    throw new ExpressionException("Expression loop exceeded 100000 iterations");
                }
            }
            return 0;
        }
    }

    private record ForNode(Node init, Node condition, Node increment, Node body) implements Node {
        @Override
        public double eval(Variables vars) {
            init.eval(vars);
            int guard = 0;
            while (condition.eval(vars) != 0) {
                body.eval(vars);
                increment.eval(vars);
                if (++guard > 100_000) {
                    throw new ExpressionException("Expression loop exceeded 100000 iterations");
                }
            }
            return 0;
        }
    }

    private record Assign(String name, Node value) implements Node {
        @Override
        public double eval(Variables vars) {
            double v = value.eval(vars);
            vars.set(name, v);
            return v;
        }
    }

    private record CompoundAssign(String name, String op, Node value) implements Node {
        @Override
        public double eval(Variables vars) {
            double current = vars.get(name);
            double v = value.eval(vars);
            double result = switch (op) {
                case "+=" -> current + v;
                case "-=" -> current - v;
                case "*=" -> current * v;
                case "/=" -> current / v;
                case "%=" -> current % v;
                default -> v;
            };
            vars.set(name, result);
            return result;
        }
    }

    /** {@code i++} / {@code i--}, used by the {@code for} loops of {@code //deform}. */
    private record PostfixIncrement(String name, double delta) implements Node {

        @Override
        public double eval(Variables vars) {
            double current = vars.get(name);
            vars.set(name, current + delta);
            return current;
        }
    }

    private record FunctionCall(String name, java.util.List<Node> args) implements Node {
        @Override
        public double eval(Variables vars) {
            double[] a = new double[args.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = args.get(i).eval(vars);
            }
            return call(name, a, vars);
        }
    }

    private static final Random RANDOM = new Random();
    private static final Noise.Perlin PERLIN = new Noise.Perlin(0);
    private static final Noise.Simplex SIMPLEX = new Noise.Simplex(0);
    private static final Noise.Voronoi VORONOI = new Noise.Voronoi(0);

    private static double arg(double[] a, int index) {
        return index < a.length ? a[index] : 0;
    }

    private static double call(String name, double[] a, Variables vars) {
        return switch (name) {
            case "abs" -> Math.abs(arg(a, 0));
            case "ceil" -> Math.ceil(arg(a, 0));
            case "floor" -> Math.floor(arg(a, 0));
            case "round" -> Math.round(arg(a, 0));
            case "sqrt" -> Math.sqrt(arg(a, 0));
            case "cbrt" -> Math.cbrt(arg(a, 0));
            case "pow" -> Math.pow(arg(a, 0), arg(a,1));
            case "exp" -> Math.exp(arg(a, 0));
            case "log" -> Math.log(arg(a, 0));
            case "log10" -> Math.log10(arg(a, 0));
            case "sin" -> Math.sin(arg(a, 0));
            case "cos" -> Math.cos(arg(a, 0));
            case "tan" -> Math.tan(arg(a, 0));
            case "asin" -> Math.asin(arg(a, 0));
            case "acos" -> Math.acos(arg(a, 0));
            case "atan" -> Math.atan(arg(a, 0));
            case "atan2" -> Math.atan2(arg(a, 0), arg(a,1));
            case "sinh" -> Math.sinh(arg(a, 0));
            case "cosh" -> Math.cosh(arg(a, 0));
            case "tanh" -> Math.tanh(arg(a, 0));
            case "min" -> Math.min(arg(a, 0), arg(a,1));
            case "max" -> Math.max(arg(a, 0), arg(a,1));
            case "clamp" -> Math.min(Math.max(arg(a, 0), arg(a,1)), arg(a,2));
            case "lerp" -> arg(a, 0) + (arg(a,1) - arg(a, 0)) * arg(a,2);
            case "sign" -> Math.signum(arg(a, 0));
            case "random" -> a.length == 0 ? RANDOM.nextDouble() : RANDOM.nextDouble() * arg(a, 0);
            case "randint" -> (double) (RANDOM.nextInt(Math.max(1, (int) arg(a, 0))));
            case "if" -> arg(a, 0) != 0 ? arg(a,1) : arg(a,2);
            case "perlin" -> PERLIN.noise(arg(a, 0), arg(a,1), arg(a,2));
            case "simplex" -> SIMPLEX.noise(arg(a, 0), arg(a,1), arg(a,2));
            case "voronoi" -> VORONOI.noise(arg(a, 0), arg(a,1), arg(a,2));
            case "rmf" -> new Noise.RidgedMultiFractal(0, 3, 2, 0.5).noise(arg(a, 0), arg(a,1), arg(a,2));
            case "band" -> {
                double noise = PERLIN.noise(arg(a, 0), arg(a,1), arg(a,2));
                yield 1.0 - Math.abs(noise);
            }
            case "block" -> arg(a, 0);
            case "pi" -> Math.PI;
            case "e" -> Math.E;
            case "true" -> 1;
            case "false" -> 0;
            default -> throw new ExpressionException("Unknown function '" + name + "'");
        };
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {

        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
        }

        Node parseStatements() {
            java.util.List<Node> statements = new java.util.ArrayList<>();
            skipWhitespaceAndSemicolons();
            while (pos < input.length() && input.charAt(pos) != '}') {
                Node statement = parseStatement();
                statements.add(statement);
                skipWhitespaceAndSemicolons();
            }
            if (statements.isEmpty()) {
                return new Constant(0);
            }
            return statements.size() == 1 ? statements.get(0) : new Sequence(statements);
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '#') {
                    while (pos < input.length() && input.charAt(pos) != '\n') {
                        pos++;
                    }
                } else {
                    break;
                }
            }
        }

        /** Statement level: also eats the ';' separators between statements. */
        private void skipWhitespaceAndSemicolons() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isWhitespace(c) || c == ';') {
                    pos++;
                } else if (c == '#') {
                    while (pos < input.length() && input.charAt(pos) != '\n') {
                        pos++;
                    }
                } else {
                    break;
                }
            }
        }

        private Node parseStatement() {
            skipWhitespaceAndSemicolons();
            if (matchKeyword("if")) {
                Node condition = parseParenExpression();
                Node thenBranch = parseBlockOrStatement();
                Node elseBranch = null;
                skipWhitespaceAndSemicolons();
                if (matchKeyword("else")) {
                    elseBranch = parseBlockOrStatement();
                }
                return new IfNode(condition, thenBranch, elseBranch);
            }
            if (matchKeyword("while")) {
                Node condition = parseParenExpression();
                return new WhileNode(condition, parseBlockOrStatement());
            }
            if (matchKeyword("for")) {
                expect('(');
                Node init = parseExpression();
                expect(';');
                Node condition = parseExpression();
                expect(';');
                Node increment = parseExpression();
                expect(')');
                return new ForNode(init, condition, increment, parseBlockOrStatement());
            }
            if (matchKeyword("return")) {
                return parseExpression();
            }
            return parseExpression();
        }

        private Node parseBlockOrStatement() {
            skipWhitespaceAndSemicolons();
            if (pos < input.length() && input.charAt(pos) == '{') {
                pos++;
                Node body = parseStatements();
                expect('}');
                return body;
            }
            return parseStatement();
        }

        private Node parseParenExpression() {
            expect('(');
            Node node = parseExpression();
            expect(')');
            return node;
        }

        private boolean matchKeyword(String keyword) {
            int save = pos;
            if (input.regionMatches(true, pos, keyword, 0, keyword.length())) {
                int after = pos + keyword.length();
                if (after >= input.length() || !Character.isLetterOrDigit(input.charAt(after))) {
                    pos = after;
                    return true;
                }
            }
            pos = save;
            return false;
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != c) {
                throw new ExpressionException(
                        "Expected '" + c + "' at position " + pos + " in expression: " + input);
            }
            pos++;
        }

        void expectEnd() {
            skipWhitespaceAndSemicolons();
            if (pos < input.length()) {
                throw new ExpressionException("Unexpected trailing input at " + pos + ": " + input);
            }
        }

        private Node parseExpression() {
            return parseAssignment();
        }

        private Node parseAssignment() {
            skipWhitespace();
            int save = pos;
            if (pos < input.length() && Character.isLetter(input.charAt(pos))) {
                String name = parseIdentifier();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '=' && (pos + 1 >= input.length()
                        || input.charAt(pos + 1) != '=')) {
                    pos++;
                    return new Assign(name, parseAssignment());
                }
                for (String op : new String[]{"+=", "-=", "*=", "/=", "%="}) {
                    if (input.startsWith(op, pos)) {
                        pos += 2;
                        return new CompoundAssign(name, op, parseAssignment());
                    }
                }
            }
            pos = save;
            return parseTernary();
        }

        private Node parseTernary() {
            Node condition = parseLogicalOr();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '?') {
                pos++;
                Node thenBranch = parseExpression();
                expect(':');
                Node elseBranch = parseExpression();
                return new IfNode(condition, thenBranch, elseBranch);
            }
            return condition;
        }

        private Node parseLogicalOr() {
            Node left = parseLogicalAnd();
            while (true) {
                skipWhitespace();
                if (input.startsWith("||", pos)) {
                    pos += 2;
                    left = new Binary("||", left, parseLogicalAnd());
                } else {
                    return left;
                }
            }
        }

        private Node parseLogicalAnd() {
            Node left = parseEquality();
            while (true) {
                skipWhitespace();
                if (input.startsWith("&&", pos)) {
                    pos += 2;
                    left = new Binary("&&", left, parseEquality());
                } else {
                    return left;
                }
            }
        }

        private Node parseEquality() {
            Node left = parseComparison();
            while (true) {
                skipWhitespace();
                if (input.startsWith("==", pos)) {
                    pos += 2;
                    left = new Binary("==", left, parseComparison());
                } else if (input.startsWith("!=", pos)) {
                    pos += 2;
                    left = new Binary("!=", left, parseComparison());
                } else {
                    return left;
                }
            }
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            while (true) {
                skipWhitespace();
                if (input.startsWith("<=", pos)) {
                    pos += 2;
                    left = new Binary("<=", left, parseAdditive());
                } else if (input.startsWith(">=", pos)) {
                    pos += 2;
                    left = new Binary(">=", left, parseAdditive());
                } else if (pos < input.length() && input.charAt(pos) == '<') {
                    pos++;
                    left = new Binary("<", left, parseAdditive());
                } else if (pos < input.length() && input.charAt(pos) == '>') {
                    pos++;
                    left = new Binary(">", left, parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    char op = input.charAt(pos++);
                    left = new Binary(String.valueOf(op), left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Node parseMultiplicative() {
            Node left = parsePower();
            while (true) {
                skipWhitespace();
                if (pos < input.length() && (input.charAt(pos) == '*' || input.charAt(pos) == '/'
                        || input.charAt(pos) == '%')) {
                    char op = input.charAt(pos++);
                    left = new Binary(String.valueOf(op), left, parsePower());
                } else {
                    return left;
                }
            }
        }

        private Node parsePower() {
            Node left = parseUnary();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '^') {
                pos++;
                return new Binary("^", left, parsePower());
            }
            return left;
        }

        private Node parseUnary() {
            skipWhitespace();
            if (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '-' || c == '+' || c == '!') {
                    pos++;
                    return new Unary(String.valueOf(c), parseUnary());
                }
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) {
                return new Constant(0);
            }
            char c = input.charAt(pos);
            if (c == '(') {
                pos++;
                Node node = parseExpression();
                expect(')');
                return node;
            }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < input.length()
                        && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.'
                        || input.charAt(pos) == 'e' || input.charAt(pos) == 'E'
                        || ((input.charAt(pos) == '-' || input.charAt(pos) == '+')
                        && pos > start && (input.charAt(pos - 1) == 'e' || input.charAt(pos - 1) == 'E')))) {
                    pos++;
                }
                return new Constant(Double.parseDouble(input.substring(start, pos)));
            }
            if (Character.isLetter(c) || c == '_') {
                String name = parseIdentifier();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '(') {
                    pos++;
                    java.util.List<Node> args = new java.util.ArrayList<>();
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) != ')') {
                        args.add(parseExpression());
                        while (true) {
                            skipWhitespace();
                            if (pos < input.length() && input.charAt(pos) == ',') {
                                pos++;
                                args.add(parseExpression());
                            } else {
                                break;
                            }
                        }
                    }
                    expect(')');
                    return new FunctionCall(name, args);
                }
                if (pos + 1 < input.length() && input.charAt(pos) == input.charAt(pos + 1)
                        && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    double delta = input.charAt(pos) == '+' ? 1 : -1;
                    pos += 2;
                    return new PostfixIncrement(name, delta);
                }
                return new Variable(name);
            }
            throw new ExpressionException("Unexpected character '" + c + "' at " + pos + " in " + input);
        }

        private String parseIdentifier() {
            int start = pos;
            while (pos < input.length()
                    && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }
            return input.substring(start, pos);
        }
    }
}
