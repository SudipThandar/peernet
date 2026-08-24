//! Transparent TCP termination for the PeerNet client TUN.
//!
//! The phone's kernel sends real TCP segments into the TUN; those cannot be
//! relayed verbatim (sequence numbers belong to the phone's connection).
//! This crate runs a userspace TCP/IP stack (smoltcp) between the TUN and
//! the tunnel: each phone flow is terminated locally, and its byte stream
//! is handed to the caller over plain channels for forwarding through PNTP
//! relay streams.
//!
//! Wire seam (all cross-thread communication is channels):
//!
//! ```text
//! TUN reader --push_packet()--> [TcpStack] --ToUpstream--> QUIC bridge
//! TUN writer <--pkt_out-------- [TcpStack] <--FromUpstream-- (relay data)
//! ```
//!
//! The caller owns the QUIC side: on `ToUpstream::Open` it opens a relay
//! stream carrying a `TcpRelayHeader` (the host already pipes such streams
//! to the real destination), pumps `Data`, and mirrors remote EOF back as
//! `FromUpstream::Eof`.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use smoltcp::iface::{Config, Interface, SocketSet};
use smoltcp::phy::{
    Checksum, ChecksumCapabilities, Device, DeviceCapabilities, Medium, RxToken, TxToken,
};

use smoltcp::socket::tcp;
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpListenEndpoint, Ipv4Address,
};

/// Identifies one phone-side TCP flow.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct FlowKey {
    pub src_ip: [u8; 4],
    pub src_port: u16,
    pub dst_ip: [u8; 4],
    pub dst_port: u16,
}

/// Messages flowing from the stack toward the tunnel (phone -> internet).
#[derive(Debug)]
pub enum ToUpstream {
    /// Flow became established; the bridge must open a relay stream now.
    Open { flow: FlowKey },
    Data { flow: FlowKey, bytes: Vec<u8> },
    /// Phone half- or fully closed its send side.
    Eof { flow: FlowKey },
}

/// Messages flowing from the tunnel into the stack (internet -> phone).
#[derive(Debug)]
pub enum FromUpstream {
    Data { flow: FlowKey, bytes: Vec<u8> },
    /// Relay stream finished (remote closed or bridge failed).
    Eof { flow: FlowKey },
}

const RX_BUF: usize = 64 * 1024;
const TX_BUF: usize = 64 * 1024;
/// Max bytes buffered for the phone across all flows before we stop
/// draining the upstream channel for a while (backpressure).
const PENDING_CAP: usize = 8 * RX_BUF;

/// MTU of the phone-facing interface. This MUST match the MTU the VpnService
/// builder sets (`PeerNetVpnService.MTU`): every packet this engine emits is
/// written straight into that TUN, and the kernel rejects anything larger.
/// It also fixes the MSS we advertise to the phone (MTU - 20 IP - 20 TCP).
pub const TUN_MTU: usize = 1280;


// ---------------------------------------------------------------------------
// QueueDevice: smoltcp Device backed by plain packet queues.
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct QueueDevice {
    rx_queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
    tx_queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
}

struct QdRx {
    pkt: Vec<u8>,
}

impl RxToken for QdRx {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.pkt)
    }
}

struct QdTx {
    queue: Arc<Mutex<VecDeque<Vec<u8>>>>,
}

impl TxToken for QdTx {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let result = f(&mut buf);
        self.queue.lock().unwrap().push_back(buf);
        result
    }
}

impl Device for QueueDevice {
    type RxToken<'a> = QdRx;
    type TxToken<'a> = QdTx;

    fn receive(&mut self, _timestamp: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let pkt = self.rx_queue.lock().unwrap().pop_front()?;
        Some((
            QdRx { pkt },
            QdTx {
                queue: self.tx_queue.clone(),
            },
        ))
    }

    fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(QdTx {
            queue: self.tx_queue.clone(),
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        // non_exhaustive: start from default and override.
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        // Must equal the VpnService MTU; a larger value would let smoltcp
        // build segments the TUN refuses to accept, stalling downloads.
        caps.max_transmission_unit = TUN_MTU;
        caps.max_burst_size = None;
        // Compute checksums on transmit (the phone verifies them), but do not
        // verify on receive. Packets arrive from the local kernel over a TUN,
        // where corruption is not a real risk, and a checksum smoltcp dislikes
        // would be dropped invisibly - every SYN lost with no way to see why.
        let mut sums = ChecksumCapabilities::default();
        sums.ipv4 = Checksum::Tx;
        sums.tcp = Checksum::Tx;
        sums.udp = Checksum::Tx;
        sums.icmpv4 = Checksum::Tx;
        caps.checksum = sums;
        caps
    }

}

// ---------------------------------------------------------------------------
// Packet parsing (outbound phone packets only, IPv4/TCP).
// ---------------------------------------------------------------------------

pub(crate) struct TcpInfo {
    pub src_ip: [u8; 4],
    pub dst_ip: [u8; 4],
    pub src_port: u16,
    pub dst_port: u16,
    pub flags: u8,
}

pub(crate) const TCP_SYN: u8 = 0x02;
pub(crate) const TCP_ACK: u8 = 0x10;

pub(crate) fn parse_tcp(packet: &[u8]) -> Option<TcpInfo> {
    if packet.len() < 40 || packet[0] >> 4 != 4 {
        return None;
    }
    let ihl = ((packet[0] & 0x0F) as usize) * 4;
    if ihl < 20 || packet.len() < ihl + 20 || packet[9] != 6 {
        return None;
    }
    let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    if total_len < ihl + 20 {
        return None;
    }
    let l4 = ihl;
    Some(TcpInfo {
        src_ip: [packet[12], packet[13], packet[14], packet[15]],
        dst_ip: [packet[16], packet[17], packet[18], packet[19]],
        src_port: u16::from_be_bytes([packet[l4], packet[l4 + 1]]),
        dst_port: u16::from_be_bytes([packet[l4 + 2], packet[l4 + 3]]),
        flags: packet[l4 + 13],
    })
}

// ---------------------------------------------------------------------------
// TcpStack
// ---------------------------------------------------------------------------

struct Flow {
    handle: smoltcp::iface::SocketHandle,
    opened_upstream: bool,
    sent_eof_upstream: bool,
    got_remote_eof: bool,
    pending_downstream: Vec<u8>,
}

pub struct TcpStack {
    device: QueueDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
    flows: HashMap<FlowKey, Flow>,
    pkt_in: mpsc::Receiver<Vec<u8>>,
    pkt_out: mpsc::Sender<Vec<u8>>,
    to_upstream: mpsc::Sender<ToUpstream>,
    from_upstream: mpsc::Receiver<FromUpstream>,
    started: Instant,
    /// Set when the upstream side disappears; tears the loop down.
    upstream_alive: bool,
}

impl TcpStack {
    /// Create the stack plus the channel handles other threads use to feed
    /// it. `pkt_out` receives packets that must be written back into the
    /// TUN (SYN-ACKs, data, ACKs, FINs generated by this stack).
    pub fn channels(
        pkt_out: mpsc::Sender<Vec<u8>>,
        to_upstream: mpsc::Sender<ToUpstream>,
    ) -> (Self, mpsc::Sender<Vec<u8>>, mpsc::Sender<FromUpstream>) {
        let (pkt_in_tx, pkt_in_rx) = mpsc::channel::<Vec<u8>>();
        let (upstream_tx, upstream_rx) = mpsc::channel::<FromUpstream>();

        let device = QueueDevice {
            rx_queue: Arc::new(Mutex::new(VecDeque::new())),
            tx_queue: Arc::new(Mutex::new(VecDeque::new())),
        };

        let config = Config::new(HardwareAddress::Ip);
        // Interface::new only reads capabilities/config from the device;
        // a throwaway clone sharing the same queues keeps ownership simple.
        let mut probe = device.clone();
        let mut iface = Interface::new(config, &mut probe, SmolInstant::from_millis(0));
        iface.update_ip_addrs(|addrs| {
            // Our address inside the TUN's subnet world; /32 is enough
            // because delivery is decided by our default route below.
            let _ = addrs.push(IpCidr::new(
                IpAddress::Ipv4(Ipv4Address::new(10, 215, 17, 254)),
                32,
            ));
            // Whitequark's transparent-stack trick (smoltcp#516): a /0 local
            // prefix makes every destination locally deliverable. Kept AFTER
            // the specific address so source selection prefers real IPs.
            let _ = addrs.push(IpCidr::new(
                IpAddress::Ipv4(Ipv4Address::new(0, 0, 0, 1)), // 0.0.0.1
                0,
            ));
        });
        let _ = iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1));
        // Flows target arbitrary internet addresses that are not our local
        // IP; AnyIP makes the interface accept them so listeners can match.
        iface.set_any_ip(true);

        let mut sockets = SocketSet::new(Vec::new());

        let stack = TcpStack {
            device,
            iface,
            sockets,
            flows: HashMap::new(),
            pkt_in: pkt_in_rx,
            pkt_out,
            to_upstream,
            from_upstream: upstream_rx,
            started: Instant::now(),
            upstream_alive: true,
        };
        (stack, pkt_in_tx, upstream_tx)
    }

    /// Blocking engine loop. Runs until the upstream channel receiver is
    /// dropped or all packet producers hang up.
    pub fn run(mut self) {
        while self.upstream_alive {
            self.step();
            if !self.upstream_alive {
                return;
            }
            match self.iface.poll_delay(self.now(), &self.sockets) {
                // Cap sleeps at ~5 ms regardless of what smoltcp suggests;
                // latency matters more than a little extra CPU here.
                Some(d) => {
                    let ms = d.total_millis().clamp(1, 5) as u64;
                    std::thread::sleep(Duration::from_millis(ms));
                }
                None => std::thread::sleep(Duration::from_millis(2)),
            }
        }
    }

    /// One engine iteration: ingest queued packets, advance the stack,
    /// emit outputs. Split out so tests can drive deterministically.
    pub(crate) fn step(&mut self) {
        let now = self.now();
        self.drain_incoming(now);
        if !self.upstream_alive {
            return;
        }

        let _ = self.iface.poll(now, &mut self.device, &mut self.sockets);

        self.flush_device_output();
        self.drain_from_upstream();
        self.pump_flows();
    }

    fn now(&self) -> SmolInstant {
        SmolInstant::from_millis(self.started.elapsed().as_millis() as i64)
    }

    /// Consume every queued outbound packet, registering listeners for new
    /// flows BEFORE any poll sees their SYNs.
    fn drain_incoming(&mut self, _now: SmolInstant) {
        loop {
            match self.pkt_in.try_recv() {
                Ok(pkt) => {
                    if let Some(info) = parse_tcp(&pkt) {
                        let key = FlowKey {
                            src_ip: info.src_ip,
                            src_port: info.src_port,
                            dst_ip: info.dst_ip,
                            dst_port: info.dst_port,
                        };
                        let is_syn = info.flags & TCP_SYN != 0 && info.flags & TCP_ACK == 0;
                        if is_syn && !self.flows.contains_key(&key) {
                            self.add_listener(key);
                        }
                        if self.flows.contains_key(&key) {
                            self.device.rx_queue.lock().unwrap().push_back(pkt);
                        }
                        // Unknown non-SYN traffic has no flow; drop it.
                    }
                    // Non-TCP packets never reach this stack.
                }
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.upstream_alive = false;
                    break;
                }
            }
        }
    }

    fn add_listener(&mut self, key: FlowKey) {
        let socket = tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0u8; RX_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TX_BUF]),
        );
        let handle = self.sockets.add(socket);
        let endpoint = IpListenEndpoint {
            addr: Some(IpAddress::Ipv4(Ipv4Address::from(key.dst_ip))),
            port: key.dst_port,
        };
        let sock = self.sockets.get_mut::<tcp::Socket>(handle);
        if sock.listen(endpoint).is_err() {
            self.sockets.remove(handle);
            return;
        }
        self.flows.insert(
            key,
            Flow {
                handle,
                opened_upstream: false,
                sent_eof_upstream: false,
                got_remote_eof: false,
                pending_downstream: Vec::new(),
            },
        );
    }

    fn flush_device_output(&mut self) {
        let mut queue = self.device.tx_queue.lock().unwrap();
        while let Some(pkt) = queue.pop_front() {
            if self.pkt_out.send(pkt).is_err() {
                self.upstream_alive = false;
                return;
            }
        }
    }

    /// Bytes queued for the phone across all flows. Used to apply
    /// backpressure when the phone's TCP window is smaller than what the
    /// bridge has already pushed (e.g. full-duplex echo/bulk transfers).
    fn total_pending(&self) -> usize {
        self.flows.values().map(|f| f.pending_downstream.len()).sum()
    }

    fn drain_from_upstream(&mut self) {
        loop {
            // Backpressure: if we're already buffering plenty for slow
            // phone-side windows, leave the rest queued in the channel and
            // pick it up next iteration instead of ballooning RAM or
            // dropping live flows.
            if self.total_pending() >= PENDING_CAP {
                return;
            }
            match self.from_upstream.try_recv() {
                Ok(msg) => match msg {
                    FromUpstream::Data { flow, bytes } => {
                        if let Some(f) = self.flows.get_mut(&flow) {
                            f.pending_downstream.extend_from_slice(&bytes);
                        }
                        // Unknown flow: relay died before us; drop quietly.
                    }
                    FromUpstream::Eof { flow } => {
                        if let Some(f) = self.flows.get_mut(&flow) {
                            f.got_remote_eof = true;
                        }
                    }
                },
                Err(mpsc::TryRecvError::Empty) => break,
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.upstream_alive = false;
                    break;
                }
            }
        }
    }

    fn pump_flows(&mut self) {
        let keys: Vec<FlowKey> = self.flows.keys().copied().collect();
        let mut remove: Vec<FlowKey> = Vec::new();

        for key in keys {
            let flow = self.flows.get_mut(&key).unwrap();
            let sock = self.sockets.get_mut::<tcp::Socket>(flow.handle);

            // New handshake completed? Tell the bridge to open the relay.
            if !flow.opened_upstream && sock.state() == tcp::State::Established {
                flow.opened_upstream = true;
                let _ = self.to_upstream.send(ToUpstream::Open { flow: key });
            }

            // Phone -> internet: drain whatever the kernel delivered.
            if sock.may_recv() {
                let mut buf = [0u8; 16 * 1024];
                loop {
                    match sock.recv_slice(&mut buf) {
                        Ok(0) => break,
                        Ok(n) => {
                            let msg = ToUpstream::Data {
                                flow: key,
                                bytes: buf[..n].to_vec(),
                            };
                            if self.to_upstream.send(msg).is_err() {
                                self.upstream_alive = false;
                                return;
                            }
                            if n < buf.len() {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
            }

            // Internet -> phone: push pending downstream bytes.
            if !flow.pending_downstream.is_empty() && sock.can_send() {
                let n = sock
                    .send_slice(&flow.pending_downstream)
                    .unwrap_or(0);
                flow.pending_downstream.drain(..n);
            }

            // Remote closed: propagate a FIN toward the phone once our
            // pending payload drained.
            if flow.got_remote_eof && flow.pending_downstream.is_empty() {
                if sock.state() == tcp::State::Established
                    || sock.state() == tcp::State::SynReceived
                {
                    sock.close();
                }
            }

            // Phone closed its side (we saw its FIN): tell the bridge.
            if matches!(
                sock.state(),
                tcp::State::CloseWait
                    | tcp::State::FinWait1
                    | tcp::State::FinWait2
                    | tcp::State::Closing
                    | tcp::State::LastAck
            ) && !flow.sent_eof_upstream
            {
                flow.sent_eof_upstream = true;
                let _ = self.to_upstream.send(ToUpstream::Eof { flow: key });
            }

            // Terminal states: reclaim the socket.
            if !sock.is_open() {
                remove.push(key);
            }
        }

        for key in remove {
            if let Some(flow) = self.flows.remove(&key) {
                self.sockets.remove(flow.handle);
                if !flow.sent_eof_upstream {
                    let _ = self.to_upstream.send(ToUpstream::Eof { flow: key });
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tests: a second smoltcp engine plays the phone's kernel; the two stacks
// exchange packets through crossed in-memory queues, so the whole data path
// (SYN -> listener -> relay bridge echo -> FIN) runs with zero OS networking.
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    const PHONE_IP: [u8; 4] = [10, 215, 17, 2];
    const REMOTE_IP: [u8; 4] = [93, 184, 216, 34];

    /// Echo "server" standing in for the QUIC bridge: echoes Data back,
    /// mirrors Eof. Records what it saw for assertions.
    struct Bridge {
        seen_open: Arc<Mutex<Vec<FlowKey>>>,
        seen_eof: Arc<Mutex<Vec<FlowKey>>>,
        bytes_up: Arc<Mutex<usize>>,
        handle: Option<std::thread::JoinHandle<()>>,
    }

    impl Drop for Bridge {
        fn drop(&mut self) {
            // Receiver dropped inside thread ends the stack loop on next send.
            if let Some(h) = self.handle.take() {
                let _ = h.join();
            }
        }
    }

    struct Harness {
        phone_iface: Interface,
        phone_dev: QueueDevice,
        phone_sockets: SocketSet<'static>,
        pkt_in_tx: Option<mpsc::Sender<Vec<u8>>>,
        pkt_out_rx: mpsc::Receiver<Vec<u8>>,
        up_tx: Option<mpsc::Sender<FromUpstream>>,
        stack_handle: Option<std::thread::JoinHandle<()>>,
        bridge: Bridge,
        started: Instant,
        /// Largest packet the engine handed to the TUN writer.
        max_to_phone: usize,
    }


    impl Harness {
        fn new() -> Self {
            let (pkt_out_tx, pkt_out_rx) = mpsc::channel::<Vec<u8>>();
            let (to_up_tx, to_up_rx) = mpsc::channel::<ToUpstream>();
            let (stack, pkt_in_tx, up_tx) =
                TcpStack::channels(pkt_out_tx, to_up_tx);
            let stack_thread = std::thread::spawn(move || stack.run());

            let seen_open = Arc::new(Mutex::new(Vec::new()));
            let seen_eof = Arc::new(Mutex::new(Vec::new()));
            let bytes_up = Arc::new(Mutex::new(0usize));

            let b_seen_open = seen_open.clone();
            let b_seen_eof = seen_eof.clone();
            let b_bytes = bytes_up.clone();
            let b_up = up_tx.clone();
            let bridge_thread = std::thread::spawn(move || {
                while let Ok(msg) = to_up_rx.recv() {
                    match msg {
                        ToUpstream::Open { flow } => {
                            b_seen_open.lock().unwrap().push(flow);
                        }
                        ToUpstream::Data { flow, bytes } => {
                            *b_bytes.lock().unwrap() += bytes.len();
                            // Echo back; a real bridge writes to QUIC here.
                            let _ = b_up.send(FromUpstream::Data { flow, bytes });
                        }
                        ToUpstream::Eof { flow } => {
                            b_seen_eof.lock().unwrap().push(flow);
                            let _ = b_up.send(FromUpstream::Eof { flow });
                        }
                    }
                }
            });

            // Phone-side stack: plain smoltcp interface over its own queues.
            let device = QueueDevice {
                rx_queue: Arc::new(Mutex::new(VecDeque::new())),
                tx_queue: Arc::new(Mutex::new(VecDeque::new())),
            };
            let mut probe = device.clone();
            let config = Config::new(HardwareAddress::Ip);
            let mut iface = Interface::new(config, &mut probe, SmolInstant::from_millis(0));
            iface.update_ip_addrs(|addrs| {
                let _ = addrs.push(IpCidr::new(
                    IpAddress::Ipv4(Ipv4Address::from(PHONE_IP)),
                    32,
                ));
            });
            let _ = iface
                .routes_mut()
                .add_default_ipv4_route(Ipv4Address::new(10, 215, 17, 253));
            let sockets = SocketSet::new(Vec::new());

            Harness {
                phone_iface: iface,
                phone_dev: device,
                phone_sockets: sockets,
                pkt_in_tx: Some(pkt_in_tx),
                pkt_out_rx,
                up_tx: Some(up_tx),
                stack_handle: Some(stack_thread),
                bridge: Bridge {
                    seen_open,
                    seen_eof,
                    bytes_up,
                    handle: Some(bridge_thread),
                },
                started: Instant::now(),
                max_to_phone: 0,
            }
        }


        fn add_phone_socket(&mut self) -> smoltcp::iface::SocketHandle {
            let sock = tcp::Socket::new(
                tcp::SocketBuffer::new(vec![0u8; RX_BUF]),
                tcp::SocketBuffer::new(vec![0u8; TX_BUF]),
            );
            self.phone_sockets.add(sock)
        }

        fn connect_phone_socket(
            &mut self,
            handle: smoltcp::iface::SocketHandle,
            dst_ip: [u8; 4],
            dst_port: u16,
            local_port: u16,
        ) {
            let mut cx = self.phone_iface.context();
            let sock = self.phone_sockets.get_mut::<tcp::Socket>(handle);
            sock.connect(
                &mut cx,
                (
                    IpAddress::Ipv4(Ipv4Address::from(dst_ip)),
                    dst_port,
                ),
                IpListenEndpoint {
                    addr: Some(IpAddress::Ipv4(Ipv4Address::from(PHONE_IP))),
                    port: local_port,
                },
            )
            .expect("phone connect");
        }

        /// Alternate both engines until `ms` elapses.
        fn run_for(&mut self, ms: u64) {
            let deadline = Instant::now() + Duration::from_millis(ms);
            while Instant::now() < deadline {
                // Stack -> phone packets.
                while let Ok(p) = self.pkt_out_rx.try_recv() {
                    self.max_to_phone = self.max_to_phone.max(p.len());
                    self.phone_dev.rx_queue.lock().unwrap().push_back(p);
                }

                let now = SmolInstant::from_millis(
                    self.started.elapsed().as_millis() as i64
                );
                let _ = self.phone_iface.poll(now, &mut self.phone_dev, &mut self.phone_sockets);
                // Phone -> stack packets.
                let mut tx = self.phone_dev.tx_queue.lock().unwrap();
                while let Some(p) = tx.pop_front() {
                    if let Some(sender) = &self.pkt_in_tx {
                        let _ = sender.send(p);
                    }
                }
                drop(tx);
                std::thread::sleep(Duration::from_millis(2));
            }
        }

        fn socket_established(&mut self, handle: smoltcp::iface::SocketHandle) -> bool {
            self.phone_sockets.get_mut::<tcp::Socket>(handle).state()
                == tcp::State::Established
        }

        fn send_phone(&mut self, handle: smoltcp::iface::SocketHandle, data: &[u8]) {
            let sock = self.phone_sockets.get_mut::<tcp::Socket>(handle);
            sock.send_slice(data).expect("phone send");
        }

        fn recv_phone_all(&mut self, handle: smoltcp::iface::SocketHandle) -> Vec<u8> {
            let mut out = Vec::new();
            let sock = self.phone_sockets.get_mut::<tcp::Socket>(handle);
            let mut buf = [0u8; 16 * 1024];
            while sock.can_recv() {
                match sock.recv_slice(&mut buf) {
                    Ok(n) if n > 0 => out.extend_from_slice(&buf[..n]),
                    _ => break,
                }
            }
            out
        }

        fn close_phone(&mut self, handle: smoltcp::iface::SocketHandle) {
            self.phone_sockets
                .get_mut::<tcp::Socket>(handle)
                .close();
        }

        fn upstream_opens(&self) -> Vec<FlowKey> {
            self.bridge.seen_open.lock().unwrap().clone()
        }

        fn upstream_eof_count(&self) -> usize {
            self.bridge.seen_eof.lock().unwrap().len()
        }

        fn bytes_through_bridge(&self) -> usize {
            *self.bridge.bytes_up.lock().unwrap()
        }

        fn largest_packet_to_phone(&self) -> usize {
            self.max_to_phone
        }


        fn shutdown(mut self) {
            // Dropping senders terminates the stack loop and the bridge.
            self.pkt_in_tx.take();
            self.up_tx.take();
            if let Some(h) = self.stack_handle.take() {
                let _ = h.join();
            }
            while self.pkt_out_rx.try_recv().is_ok() {}
        }
    }

    #[test]
    fn handshake_echo_and_close() {
        let mut h = Harness::new();
        let s1 = h.add_phone_socket();
        h.connect_phone_socket(s1, REMOTE_IP, 443, 40001);
        h.run_for(300);

        assert!(h.socket_established(s1), "flow must establish");
        assert_eq!(h.upstream_opens().len(), 1, "bridge sees one Open");

        h.send_phone(s1, b"hello-peernet");
        h.run_for(300);
        let got = h.recv_phone_all(s1);
        assert_eq!(got, b"hello-peernet", "echo roundtrip through the stack");

        h.close_phone(s1);
        h.run_for(400);
        assert!(
            h.upstream_eof_count() >= 1,
            "bridge learns the phone closed"
        );
        h.shutdown();
    }

    #[test]
    fn two_concurrent_flows_same_destination() {
        let mut h = Harness::new();
        let a = h.add_phone_socket();
        let b = h.add_phone_socket();
        // Same destination endpoint, different local ports - this is the
        // per-SYN-listener case that breaks naive designs.
        h.connect_phone_socket(a, REMOTE_IP, 443, 40011);
        h.connect_phone_socket(b, REMOTE_IP, 443, 40012);
        h.run_for(500);

        assert!(h.socket_established(a), "flow A establishes");
        assert!(h.socket_established(b), "flow B establishes too");
        assert_eq!(h.upstream_opens().len(), 2);

        h.send_phone(a, b"AAA");
        h.send_phone(b, b"BBB");
        h.run_for(300);
        assert_eq!(h.recv_phone_all(a), b"AAA");
        assert_eq!(h.recv_phone_all(b), b"BBB");
        h.shutdown();
    }

    #[test]
    fn bulk_transfer_256k() {
        let mut h = Harness::new();
        let s = h.add_phone_socket();
        h.connect_phone_socket(s, REMOTE_IP, 8080, 40100);
        h.run_for(300);
        assert!(h.socket_established(s));

        let payload: Vec<u8> = (0..256 * 1024u32).map(|i| (i % 251) as u8).collect();
        // Feed in chunks so the kernel-side buffers behave like reality.
        for chunk in payload.chunks(16 * 1024) {
            h.send_phone(s, chunk);
            h.run_for(120);
        }
        h.run_for(600);

        assert!(h.bytes_through_bridge() >= payload.len(), "all bytes crossed");
        // The echo comes back while our receive window is the bottleneck;
        // keep interleaving reads with engine time slices until complete.
        let mut got = Vec::with_capacity(payload.len());
        let deadline = Instant::now() + Duration::from_secs(15);
        while got.len() < payload.len() && Instant::now() < deadline {
            got.extend_from_slice(&h.recv_phone_all(s));
            h.run_for(40);
        }
        assert_eq!(got.len(), payload.len(), "echo returned everything");
        assert_eq!(got, payload, "bulk payload intact");
        // The whole point of matching the device MTU to the VpnService MTU:
        // nothing we emit may exceed what the TUN accepts. Both bounds matter
        // - the lower one proves this bulk run really produced full-size
        // segments, so the upper bound is not passing vacuously.
        assert!(
            h.largest_packet_to_phone() > 1000,
            "bulk transfer should emit full-size segments, saw {}",
            h.largest_packet_to_phone()
        );
        assert!(
            h.largest_packet_to_phone() <= TUN_MTU,
            "emitted {}B, TUN only accepts {}B",
            h.largest_packet_to_phone(),
            TUN_MTU
        );
        h.shutdown();
    }


    // -- deterministic, single-threaded diagnostics --------------------------

    fn checksum_words(data: &[u8]) -> u16 {
        let mut sum: u32 = 0;
        let mut i = 0;
        while i + 1 < data.len() {
            sum += u16::from_be_bytes([data[i], data[i + 1]]) as u32;
            i += 2;
        }
        if i < data.len() {
            sum += (data[i] as u32) << 8;
        }
        while sum >> 16 != 0 {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        !(sum as u16)
    }

    /// Hand-crafted IPv4+TCP SYN with valid checksums.
    fn build_syn(src: [u8; 4], dst: [u8; 4], sport: u16, dport: u16) -> Vec<u8> {
        let mut ip = vec![0u8; 20];
        ip[0] = 0x45;
        ip[2..4].copy_from_slice(&(40u16).to_be_bytes());
        ip[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
        ip[8] = 64;
        ip[9] = 6;
        ip[12..16].copy_from_slice(&src);
        ip[16..20].copy_from_slice(&dst);
        let c = checksum_words(&ip);
        ip[10..12].copy_from_slice(&c.to_be_bytes());

        let mut tcp = vec![0u8; 20];
        tcp[0..2].copy_from_slice(&sport.to_be_bytes());
        tcp[2..4].copy_from_slice(&dport.to_be_bytes());
        tcp[4..8].copy_from_slice(&1000u32.to_be_bytes());
        tcp[12] = 5 << 4;
        tcp[13] = TCP_SYN;
        tcp[14..16].copy_from_slice(&8192u16.to_be_bytes());

        let mut pseudo = Vec::with_capacity(12 + tcp.len());
        pseudo.extend_from_slice(&src);
        pseudo.extend_from_slice(&dst);
        pseudo.extend_from_slice(&[0, 6]);
        pseudo.extend_from_slice(&(tcp.len() as u16).to_be_bytes());
        pseudo.extend_from_slice(&tcp);
        let c = checksum_words(&pseudo);
        tcp[16..18].copy_from_slice(&c.to_be_bytes());

        ip.extend_from_slice(&tcp);
        ip
    }

    #[test]
    fn syn_produces_synack_without_threads() {
        let (pkt_out_tx, pkt_out_rx) = mpsc::channel();
        let (to_up_tx, _to_up_rx) = mpsc::channel::<ToUpstream>();
        let (mut st, pkt_in_tx, _up_tx) =
            TcpStack::channels(pkt_out_tx, to_up_tx);

        pkt_in_tx
            .send(build_syn(PHONE_IP, REMOTE_IP, 40001, 443))
            .unwrap();

        st.step();
        eprintln!("after step1: flows={:?} rxq={} listeners_ok", st.flows.keys().collect::<Vec<_>>(), st.device.rx_queue.lock().unwrap().len());
        for _ in 0..3 {
            st.step();
        }
        eprintln!("txq after steps: {}", st.device.tx_queue.lock().unwrap().len());

        match pkt_out_rx.try_recv() {
            Ok(reply) => {
                eprintln!("reply len={} flags=0x{:02x}", reply.len(), reply[33]);
                assert!(reply.len() >= 40);
                assert_eq!(&reply[12..16], &REMOTE_IP);
                assert_eq!(&reply[16..20], &PHONE_IP);
                assert_eq!(reply[33], TCP_SYN | TCP_ACK, "must be SYN-ACK");
            }
            Err(e) => panic!("no SYN-ACK produced: {e}; flows={:?}", st.flows.keys().collect::<Vec<_>>()),
        }
    }
}
