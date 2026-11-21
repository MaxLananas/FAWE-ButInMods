//! FAWE-ButInMods: the FAWE engine as a Horizon code mod.
//!
//! See `horizon-mod.toml`; the Java original lives in `../core` and `../fabric`.

pub mod calc;
pub mod clipboard;
pub mod expression;
pub mod history;
pub mod math;
pub mod mask;
pub mod noise;
pub mod operations;
pub mod pattern;
pub mod region;
pub mod registry;
pub mod selection;
pub mod session;
pub mod set;
pub mod transform;
pub mod world;

use std::sync::{Arc, Mutex};

use mod_api::{Hooks, RegisterCommandsEvent, RunModCommandEvent, ServerHost, ServerStartedEvent};

use clipboard::ClipboardManager;
use registry::HorizonRegistryCache;
use session::SessionManager;

pub struct Mod {
    sessions: Mutex<SessionManager>,
    clipboards: Mutex<ClipboardManager>,
    registry_cache: Arc<Mutex<HorizonRegistryCache>>,
}

impl Default for Mod {
    fn default() -> Self {
        Self {
            sessions: Mutex::new(SessionManager::new()),
            clipboards: Mutex::new(ClipboardManager::default()),
            registry_cache: HorizonRegistryCache::new_shared(),
        }
    }
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
        set::run(host, event, &self.sessions, &self.clipboards, &self.registry_cache)
    }

    fn server_started(&self, _host: &mut dyn ServerHost, _event: &mut ServerStartedEvent) {}
}
