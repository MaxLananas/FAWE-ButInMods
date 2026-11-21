//! Per-source selection boxes, FAWE's `LocalSession` selection slice.
//!
//! WorldEdit keeps a selection per actor in its session; the Horizon mod has
//! no session object yet, so this module keeps the same shape behind a small
//! store: [`SelectionStore`] maps the source of each command to a cuboid.
//! A player is keyed by their UUID; the console and other non-player sources
//! share the key `SelectionKey::CONSOLE`, as the console has one selection.
//!
//! The store is per-process and never persisted — FAWE's selections are the
//! same. A reload of the mod starts everyone fresh.

use std::collections::HashMap;

use crate::region::{BlockVector3, CuboidRegion};

/// Which source owns a selection.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum SelectionKey {
    /// A player, keyed by the UUID the host reports.
    Player(u128),
    /// The server console, a command block or a function — together for now.
    Console,
}

impl SelectionKey {
    /// The shared slot for non-player sources.
    pub const CONSOLE: Self = Self::Console;
}

/// The selection of one source, `//pos1` and `//pos2`.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Selection {
    pos1: Option<BlockVector3>,
    pos2: Option<BlockVector3>,
}

impl Selection {
    /// Mark `//pos1`, flipping the box the way [`CuboidRegion`] does.
    pub fn set_pos1(&mut self, pos: BlockVector3) {
        self.pos1 = Some(pos);
    }

    /// Mark `//pos2`, flipping the box the way [`CuboidRegion`] does.
    pub fn set_pos2(&mut self, pos: BlockVector3) {
        self.pos2 = Some(pos);
    }

    /// `true` when both corners are marked.
    pub fn is_complete(&self) -> bool {
        self.pos1.is_some() && self.pos2.is_some()
    }

    /// The cuboid both corners make, when both are marked.
    pub fn cuboid(&self) -> Option<CuboidRegion> {
        match (self.pos1, self.pos2) {
            (Some(a), Some(b)) => Some(CuboidRegion::new(a, b)),
            _ => None,
        }
    }

    /// Describe what is marked so far, for `fawebim sel`.
    pub fn describe(&self) -> String {
        match (self.pos1, self.pos2) {
            (None, None) => "no selection (pos1 and pos2 unset)".to_string(),
            (Some(pos1), None) => format!(
                "pos1 = ({}, {}, {}); pos2 unset",
                pos1.x, pos1.y, pos1.z
            ),
            (None, Some(pos2)) => format!(
                "pos1 unset; pos2 = ({}, {}, {})",
                pos2.x, pos2.y, pos2.z
            ),
            (Some(pos1), Some(pos2)) => {
                let cuboid = CuboidRegion::new(pos1, pos2);
                format!(
                    "({}, {}, {}) -> ({}, {}, {}) ({})",
                    cuboid.min().x,
                    cuboid.min().y,
                    cuboid.min().z,
                    cuboid.max().x,
                    cuboid.max().y,
                    cuboid.max().z,
                    cuboid.describe()
                )
            }
        }
    }
}

/// The selections of every source, in one map.
#[derive(Default)]
pub struct SelectionStore {
    selections: HashMap<SelectionKey, Selection>,
}

impl SelectionStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// The selection of `key`, creating an empty one on first use.
    pub fn get(&mut self, key: SelectionKey) -> &mut Selection {
        self.selections.entry(key).or_default()
    }

    /// The selection of `key`, when one was ever touched.
    pub fn peek(&self, key: SelectionKey) -> Option<&Selection> {
        self.selections.get(&key)
    }

    /// Forget the selection of `key` (`//sel -d` style).
    pub fn clear(&mut self, key: SelectionKey) {
        self.selections.remove(&key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cuboid_needs_both_corners() {
        let mut store = SelectionStore::new();
        let selection = store.get(SelectionKey::Console);
        assert!(selection.cuboid().is_none());
        selection.pos1 = Some(BlockVector3::at(0, 0, 0));
        assert!(selection.cuboid().is_none());
        selection.pos2 = Some(BlockVector3::at(3, 3, 3));
        let cuboid = selection.cuboid().unwrap();
        assert_eq!(cuboid.volume(), 64);
    }

    #[test]
    fn describe_reports_progress() {
        let mut store = SelectionStore::new();
        assert_eq!(
            store.peek(SelectionKey::Console).map(Selection::describe),
            None
        );
        let selection = store.get(SelectionKey::Console);
        assert!(selection.describe().contains("no selection"));
        selection.pos1 = Some(BlockVector3::at(1, 2, 3));
        assert!(selection.describe().contains("pos2 unset"));
        selection.pos2 = Some(BlockVector3::at(4, 5, 6));
        let text = selection.describe();
        assert!(text.contains("(1, 2, 3) -> (4, 5, 6)"), "{text}");
        assert!(text.contains("cuboid: 4x4x4"), "{text}");
    }

    #[test]
    fn clear_forgets_the_selection() {
        let mut store = SelectionStore::new();
        store.get(SelectionKey::Player(42)).pos1 = Some(BlockVector3::at(1, 1, 1));
        assert!(store.peek(SelectionKey::Player(42)).is_some());
        store.clear(SelectionKey::Player(42));
        assert!(store.peek(SelectionKey::Player(42)).is_none());
    }
}
