//! IPC wire protocol between the Kotlin `NativeServiceClient` and this service.
//!
//! Transport: length-prefixed JSON over an abstract-namespace AF_UNIX socket.
//! Each frame is a 4-byte big-endian unsigned length header followed by exactly
//! that many bytes of UTF-8 JSON (see Milestone 3 codespec 6.2 / 6.3).

use std::io::{self, Read, Write};

use serde::{Deserialize, Serialize};

/// Maximum accepted frame size (16 MiB). Guards against a malformed or hostile
/// length header causing an unbounded allocation.
const MAX_FRAME_LEN: usize = 16 * 1024 * 1024;

/// Commands sent by the client to the service.
// Some payload fields are parsed but not yet consumed by the stubbed M3
// handlers; they are retained so the wire contract is complete now.
#[allow(dead_code)]
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Command {
    /// Heartbeat probe. Expects a [`Event::Pong`] reply.
    Ping,
    /// One-time Apple ID activation. `two_fa_code` is present on the second
    /// round trip once the user has supplied the 2FA challenge.
    Activate {
        apple_id: String,
        password: String,
        #[serde(rename = "2fa_code", default)]
        two_fa_code: Option<String>,
    },
    /// Send an outgoing iMessage.
    SendMessage {
        message_id: String,
        recipients: Vec<String>,
        text: String,
        #[serde(default)]
        attachments: Vec<String>,
    },
    /// Fetch messages received since the given Unix-millisecond timestamp.
    GetMessages { since: i64 },
}

/// Events/responses sent by the service to the client.
// ActivationStatus/Ack are part of the contract but only emitted once the
// activation and send handlers are implemented (Milestone 3).
#[allow(dead_code)]
#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Event {
    /// Heartbeat reply to [`Command::Ping`].
    Pong,
    /// Progress of an in-flight activation.
    ActivationStatus {
        status: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        handles: Option<Vec<String>>,
    },
    /// Acknowledgement that an outgoing message was accepted for delivery.
    Ack { message_id: String },
    /// A command failed or is not yet supported.
    Error { message: String },
}

/// Reads one length-prefixed JSON frame and decodes it into a [`Command`].
///
/// Returns `Ok(None)` on a clean end-of-stream (client disconnected between
/// frames), which the accept loop treats as a normal disconnect.
pub fn read_command<R: Read>(reader: &mut R) -> io::Result<Option<Command>> {
    let mut header = [0u8; 4];
    match reader.read_exact(&mut header) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }

    let len = u32::from_be_bytes(header) as usize;
    if len > MAX_FRAME_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame length {len} exceeds maximum {MAX_FRAME_LEN}"),
        ));
    }

    let mut payload = vec![0u8; len];
    reader.read_exact(&mut payload)?;

    let command = serde_json::from_slice::<Command>(&payload)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    Ok(Some(command))
}

/// Serializes an [`Event`] and writes it as a single length-prefixed JSON frame.
pub fn write_event<W: Write>(writer: &mut W, event: &Event) -> io::Result<()> {
    let payload =
        serde_json::to_vec(event).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let len = u32::try_from(payload.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "event exceeds u32 length"))?;

    writer.write_all(&len.to_be_bytes())?;
    writer.write_all(&payload)?;
    writer.flush()
}
