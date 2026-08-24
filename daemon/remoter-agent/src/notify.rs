//! Desktop notifications, one per spawn and a critical one on lock. If one pops
//! up and it wasn't you, you know.

use std::process::{Command, Stdio};

pub trait Notifier: Send + Sync {
    fn notify(&self, critical: bool, body: &str);
}

pub struct NotifySend {
    pub bin: std::path::PathBuf,
}

impl Notifier for NotifySend {
    fn notify(&self, critical: bool, body: &str) {
        let _ = Command::new(&self.bin)
            .args(["-a", "remoter", "-u", if critical { "critical" } else { "normal" }, "remoter"])
            .arg(escape_markup(body))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map(|mut c| std::thread::spawn(move || c.wait()));
    }
}

/// Notification daemons render some markup, and folder names come from disk.
pub fn escape_markup(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&#39;")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn markup_is_escaped() {
        assert_eq!(escape_markup("<b>x</b> & 'y'"), "&lt;b&gt;x&lt;/b&gt; &amp; &#39;y&#39;");
    }
}
