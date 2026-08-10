//! Wire contract shared by remoterd, remoter-agent, remoterctl and the phone.
//!
//! Field names here are the JSON names. The Kotlin side is kept in step by the
//! contract tests, which parse `fixtures/` and rebuild every signed string byte
//! for byte.

pub mod admin;
pub mod api;
pub mod b64;
pub mod canonical;
pub mod codes;
pub mod ipc;
pub mod local;
pub mod names;
pub mod pair;

pub use codes::ErrorCode;
