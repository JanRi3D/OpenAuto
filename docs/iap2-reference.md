# iAP2 reference

What this receiver implements of iAP2 (the link and control session under CarPlay), where each fact
comes from, and how sure it is. Nothing here has been tried with an iPhone. Apple's Accessory
Interface Specification is available only under the MFi agreement and was not used; copies found
online were not consulted.

Confidence terms used below:

- **two sources**: two public implementations agree. Whether they were written independently, or
  share an origin, is not known, so this is agreement, not independent confirmation;
- **one source**: a single public implementation or capture tool;
- **assumption**: chosen here, no source; listed under §7 for physical verification.

## 1. Sources

| Source | What it is | Licence | Used for |
|---|---|---|---|
| JJTech0130, `iap2.lua` (gist 78527c600f7b4d0a7aeb294eab06d8ba) | Wireshark dissector | none stated | message IDs, control session framing, synchronization payload layout, ZeroACK mode, EAK payload |
| JJTech0130, `carkit_iap2.py` (gist a878496479afedb93dd02399551e3aec) | link test against CarPlay Simulator's lockdown service | none stated | packet length and checksums, the ACK after SYN reusing the SYN's number, the detection bytes (marked optional there) |
| wiomoc/iap2 and the write-up at wiomoc.de/misc/posts/mfi_iap.html | Python accessory over Bluetooth and USB, with a wireless CarPlay start | none stated | identification schema (IDs 0–24), encodings, IdentificationRejected format, detection echo, adopting the phone's SYN+ACK, authentication before identification, the two USB modes |
| usenocturne/iap2-rs | Rust accessory library over Bluetooth RFCOMM | GPL-3.0 | identification IDs 0–13 and the component at 17, text termination, repeated SupportedLanguage, detection, synchronization values |
| Apple Developer Forums, thread 811397 | developer report | n/a | a successful authentication and a rejected identification, reported without a timeline |
| Apple Developer Forums, thread 26175 | developer report | n/a | Apple's accessory test system flagging an accessory EAK sent without any out-of-order packet |
| f-io/LIVI at commit 0a3dcaa (`iap2-usbmux/src/linux.rs`, `iap2-wired/src/carkit.rs`, `iap2-usbmux/src/ncm.rs`) | Linux head unit with wired CarPlay | GPL-3.0 | research leads only, for the wired route in §3: a vendor request that makes the phone offer its CarPlay configurations, the `com.apple.carkit.service` channel over USBMUX and Lockdown, the phone's NCM function |

Only facts were taken from these sources. No code was copied from any of them.

## 2. Link synchronization, field by field (provisional)

The public sources show the layout of the synchronization payload, not what each side must do with
the other's values. The direction and timing rules below are therefore provisional until checked
against an iPhone.

The accessory sends SYN with its proposal; the phone answers SYN+ACK; the accessory answers ACK, or
RST if the answer cannot be used. There is no further round: a phone that changes a session in its
answer (a counter-proposal) would need a second SYN, which is not implemented. Code:
`Iap2LinkLayer.checkAnswer`.

| Field | What the phone's value means here | Rule | Why | Basis |
|---|---|---|---|---|
| Link version | the packet format both sides use | must equal the proposal (1) | every other field's meaning depends on it | two sources send 1; wiomoc refuses other versions |
| Maximum outstanding packets | how many unacknowledged packets the phone accepts | at least 1; this side keeps at most min(value, 127) in flight; larger than proposed is fine | 0 would let nothing be sent; 127 keeps old and new 8-bit sequence numbers apart | layout: dissector; direction: assumption (wiomoc applies the phone's whole answer, the other two ignore it) |
| Maximum received packet length | the largest packet the phone accepts | at least 11 (header, one payload byte, checksum); this side's packets stay within it; packets up to the larger of the proposal and this value are accepted | a larger value than proposed is the phone's own capacity, not an error; accepting up to the larger value works under either reading of the field | layout: dissector; direction: assumption |
| Retransmission timeout | link timing, taken from the answer | positive outside ZeroACK mode | zero would resend constantly | wiomoc applies the answer's values; otherwise assumption |
| Cumulative acknowledgement timeout | how long a receiver may delay its acknowledgement | shorter than the retransmission timeout | otherwise packets are resent before their acknowledgement is due | assumption |
| Maximum retransmissions | resends before the link is given up | any value; 0 means the first timeout ends the link | a valid, if strict, choice | assumption |
| Maximum cumulative acknowledgements | packets a receiver may take before acknowledging | any value; this side acknowledges at the smallest of this value and both windows | acknowledging no later than either window keeps a sender from stalling, whichever window it applies | assumption |
| All four timing fields zero | ZeroACK mode | refused as unsupported (RST) | no acknowledgement-free mode is implemented | the dissector names this mode |
| Session table | the sessions on the link | IDs non-zero and unique; every answered session must be a proposed one with the same ID, type and version; exactly one control session (type 0); anything else is unsupported (RST) | the control session carries identification and authentication; a renamed or re-versioned session is a counter-proposal | public accessories send on the IDs they proposed (1 or 10) |

Proposal of the Bluetooth profile (`BluetoothProfile.linkProposal`): version 1, 5 outstanding,
4096-byte packets, retransmission after 2000 ms up to 30 times, acknowledgement within 22 ms or
after 3 packets, control session 10 version 1. The values come from the public implementations
(4096 bytes and session 10 version 1 from iap2-rs and wiomoc, 5 / 22 ms / 30 / 2000 ms from the
CarPlay Simulator test, 3 cumulative acknowledgements from all three), not from Apple.

## 3. Start-up and link cases

| Case | Behaviour | Basis |
|---|---|---|
| Detection | `FF 55 02 00 EE 10` sent first and repeated every retransmission timeout until the phone echoes the same six bytes; then SYN. Other answers are skipped (one damaged echo must not end the link); no echo before the retries run out ends the link as `DETECTION_FAILED`, naming the last answer | wiomoc waits for the echo; iap2-rs sends the same bytes; the CarPlay Simulator test marks them optional on its service |
| Accessory starts synchronization | supported | all three |
| Phone starts synchronization (SYN before the accessory's) | unsupported, link ends as `UNSUPPORTED` | assumption that this does not happen on the profile's transport |
| Both sides send SYN | unsupported, RST and `UNSUPPORTED` | assumption |
| Final ACK lost | the phone's repeated, identical SYN+ACK is acknowledged again | follows from the protocol |
| Phone sends SYN on a synchronized link | link ends as `RESYNC_REQUESTED`; the owner starts a new link, which gets a new control session; resynchronizing in place is unsupported | assumption |
| ACK after SYN | repeats the SYN's sequence number | CarPlay Simulator test; iap2-rs advances it instead |
| EAK received | the chosen recovery subset: its acknowledgement number counts, its list of out-of-order packets is not used, and all unacknowledged packets are resent on timeout | layout: dissector; iap2-rs resends from the list instead |
| EAK sent | never, part of the same chosen subset: out-of-order packets are dropped and answered with a plain acknowledgement, so this side never holds anything an EAK could report | a choice, not a requirement. Forum thread 26175 shows Apple's test system flagging an EAK sent although no packet had arrived out of order; that is about an unsolicited EAK, not a ban on EAK |
| RST, SLP from the phone | the link ends | dissector naming |

### Transport profile: Bluetooth RFCOMM

One profile is implemented for a start, iAP2 over Bluetooth RFCOMM, because it is the transport
both public accessories that were read use, and because it needs nothing from the radio's USB stack.

The wired route is unassessed for API 16 and this radio. In it the head unit is the USB host:
LIVI (§1), on Linux, sends an Apple vendor request so the phone re-enumerates with its CarPlay
configurations, selects one of them through sysfs, carries iAP2 over the `com.apple.carkit.service`
channel through USBMUX and Lockdown (with pairing records), and carries the media link over the
phone's NCM function, through the kernel driver or a user-space NCM host bridged to a TAP device.
These are research leads, not a plan to port, and not evidence that any of it works on Android.
What an assessment would have to settle on API 16, each on its own:

- configuration and alternate-setting changes: `UsbDeviceConnection.setConfiguration` and
  `setInterface` exist only from API 21; whether a control transfer (`SET_CONFIGURATION` sent by
  hand) is honoured with the kernel's own view of the device on this radio is unknown;
- descriptor access and parsing beyond the active configuration (`getRawDescriptors` is API 13,
  the configuration list of `UsbDevice` is API 21);
- which kernel drivers (for example `cdc_ncm`, `ipheth`) claim the phone's interfaces on this radio,
  and whether an app can claim them instead;
- USBMUX, Lockdown and pairing as an app, without the Linux daemons;
- the media link as an app without root: no TAP device, no interface configuration.

The Linux sysfs, TAP and daemon assumptions do not carry over to an Android app.

Start-up assumptions of the Bluetooth profile, all unverified: the RFCOMM channel is found through
SDP, to the phone's iAP2 service (`00000000-deca-fade-deca-deafdecacafe`) or by the phone connecting
to the accessory's (`00000000-deca-fade-deca-deafdecacaff`); once a byte stream exists the link
starts with detection. The debug "Transport check" (TEST-RESULTS §3d) tries exactly these: a
connect to the phone's UUID or a wait on the accessory's, then the detection bytes, with the socket
and the echo reported apart; its "iAP2 handshake" buttons go on from the same socket through
`Iap2Stream` (the connection on a blocking stream: a reader thread, the link owned and timed by
one thread, RST on stop or at the time limit) with a synthetic identity, and report the link, the
session, the exchange order and whatever the phone sends afterwards. Until an iPhone answers, both
UUIDs and the byte sequence stay hypotheses, and a timeout is no evidence either way. Not yet run
on the radio.

## 4. Identification payload (Bluetooth profile)

Encoding rules: text is UTF-8 followed by one zero byte (two sources); enumerations are one byte
(two sources); a flag is a parameter without a value (one source); message lists are big-endian
16-bit message IDs (two sources); a group is a nested parameter list (two sources).

| ID | Parameter | Encoding | Value sent | Basis |
|---|---|---|---|---|
| 0 | Name | text | configured, default "OpenAuto" | two sources |
| 1 | ModelIdentifier | text | configured | two sources |
| 2 | Manufacturer | text | configured | two sources |
| 3 | SerialNumber | text | configured | two sources |
| 4 | FirmwareVersion | text | configured | two sources |
| 5 | HardwareVersion | text | configured | two sources |
| 6 | MessagesSentByAccessory | message IDs | none: nothing is sent beyond identification and authentication | ID: two sources; empty value: assumption (wiomoc sends an empty parameter, iap2-rs leaves an empty list out) |
| 7 | MessagesReceivedFromDevice | message IDs | none | as 6 |
| 8 | PowerProvidingCapability | one byte (0 none, 2 advanced) | 0 | two sources (iap2-rs names it power source type) |
| 9 | MaximumCurrentDrawnFromDevice | 16-bit mA | 0 | two sources |
| 12 | CurrentLanguage | text | "en", the receiver's only language | two sources |
| 13 | SupportedLanguage | text, may repeat | "en", once | two sources |
| 17 | BluetoothTransportComponent | group: 0 identifier (16-bit), 1 name (text), 2 supports iAP2 (flag), 3 Bluetooth address (6 bytes) | identifier 0, "Bluetooth", flag, the receiver's address | wiomoc names it; iap2-rs sends the same layout as its parameter 17 |

Not sent, because the receiver implements none of it: 10 SupportedExternalAccessoryProtocol,
11 AppMatchTeamID, 14 SerialTransportComponent, 15 USBDeviceTransportComponent,
16 USBHostTransportComponent, 18 iAP2HIDComponent, 20 VehicleInformationComponent,
21 VehicleStatusComponent, 24 WirelessCarPlayTransportComponent.

Bounds (`BluetoothProfile.Identification`): text values are non-empty, contain no zero character
and take at most 63 UTF-8 bytes (a local limit); the address is 6 bytes and neither all zero nor
Android's `02:00:00:00:00:00` placeholder (Android 6 and newer hide the real address from apps).

Not known: which parameters are mandatory; whether empty message lists are accepted; whether a
phone accepts an accessory that offers no feature at all; the product plan requirement mentioned in
the forum thread (no parameter ID found).

## 5. Rejection and correction

IdentificationRejected lists the rejected parameters as parameters without values, using the
identification's IDs (one source: wiomoc). `ControlSession` treats a rejection as understood only
when it names at least one parameter and every named one was sent. It then asks the payload for a
correction and sends it only if the correction changes exactly the named parameters. Parameters are
compared by content: repeated parameters as a multiset and a group by its sub-parameters, so
reordering alone is no change, and any change to a parameter that was not named is refused. At most
three IdentificationInformation messages are sent per session (a local limit).

Corrections the Bluetooth profile supports: a rejected Name goes back to the default "OpenAuto"
when it was configured otherwise. Every other parameter is mandatory as sent; its rejection ends
the session as `IDENTIFICATION_REJECTED`, with the field and the reason in the failure detail.

## 6. Message order

The phone starts both exchanges. The order between them is provisional: identification first was
the original plan; the wiomoc write-up describes authentication first; the forum report (811397)
gives the outcome of both exchanges but no order. No source makes an order mandatory. Both orders are accepted and tested; each exchange keeps its
own order, and `ControlSession.firstExchange()` records which one the phone started.

## 7. Needs verification with a real iPhone

- Every assumption in §2 to §4, in particular the direction of the two receive-limit fields, the
  detection echo on RFCOMM, the session ID and version a phone accepts, empty message lists, and
  the identification an iPhone accepts at all.
- That an iPhone accepts this accessory's certificate and signature (needs a real accessory identity).
- ARMv7 behaviour on the radio, the RFCOMM transport, and everything of CarPlay above the control
  session, none of which exists yet.
- The wired route of §3, should it be considered: what the radio's Android sees of a connected
  iPhone (configurations, interfaces, drivers) and which of the steps listed there an app can do.
