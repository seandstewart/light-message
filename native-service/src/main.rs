//! rustpush IPC service (LightOS iMessage client, Milestone 3).
//!
//! A standalone process, packaged inside the APK as `librustpush_service.so`
//! and launched by `NativeServiceLauncher`. It listens on an abstract-namespace
//! AF_UNIX socket and exchanges length-prefixed JSON frames with the Kotlin
//! `NativeServiceClient` (codespec 4.1–4.5).
//!
//! Status: the transport, heartbeat, and command dispatch skeleton are
//! implemented. The Apple-facing command handlers (activation, send, fetch) are
//! stubbed and return a structured `ERROR` event until wired to `rustpush`.

mod protocol;
mod socket;

use std::io::{BufReader, BufWriter};

use protocol::{read_command, write_event, Command, Event};

/// Default abstract socket name. Must match `NativeServiceClient`'s
/// `abstract:rustpush_ipc` address.
const DEFAULT_SOCKET_NAME: &str = "rustpush_ipc";
const LISTEN_BACKLOG: i32 = 4;

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
}

impl LogLevel {
    fn parse(value: &str) -> Option<Self> {
        match value.to_ascii_lowercase().as_str() {
            "error" => Some(Self::Error),
            "warn" | "warning" => Some(Self::Warn),
            "info" => Some(Self::Info),
            "debug" | "trace" => Some(Self::Debug),
            _ => None,
        }
    }
}

struct Config {
    socket_name: String,
    log_level: LogLevel,
}

impl Config {
    /// Parses `--socket <name>` and `--log-level <level>` (codespec 6.1:
    /// "Build process args: socket path, log level").
    fn from_args() -> Result<Self, String> {
        let mut socket_name = DEFAULT_SOCKET_NAME.to_string();
        let mut log_level = LogLevel::Info;

        let mut args = std::env::args().skip(1);
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--socket" => {
                    socket_name = args
                        .next()
                        .ok_or_else(|| "--socket requires a value".to_string())?;
                }
                "--log-level" => {
                    let value = args
                        .next()
                        .ok_or_else(|| "--log-level requires a value".to_string())?;
                    log_level = LogLevel::parse(&value)
                        .ok_or_else(|| format!("invalid log level: {value}"))?;
                }
                other => return Err(format!("unknown argument: {other}")),
            }
        }

        Ok(Config {
            socket_name,
            log_level,
        })
    }
}

fn main() {
    let config = match Config::from_args() {
        Ok(config) => config,
        Err(err) => {
            eprintln!("[error] {err}");
            eprintln!(
                "usage: rustpush-service [--socket <name>] [--log-level error|warn|info|debug]"
            );
            std::process::exit(2);
        }
    };

    let mut logger = Logger {
        level: config.log_level,
    };

    // Force-link the rustpush core so the produced .so genuinely embeds the
    // Apple push implementation (not just this thin shell). This also fails the
    // build early if the rustpush dependency stops compiling for the target.
    let _rustpush_linked = std::mem::size_of::<rustpush::RegisterMeta>();
    logger.info("rustpush core linked");

    let listener = match socket::bind_abstract(config.socket_name.as_bytes(), LISTEN_BACKLOG) {
        Ok(listener) => listener,
        Err(err) => {
            logger.error(&format!(
                "failed to bind abstract socket '{}': {err}",
                config.socket_name
            ));
            std::process::exit(1);
        }
    };
    logger.info(&format!(
        "listening on abstract:{} (backlog {LISTEN_BACKLOG})",
        config.socket_name
    ));

    // One client (the app) at a time; loop to support reconnects (codespec 4.5).
    for incoming in listener.incoming() {
        match incoming {
            Ok(stream) => {
                logger.info("client connected");
                if let Err(err) = serve_connection(stream, &mut logger) {
                    logger.warn(&format!("connection ended: {err}"));
                }
                logger.info("client disconnected");
            }
            Err(err) => logger.error(&format!("accept failed: {err}")),
        }
    }
}

/// Serves a single client connection until end-of-stream or a transport error.
fn serve_connection(
    stream: std::os::unix::net::UnixStream,
    logger: &mut Logger,
) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut writer = BufWriter::new(stream);

    loop {
        let command = match read_command(&mut reader)? {
            Some(command) => command,
            None => return Ok(()),
        };

        let response = handle_command(command, logger);
        write_event(&mut writer, &response)?;
    }
}

/// Maps a decoded [`Command`] to the [`Event`] to send back.
fn handle_command(command: Command, logger: &mut Logger) -> Event {
    match command {
        Command::Ping => {
            logger.debug("PING -> PONG");
            Event::Pong
        }
        Command::Activate { apple_id, .. } => {
            logger.info(&format!(
                "ACTIVATE requested for {apple_id} (not implemented)"
            ));
            // TODO(m3): drive rustpush activation via
            // rustpush::authenticate_apple / AppleAccount, relay the 2FA
            // challenge, and emit ACTIVATION_STATUS {status, handles}.
            unimplemented_event("ACTIVATE")
        }
        Command::SendMessage { message_id, .. } => {
            logger.info(&format!("SEND_MESSAGE {message_id} (not implemented)"));
            // TODO(m3): encrypt and send via rustpush::IMClient, then reply
            // Event::Ack { message_id } on acceptance.
            unimplemented_event("SEND_MESSAGE")
        }
        Command::GetMessages { since } => {
            logger.info(&format!("GET_MESSAGES since {since} (not implemented)"));
            // TODO(m3): fetch messages from rustpush and stream them back.
            unimplemented_event("GET_MESSAGES")
        }
    }
}

fn unimplemented_event(command: &str) -> Event {
    Event::Error {
        message: format!("{command} is not yet implemented in the native service"),
    }
}

/// Minimal leveled stderr logger. `rustpush-service` writes to stderr so that
/// `NativeServiceLauncher` can capture the process's diagnostic output.
struct Logger {
    level: LogLevel,
}

impl Logger {
    fn log(&self, level: LogLevel, tag: &str, message: &str) {
        if level <= self.level {
            eprintln!("[{tag}] {message}");
        }
    }

    fn error(&self, message: &str) {
        self.log(LogLevel::Error, "error", message);
    }

    fn warn(&self, message: &str) {
        self.log(LogLevel::Warn, "warn", message);
    }

    fn info(&self, message: &str) {
        self.log(LogLevel::Info, "info", message);
    }

    fn debug(&self, message: &str) {
        self.log(LogLevel::Debug, "debug", message);
    }
}
