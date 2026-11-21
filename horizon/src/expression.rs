//! The expression engine behind `//calc`, `//deform`, `//generate` and the
//! `=`-prefixed masks/patterns — a port of `Expression.java` from the Java
//! reference.
//!
//! Supports WorldEdit's operators (`+ - * / % ^ == != < <= > >= && || !`) and
//! its function set (trigonometry, rounding, `min/max/abs/pow/random`,
//! `perlin`, `voronoi` and `ridgedmulti` with WorldEdit's arguments,
//! `if/while/for` statements and variable assignment). WorldEdit's `megabuf`,
//! `query` and `rotate` families are not there, and a call of one is refused
//! as an unknown function.

use crate::noise::{self, JavaRandom, NoiseFn};
use std::cell::RefCell;

/// An expression that cannot be read or cannot be evaluated: a syntax error,
/// an unknown function, a loop that does not end. It is the player's input
/// that is wrong, so the commands answer it as such.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExpressionError(pub String);

impl std::fmt::Display for ExpressionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ExpressionError {}

fn error<T>(message: impl Into<String>) -> Result<T, ExpressionError> {
    Err(ExpressionError(message.into()))
}

/// libnoise's bound on octaves, which WorldEdit's noise functions enforce.
const MOST_OCTAVES: i32 = 30;
/// The guard on loops, as in the Java reference.
const LOOP_GUARD: u64 = 100_000;
/// How deep a formula may nest: the parser is a recursive descent.
const MAX_DEPTH: usize = 128;

/// The variables of one evaluation. Variables are case-insensitive; a linear
/// search over a handful of names costs less than hashing.
#[derive(Default)]
pub struct Variables {
    names: Vec<String>,
    values: Vec<f64>,
    /// Where calls put their arguments: a stack, a call inside an argument
    /// putting its own above, so evaluating a call allocates nothing.
    arguments: Vec<f64>,
    argument_top: usize,
}

impl Variables {
    pub fn new() -> Self {
        Self::default()
    }

    fn index_of(&self, key: &str) -> Option<usize> {
        self.names.iter().position(|name| name == key)
    }

    /// Sets the variable, creating it when new; used for builder-style setup.
    pub fn set(mut self, name: &str, value: f64) -> Self {
        self.set_mut(name, value);
        self
    }

    /// Sets the variable, creating it when new.
    pub fn set_mut(&mut self, name: &str, value: f64) {
        let key = name.to_lowercase();
        match self.index_of(&key) {
            Some(index) => self.values[index] = value,
            None => {
                self.names.push(key);
                self.values.push(value);
            }
        }
    }

    /// The value of the variable, 0 when never set.
    pub fn get(&self, name: &str) -> f64 {
        match self.index_of(&name.to_lowercase()) {
            Some(index) => self.values[index],
            None => 0.0,
        }
    }

    /// Whether the variable was ever set.
    pub fn has(&self, name: &str) -> bool {
        self.index_of(&name.to_lowercase()).is_some()
    }

    fn arg_push(&mut self, value: f64) {
        if self.argument_top == self.arguments.len() {
            self.arguments.push(value);
        } else {
            self.arguments[self.argument_top] = value;
        }
        self.argument_top += 1;
    }

    fn arg_at(&self, from: usize, index: usize) -> f64 {
        self.arguments[from + index]
    }
}

/// Argument `index` of a call, 0 when the call has fewer.
fn arg(vars: &Variables, from: usize, count: usize, index: usize) -> f64 {
    if index < count {
        vars.arg_at(from, index)
    } else {
        0.0
    }
}

/// An expression, read once and evaluated any number of times.
pub struct Expression {
    root: Node,
}

impl std::fmt::Debug for Expression {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Expression")
    }
}

impl Expression {
    /// Reads an expression, refusing what cannot be read as an
    /// [`ExpressionError`].
    pub fn compile(input: &str) -> Result<Self, ExpressionError> {
        let mut parser = Parser::new(input);
        let root = parser.parse_statements()?;
        parser.expect_end()?;
        Ok(Self { root })
    }

    /// The value of the expression in `variables`.
    pub fn evaluate(&self, variables: &mut Variables) -> Result<f64, ExpressionError> {
        self.root.eval(variables)
    }

    /// The integer part of the value, as Java's `evaluateInt` gives it.
    pub fn evaluate_int(&self, variables: &mut Variables) -> Result<i32, ExpressionError> {
        Ok(self.evaluate(variables)?.floor() as i32)
    }
}

enum Node {
    Constant(f64),
    Variable {
        name: String,
        fallback: f64,
    },
    Unary(u8, Box<Node>),
    Binary(BinaryOp, Box<Node>, Box<Node>),
    Sequence(Vec<Node>),
    If(Box<Node>, Box<Node>, Option<Box<Node>>),
    While(Box<Node>, Box<Node>),
    For(Box<Node>, Box<Node>, Box<Node>, Box<Node>),
    Assign(String, Box<Node>),
    CompoundAssign(String, u8, Box<Node>),
    PostfixIncrement(String, f64),
    FunctionCall(FunctionKind, Vec<Node>),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum BinaryOp {
    Add,
    Subtract,
    Multiply,
    Divide,
    Remainder,
    Power,
    Equal,
    NotEqual,
    Less,
    LessOrEqual,
    Greater,
    GreaterOrEqual,
    And,
    Or,
}

impl Node {
    fn eval(&self, vars: &mut Variables) -> Result<f64, ExpressionError> {
        match self {
            Node::Constant(value) => Ok(*value),
            Node::Variable { name, fallback } => Ok(match vars.index_of(name) {
                Some(index) => vars.values[index],
                None => *fallback,
            }),
            Node::Unary(op, operand) => {
                let value = operand.eval(vars)?;
                Ok(match *op {
                    b'-' => -value,
                    b'+' => value,
                    b'!' => f64::from(value == 0.0),
                    _ => unreachable!("unary operator"),
                })
            }
            Node::Binary(op, left, right) => {
                // Short-circuit for the logical operators.
                if matches!(op, BinaryOp::And | BinaryOp::Or) {
                    let left = left.eval(vars)? != 0.0;
                    let value = if matches!(op, BinaryOp::And) {
                        left && right.eval(vars)? != 0.0
                    } else {
                        left || right.eval(vars)? != 0.0
                    };
                    return Ok(f64::from(value));
                }
                let a = left.eval(vars)?;
                let b = right.eval(vars)?;
                Ok(match op {
                    BinaryOp::Add => a + b,
                    BinaryOp::Subtract => a - b,
                    BinaryOp::Multiply => a * b,
                    BinaryOp::Divide => a / b,
                    BinaryOp::Remainder => a % b,
                    BinaryOp::Power => a.powf(b),
                    BinaryOp::Equal => f64::from(a == b),
                    BinaryOp::NotEqual => f64::from(a != b),
                    BinaryOp::Less => f64::from(a < b),
                    BinaryOp::LessOrEqual => f64::from(a <= b),
                    BinaryOp::Greater => f64::from(a > b),
                    BinaryOp::GreaterOrEqual => f64::from(a >= b),
                    BinaryOp::And | BinaryOp::Or => unreachable!(),
                })
            }
            Node::Sequence(statements) => {
                let mut result = 0.0;
                for statement in statements {
                    result = statement.eval(vars)?;
                }
                Ok(result)
            }
            Node::If(condition, then_branch, else_branch) => {
                if condition.eval(vars)? != 0.0 {
                    then_branch.eval(vars)
                } else {
                    match else_branch {
                        Some(branch) => branch.eval(vars),
                        None => Ok(0.0),
                    }
                }
            }
            Node::While(condition, body) => {
                let mut guard = 0u64;
                while condition.eval(vars)? != 0.0 {
                    body.eval(vars)?;
                    guard += 1;
                    if guard > LOOP_GUARD {
                        return error(format!(
                            "Expression loop exceeded {LOOP_GUARD} iterations"
                        ));
                    }
                }
                Ok(0.0)
            }
            Node::For(init, condition, increment, body) => {
                init.eval(vars)?;
                let mut guard = 0u64;
                while condition.eval(vars)? != 0.0 {
                    body.eval(vars)?;
                    increment.eval(vars)?;
                    guard += 1;
                    if guard > LOOP_GUARD {
                        return error(format!(
                            "Expression loop exceeded {LOOP_GUARD} iterations"
                        ));
                    }
                }
                Ok(0.0)
            }
            Node::Assign(name, value) => {
                let value = value.eval(vars)?;
                vars.set_mut(name, value);
                Ok(value)
            }
            Node::CompoundAssign(name, op, value) => {
                let current = vars.get(name);
                let value = value.eval(vars)?;
                let result = match *op {
                    b'+' => current + value,
                    b'-' => current - value,
                    b'*' => current * value,
                    b'/' => current / value,
                    b'%' => current % value,
                    _ => value,
                };
                vars.set_mut(name, result);
                Ok(result)
            }
            Node::PostfixIncrement(name, delta) => {
                let current = vars.get(name);
                vars.set_mut(name, current + delta);
                Ok(current)
            }
            Node::FunctionCall(function, arguments) => {
                // Each argument is evaluated before its slot is taken, and a
                // call inside it may have grown the stack meanwhile, so the
                // stack is read again for every one.
                let from = vars.argument_top;
                for argument in arguments {
                    let value = argument.eval(vars)?;
                    vars.arg_push(value);
                }
                let result = function.apply(vars, from, arguments.len());
                vars.argument_top = from;
                result
            }
        }
    }
}

/// How a call evaluates, decided per call site: most functions are the same
/// everywhere, a noise keeps the generator of the seed it was last given, one
/// per call written in the expression.
enum FunctionKind {
    /// A function of the arguments alone.
    Pure(Box<dyn Fn(&Variables, usize, usize) -> Result<f64, ExpressionError>>),
    /// Perlin noises of consecutive seeds, rebuilt for the last seed asked
    /// for. A call's seed is nearly always a constant, and making a
    /// generator costs a permutation table.
    PerlinOctaves(RefCell<OctaveCache>),
    /// `ridgedmulti`: the same octave cache, folded into ridges.
    RidgedMultiOctaves(RefCell<OctaveCache>),
    /// `voronoi(seed, x, y, z, frequency)` keeps the last generator it built.
    VoronoiCells(RefCell<Option<(i32, noise::Voronoi)>>),
}

impl FunctionKind {
    fn apply(&self, vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
        match self {
            FunctionKind::Pure(function) => function(vars, from, count),
            FunctionKind::PerlinOctaves(cache) => fractal_perlin(cache, vars, from, count),
            FunctionKind::RidgedMultiOctaves(cache) => ridged_multi(cache, vars, from, count),
            FunctionKind::VoronoiCells(last) => voronoi_cells(last, vars, from, count),
        }
    }
}

/// How deep a Perlin octave list reaches, kept per seed.
#[derive(Default)]
struct OctaveCache {
    seed: Option<i64>,
    noises: Vec<noise::Perlin>,
}

impl OctaveCache {
    fn get(&mut self, seed: i64, count: usize) -> &[noise::Perlin] {
        if self.seed != Some(seed) || self.noises.len() < count {
            self.noises = (0..count as i64).map(|i| noise::Perlin::new(seed + i)).collect();
            self.seed = Some(seed);
        }
        &self.noises
    }
}

thread_local! {
    static RANDOM: RefCell<JavaRandom> = const { RefCell::new(JavaRandom::new(0)) };
    static STATIC_PERLIN: RefCell<Option<noise::Perlin>> = const { RefCell::new(None) };
    static STATIC_SIMPLEX: RefCell<Option<noise::Simplex>> = const { RefCell::new(None) };
    static STATIC_VORONOI: RefCell<Option<noise::Voronoi>> = const { RefCell::new(None) };
    static STATIC_RIDGED: RefCell<Option<noise::RidgedMultiFractal>> = const { RefCell::new(None) };
}

fn static_perlin<R>(f: impl FnOnce(&noise::Perlin) -> R) -> R {
    STATIC_PERLIN.with(|slot| f(slot.borrow_mut().get_or_insert_with(|| noise::Perlin::new(0))))
}
fn static_simplex<R>(f: impl FnOnce(&noise::Simplex) -> R) -> R {
    STATIC_SIMPLEX.with(|slot| f(slot.borrow_mut().get_or_insert_with(|| noise::Simplex::new(0))))
}
fn static_voronoi<R>(f: impl FnOnce(&noise::Voronoi) -> R) -> R {
    STATIC_VORONOI.with(|slot| f(slot.borrow_mut().get_or_insert_with(|| noise::Voronoi::new(0))))
}
fn static_ridged<R>(f: impl FnOnce(&noise::RidgedMultiFractal) -> R) -> R {
    STATIC_RIDGED.with(|slot| {
        f(slot
            .borrow_mut()
            .get_or_insert_with(|| noise::RidgedMultiFractal::new(0, 3, 2.0, 0.5)))
    })
}
fn random_double() -> f64 {
    RANDOM.with(|random| random.borrow_mut().next_double())
}
fn random_int(bound: i32) -> f64 {
    RANDOM.with(|random| random.borrow_mut().next_int_below(bound) as f64)
}

/// The octave count of a noise call, refused beyond what libnoise takes.
fn octaves(function: &str, value: f64) -> Result<i32, ExpressionError> {
    let octaves = value as i32;
    if octaves < 1 || octaves > MOST_OCTAVES {
        return error(format!(
            "{function} takes 1 to {MOST_OCTAVES} octaves, not {octaves}"
        ));
    }
    Ok(octaves)
}

/// `perlin(x, y, z)`, and WorldEdit's `perlin(seed, x, y, z, frequency,
/// octaves, persistence)`: octaves of Perlin noise, each twice as fine and
/// `persistence` times as strong as the one before, summed the way libnoise,
/// which WorldEdit uses, sums them.
fn fractal_perlin(
    cache: &RefCell<OctaveCache>,
    vars: &Variables,
    from: usize,
    count: usize,
) -> Result<f64, ExpressionError> {
    if count <= 3 {
        return Ok(static_perlin(|perlin| {
            perlin.noise(
                vars.arg_at(from, 0),
                arg(vars, from, count, 1),
                arg(vars, from, count, 2),
            )
        }));
    }
    if count != 7 {
        return error(format!(
            "perlin takes x, y, z or seed, x, y, z, frequency, octaves, persistence; not {count} arguments"
        ));
    }
    let frequency = vars.arg_at(from, 4);
    let octave_count = octaves("perlin", vars.arg_at(from, 5))? as usize;
    let seed = vars.arg_at(from, 0) as i64;
    let persistence = vars.arg_at(from, 6);
    let mut x = vars.arg_at(from, 1) * frequency;
    let mut y = vars.arg_at(from, 2) * frequency;
    let mut z = vars.arg_at(from, 3) * frequency;
    let value = {
        let mut cache = cache.borrow_mut();
        let layers = cache.get(seed, octave_count);
        let mut value = 0.0;
        let mut strength = 1.0;
        for layer in layers {
            value += layer.noise(x, y, z) * strength;
            x *= 2.0;
            y *= 2.0;
            z *= 2.0;
            strength *= persistence;
        }
        value
    };
    Ok(value)
}

/// WorldEdit's `ridgedmulti(seed, x, y, z, frequency, octaves)`: libnoise's
/// ridged multifractal, octaves of Perlin noise folded into ridges, each
/// weighted by the one before, from -1 to about 1.
fn ridged_multi(
    cache: &RefCell<OctaveCache>,
    vars: &Variables,
    from: usize,
    _count: usize,
) -> Result<f64, ExpressionError> {
    let frequency = vars.arg_at(from, 4);
    let octave_count = octaves("ridgedmulti", vars.arg_at(from, 5))? as usize;
    let seed = vars.arg_at(from, 0) as i64;
    let mut x = vars.arg_at(from, 1) * frequency;
    let mut y = vars.arg_at(from, 2) * frequency;
    let mut z = vars.arg_at(from, 3) * frequency;
    let value = {
        let mut cache = cache.borrow_mut();
        let layers = cache.get(seed, octave_count);
        let mut value = 0.0;
        let mut weight = 1.0;
        let mut spectral = 1.0;
        for layer in layers {
            let mut signal = 1.0 - layer.noise(x, y, z).abs();
            signal *= signal * weight;
            weight = (signal * 2.0).clamp(0.0, 1.0);
            value += signal * spectral;
            x *= 2.0;
            y *= 2.0;
            z *= 2.0;
            spectral *= 0.5;
        }
        value
    };
    Ok(value * 1.25 - 1.0)
}

/// `voronoi(x, y, z)`, the distance to the nearest point of a Worley pattern,
/// and WorldEdit's `voronoi(seed, x, y, z, frequency)`: the value of the cell
/// around that point, one number from -1 to 1 over the whole cell, as
/// libnoise's Voronoi gives it.
fn voronoi_cells(
    last: &RefCell<Option<(i32, noise::Voronoi)>>,
    vars: &Variables,
    from: usize,
    count: usize,
) -> Result<f64, ExpressionError> {
    if count <= 3 {
        return Ok(static_voronoi(|voronoi| {
            voronoi.noise(
                vars.arg_at(from, 0),
                arg(vars, from, count, 1),
                arg(vars, from, count, 2),
            )
        }));
    }
    if count != 5 {
        return error(format!(
            "voronoi takes x, y, z or seed, x, y, z, frequency; not {count} arguments"
        ));
    }
    let seed = vars.arg_at(from, 0) as i32;
    let frequency = vars.arg_at(from, 4);
    let x = vars.arg_at(from, 1) * frequency;
    let y = vars.arg_at(from, 2) * frequency;
    let z = vars.arg_at(from, 3) * frequency;
    let cell = {
        let mut slot = last.borrow_mut();
        if slot.as_ref().map(|(seed_, _)| *seed_) != Some(seed) {
            *slot = Some((seed, noise::Voronoi::new(seed as i64)));
        }
        slot.as_ref().expect("cached voronoi").1.cell_value(x, y, z)
    };
    Ok(cell)
}

/// A pure function of one double through `f`.
fn pure1(f: fn(f64) -> f64) -> FunctionKind {
    FunctionKind::Pure(Box::new(move |vars: &Variables, from: usize, _count: usize| {
        Ok(f(vars.arg_at(from, 0)))
    }))
}

/// A pure function of two doubles through `f`.
fn pure2(f: fn(f64, f64) -> f64) -> FunctionKind {
    FunctionKind::Pure(Box::new(move |vars: &Variables, from: usize, _count: usize| {
        Ok(f(vars.arg_at(from, 0), vars.arg_at(from, 1)))
    }))
}

fn java_round(value: f64) -> f64 {
    noise::java_round(value)
}

fn java_signum(value: f64) -> f64 {
    noise::java_signum(value)
}

fn random_argument(vars: &Variables, from: usize, count: usize) -> f64 {
    if count == 0 {
        random_double()
    } else {
        random_double() * vars.arg_at(from, 0)
    }
}

fn randint_argument(vars: &Variables, from: usize, _count: usize) -> f64 {
    let bound = vars.arg_at(from, 0).max(1.0) as i32;
    random_int(bound.max(1))
}

fn min_variadic(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    let mut min = vars.arg_at(from, 0);
    for index in 1..count {
        min = min.min(vars.arg_at(from, index));
    }
    Ok(min)
}

fn max_variadic(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    let mut max = vars.arg_at(from, 0);
    for index in 1..count {
        max = max.max(vars.arg_at(from, index));
    }
    Ok(max)
}

fn clamp_argument(vars: &Variables, from: usize, _count: usize) -> Result<f64, ExpressionError> {
    Ok(vars.arg_at(from, 0).clamp(vars.arg_at(from, 1), vars.arg_at(from, 2)))
}

fn lerp_argument(vars: &Variables, from: usize, _count: usize) -> Result<f64, ExpressionError> {
    let a = vars.arg_at(from, 0);
    Ok(a + (vars.arg_at(from, 1) - a) * vars.arg_at(from, 2))
}

fn identity_argument(vars: &Variables, from: usize, _count: usize) -> Result<f64, ExpressionError> {
    Ok(vars.arg_at(from, 0))
}

fn simplex_argument(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    Ok(static_simplex(|simplex| {
        simplex.noise(
            vars.arg_at(from, 0),
            arg(vars, from, count, 1),
            arg(vars, from, count, 2),
        )
    }))
}

fn rmf_argument(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    Ok(static_ridged(|ridged| {
        ridged.noise(
            vars.arg_at(from, 0),
            arg(vars, from, count, 1),
            arg(vars, from, count, 2),
        )
    }))
}

fn band_argument(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    Ok(static_perlin(|perlin| {
        1.0 - perlin
            .noise(
                vars.arg_at(from, 0),
                arg(vars, from, count, 1),
                arg(vars, from, count, 2),
            )
            .abs()
    }))
}

fn constant_argument(value: f64) -> FunctionKind {
    FunctionKind::Pure(Box::new(move |_, _, _| Ok(value)))
}

/// A definition of a function: its name as spelled, the least and most
/// arguments it takes, and how to make it for one call site (a fresh noise
/// cache per call site, as in the Java reference).
struct Definition {
    name: &'static str,
    fewest: usize,
    most: usize,
    make: Box<dyn Fn() -> FunctionKind>,
}

fn unary_definition(name: &'static str, f: fn(f64) -> f64) -> Definition {
    Definition {
        name,
        fewest: 1,
        most: 1,
        make: Box::new(move || pure1(f)),
    }
}

/// The functions the language knows, as in the Java reference.
fn definitions() -> Vec<Definition> {
    let table = vec![
        unary_definition("abs", f64::abs),
        unary_definition("ceil", f64::ceil),
        unary_definition("floor", f64::floor),
        unary_definition("rint", f64::round_ties_even),
        unary_definition("round", java_round),
        unary_definition("sqrt", f64::sqrt),
        unary_definition("cbrt", f64::cbrt),
        unary_definition("exp", f64::exp),
        unary_definition("log", f64::ln),
        unary_definition("ln", f64::ln),
        unary_definition("log10", f64::log10),
        unary_definition("sin", f64::sin),
        unary_definition("cos", f64::cos),
        unary_definition("tan", f64::tan),
        unary_definition("asin", f64::asin),
        unary_definition("acos", f64::acos),
        unary_definition("atan", f64::atan),
        unary_definition("sinh", f64::sinh),
        unary_definition("cosh", f64::cosh),
        unary_definition("tanh", f64::tanh),
        unary_definition("sign", java_signum),
        Definition { name: "pow", fewest: 2, most: 2, make: Box::new(|| pure2(f64::powf)) },
        Definition { name: "atan2", fewest: 2, most: 2, make: Box::new(|| pure2(f64::atan2)) },
        Definition { name: "min", fewest: 1, most: usize::MAX, make: Box::new(|| FunctionKind::Pure(Box::new(min_variadic))) },
        Definition { name: "max", fewest: 1, most: usize::MAX, make: Box::new(|| FunctionKind::Pure(Box::new(max_variadic))) },
        Definition { name: "clamp", fewest: 3, most: 3, make: Box::new(|| FunctionKind::Pure(Box::new(clamp_argument))) },
        Definition { name: "lerp", fewest: 3, most: 3, make: Box::new(|| FunctionKind::Pure(Box::new(lerp_argument))) },
        Definition { name: "random", fewest: 0, most: 1, make: Box::new(|| FunctionKind::Pure(Box::new(random_argument_wrap))) },
        Definition { name: "randint", fewest: 1, most: 1, make: Box::new(|| FunctionKind::Pure(Box::new(randint_argument_wrap))) },
        Definition { name: "block", fewest: 1, most: 1, make: Box::new(|| FunctionKind::Pure(Box::new(identity_argument))) },
        Definition { name: "pi", fewest: 0, most: 0, make: Box::new(|| constant_argument(std::f64::consts::PI)) },
        Definition { name: "e", fewest: 0, most: 0, make: Box::new(|| constant_argument(std::f64::consts::E)) },
        Definition { name: "true", fewest: 0, most: 0, make: Box::new(|| constant_argument(1.0)) },
        Definition { name: "false", fewest: 0, most: 0, make: Box::new(|| constant_argument(0.0)) },
        Definition { name: "perlin", fewest: 1, most: 7, make: Box::new(|| FunctionKind::PerlinOctaves(RefCell::new(OctaveCache::default()))) },
        Definition { name: "voronoi", fewest: 1, most: 5, make: Box::new(|| FunctionKind::VoronoiCells(RefCell::new(None))) },
        Definition { name: "ridgedmulti", fewest: 6, most: 6, make: Box::new(|| FunctionKind::RidgedMultiOctaves(RefCell::new(OctaveCache::default()))) },
        Definition { name: "simplex", fewest: 1, most: 3, make: Box::new(|| FunctionKind::Pure(Box::new(simplex_argument))) },
        Definition { name: "rmf", fewest: 1, most: 3, make: Box::new(|| FunctionKind::Pure(Box::new(rmf_argument))) },
        Definition { name: "band", fewest: 1, most: 3, make: Box::new(|| FunctionKind::Pure(Box::new(band_argument))) },
    ];
    table
}

fn random_argument_wrap(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    Ok(random_argument(vars, from, count))
}

fn randint_argument_wrap(vars: &Variables, from: usize, count: usize) -> Result<f64, ExpressionError> {
    Ok(randint_argument(vars, from, count))
}

// ---------------------------------------------------------------------------
// Parser: a recursive descent over the expression's characters, as in the
// Java reference. A line of a thousand brackets, which a player can type, is
// refused once it nests more than [`MAX_DEPTH`] levels deep.
// ---------------------------------------------------------------------------

struct Parser {
    input: String,
    chars: Vec<char>,
    pos: usize,
    depth: usize,
}

impl Parser {
    fn new(input: &str) -> Self {
        Self {
            input: input.to_owned(),
            chars: input.chars().collect(),
            pos: 0,
            depth: 0,
        }
    }

    fn input(&self) -> &str {
        &self.input
    }

    fn at(&self, offset: usize) -> Option<char> {
        self.chars.get(self.pos + offset).copied()
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }

    /// Runs a nested parse, refusing the formula once it nests too deep.
    fn nested(&mut self, parse: impl FnOnce(&mut Self) -> Result<Node, ExpressionError>) -> Result<Node, ExpressionError> {
        if self.depth + 1 > MAX_DEPTH {
            return error(format!("The expression nests more than {MAX_DEPTH} levels deep"));
        }
        self.depth += 1;
        let result = parse(self);
        self.depth -= 1;
        result
    }

    fn skip_whitespace(&mut self) {
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c.is_whitespace() {
                self.pos += 1;
            } else if c == '#' {
                // A comment runs to the end of its line.
                while self.pos < self.chars.len() && self.chars[self.pos] != '\n' {
                    self.pos += 1;
                }
            } else {
                return;
            }
        }
    }

    /// Statement level: also eats the `;` separators between statements.
    fn skip_whitespace_and_semicolons(&mut self) {
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c.is_whitespace() || c == ';' {
                self.pos += 1;
            } else if c == '#' {
                while self.pos < self.chars.len() && self.chars[self.pos] != '\n' {
                    self.pos += 1;
                }
            } else {
                return;
            }
        }
    }

    fn parse_statements(&mut self) -> Result<Node, ExpressionError> {
        let mut statements = Vec::new();
        self.skip_whitespace_and_semicolons();
        while self.pos < self.chars.len() && self.peek() != Some('}') {
            let statement = self.parse_statement()?;
            statements.push(statement);
            self.skip_whitespace_and_semicolons();
        }
        Ok(match statements.len() {
            0 => Node::Constant(0.0),
            1 => statements.pop().expect("one statement"),
            _ => Node::Sequence(statements),
        })
    }

    fn parse_statement(&mut self) -> Result<Node, ExpressionError> {
        self.skip_whitespace_and_semicolons();
        if self.match_keyword("if") {
            let condition = self.parse_paren_expression()?;
            let then_branch = self.parse_block_or_statement()?;
            let mut else_branch = None;
            self.skip_whitespace_and_semicolons();
            if self.match_keyword("else") {
                else_branch = Some(self.parse_block_or_statement()?);
            }
            return Ok(Node::If(
                Box::new(condition),
                Box::new(then_branch),
                else_branch.map(Box::new),
            ));
        }
        if self.match_keyword("while") {
            let condition = self.parse_paren_expression()?;
            let body = self.parse_block_or_statement()?;
            return Ok(Node::While(Box::new(condition), Box::new(body)));
        }
        if self.match_keyword("for") {
            self.expect('(')?;
            let init = self.parse_expression()?;
            self.expect(';')?;
            let condition = self.parse_expression()?;
            self.expect(';')?;
            let increment = self.parse_expression()?;
            self.expect(')')?;
            let body = self.parse_block_or_statement()?;
            return Ok(Node::For(
                Box::new(init),
                Box::new(condition),
                Box::new(increment),
                Box::new(body),
            ));
        }
        if self.match_keyword("return") {
            return self.parse_expression();
        }
        self.parse_expression()
    }

    fn parse_block_or_statement(&mut self) -> Result<Node, ExpressionError> {
        self.skip_whitespace_and_semicolons();
        if self.peek() == Some('{') {
            self.pos += 1;
            let body = self.nested(|parser| parser.parse_statements())?;
            self.expect('}')?;
            return Ok(body);
        }
        self.nested(|parser| parser.parse_statement())
    }

    fn parse_paren_expression(&mut self) -> Result<Node, ExpressionError> {
        self.expect('(')?;
        let node = self.parse_expression()?;
        self.expect(')')?;
        Ok(node)
    }

    fn match_keyword(&mut self, keyword: &str) -> bool {
        let save = self.pos;
        let key: Vec<char> = keyword.chars().collect();
        for (index, want) in key.iter().enumerate() {
            match self.chars.get(self.pos + index) {
                Some(c) if c.to_ascii_lowercase() == *want => {}
                _ => {
                    self.pos = save;
                    return false;
                }
            }
        }
        let after = self.pos + key.len();
        match self.chars.get(after) {
            None => {}
            Some(c) if c.is_alphanumeric() => {
                self.pos = save;
                return false;
            }
            _ => {}
        }
        self.pos = after;
        true
    }

    fn expect(&mut self, c: char) -> Result<(), ExpressionError> {
        self.skip_whitespace();
        if self.pos >= self.chars.len() || self.chars[self.pos] != c {
            return error(format!(
                "Expected '{c}' at position {} in expression: {}",
                self.pos,
                self.input()
            ));
        }
        self.pos += 1;
        Ok(())
    }

    fn expect_end(&mut self) -> Result<(), ExpressionError> {
        self.skip_whitespace_and_semicolons();
        if self.pos < self.chars.len() {
            return error(format!(
                "Unexpected trailing input at {}: {}",
                self.pos,
                self.input()
            ));
        }
        Ok(())
    }

    fn parse_expression(&mut self) -> Result<Node, ExpressionError> {
        self.parse_assignment()
    }

    fn parse_assignment(&mut self) -> Result<Node, ExpressionError> {
        self.skip_whitespace();
        let save = self.pos;
        if let Some(c) = self.peek() {
            if c.is_alphabetic() {
                let name = self.parse_identifier();
                self.skip_whitespace();
                if self.peek() == Some('=')
                    && self.at(1) != Some('=')
                {
                    self.pos += 1;
                    return Ok(Node::Assign(
                        name,
                        Box::new(self.parse_assignment()?),
                    ));
                }
                for op in ["+=", "-=", "*=", "/=", "%="] {
                    if self.take_str(op) {
                        return Ok(Node::CompoundAssign(
                            name,
                            op.as_bytes()[0],
                            Box::new(self.parse_assignment()?),
                        ));
                    }
                }
            }
        }
        self.pos = save;
        self.parse_ternary()
    }

    fn take_str(&mut self, text: &str) -> bool {
        let key: Vec<char> = text.chars().collect();
        for (index, want) in key.iter().enumerate() {
            if self.chars.get(self.pos + index) != Some(want) {
                return false;
            }
        }
        self.pos += key.len();
        true
    }

    fn parse_ternary(&mut self) -> Result<Node, ExpressionError> {
        let condition = self.parse_logical_or()?;
        self.skip_whitespace();
        if self.peek() == Some('?') {
            self.pos += 1;
            let then_branch = self.parse_expression()?;
            self.expect(':')?;
            let else_branch = self.parse_expression()?;
            return Ok(Node::If(
                Box::new(condition),
                Box::new(then_branch),
                Some(Box::new(else_branch)),
            ));
        }
        Ok(condition)
    }

    fn parse_logical_or(&mut self) -> Result<Node, ExpressionError> {
        let mut left = self.parse_logical_and()?;
        loop {
            self.skip_whitespace();
            if self.take_str("||") {
                left = Node::Binary(
                    BinaryOp::Or,
                    Box::new(left),
                    Box::new(self.parse_logical_and()?),
                );
            } else {
                return Ok(left);
            }
        }
    }

    fn parse_logical_and(&mut self) -> Result<Node, ExpressionError> {
        let mut left = self.parse_equality()?;
        loop {
            self.skip_whitespace();
            if self.take_str("&&") {
                left = Node::Binary(
                    BinaryOp::And,
                    Box::new(left),
                    Box::new(self.parse_equality()?),
                );
            } else {
                return Ok(left);
            }
        }
    }

    fn parse_equality(&mut self) -> Result<Node, ExpressionError> {
        let mut left = self.parse_comparison()?;
        loop {
            self.skip_whitespace();
            if self.take_str("==") {
                left = Node::Binary(
                    BinaryOp::Equal,
                    Box::new(left),
                    Box::new(self.parse_comparison()?),
                );
            } else if self.take_str("!=") {
                left = Node::Binary(
                    BinaryOp::NotEqual,
                    Box::new(left),
                    Box::new(self.parse_comparison()?),
                );
            } else {
                return Ok(left);
            }
        }
    }

    fn parse_comparison(&mut self) -> Result<Node, ExpressionError> {
        let mut left = self.parse_additive()?;
        loop {
            self.skip_whitespace();
            if self.take_str("<=") {
                left = Node::Binary(
                    BinaryOp::LessOrEqual,
                    Box::new(left),
                    Box::new(self.parse_additive()?),
                );
            } else if self.take_str(">=") {
                left = Node::Binary(
                    BinaryOp::GreaterOrEqual,
                    Box::new(left),
                    Box::new(self.parse_additive()?),
                );
            } else if self.peek() == Some('<') {
                self.pos += 1;
                left = Node::Binary(
                    BinaryOp::Less,
                    Box::new(left),
                    Box::new(self.parse_additive()?),
                );
            } else if self.peek() == Some('>') {
                self.pos += 1;
                left = Node::Binary(
                    BinaryOp::Greater,
                    Box::new(left),
                    Box::new(self.parse_additive()?),
                );
            } else {
                return Ok(left);
            }
        }
    }

    fn parse_additive(&mut self) -> Result<Node, ExpressionError> {
        let mut left = self.parse_multiplicative()?;
        loop {
            self.skip_whitespace();
            match self.peek() {
                Some(c @ ('+' | '-')) => {
                    self.pos += 1;
                    let op = if c == '+' { BinaryOp::Add } else { BinaryOp::Subtract };
                    left = Node::Binary(
                        op,
                        Box::new(left),
                        Box::new(self.parse_multiplicative()?),
                    );
                }
                _ => return Ok(left),
            }
        }
    }

    fn parse_multiplicative(&mut self) -> Result<Node, ExpressionError> {
        let mut left = self.parse_power()?;
        loop {
            self.skip_whitespace();
            match self.peek() {
                Some(c @ ('*' | '/' | '%')) => {
                    self.pos += 1;
                    let op = match c {
                        '*' => BinaryOp::Multiply,
                        '/' => BinaryOp::Divide,
                        _ => BinaryOp::Remainder,
                    };
                    left = Node::Binary(op, Box::new(left), Box::new(self.parse_power()?));
                }
                _ => return Ok(left),
            }
        }
    }

    fn parse_power(&mut self) -> Result<Node, ExpressionError> {
        let left = self.parse_unary()?;
        self.skip_whitespace();
        if self.peek() == Some('^') {
            self.pos += 1;
            return Ok(Node::Binary(
                BinaryOp::Power,
                Box::new(left),
                Box::new(self.parse_power()?),
            ));
        }
        Ok(left)
    }

    fn parse_unary(&mut self) -> Result<Node, ExpressionError> {
        self.skip_whitespace();
        if let Some(c @ ('-' | '+' | '!')) = self.peek() {
            self.pos += 1;
            let operand = self.nested(|parser| parser.parse_unary())?;
            return Ok(Node::Unary(c as u8, Box::new(operand)));
        }
        self.parse_primary()
    }

    fn parse_primary(&mut self) -> Result<Node, ExpressionError> {
        self.skip_whitespace();
        let end_of_expression = || {
            ExpressionError(format!("Unexpected end of expression: {}", self.input()))
        };
        let c = match self.peek() {
            None => return Err(end_of_expression()),
            Some(c) => c,
        };
        if c == '(' {
            self.pos += 1;
            let node = self.nested(|parser| parser.parse_expression())?;
            self.expect(')')?;
            return Ok(node);
        }
        if c.is_ascii_digit() || c == '.' {
            let start = self.pos;
            while self.pos < self.chars.len() {
                let c = self.chars[self.pos];
                let follows_exponent = self.pos > start
                    && matches!(c, '-' | '+')
                    && matches!(self.chars[self.pos - 1], 'e' | 'E');
                if c.is_ascii_digit() || c == '.' || c == 'e' || c == 'E' || follows_exponent {
                    self.pos += 1;
                } else {
                    break;
                }
            }
            let text: String = self.chars[start..self.pos].iter().collect();
            let parse_text = if text.starts_with('.') {
                format!("0{text}")
            } else {
                text.clone()
            };
            return parse_text.parse::<f64>().map(Node::Constant).map_err(|_| {
                ExpressionError(format!("'{text}' is not a number in expression: {}", self.input()))
            });
        }
        if c.is_alphabetic() || c == '_' {
            let name = self.parse_identifier();
            self.skip_whitespace();
            if self.peek() == Some('(') {
                self.pos += 1;
                let mut arguments = Vec::new();
                self.skip_whitespace();
                if self.peek() != Some(')') {
                    arguments.push(self.nested(|parser| parser.parse_expression())?);
                    loop {
                        self.skip_whitespace();
                        if self.peek() == Some(',') {
                            self.pos += 1;
                            arguments.push(self.parse_expression()?);
                        } else {
                            break;
                        }
                    }
                }
                self.expect(')')?;
                return self.call(&name, arguments);
            }
            if matches!(self.peek(), Some(c) if c == '+' || c == '-') && self.at(1) == Some(self.peek().unwrap()) {
                let delta = if self.peek() == Some('+') { 1.0 } else { -1.0 };
                self.pos += 2;
                return Ok(Node::PostfixIncrement(name, delta));
            }
            return Ok(Node::Variable {
                name: name.to_lowercase(),
                fallback: match name.to_lowercase().as_str() {
                    "pi" => std::f64::consts::PI,
                    "e" => std::f64::consts::E,
                    "true" => 1.0,
                    _ => 0.0,
                },
            });
        }
        Err(ExpressionError(format!(
            "Unexpected character '{c}' at {} in {}",
            self.pos,
            self.input()
        )))
    }

    fn parse_identifier(&mut self) -> String {
        let start = self.pos;
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c.is_alphanumeric() || c == '_' {
                self.pos += 1;
            } else {
                break;
            }
        }
        self.chars[start..self.pos].iter().collect()
    }

    /// A call of a function, refused here when the function does not exist
    /// or does not take that many arguments. `if(condition, then, else)`
    /// evaluates only the branch it takes, as the statement does.
    fn call(&self, name: &str, arguments: Vec<Node>) -> Result<Node, ExpressionError> {
        if name.eq_ignore_ascii_case("if") {
            if arguments.len() != 3 {
                return error(format!(
                    "'if' takes 3 arguments, not {}",
                    arguments.len()
                ));
            }
            let mut parts = arguments.into_iter();
            let condition = parts.next().expect("three arguments");
            let then_branch = parts.next().expect("three arguments");
            let else_branch = parts.next().expect("three arguments");
            return Ok(Node::If(
                Box::new(condition),
                Box::new(then_branch),
                Some(Box::new(else_branch)),
            ));
        }
        let definitions = definitions();
        let Some(definition) = definitions.iter().find(|definition| definition.name == name) else {
            return error(format!("Unknown function '{name}'"));
        };
        let count = arguments.len();
        if count < definition.fewest || count > definition.most {
            let takes = if definition.fewest == definition.most {
                definition.fewest.to_string()
            } else if definition.most == usize::MAX {
                format!("at least {}", definition.fewest)
            } else {
                format!("{} to {}", definition.fewest, definition.most)
            };
            let suffix = if definition.fewest == 1 && definition.most == 1 {
                " argument"
            } else {
                " arguments"
            };
            return error(format!("'{name}' takes {takes}{suffix}, not {count}"));
        }
        Ok(Node::FunctionCall((definition.make)(), arguments))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn evaluate(input: &str) -> f64 {
        Expression::compile(input)
            .unwrap_or_else(|e| panic!("'{input}' does not compile: {e}"))
            .evaluate(&mut Variables::new())
            .unwrap_or_else(|e| panic!("'{input}' does not evaluate: {e}"))
    }

    fn assert_close(actual: f64, expected: f64) {
        assert!(
            (actual - expected).abs() < 1e-9,
            "expected {expected}, got {actual}"
        );
    }

    #[test]
    fn precedence_and_grouping() {
        assert_close(evaluate("2+3*4"), 14.0);
        assert_close(evaluate("(2+3)*4"), 20.0);
        assert_close(evaluate("10%4"), 2.0);
        assert_close(evaluate("2^3^2"), 512.0);
        // As in the Java reference, power reads a unary operand: -2^2 == 4.
        assert_close(evaluate("-2^2"), 4.0);
        assert_close(evaluate("20/5/2"), 2.0);
        assert_close(evaluate("1+2-3+4-5"), -1.0);
    }

    #[test]
    fn unary_and_binary_operators() {
        assert_close(evaluate("-3"), -3.0);
        assert_close(evaluate("--3"), 3.0);
        assert_close(evaluate("+7"), 7.0);
        assert_close(evaluate("!0"), 1.0);
        assert_close(evaluate("!5"), 0.0);
        assert_close(evaluate("3 > 2 && 2 > 1"), 1.0);
        assert_close(evaluate("3 > 2 && 1 > 2"), 0.0);
        assert_close(evaluate("1 > 2 || 2 > 1"), 1.0);
        assert_close(evaluate("1 > 2 || 2 > 3"), 0.0);
        assert_close(evaluate("1 == 1"), 1.0);
        assert_close(evaluate("1 != 1"), 0.0);
        assert_close(evaluate("2 <= 2"), 1.0);
        assert_close(evaluate("2 >= 3"), 0.0);
    }

    #[test]
    fn ternary_and_if() {
        assert_close(evaluate("1 > 2 ? 3 : 4"), 4.0);
        assert_close(evaluate("2 > 1 ? 3 : 4"), 3.0);
        assert_close(evaluate("if (2 > 1) { 3 } else { 4 }"), 3.0);
        assert_close(evaluate("if (1 > 2) { 3 } else { 4 }"), 4.0);
        assert_close(evaluate("if (1 > 2) { 3 }"), 0.0);
        assert_close(evaluate("if(true){10}"), 10.0);
    }

    #[test]
    fn assignments_and_postfix() {
        assert_close(evaluate("a = 5; a + 1"), 6.0);
        assert_close(evaluate("a = 1; a += 4; a"), 5.0);
        assert_close(evaluate("a = 1; a -= 4; a"), -3.0);
        assert_close(evaluate("a = 2; a *= 4; a"), 8.0);
        assert_close(evaluate("a = 8; a /= 2; a"), 4.0);
        assert_close(evaluate("a = 9; a %= 4; a"), 1.0);
        assert_close(evaluate("a = 5; a++"), 5.0);
        assert_close(evaluate("a = 5; a++; a"), 6.0);
        assert_close(evaluate("a = 5; a--"), 5.0);
        assert_close(evaluate("a = 5; a--; a"), 4.0);
        assert_close(evaluate("x = 3; y = x * 2; y + x"), 9.0);
    }

    #[test]
    fn loops() {
        assert_close(evaluate("i = 0; r = 0; while (i < 10) { r = r + i; i = i + 1; } r"), 45.0);
        assert_close(evaluate("r = 0; for (i = 0; i < 5; i = i + 1) { r = r + 2; } r"), 10.0);
    }

    #[test]
    fn loop_guard_errors_out() {
        let error = Expression::compile("while (1) { }")
            .unwrap()
            .evaluate(&mut Variables::new())
            .unwrap_err();
        assert!(error.0.contains("100000"));
    }

    #[test]
    fn constants_and_variables() {
        assert_close(evaluate("pi"), std::f64::consts::PI);
        assert_close(evaluate("e"), std::f64::consts::E);
        assert_close(evaluate("true"), 1.0);
        assert_close(evaluate("unset_variable"), 0.0);
        assert_close(evaluate("PI + 0"), std::f64::consts::PI);
        // Function names are exact, as in the Java reference: "Sin" is unknown.
        assert!(Expression::compile("Sin(0)").is_err());
        assert_close(evaluate("sin(0)"), 0.0);
    }

    #[test]
    fn functions_of_the_language() {
        assert_close(evaluate("abs(-4)"), 4.0);
        assert_close(evaluate("ceil(1.25)"), 2.0);
        assert_close(evaluate("floor(1.75)"), 1.0);
        assert_close(evaluate("floor(-1.5)"), -2.0);
        assert_close(evaluate("rint(2.5)"), 2.0);
        assert_close(evaluate("rint(3.5)"), 4.0);
        assert_close(evaluate("round(2.5)"), 3.0);
        assert_close(evaluate("round(-2.5)"), -2.0);
        assert_close(evaluate("sqrt(9)"), 3.0);
        assert_close(evaluate("sin(0)"), 0.0);
        assert_close(evaluate("cos(0)"), 1.0);
        assert_close(evaluate("pow(2, 10)"), 1024.0);
        assert_close(evaluate("min(3, 1, 2)"), 1.0);
        assert_close(evaluate("max(3, 7, 2)"), 7.0);
        assert_close(evaluate("clamp(15, 0, 10)"), 10.0);
        assert_close(evaluate("clamp(-5, 0, 10)"), 0.0);
        assert_close(evaluate("clamp(5, 0, 10)"), 5.0);
        assert_close(evaluate("lerp(0, 10, 0.25)"), 2.5);
        assert_close(evaluate("block(9)"), 9.0);
        assert_close(evaluate("min(9)"), 9.0);
        // Nested calls share the argument stack correctly.
        assert_close(evaluate("sqrt(pow(2, 4))"), 4.0);
        assert_close(evaluate("max(min(3, 9), pow(2, 2))"), 4.0);
    }

    #[test]
    fn unknown_function_and_arity_are_refused_at_compile() {
        let error = Expression::compile("megabuf(1)").unwrap_err();
        assert!(error.0.contains("Unknown function 'megabuf'"));

        let error = Expression::compile("sqrt(1, 2)").unwrap_err();
        assert!(error.0.contains("'sqrt' takes 1 argument, not 2"));

        let error = Expression::compile("pow(2)").unwrap_err();
        assert!(error.0.contains("'pow' takes 2 arguments, not 1"));

        let error = Expression::compile("x = if(1, 2)").unwrap_err();
        assert!(error.0.contains("'if' takes 3 arguments, not 2"));
    }

    #[test]
    fn syntax_errors_are_refused_at_compile() {
        assert!(Expression::compile("2 +").is_err());
        assert!(Expression::compile("(2").is_err());
        assert!(Expression::compile("2 $ 3").is_err());
        assert!(Expression::compile(") 1 2 3").is_err());
        // Empty input evaluates to zero, as in the Java reference.
        assert_close(evaluate(""), 0.0);
        assert_close(evaluate("   ;  "), 0.0);
    }

    #[test]
    fn nesting_is_bounded() {
        // The default test-thread stack is 8 MiB and debug frames are large;
        // the parser itself stops at [`MAX_DEPTH`], but reaching it must not
        // take the thread down. The port keeps the reference's 128.
        let thread = std::thread::Builder::new()
            .stack_size(32 * 1024 * 1024)
            .spawn(|| {
                let deep = "(".repeat(200) + "1" + &")".repeat(200);
                assert!(Expression::compile(&deep).is_err());
            })
            .unwrap();
        thread.join().unwrap();
    }

    #[test]
    fn noise_functions_are_deterministic() {
        let first = Expression::compile("perlin(1, 2, 3)").unwrap();
        let second = Expression::compile("perlin(1, 2, 3)").unwrap();
        let a = first.evaluate(&mut Variables::new()).unwrap();
        let b = second.evaluate(&mut Variables::new()).unwrap();
        assert_eq!(a, b);
        assert!(a.is_finite());

        let voronoi = Expression::compile("voronoi(7, 1.5, 2.5, 3.5, 0.25)").unwrap();
        assert_eq!(
            voronoi.evaluate(&mut Variables::new()).unwrap(),
            voronoi.evaluate(&mut Variables::new()).unwrap()
        );

        let ridged = Expression::compile("ridgedmulti(3, 10, 20, 30, 0.5, 2)").unwrap();
        let value = ridged.evaluate(&mut Variables::new()).unwrap();
        assert!(value.is_finite());
    }

    #[test]
    fn wrong_noise_arities_are_refused() {
        let error = Expression::compile("perlin(1, 2, 3, 4, 5)")
            .unwrap()
            .evaluate(&mut Variables::new())
            .unwrap_err();
        assert!(error.0.contains("perlin"));

        let error = Expression::compile("perlin(1, 2, 3, 4, 5, 99, 0.5)")
            .unwrap()
            .evaluate(&mut Variables::new())
            .unwrap_err();
        assert!(error.0.contains("octaves"));
    }

    #[test]
    fn variables_keep_first_evaluation_results() {
        let expression = Expression::compile("x = 41; x + 1").unwrap();
        let mut variables = Variables::new().set("y", 100.0);
        assert_close(expression.evaluate(&mut variables).unwrap(), 42.0);
        // The expression's own assignments persist in the variables.
        assert_eq!(variables.get("x"), 41.0);
        assert_eq!(variables.get("y"), 100.0);
        // A fresh, separate evaluation starts from what the caller set.
        let mut variables = Variables::new();
        assert_close(expression.evaluate(&mut variables).unwrap(), 42.0);
    }
}
