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
 * {@code perlin}, {@code voronoi} and {@code ridgedmulti} with WorldEdit's
 * arguments, {@code if/while/for} statements and variable assignment).
 * WorldEdit's {@code megabuf}, {@code query} and {@code rotate} families are
 * not there, and a call of one is refused as an unknown function.</p>
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

        /**
         * The slot of a variable, made at 0 when it is new, for a caller that
         * sets it again for every block: {@link #set(int, double)} then costs
         * no search. A slot stays the variable's for as long as it is kept.
         */
        public int slot(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            int index = indexOf(key);
            if (index >= 0) {
                return index;
            }
            set(key, 0);
            return size - 1;
        }

        public void set(int slot, double value) {
            values[slot] = value;
        }

        /**
         * Forgets every variable made after the first {@code count}, so that one
         * set of variables reused from block to block starts each evaluation as
         * a new one would, with only the inputs the caller sets.
         */
        public void keepFirst(int count) {
            size = Math.min(size, count);
        }

        public Variables copy() {
            Variables copy = new Variables();
            copy.names = names.clone();
            copy.values = values.clone();
            copy.size = size;
            return copy;
        }

        /**
         * Where calls put their arguments: a stack, a call inside an argument
         * putting its own above, so evaluating a call allocates nothing.
         */
        private double[] arguments = new double[8];
        private int argumentTop;

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

    private record Unary(char op, Node operand) implements Node {
        @Override
        public double eval(Variables vars) {
            double v = operand.eval(vars);
            return switch (op) {
                case '-' -> -v;
                case '+' -> v;
                case '!' -> v == 0 ? 1 : 0;
                default -> throw new IllegalStateException(String.valueOf(op));
            };
        }
    }

    /**
     * The binary operators, told apart when the expression is read: every
     * operator of every block used to be found again by comparing strings.
     */
    private enum Operator {
        ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, POWER, EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER,
        GREATER_OR_EQUAL, AND, OR;

        static Operator of(String symbol) {
            return switch (symbol) {
                case "+" -> ADD;
                case "-" -> SUBTRACT;
                case "*" -> MULTIPLY;
                case "/" -> DIVIDE;
                case "%" -> REMAINDER;
                case "^" -> POWER;
                case "==" -> EQUAL;
                case "!=" -> NOT_EQUAL;
                case "<" -> LESS;
                case "<=" -> LESS_OR_EQUAL;
                case ">" -> GREATER;
                case ">=" -> GREATER_OR_EQUAL;
                case "&&" -> AND;
                case "||" -> OR;
                default -> throw new IllegalStateException(symbol);
            };
        }
    }

    private record Binary(Operator op, Node left, Node right) implements Node {

        Binary(String symbol, Node left, Node right) {
            this(Operator.of(symbol), left, right);
        }

        @Override
        public double eval(Variables vars) {
            // Short-circuit for the logical operators.
            if (op == Operator.AND) {
                return left.eval(vars) != 0 && right.eval(vars) != 0 ? 1 : 0;
            }
            if (op == Operator.OR) {
                return left.eval(vars) != 0 || right.eval(vars) != 0 ? 1 : 0;
            }
            double a = left.eval(vars);
            double b = right.eval(vars);
            return switch (op) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case REMAINDER -> a % b;
                case POWER -> Math.pow(a, b);
                case EQUAL -> a == b ? 1 : 0;
                case NOT_EQUAL -> a != b ? 1 : 0;
                case LESS -> a < b ? 1 : 0;
                case LESS_OR_EQUAL -> a <= b ? 1 : 0;
                case GREATER -> a > b ? 1 : 0;
                case GREATER_OR_EQUAL -> a >= b ? 1 : 0;
                case AND, OR -> throw new IllegalStateException(op.name());
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

    /** {@code a += b} and its kin, {@code op} being the operator's first character. */
    private record CompoundAssign(String name, char op, Node value) implements Node {
        @Override
        public double eval(Variables vars) {
            double current = vars.get(name);
            double v = value.eval(vars);
            double result = switch (op) {
                case '+' -> current + v;
                case '-' -> current - v;
                case '*' -> current * v;
                case '/' -> current / v;
                case '%' -> current % v;
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

    /**
     * A function of the language. It is found when the expression is read, so
     * an unknown name or a wrong number of arguments is refused before an edit
     * starts, and a call costs no lookup of its name.
     */
    @FunctionalInterface
    private interface Function {

        /** The value for the {@code count} arguments from {@code from} in {@code args}. */
        double apply(double[] args, int from, int count);
    }

    private record FunctionCall(Function function, Node[] args) implements Node {
        @Override
        public double eval(Variables vars) {
            // Each argument is evaluated before its slot is taken, and a call
            // inside it may have grown the stack meanwhile, so the array is
            // read again for every one.
            int from = vars.argumentTop;
            int top = from + args.length;
            if (top > vars.arguments.length) {
                vars.arguments = Arrays.copyOf(vars.arguments, Math.max(top, vars.arguments.length * 2));
            }
            vars.argumentTop = top;
            try {
                for (int i = 0; i < args.length; i++) {
                    double value = args[i].eval(vars);
                    vars.arguments[from + i] = value;
                }
                return function.apply(vars.arguments, from, args.length);
            } finally {
                vars.argumentTop = from;
            }
        }
    }

    /**
     * A function's name, the numbers of arguments it takes, and how to make
     * it: most are the same everywhere, a noise keeps the generator of the
     * seed it was last given, one per call written in the expression.
     */
    private record Definition(int fewest, int most, java.util.function.Supplier<Function> make) {

        boolean takes(int count) {
            return count >= fewest && count <= most;
        }
    }

    private static final java.util.Map<String, Definition> FUNCTIONS = new java.util.HashMap<>();

    private static void define(String name, int fewest, int most, Function function) {
        FUNCTIONS.put(name, new Definition(fewest, most, () -> function));
    }

    private static void define1(String name, java.util.function.DoubleUnaryOperator function) {
        define(name, 1, 1, (a, from, count) -> function.applyAsDouble(a[from]));
    }

    private static void define2(String name, java.util.function.DoubleBinaryOperator function) {
        define(name, 2, 2, (a, from, count) -> function.applyAsDouble(a[from], a[from + 1]));
    }

    private static final Random RANDOM = new Random();
    private static final Noise.Perlin PERLIN = new Noise.Perlin(0);
    private static final Noise.Simplex SIMPLEX = new Noise.Simplex(0);
    private static final Noise.Voronoi VORONOI = new Noise.Voronoi(0);
    private static final Noise.RidgedMultiFractal RIDGED = new Noise.RidgedMultiFractal(0, 3, 2, 0.5);
    /** libnoise's bound on octaves, which WorldEdit's noise functions enforce. */
    private static final int MOST_OCTAVES = 30;

    static {
        define1("abs", Math::abs);
        define1("ceil", Math::ceil);
        define1("floor", Math::floor);
        define1("rint", Math::rint);
        define1("round", Math::round);
        define1("sqrt", Math::sqrt);
        define1("cbrt", Math::cbrt);
        define1("exp", Math::exp);
        define1("log", Math::log);
        define1("ln", Math::log);
        define1("log10", Math::log10);
        define1("sin", Math::sin);
        define1("cos", Math::cos);
        define1("tan", Math::tan);
        define1("asin", Math::asin);
        define1("acos", Math::acos);
        define1("atan", Math::atan);
        define1("sinh", Math::sinh);
        define1("cosh", Math::cosh);
        define1("tanh", Math::tanh);
        define1("sign", Math::signum);
        define2("pow", Math::pow);
        define2("atan2", Math::atan2);
        // Any number of arguments, as in WorldEdit.
        define("min", 1, Integer.MAX_VALUE, (a, from, count) -> {
            double min = a[from];
            for (int i = 1; i < count; i++) {
                min = Math.min(min, a[from + i]);
            }
            return min;
        });
        define("max", 1, Integer.MAX_VALUE, (a, from, count) -> {
            double max = a[from];
            for (int i = 1; i < count; i++) {
                max = Math.max(max, a[from + i]);
            }
            return max;
        });
        define("clamp", 3, 3, (a, from, count) -> Math.min(Math.max(a[from], a[from + 1]), a[from + 2]));
        define("lerp", 3, 3, (a, from, count) -> a[from] + (a[from + 1] - a[from]) * a[from + 2]);
        define("random", 0, 1, (a, from, count) -> count == 0 ? RANDOM.nextDouble()
                : RANDOM.nextDouble() * a[from]);
        define("randint", 1, 1, (a, from, count) -> RANDOM.nextInt(Math.max(1, (int) a[from])));
        define("block", 1, 1, (a, from, count) -> a[from]);
        define("pi", 0, 0, (a, from, count) -> Math.PI);
        define("e", 0, 0, (a, from, count) -> Math.E);
        define("true", 0, 0, (a, from, count) -> 1);
        define("false", 0, 0, (a, from, count) -> 0);
        // x, y and z, a missing one read as 0. WorldEdit's forms, which start
        // with a seed, are the ones with more arguments.
        FUNCTIONS.put("perlin", new Definition(1, 7, FractalPerlin::new));
        FUNCTIONS.put("voronoi", new Definition(1, 5, VoronoiCells::new));
        FUNCTIONS.put("ridgedmulti", new Definition(6, 6, RidgedMulti::new));
        define("simplex", 1, 3, (a, from, count) -> SIMPLEX.noise(a[from], arg(a, from, count, 1),
                arg(a, from, count, 2)));
        define("rmf", 1, 3, (a, from, count) -> RIDGED.noise(a[from], arg(a, from, count, 1),
                arg(a, from, count, 2)));
        define("band", 1, 3, (a, from, count) -> 1.0 - Math.abs(PERLIN.noise(a[from], arg(a, from, count, 1),
                arg(a, from, count, 2))));
    }

    /** Argument {@code index} of a call, 0 when the call has fewer. */
    private static double arg(double[] a, int from, int count, int index) {
        return index < count ? a[from + index] : 0;
    }

    /** The octave count of a noise call, refused beyond what libnoise takes. */
    private static int octaves(String function, double value) {
        int octaves = (int) value;
        if (octaves < 1 || octaves > MOST_OCTAVES) {
            throw new ExpressionException(function + " takes 1 to " + MOST_OCTAVES + " octaves, not " + octaves);
        }
        return octaves;
    }

    /**
     * Perlin noises of consecutive seeds, one per octave, kept for the seed
     * the last call asked for: a call's seed is nearly always a constant, and
     * making a generator costs a permutation table. Another thread finding an
     * older set, or none, makes its own; each set is complete once seen.
     */
    private static final class PerlinOctaves {

        private record Layers(int seed, Noise.Perlin[] noises) {
        }

        private Layers last;

        Noise.Perlin[] get(int seed, int count) {
            Layers layers = last;
            if (layers == null || layers.seed() != seed || layers.noises().length < count) {
                Noise.Perlin[] noises = new Noise.Perlin[count];
                for (int i = 0; i < count; i++) {
                    noises[i] = new Noise.Perlin(seed + i);
                }
                layers = new Layers(seed, noises);
                last = layers;
            }
            return layers.noises();
        }
    }

    /**
     * {@code perlin(x, y, z)}, and WorldEdit's {@code perlin(seed, x, y, z,
     * frequency, octaves, persistence)}: octaves of Perlin noise, each twice as
     * fine and {@code persistence} times as strong as the one before, summed
     * the way libnoise, which WorldEdit uses, sums them. Its seven arguments
     * read as the three of the short form made a noise of the seed and two
     * coordinates, with no frequency: nothing like what the formula meant.
     */
    private static final class FractalPerlin implements Function {

        private final PerlinOctaves octaves = new PerlinOctaves();

        @Override
        public double apply(double[] a, int from, int count) {
            if (count <= 3) {
                return PERLIN.noise(a[from], arg(a, from, count, 1), arg(a, from, count, 2));
            }
            if (count != 7) {
                throw new ExpressionException("perlin takes x, y, z or seed, x, y, z, frequency, octaves, "
                        + "persistence; not " + count + " arguments");
            }
            double frequency = a[from + 4];
            int octaveCount = octaves("perlin", a[from + 5]);
            Noise.Perlin[] layers = octaves.get((int) a[from], octaveCount);
            double persistence = a[from + 6];
            double x = a[from + 1] * frequency;
            double y = a[from + 2] * frequency;
            double z = a[from + 3] * frequency;
            double value = 0;
            double strength = 1;
            for (int i = 0; i < octaveCount; i++) {
                value += layers[i].noise(x, y, z) * strength;
                x *= 2;
                y *= 2;
                z *= 2;
                strength *= persistence;
            }
            return value;
        }
    }

    /**
     * WorldEdit's {@code ridgedmulti(seed, x, y, z, frequency, octaves)}:
     * libnoise's ridged multifractal, octaves of Perlin noise folded into
     * ridges, each weighted by the one before, from -1 to about 1.
     */
    private static final class RidgedMulti implements Function {

        private final PerlinOctaves octaves = new PerlinOctaves();

        @Override
        public double apply(double[] a, int from, int count) {
            double frequency = a[from + 4];
            int octaveCount = octaves("ridgedmulti", a[from + 5]);
            Noise.Perlin[] layers = octaves.get((int) a[from], octaveCount);
            double x = a[from + 1] * frequency;
            double y = a[from + 2] * frequency;
            double z = a[from + 3] * frequency;
            double value = 0;
            double weight = 1;
            double spectral = 1;
            for (int i = 0; i < octaveCount; i++) {
                double signal = 1 - Math.abs(layers[i].noise(x, y, z));
                signal *= signal * weight;
                weight = Math.min(1, Math.max(0, signal * 2));
                value += signal * spectral;
                x *= 2;
                y *= 2;
                z *= 2;
                spectral *= 0.5;
            }
            return value * 1.25 - 1;
        }
    }

    /**
     * {@code voronoi(x, y, z)}, the distance to the nearest point of a Worley
     * pattern, and WorldEdit's {@code voronoi(seed, x, y, z, frequency)}: the
     * value of the cell around that point, one number from -1 to 1 over the
     * whole cell, as libnoise's Voronoi gives it.
     */
    private static final class VoronoiCells implements Function {

        private record Seeded(int seed, Noise.Voronoi noise) {
        }

        private Seeded last;

        @Override
        public double apply(double[] a, int from, int count) {
            if (count <= 3) {
                return VORONOI.noise(a[from], arg(a, from, count, 1), arg(a, from, count, 2));
            }
            if (count != 5) {
                throw new ExpressionException("voronoi takes x, y, z or seed, x, y, z, frequency; not " + count
                        + " arguments");
            }
            int seed = (int) a[from];
            Seeded seeded = last;
            if (seeded == null || seeded.seed() != seed) {
                seeded = new Seeded(seed, new Noise.Voronoi(seed));
                last = seeded;
            }
            double frequency = a[from + 4];
            return seeded.noise().cellValue(a[from + 1] * frequency, a[from + 2] * frequency,
                    a[from + 3] * frequency);
        }
    }

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
                        return new CompoundAssign(name, op.charAt(0), parseAssignment());
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
                    return new Unary(c, parseUnary());
                }
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) {
                // WorldEdit refuses a line that stops where a value is due:
                // "2+" read as 2 + 0 gave a result for what was never typed.
                throw new ExpressionException("Unexpected end of expression: " + input);
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
                    return call(name, args);
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

        /**
         * A call of a function, refused here when the function does not exist
         * or does not take that many arguments. {@code if(condition, then,
         * else)} evaluates only the branch it takes, as the statement does.
         */
        private Node call(String name, java.util.List<Node> args) {
            if (name.equals("if")) {
                if (args.size() != 3) {
                    throw new ExpressionException("'if' takes 3 argument(s), not " + args.size());
                }
                return new IfNode(args.get(0), args.get(1), args.get(2));
            }
            Definition definition = FUNCTIONS.get(name);
            if (definition == null) {
                throw new ExpressionException("Unknown function '" + name + "'");
            }
            if (!definition.takes(args.size())) {
                String takes = definition.fewest() == definition.most() ? String.valueOf(definition.fewest())
                        : definition.most() == Integer.MAX_VALUE ? "at least " + definition.fewest()
                        : definition.fewest() + " to " + definition.most();
                throw new ExpressionException("'" + name + "' takes " + takes + " argument(s), not " + args.size());
            }
            return new FunctionCall(definition.make().get(), args.toArray(new Node[0]));
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
