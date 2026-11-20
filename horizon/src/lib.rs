//! FAWE-ButInMods: the FAWE engine as a Horizon code mod.
//!
//! See `horizon-mod.toml`; the Java original lives in `../core` and `../fabric`.

pub mod calc;
pub mod expression;
pub mod noise;

use mod_api::{Hooks, RegisterCommandsEvent, ServerHost, ServerStartedEvent};

#[derive(Default)]
pub struct Mod;

#[mod_api::horizon_mod]
impl Hooks for Mod {
    fn register_commands(
        &self,
        _host: &mut dyn ServerHost,
        event: &mut RegisterCommandsEvent<'_>,
    ) {
        calc::register(event);
    }

    fn run_mod_command(
        &self,
        host: &mut dyn ServerHost,
        event: &mut mod_api::RunModCommandEvent<'_>,
    ) -> mod_api::Flow {
        calc::run(host, event)
    }

    fn server_started(&self, _host: &mut dyn ServerHost, _event: &mut ServerStartedEvent) {}
}
