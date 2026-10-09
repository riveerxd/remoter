//! The half of the laptop daemon that runs as you: files, desktop, sessions.
//! Only talks to remoterd.

pub mod config;
pub mod error;
pub mod guard;
pub mod launcher;
pub mod list;
pub mod mkdir;
pub mod natural;
pub mod notify;
pub mod peer;
pub mod policy;
pub mod procs;
pub mod recent;
pub mod search;
pub mod sessions;
pub mod server;
pub mod sys;
pub mod tokens;
pub mod transcripts;
pub mod trust;

pub use error::AgentError;
