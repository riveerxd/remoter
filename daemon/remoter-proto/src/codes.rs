use serde::{Deserialize, Serialize};

/// Every error the API returns. The app maps each one to its own copy and never
/// shows `message` raw.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    PathOutsideHome,
    PathDenied,
    PathUnsupported,
    NotFound,
    NotADirectory,
    NameInvalid,
    Exists,
    UntrustedFolder,
    Locked,
    DeviceUnknown,
    ReattestRequired,
    SigInvalid,
    NonceReused,
    ClockSkew,
    RateLimited,
    SessionCap,
    /// A session already runs in that folder, and claude allows one Remote Control per folder.
    FolderBusy,
    /// The conversation asked to resume is open in a claude right now. claude would let a second
    /// one take it over, and both would write the same transcript.
    ConversationOpen,
    /// Not your process, or one remoter itself needs.
    ProcessDenied,
    ViewTokenRequired,
    SpawnFailed,
    DesktopDown,
    AgentDown,
    PairExpired,
    PairRejected,
    BadRequest,
    Internal,
}

impl ErrorCode {
    pub const ALL: [ErrorCode; 27] = [
        ErrorCode::PathOutsideHome,
        ErrorCode::PathDenied,
        ErrorCode::PathUnsupported,
        ErrorCode::NotFound,
        ErrorCode::NotADirectory,
        ErrorCode::NameInvalid,
        ErrorCode::Exists,
        ErrorCode::UntrustedFolder,
        ErrorCode::Locked,
        ErrorCode::DeviceUnknown,
        ErrorCode::ReattestRequired,
        ErrorCode::SigInvalid,
        ErrorCode::NonceReused,
        ErrorCode::ClockSkew,
        ErrorCode::RateLimited,
        ErrorCode::SessionCap,
        ErrorCode::FolderBusy,
        ErrorCode::ConversationOpen,
        ErrorCode::ProcessDenied,
        ErrorCode::ViewTokenRequired,
        ErrorCode::SpawnFailed,
        ErrorCode::DesktopDown,
        ErrorCode::AgentDown,
        ErrorCode::PairExpired,
        ErrorCode::PairRejected,
        ErrorCode::BadRequest,
        ErrorCode::Internal,
    ];

    pub fn http_status(self) -> u16 {
        use ErrorCode::*;
        match self {
            PathOutsideHome | PathDenied | PathUnsupported | UntrustedFolder | ProcessDenied => 403,
            NotFound => 404,
            NotADirectory | NameInvalid | BadRequest => 400,
            Exists => 409,
            Locked => 423,
            DeviceUnknown | SigInvalid | NonceReused => 401,
            ReattestRequired | ViewTokenRequired => 403,
            ClockSkew => 400,
            RateLimited => 429,
            SessionCap | FolderBusy | ConversationOpen => 409,
            PairExpired | PairRejected => 403,
            SpawnFailed | Internal => 500,
            DesktopDown | AgentDown => 503,
        }
    }

    pub fn as_str(self) -> &'static str {
        use ErrorCode::*;
        match self {
            PathOutsideHome => "path_outside_home",
            PathDenied => "path_denied",
            PathUnsupported => "path_unsupported",
            NotFound => "not_found",
            NotADirectory => "not_a_directory",
            NameInvalid => "name_invalid",
            Exists => "exists",
            UntrustedFolder => "untrusted_folder",
            Locked => "locked",
            DeviceUnknown => "device_unknown",
            ReattestRequired => "reattest_required",
            SigInvalid => "sig_invalid",
            NonceReused => "nonce_reused",
            ClockSkew => "clock_skew",
            RateLimited => "rate_limited",
            SessionCap => "session_cap",
            FolderBusy => "folder_busy",
            ConversationOpen => "conversation_open",
            ProcessDenied => "process_denied",
            ViewTokenRequired => "view_token_required",
            SpawnFailed => "spawn_failed",
            DesktopDown => "desktop_down",
            AgentDown => "agent_down",
            PairExpired => "pair_expired",
            PairRejected => "pair_rejected",
            BadRequest => "bad_request",
            Internal => "internal",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serde_name_matches_as_str_for_every_code() {
        for code in ErrorCode::ALL {
            let json = serde_json::to_string(&code).expect("serializes");
            assert_eq!(json, format!("\"{}\"", code.as_str()));
            let back: ErrorCode = serde_json::from_str(&json).expect("parses");
            assert_eq!(back, code);
        }
    }

    #[test]
    fn every_status_is_an_error_status() {
        for code in ErrorCode::ALL {
            assert!((400..600).contains(&code.http_status()), "{code:?}");
        }
    }
}
