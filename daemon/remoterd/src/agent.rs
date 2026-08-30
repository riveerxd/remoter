//! Client for the agent socket. `SO_PEERCRED` checks the other end really is
//! the agent's user, so nothing else squatting the path gets the phone's requests.

use std::path::PathBuf;
use std::time::Duration;

use remoter_proto::ErrorCode;
use remoter_proto::ipc::{AgentFailure, AgentReply, AgentRequest, MAX_FRAME};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[derive(Debug, Clone)]
pub struct AgentClient {
    pub socket: PathBuf,
    pub agent_uid: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CallError {
    /// never reached it, or no answer
    Down(String),
    Failed(AgentFailure),
}

impl CallError {
    pub fn code(&self) -> ErrorCode {
        match self {
            CallError::Down(_) => ErrorCode::AgentDown,
            CallError::Failed(f) => f.code,
        }
    }
}

impl AgentClient {
    pub async fn call(&self, req: &AgentRequest, timeout: Duration) -> Result<serde_json::Value, CallError> {
        let down = |m: String| CallError::Down(m);
        let mut s = tokio::time::timeout(Duration::from_secs(2), tokio::net::UnixStream::connect(&self.socket))
            .await
            .map_err(|_| down("connect timed out".into()))?
            .map_err(|e| down(format!("connect: {e}")))?;
        let peer = s.peer_cred().map_err(|e| down(format!("peer: {e}")))?;
        if peer.uid() != self.agent_uid {
            return Err(down(format!("agent socket is served by uid {}, not {}", peer.uid(), self.agent_uid)));
        }
        let body = serde_json::to_vec(req).map_err(|e| down(e.to_string()))?;
        let exchange = async {
            s.write_all(&body).await?;
            s.shutdown().await?;
            let mut out = Vec::new();
            (&mut s).take(MAX_FRAME as u64 + 1).read_to_end(&mut out).await?;
            Ok::<_, std::io::Error>(out)
        };
        let out = tokio::time::timeout(timeout, exchange)
            .await
            .map_err(|_| down("agent timed out".into()))?
            .map_err(|e| down(format!("agent: {e}")))?;
        if out.len() > MAX_FRAME {
            return Err(down("agent reply too large".into()));
        }
        match serde_json::from_slice::<AgentReply>(&out) {
            Ok(AgentReply::Ok(v)) => Ok(v),
            Ok(AgentReply::Err(f)) => Err(CallError::Failed(f)),
            // empty reply = the agent refused our peer check
            Err(_) => Err(down("no usable reply from the agent".into())),
        }
    }

    pub async fn call_as<T: serde::de::DeserializeOwned>(&self, req: &AgentRequest, timeout: Duration) -> Result<T, CallError> {
        let v = self.call(req, timeout).await?;
        serde_json::from_value(v).map_err(|e| CallError::Down(format!("agent reply: {e}")))
    }
}
