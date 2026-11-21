//! The `//calculate` slice of FAWE, for the Horizon port: the command itself.
//! A port of what `UtilityExtras.calculate()` registers in the Java reference:
//! `//calculate` evaluates a formula and prints `<input> = <value>`.
//!
//! Two usages, both operator commands:
//!
//! - `/fawebim calc <formula:text>` — FAWE's `//calculate` with the selection
//!   variables unbound; like the reference, an unassigned name reads as 0;
//! - `/fawebim calc at <x:number> <y:number> <z:number> <formula:text>` — the
//!   same with the coordinates given, which is where a selection-would-go
//!   plugs in once the port tracks one. FAWE has no such usage; it is the
//!   port's own convenience, kept alongside the faithful one.
//!
//! The formula is a real FAWE expression (`Expression.java`); every syntax
//! error, unknown function or non-terminating loop comes back as the
//! player's mistake, quoted. The result is feedback to the source, which the
//! game routes like any command output.

use crate::expression::{Expression, Variables};
use mod_api::{
    Command, CommandArgumentValue, Flow, RegisterCommandsEvent, RunModCommandEvent, ServerHost,
    Text,
};

pub(crate) fn register(event: &mut RegisterCommandsEvent<'_>) {
    event.commands.extend([
        Command::op("fawebim calc <formula:text>", 2),
        Command::op("fawebim calc at <x:number> <y:number> <z:number> <formula:text>", 2),
    ]);
}

/// The one thing the command does, without the host: read-compile-evaluate.
/// Kept separate so it is unit-testable.
pub(crate) fn evaluate_formula(formula: &str, x: f64, y: f64, z: f64) -> Result<f64, String> {
    let expression = Expression::compile(formula).map_err(|error| error.0)?;
    let mut variables = Variables::new().set("x", x).set("y", y).set("z", z);
    expression.evaluate(&mut variables).map_err(|error| error.0)
}

pub(crate) fn run(host: &mut dyn ServerHost, event: &mut RunModCommandEvent<'_>) -> Flow {
    let is_fawebim = event
        .command
        .literals
        .first()
        .is_some_and(|word| word == "fawebim");
    if !is_fawebim || event.command.literals.get(1).is_none_or(|word| word != "calc") {
        return Flow::Continue;
    }

    let formula = match event.command.argument("formula") {
        Some(CommandArgumentValue::String(formula)) => formula,
        _ => return Flow::Continue,
    };

    let at = match event.command.literals.get(2) {
        Some(word) if word == "at" => {
            let read = |name| match event.command.argument(name) {
                Some(CommandArgumentValue::Number(value)) => Some(*value),
                _ => None,
            };
            match (read("x"), read("y"), read("z")) {
                (Some(x), Some(y), Some(z)) => (x, y, z),
                _ => {
                    feedback(host, "fawebim calc: expected three numbers for 'at'");
                    return Flow::Cancel;
                }
            }
        }
        _ => (0.0, 0.0, 0.0),
    };

    let line = match evaluate_formula(&formula, at.0, at.1, at.2) {
        Ok(value) => format!("fawebim calc: {formula} = {}", format_value(value)),
        Err(error) => format!(
            "fawebim calc: '{formula}' could not be parsed as a valid expression: {error}"
        ),
    };
    feedback(host, &line);
    Flow::Cancel
}

/// The result as FAWE writes it: grouped thousands, at most five decimals, so
/// 0.1+0.2 prints as 0.3 rather than the double's 0.30000000000000004. Always
/// Locale.ROOT-like: the decimal separator is `.`. NaN and the infinities
/// print as `NaN`, `∞` and `-∞`, as Java's DecimalFormat writes them.
fn format_value(value: f64) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    if value.is_infinite() {
        return if value.is_sign_negative() { "-∞" } else { "∞" }.to_string();
    }
    let negative = value < 0.0;
    let absolute = value.abs();

    let mut integer = absolute.trunc();
    let mut fraction = ((absolute - integer) * 100_000.0).round_ties_even() as u64;
    // 0.999999 rounded up to 1.00000 carries into the integer part.
    if fraction == 100_000 {
        fraction = 0;
        integer += 1.0;
    }
    let negative = negative && (integer != 0.0 || fraction != 0);

    // Integer part: exact for everything below 2^53; beyond that, scale down
    // to whole thousands so the digits stay exact (and the fraction, which
    // cannot be represented at that magnitude anyway, is zero).
    let mut chunks: Vec<u64> = Vec::new();
    if integer >= 9_007_199_254_740_992.0 {
        // 2^53; no exact fractional part possible, print via repeated division.
        let mut remaining = integer;
        while remaining >= 1000.0 {
            let chunk = remaining % 1000.0;
            chunks.push(chunk as u64);
            remaining = (remaining / 1000.0).trunc();
        }
        chunks.push(remaining as u64);
    } else {
        let mut remaining = integer as u64;
        loop {
            chunks.push(remaining % 1000);
            remaining /= 1000;
            if remaining == 0 {
                break;
            }
        }
    }
    let mut out = String::with_capacity(chunks.len() * 4 + 8);
    if negative {
        out.push('-');
    }
    for (index, chunk) in chunks.iter().rev().enumerate() {
        if index > 0 {
            out.push(',');
        }
        if index > 0 {
            out.push_str(&format!("{chunk:03}"));
        } else {
            out.push_str(&chunk.to_string());
        }
    }

    if fraction != 0 {
        // Five decimals, trailing zeros trimmed, as DecimalFormat("#,##0.#####").
        let mut digits = [0u8; 5];
        for slot in digits.iter_mut().rev() {
            *slot = b'0' + (fraction % 10) as u8;
            fraction /= 10;
        }
        let mut len = 5;
        while len > 0 && digits[len - 1] == b'0' {
            len -= 1;
        }
        out.push('.');
        out.push_str(std::str::from_utf8(&digits[..len]).expect("ascii digits"));
    }
    out
}

fn feedback(host: &mut dyn ServerHost, line: &str) {
    // Text formation is the only fallible part; plain ASCII always fits.
    let _ = host.commands().feedback(Text::literal(line));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evaluate_formula_binds_coordinates() {
        assert_eq!(evaluate_formula("x + y + z", 1.0, 2.0, 3.0).unwrap(), 6.0);
        assert_eq!(evaluate_formula("x * 2", 21.0, 0.0, 0.0).unwrap(), 42.0);
    }

    #[test]
    fn evaluate_formula_defaults_zero() {
        assert_eq!(evaluate_formula("x + 5", 0.0, 0.0, 0.0).unwrap(), 5.0);
    }

    #[test]
    fn format_value_groups_thousands_and_trims() {
        assert_eq!(format_value(7.0), "7");
        assert_eq!(format_value(0.1 + 0.2), "0.3");
        assert_eq!(format_value(1234.5), "1,234.5");
        assert_eq!(format_value(1234567.891), "1,234,567.891");
        assert_eq!(format_value(-42.0), "-42");
        assert_eq!(format_value(-0.5), "-0.5");
        assert_eq!(format_value(f64::NAN), "NaN");
        assert_eq!(format_value(f64::INFINITY), "∞");
        assert_eq!(format_value(f64::NEG_INFINITY), "-∞");
        assert_eq!(format_value(1.23456789), "1.23457");
    }

    #[test]
    fn evaluate_formula_reports_expression_errors() {
        let error = evaluate_formula("megabuf(1)", 0.0, 0.0, 0.0).unwrap_err();
        assert!(error.contains("Unknown function"));

        let error = evaluate_formula("while (1) { }", 0.0, 0.0, 0.0).unwrap_err();
        assert!(error.contains("100000"));
    }
}
