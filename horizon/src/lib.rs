//! FAWE-ButInMods: the FAWE engine as a Horizon code mod.
//!
//! See `horizon-mod.toml`; the Java original lives in `../core` and `../fabric`.
//!
//! So far ported: the expression engine behind `//calc` (a port of
//! `Expression.java` + the noise functions behind it), the region math for a
//! cuboid selection (`//pos1` / `//pos2`), and the fill — the smallest slice
//! of FAWE's `//set`. MIT-style `//` aliases and the wand are not there yet.

pub mod calc;
pub mod expression;
pub mod noise;
pub mod region;
pub mod selection;
pub mod set;

use std::sync::Mutex;

use mod_api::{Hooks, RegisterCommandsEvent, RunModCommandEvent, ServerHost, ServerStartedEvent};

use selection::SelectionStore;

#[derive(Default)]
pub struct Mod {
    selections: Mutex<SelectionStore>,
}

#[mod_api::horizon_mod]
impl Hooks for Mod {
    fn register_commands(
        &self,
        _host: &mut dyn ServerHost,
        event: &mut RegisterCommandsEvent<'_>,
    ) {
        calc::register(event);
        set::register(event);
    }

    fn run_mod_command(
        &self,
        host: &mut dyn ServerHost,
        event: &mut RunModCommandEvent<'_>,
    ) -> mod_api::Flow {
        let flow = calc::run(host, &mut *event);
        if matches!(flow, mod_api::Flow::Cancel) {
            return mod_api::Flow::Cancel;
        }
        set::run(host, event, &self.selections)
    }

    fn server_started(&self, _host: &mut dyn ServerHost, _event: &mut ServerStartedEvent) {}
}
