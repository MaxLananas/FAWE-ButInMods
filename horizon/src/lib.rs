//! FAWE-ButInMods: the FAWE engine as a Horizon code mod.
//!
//! See `horizon-mod.toml`; the Java original lives in `../core` and `../fabric`.

use mod_api::{Hooks, ServerHost, ServerStartedEvent};

#[derive(Default)]
pub struct Mod;

#[mod_api::horizon_mod]
impl Hooks for Mod {
    fn server_started(&self, _host: &mut dyn ServerHost, _event: &mut ServerStartedEvent) {}
}
