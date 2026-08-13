//! Devices that unpaired themselves from the phone. remoterd can't edit the
//! root owned device file, so it lists them here (0644 in its state dir)
//! until `sudo remoterctl` tidies the device file, and both daemons treat
//! them as unknown.

use std::collections::HashSet;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::Devices;

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UnpairedFile {
    pub devices: Vec<String>,
}

pub enum Unpaired {
    Some(HashSet<String>),
    /// The file exists but can't be read. Every device is refused, which is
    /// the safe way to be wrong.
    Unreadable,
}

pub fn read(path: &Path) -> Unpaired {
    match std::fs::read(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Unpaired::Some(HashSet::new()),
        Ok(b) => match serde_json::from_slice::<UnpairedFile>(&b) {
            Ok(f) => Unpaired::Some(f.devices.into_iter().collect()),
            Err(_) => Unpaired::Unreadable,
        },
        Err(_) => Unpaired::Unreadable,
    }
}

/// The device file minus the unpaired list.
pub fn effective(all: &Devices, path: &Path) -> Devices {
    match read(path) {
        Unpaired::Some(ids) => all.without(&ids),
        Unpaired::Unreadable => Devices::default(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testkit::{Phone, device_file};

    #[test]
    fn missing_listed_and_broken() {
        let dir = std::env::temp_dir().join(format!("remoter-unpaired-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("mk");
        let a = Phone::new("01K6B7Y3M4N5P6Q7R8S9T0V1W2", 1, b"a".to_vec());
        let b = Phone::new("01K6B7Y3M4N5P6Q7R8S9T0V1W3", 2, b"b".to_vec());
        let all = Devices::parse(&device_file(&[&a, &b])).expect("devices");
        let f = dir.join("unpaired.json");
        assert_eq!(effective(&all, &f).len(), 2, "no file");
        std::fs::write(&f, format!(r#"{{"devices":["{}"]}}"#, a.id)).expect("w");
        let e = effective(&all, &f);
        assert!(e.get(&a.id).is_none() && e.get(&b.id).is_some());
        std::fs::write(&f, "garbage").expect("w");
        assert!(effective(&all, &f).is_empty(), "unreadable refuses everyone");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
