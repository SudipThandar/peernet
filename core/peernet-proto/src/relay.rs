//! PNTP relay headers (spec Sections 9.6/9.7).
//!
//! TCP: a QUIC bidirectional stream starts with one framed
//! [`PeerMessage`](super::PeerMessage) whose payload is an encoded
//! [`TcpRelayHeader`]; every byte after that frame is raw TCP payload.
//!
//! UDP: each QUIC datagram carries exactly one raw [`UdpRelayHeader`]
//! followed by the datagram payload — no PNTP framing.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use super::PntpError;

pub const RELAY_MAGIC: [u8; 2] = *b"PN";
pub const RELAY_VERSION: u8 = 1;
pub const RELAY_TYPE_TCP: u8 = 0x01;
pub const RELAY_TYPE_UDP: u8 = 0x02;

pub const FLAG_IPV4: u8 = 0x01;
pub const FLAG_IPV6: u8 = 0x02;
pub const FLAG_DNS: u8 = 0x04;
pub const FLAG_STUN: u8 = 0x08;
pub const FLAG_RTC: u8 = 0x10;

/// Wire sizes: magic(2)+ver(1)+type(1) + ports(2+2) + flags(1) + ip(4|16).
pub const TCP_HEADER_LEN_V4: usize = 13;
pub const TCP_HEADER_LEN_V6: usize = 25;
pub const UDP_HEADER_BASE: usize = 7; // magic..type + session(4)
pub const UDP_HEADER_LEN_V4: usize = UDP_HEADER_BASE + 2 + 4 + 2 + 1;
pub const UDP_HEADER_LEN_V6: usize = UDP_HEADER_BASE + 2 + 16 + 2 + 1;

fn encode_ip(ip: IpAddr, out: &mut Vec<u8>) -> u8 {
    match ip {
        IpAddr::V4(v4) => {
            out.extend_from_slice(&v4.octets());
            FLAG_IPV4
        }
        IpAddr::V6(v6) => {
            out.extend_from_slice(&v6.octets());
            FLAG_IPV6
        }
    }
}

fn decode_ip(flags: u8, cur: &mut usize, bytes: &[u8]) -> Result<IpAddr, PntpError> {
    let need = if flags & FLAG_IPV6 != 0 { 16 } else { 4 };
    if *cur + need > bytes.len() {
        return Err(PntpError::UnexpectedEof);
    }
    let ip = if need == 4 {
        IpAddr::V4(Ipv4Addr::new(bytes[*cur], bytes[*cur + 1], bytes[*cur + 2], bytes[*cur + 3]))
    } else {
        let mut oct = [0u8; 16];
        oct.copy_from_slice(&bytes[*cur..*cur + 16]);
        IpAddr::V6(Ipv6Addr::from(oct))
    };
    *cur += need;
    Ok(ip)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpRelayHeader {
    pub src_port: u16,
    pub dst_ip: IpAddr,
    pub dst_port: u16,
}

impl TcpRelayHeader {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(TCP_HEADER_LEN_V6);
        out.extend_from_slice(&RELAY_MAGIC);
        out.push(RELAY_VERSION);
        out.push(RELAY_TYPE_TCP);
        out.extend_from_slice(&self.src_port.to_be_bytes());
        let flag = encode_ip(self.dst_ip, &mut out);
        out.extend_from_slice(&self.dst_port.to_be_bytes());
        out.push(flag);
        out
    }

    /// Decodes from the start of `payload`; returns (header, payload_start).
    pub fn decode(payload: &[u8]) -> Result<(Self, usize), PntpError> {
        let v4_len_ok = payload.len() >= TCP_HEADER_LEN_V4;
        if payload.len() < TCP_HEADER_LEN_V4 || payload[0..2] != RELAY_MAGIC {
            return Err(PntpError::InvalidMagic);
        }
        if payload[2] != RELAY_VERSION || payload[3] != RELAY_TYPE_TCP {
            return Err(PntpError::BadVersion(payload[2]));
        }
        let mut cur = 4usize;
        let src_port = u16::from_be_bytes([payload[cur], payload[cur + 1]]);
        cur += 2;
        // Flags live at the tail; peek ip length from version byte position:
        // we cannot know until we read flags, so try v4 then v6.
        let flags_v4 = *payload.get(TCP_HEADER_LEN_V4 - 1).unwrap_or(&0);
        let (dst_ip, dst_port, _flag) = if flags_v4 & FLAG_IPV6 != 0 && payload.len() >= TCP_HEADER_LEN_V6 {
            cur += 16;
            let mut oct = [0u8; 16];
            oct.copy_from_slice(&payload[cur - 16..cur]);
            let port = u16::from_be_bytes([payload[cur], payload[cur + 1]]);
            let f = payload[cur + 2];
            (IpAddr::V6(Ipv6Addr::from(oct)), port, f)
        } else if !v4_len_ok {
            return Err(PntpError::UnexpectedEof);
        } else {
            let ip = decode_ip(FLAG_IPV4, &mut cur, payload)?;
            let port = u16::from_be_bytes([payload[cur], payload[cur + 1]]);
            let f = payload[cur + 2];
            (ip, port, f)
        };
        let start = if dst_ip.is_ipv4() { TCP_HEADER_LEN_V4 } else { TCP_HEADER_LEN_V6 };
        Ok((Self { src_port, dst_ip, dst_port }, start))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UdpRelayHeader {
    pub session_id: u32,
    pub src_port: u16,
    pub dst_ip: IpAddr,
    pub dst_port: u16,
}

impl UdpRelayHeader {
    pub fn encode(&self, extra_flags: u8) -> Vec<u8> {
        let mut out = Vec::with_capacity(UDP_HEADER_LEN_V6);
        out.extend_from_slice(&RELAY_MAGIC);
        out.push(RELAY_VERSION);
        out.push(RELAY_TYPE_UDP);
        out.extend_from_slice(&self.session_id.to_be_bytes());
        out.extend_from_slice(&self.src_port.to_be_bytes());
        let mut flag = encode_ip(self.dst_ip, &mut out);
        flag |= extra_flags;
        out.extend_from_slice(&self.dst_port.to_be_bytes());
        out.push(flag);
        out
    }

    /// Decodes a datagram; returns (header, payload_start).
    pub fn decode(datagram: &[u8]) -> Result<(Self, usize), PntpError> {
        if datagram.len() < UDP_HEADER_LEN_V4 || datagram[0..2] != RELAY_MAGIC {
            return Err(PntpError::InvalidMagic);
        }
        if datagram[2] != RELAY_VERSION || datagram[3] != RELAY_TYPE_UDP {
            return Err(PntpError::BadVersion(datagram[2]));
        }
        let mut cur = UDP_HEADER_BASE;
        let session_id = u32::from_be_bytes([
            datagram[cur], datagram[cur + 1], datagram[cur + 2], datagram[cur + 3],
        ]);
        cur += 4;
        let src_port = u16::from_be_bytes([datagram[cur], datagram[cur + 1]]);
        cur += 2;
        // Determine ip length by scanning both layouts; flags are last byte.
        let total_v4 = UDP_HEADER_LEN_V4;
        let (dst_ip, dst_port) = if datagram.len() >= total_v4
            && datagram[total_v4 - 1] & FLAG_IPV6 == 0
        {
            let ip = decode_ip(FLAG_IPV4, &mut cur, datagram)?;
            let port = u16::from_be_bytes([datagram[cur], datagram[cur + 1]]);
            (ip, port)
        } else if datagram.len() >= UDP_HEADER_LEN_V6 {
            let ip = decode_ip(FLAG_IPV6, &mut cur, datagram)?;
            let port = u16::from_be_bytes([datagram[cur], datagram[cur + 1]]);
            (ip, port)
        } else {
            return Err(PntpError::UnexpectedEof);
        };
        Ok((Self { session_id, src_port, dst_ip, dst_port }, cur))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tcp_header_roundtrip_v4() {
        let h = TcpRelayHeader {
            src_port: 4444,
            dst_ip: IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4)),
            dst_port: 80,
        };
        let enc = h.encode();
        assert_eq!(enc.len(), TCP_HEADER_LEN_V4);
        let (dec, start) = TcpRelayHeader::decode(&enc).unwrap();
        assert_eq!(h, dec);
        assert_eq!(start, TCP_HEADER_LEN_V4);
    }

    #[test]
    fn tcp_header_rejects_bad_type() {
        let mut enc = TcpRelayHeader {
            src_port: 1,
            dst_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            dst_port: 2,
        }
        .encode();
        enc[3] = RELAY_TYPE_UDP;
        assert!(TcpRelayHeader::decode(&enc).is_err());
    }

    #[test]
    fn udp_header_roundtrip_with_flags() {
        let h = UdpRelayHeader {
            session_id: 77,
            src_port: 5353,
            dst_ip: IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)),
            dst_port: 53,
        };
        let enc = h.encode(FLAG_DNS | FLAG_RTC);
        assert_eq!(enc.len(), UDP_HEADER_LEN_V4);
        let (dec, start) = UdpRelayHeader::decode(&enc).unwrap();
        assert_eq!(h, dec);
        assert!(start <= enc.len());
    }

    #[test]
    fn udp_header_truncated_rejected() {
        assert!(UdpRelayHeader::decode(&[0x50, 0x4e]).is_err());
    }
}
