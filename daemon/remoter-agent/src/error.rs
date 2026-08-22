use remoter_proto::ErrorCode;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentError {
    pub code: ErrorCode,
    pub detail: String,
}

impl AgentError {
    pub fn new(code: ErrorCode, detail: impl Into<String>) -> Self {
        AgentError { code, detail: detail.into() }
    }
}

impl std::fmt::Display for AgentError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code.as_str(), self.detail)
    }
}

impl std::error::Error for AgentError {}
