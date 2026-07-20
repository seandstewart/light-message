//! Abstract-namespace AF_UNIX socket helper.
//!
//! The Kotlin side connects with `LocalSocket` in the abstract namespace
//! (`abstract:rustpush_ipc`). `std::os::linux::net`, which exposes safe
//! abstract-socket binding, is not available on `target_os = "android"`, so we
//! bind via `libc` and wrap the resulting descriptor in a std [`UnixListener`].

use std::io;
use std::mem;
use std::os::unix::io::FromRawFd;
use std::os::unix::net::UnixListener;

/// Binds an abstract-namespace AF_UNIX stream socket and starts listening.
///
/// `name` is the abstract socket name without the leading NUL (e.g.
/// `b"rustpush_ipc"`). Abstract sockets have no filesystem entry and are
/// reclaimed automatically when the listener is dropped.
pub fn bind_abstract(name: &[u8], backlog: i32) -> io::Result<UnixListener> {
    // sun_path holds a leading NUL byte (abstract marker) followed by the name.
    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    if name.len() + 1 > addr.sun_path.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "abstract socket name too long",
        ));
    }
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    // addr.sun_path[0] stays 0 (abstract namespace marker).
    for (i, byte) in name.iter().enumerate() {
        addr.sun_path[i + 1] = *byte as libc::c_char;
    }

    // Address length covers the family field, the leading NUL, and the name.
    let addr_len = (mem::size_of::<libc::sa_family_t>() + 1 + name.len()) as libc::socklen_t;

    // SAFETY: standard libc socket/bind/listen sequence with checked returns.
    // On any error before wrapping we close the fd to avoid leaking it.
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }

        if libc::bind(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            addr_len,
        ) < 0
        {
            let err = io::Error::last_os_error();
            libc::close(fd);
            return Err(err);
        }

        if libc::listen(fd, backlog) < 0 {
            let err = io::Error::last_os_error();
            libc::close(fd);
            return Err(err);
        }

        Ok(UnixListener::from_raw_fd(fd))
    }
}
