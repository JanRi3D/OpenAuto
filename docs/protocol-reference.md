# Android Auto head-unit protocol ("GAL" / "AAP"): notes from open-source implementations

**Evidence class for the entire document:** everything below is *observed/implemented in* reverse-engineered,
unofficial open-source projects (aasdk, openauto, WirelessAndroidAutoDongle, aa-proxy-rs, headunit).
Nothing here is "documented by Google". Where a value is only an enum label chosen by the reverse-engineering
author (e.g. `UNK_1`), it is reproduced as-is.

Every fact is traceable to a URL in the "Sources" list. What was not checked is marked **NOT CHECKED**
and collected in the final "Gaps" section.

All sources were read on 2026-10-02 and 2026-10-03.
Branches: `f1xpl/aasdk` and `f1xpl/openauto` → `master`; `nisargjhaveri/WirelessAndroidAutoDongle` → `main`;
`manio/aa-proxy-rs` → `main`; `mikereidis/headunit` → `master`.

Companion files (verbatim PEM text from aasdk `src/Messenger/Cryptor.cpp`):

- `docs/reference/headunit-cert.pem`
- `docs/reference/headunit-key.pem`

---

## Sources

Abbreviations used below: `AASDK = https://raw.githubusercontent.com/f1xpl/aasdk/master`,
`OPENAUTO = https://raw.githubusercontent.com/f1xpl/openauto/master`,
`AAWG = https://raw.githubusercontent.com/nisargjhaveri/WirelessAndroidAutoDongle/main/aa_wireless_dongle/package/aawg/src`,
`AAPROXY = https://raw.githubusercontent.com/manio/aa-proxy-rs/main`,
`HEADUNIT = https://raw.githubusercontent.com/mikereidis/headunit/master`.

### aasdk — Messenger / framing
| URL | Contained |
|---|---|
| `AASDK/include/f1x/aasdk/Messenger/FrameHeader.hpp` | FrameHeader class, `getSizeOf() = 2` |
| `AASDK/src/Messenger/FrameHeader.cpp` | header byte parse/serialise |
| `AASDK/include/f1x/aasdk/Messenger/FrameType.hpp` | FrameType enum |
| `AASDK/include/f1x/aasdk/Messenger/EncryptionType.hpp` | EncryptionType enum |
| `AASDK/include/f1x/aasdk/Messenger/MessageType.hpp` | MessageType enum |
| `AASDK/include/f1x/aasdk/Messenger/FrameSize.hpp` | FrameSize class |
| `AASDK/src/Messenger/FrameSize.cpp` | size field encode/decode (big-endian) |
| `AASDK/include/f1x/aasdk/Messenger/ChannelId.hpp` | ChannelId enum |
| `AASDK/src/Messenger/ChannelId.cpp` | channelIdToString |
| `AASDK/include/f1x/aasdk/Messenger/Message.hpp`, `AASDK/src/Messenger/Message.cpp` | Message payload container |
| `AASDK/include/f1x/aasdk/Messenger/MessageId.hpp`, `AASDK/src/Messenger/MessageId.cpp` | 2-byte big-endian message id |
| `AASDK/include/f1x/aasdk/Messenger/Timestamp.hpp`, `AASDK/src/Messenger/Timestamp.cpp` | 8-byte big-endian timestamp |
| `AASDK/include/f1x/aasdk/Messenger/MessageOutStream.hpp` | `cMaxFramePayloadSize = 0x4000` |
| `AASDK/src/Messenger/MessageOutStream.cpp` | frame splitting + encryption on send |
| `AASDK/src/Messenger/MessageInStream.cpp` | frame reassembly + decryption on receive |
| `AASDK/src/Messenger/Cryptor.cpp` | OpenSSL BIO plumbing, inline certificate + private key PEM |
| `AASDK/src/Transport/SSLWrapper.cpp` | TLS method, connect state, verify mode |
| `AASDK/include/f1x/aasdk/Version.hpp` | `AASDK_MAJOR = 1`, `AASDK_MINOR = 1` |
| `AASDK/CMakeLists.txt` | (checked: no version definitions there) |

### aasdk — channels
| URL | Contained |
|---|---|
| `AASDK/src/Channel/Control/ControlServiceChannel.cpp` | control channel send/receive, version request/response byte layout |
| `AASDK/src/Channel/AV/VideoServiceChannel.cpp` | video channel handlers |
| `AASDK/src/Channel/AV/AudioServiceChannel.cpp` | audio channel handlers |
| `AASDK/src/Channel/AV/AVInputServiceChannel.cpp` | microphone channel handlers, timestamped media send |
| `AASDK/src/Channel/AV/MediaAudioServiceChannel.cpp` | binds AudioServiceChannel to `ChannelId::MEDIA_AUDIO` |
| `AASDK/src/Channel/Sensor/SensorServiceChannel.cpp` | sensor channel handlers |
| `AASDK/src/Channel/Input/InputServiceChannel.cpp` | input channel handlers |
| `AASDK/src/Channel/Bluetooth/BluetoothServiceChannel.cpp` | bluetooth channel handlers |
| `https://api.github.com/repos/f1xpl/aasdk/contents/src/Channel/AV` | directory listing |

### aasdk — protobuf (`AASDK/aasdk_proto/<name>`)
Directory listing: `https://api.github.com/repos/f1xpl/aasdk/contents/aasdk_proto`. Every `.proto`
file listed there was read (full list of 101 names is reproduced in §2–§7 as each message is transcribed).
Files read: AVChannelData, AVChannelMessageIdsEnum, AVChannelSetupRequestMessage, AVChannelSetupResponseMessage,
AVChannelSetupStatusEnum, AVChannelStartIndicationMessage, AVChannelStopIndicationMessage, AVInputChannelData,
AVInputOpenRequestMessage, AVInputOpenResponseMessage, AVMediaAckIndicationMessage, AVStreamTypeEnum,
AbsoluteInputEventData, AbsoluteInputEventsData, AccelData, AudioConfigData, AudioFocusRequestMessage,
AudioFocusResponseMessage, AudioFocusStateEnum, AudioFocusTypeEnum, AudioTypeEnum, AuthCompleteIndicationMessage,
BindingRequestMessage, BindingResponseMessage, BluetoothChannelData, BluetoothChannelMessageIdsEnum,
BluetoothPairingMethodEnum, BluetoothPairingRequestMessage, BluetoothPairingResponseMessage,
BluetoothPairingStatusEnum, ButtonCodeEnum, ButtonEventData, ButtonEventsData, ChannelDescriptorData,
ChannelOpenRequestMessage, ChannelOpenResponseMessage, CompassData, ControlMessageIdsEnum, DiagnosticsData,
DoorData, DrivingStatusData, DrivingStatusEnum, EnvironmentData, FuelLevelData, GPSLocationData, GearData, GearEnum,
GyroData, HVACData, HeadlightStatusEnum, IndicatorStatusEnum, InputChannelData, InputChannelMessageIdsEnum,
InputEventIndicationMessage, LightData, NavigationChannelData, NavigationFocusRequestMessage,
NavigationFocusResponseMessage, NavigationImageOptionsData, NightModeData, OdometerData, ParkingBrakeData,
PassengerData, PingRequestMessage, PingResponseMessage, RPMData, RelativeInputEventData, RelativeInputEventsData,
SensorChannelData, SensorChannelMessageIdsEnum, SensorData, SensorEventIndicationMessage, SensorStartRequestMessage,
SensorStartResponseMessage, SensorTypeEnum, ServiceDiscoveryRequestMessage, ServiceDiscoveryResponseMessage,
ShutdownReasonEnum, ShutdownRequestMessage, ShutdownResponseMessage, SpeedData, StatusEnum, SteeringWheelData,
TouchActionEnum, TouchConfigData, TouchEventData, TouchLocationData, VendorExtensionChannelData,
VersionResponseStatusEnum, VideoConfigData, VideoFPSEnum, VideoFocusIndicationMessage, VideoFocusModeEnum,
VideoFocusReasonEnum, VideoFocusRequestMessage, VideoResolutionEnum. (Plus `CMakeLists.txt` listed, not read.)

### aasdk — USB / AOA
| URL | Contained |
|---|---|
| `https://api.github.com/repos/f1xpl/aasdk/contents/include/f1x/aasdk/USB` | directory listing |
| `AASDK/src/USB/AccessoryModeProtocolVersionQuery.cpp` + `.hpp` | GET_PROTOCOL (51) |
| `AASDK/src/USB/AccessoryModeSendStringQuery.cpp` + `.hpp` | SEND_STRING (52) |
| `AASDK/src/USB/AccessoryModeStartQuery.cpp` + `.hpp` | START (53) |
| `AASDK/src/USB/AccessoryModeQueryChain.cpp` | query order |
| `AASDK/src/USB/AccessoryModeQueryFactory.cpp` | exact string values |
| `AASDK/include/f1x/aasdk/USB/AccessoryModeQuery.hpp` | `USB_TYPE_VENDOR = 0x40`, timeout 1000 ms |
| `AASDK/include/f1x/aasdk/USB/AccessoryModeSendStringType.hpp` | string index enum |
| `AASDK/include/f1x/aasdk/USB/AccessoryModeQueryType.hpp` | query type enum |
| `AASDK/src/USB/AOAPDevice.cpp` + `.hpp` | endpoint discovery, VID/PIDs |
| `AASDK/src/USB/USBHub.cpp` + `.hpp` | hotplug, VID/PID filter |
| `AASDK/src/USB/ConnectedAccessoriesEnumerator.cpp` | enumerate already-connected devices |
| `AASDK/src/USB/USBWrapper.cpp` | `fillControlSetup` = `libusb_fill_control_setup` |

### aasdk / openauto — repo metadata
| URL | Contained |
|---|---|
| `https://api.github.com/repos/f1xpl/aasdk/contents/` | root listing — **no LICENSE file present** |
| `https://api.github.com/repos/f1xpl/openauto/contents/` | root listing — **no LICENSE file present** |
| `AASDK/LICENSE`, `OPENAUTO/LICENSE` | HTTP 404 |
| `AASDK/Readme.md` | "### License / GNU GPLv3" |
| `OPENAUTO/Readme.md` | "### License / GNU GPLv3"; wireless-mode remark |

### openauto
| URL | Contained |
|---|---|
| `https://api.github.com/repos/f1xpl/openauto/contents/src/autoapp/Service` | directory listing |
| `OPENAUTO/src/autoapp/Service/AndroidAutoEntity.cpp` | startup sequence, ServiceDiscoveryResponse values, focus/shutdown handling |
| `OPENAUTO/src/autoapp/Service/AndroidAutoEntityFactory.cpp` | transport/cryptor/messenger wiring, Pinger 5000 ms |
| `OPENAUTO/src/autoapp/Service/Pinger.cpp` | ping timeout logic |
| `OPENAUTO/src/autoapp/Service/ServiceFactory.cpp` | service order, audio configs |
| `OPENAUTO/src/autoapp/Service/SensorService.cpp` | sensor declarations and events |
| `OPENAUTO/src/autoapp/Service/VideoService.cpp` | video setup response, focus indication |
| `OPENAUTO/src/autoapp/Service/AudioService.cpp` | audio channel behaviour |
| `OPENAUTO/src/autoapp/Service/MediaAudioService.cpp`, `SpeechAudioService.cpp`, `SystemAudioService.cpp` | channel binding |
| `OPENAUTO/src/autoapp/Service/AudioInputService.cpp` | microphone behaviour |
| `OPENAUTO/src/autoapp/Service/InputService.cpp` | input declarations and events |
| `OPENAUTO/src/autoapp/Service/BluetoothService.cpp` | bluetooth declarations and pairing response |
| `OPENAUTO/src/autoapp/Projection/InputDevice.cpp` | key → ButtonCode mapping (verbatim `handleKeyEvent`) |
| `OPENAUTO/src/autoapp/Configuration/Configuration.cpp` | defaults (`reset()`) |
| `OPENAUTO/src/autoapp/UI/ConnectDialog.cpp` | TCP connect to `<ip>:5277` |
| `OPENAUTO/src/autoapp/App.cpp`, `OPENAUTO/src/autoapp/autoapp.cpp` | app wiring (USB hub, TCP) |

### WirelessAndroidAutoDongle (nisargjhaveri)
| URL | Contained |
|---|---|
| `https://api.github.com/repos/nisargjhaveri/WirelessAndroidAutoDongle/contents/` (+ `/aa_wireless_dongle`, `/aa_wireless_dongle/package`, `/aa_wireless_dongle/package/aawg/src`, `/aa_wireless_dongle/package/aawg/src/proto`) | directory listings |
| `AAWG/bluetoothHandler.cpp` + `.h` | BlueZ profile registration (UUID, channel 8, role server), HSP |
| `AAWG/bluetoothProfiles.cpp` + `.h` | RFCOMM message framing, message ids, WifiStartRequest/WifiInfoResponse exchange |
| `AAWG/bluetoothCommon.h` | DBus typedefs |
| `AAWG/proxyHandler.cpp` | TCP server (listen), AA frame-length parsing |
| `AAWG/aawgd.cpp` | main loop |
| `AAWG/common.h`, `AAWG/common.cpp` | WifiInfo defaults (SSID, key, 10.0.0.1, port 5288) |
| `AAWG/proto/WifiStartRequest.proto`, `AAWG/proto/WifiInfoResponse.proto` | RFCOMM protobufs |
| `https://raw.githubusercontent.com/nisargjhaveri/WirelessAndroidAutoDongle/main/LICENSE` | MIT |
| `https://raw.githubusercontent.com/nisargjhaveri/WirelessAndroidAutoDongle/main/README.md` | setup flow (no protocol detail) |

### aa-proxy-rs (manio)
| URL | Contained |
|---|---|
| `https://api.github.com/repos/manio/aa-proxy-rs/contents/` and `/src`, `/src/protos` | directory listings |
| `AAPROXY/src/bluetooth.rs` | constants, UUIDs, MessageId enum, frame reader/writer (**only partly read; see Gaps**) |
| `AAPROXY/src/bt_helper.rs` | (checked: only `bluetoothctl` device management, no profile code) |
| `AAPROXY/src/config.rs` | `TCP_SERVER_PORT = 5288`, `TCP_DHU_PORT = 5277` |
| `AAPROXY/src/proxy.rs` | TCP listener binding on both ports |
| `AAPROXY/src/protos/WifiStartRequest.proto`, `WifiInfoResponse.proto`, `protos.proto` | protobufs |
| `AAPROXY/LICENSE` | GPL v2 |
| `AAPROXY/README.md` | profile-registration rationale quote, provenance |

### headunit (mikereidis)
| URL | Contained |
|---|---|
| `https://api.github.com/repos/mikereidis/headunit/contents/` and `/jni` | directory listings (`/hu` does not exist; C sources are under `/jni`) |
| `HEADUNIT/jni/hu_ssl.h` | a *different* self-signed test certificate/key (see §9) |
| `HEADUNIT/jni/hu_ssl.c` | TLS client mode, verify none, comments |
| `HEADUNIT/jni/hu_tcp.c` | `connect()` to `INADDR_LOOPBACK:5277` |
| `HEADUNIT/LICENSE` | AGPL v3 |
| `HEADUNIT/README.md` | (checked: no mention of head unit server / 5277) |

---

## 1. Frame format — observed/implemented in aasdk

### 1.1 Frame header (2 bytes)
Source: `FrameHeader.cpp`, `FrameType.hpp`, `EncryptionType.hpp`, `MessageType.hpp`, `ChannelId.hpp`.

```
byte 0 : channel id   (uint8, see ChannelId enum)
byte 1 : flags        = encryptionType | messageType | frameType   (bitwise OR)
```

Flag bit values (exact enum values):

| Enum | Value | Bit |
|---|---|---|
| `FrameType::MIDDLE` | `0` | bits 0–1 = 00 |
| `FrameType::FIRST` | `1 << 0` = `0x01` | bit 0 |
| `FrameType::LAST` | `1 << 1` = `0x02` | bit 1 |
| `FrameType::BULK` | `FIRST \| LAST` = `0x03` | bits 0–1 = 11 (single-frame message) |
| `MessageType::SPECIFIC` | `0` | bit 2 clear |
| `MessageType::CONTROL` | `1 << 2` = `0x04` | bit 2 |
| `EncryptionType::PLAIN` | `0` | bit 3 clear |
| `EncryptionType::ENCRYPTED` | `1 << 3` = `0x08` | bit 3 |

Parsing (verbatim logic): `frameType = byte1 & 0x03`, `encryptionType = byte1 & 0x08`, `messageType = byte1 & 0x04`.

`ChannelId` enum (implicit values, `ChannelId.hpp`):

| Name | Value |
|---|---|
| `CONTROL` | 0 |
| `INPUT` | 1 |
| `SENSOR` | 2 |
| `VIDEO` | 3 |
| `MEDIA_AUDIO` | 4 |
| `SPEECH_AUDIO` | 5 |
| `SYSTEM_AUDIO` | 6 |
| `AV_INPUT` | 7 |
| `BLUETOOTH` | 8 |
| `NONE` | 255 |

### 1.2 Frame size field (2 or 6 bytes), big-endian
Source: `FrameSize.cpp`, `FrameSize.hpp`, `MessageInStream.cpp`, `MessageOutStream.cpp`.

```
bytes 2..3 : uint16 BE  frame payload length (bytes that follow this size field in this frame)
bytes 4..7 : uint32 BE  total message size  — PRESENT ONLY WHEN frameType == FIRST (0x01)
```

- `FrameSize::getSizeOf(EXTENDED) = 6`, `getSizeOf(SHORT) = 2`.
- Receiver: after the 2-byte header it reads `FrameSize::getSizeOf(frameType == FIRST ? EXTENDED : SHORT)` bytes,
  then reads `frameSize.getSize()` (the uint16) payload bytes. The uint32 total size is decoded but not otherwise used.
- Sender (`setFrameSize`): for a FIRST frame it writes `FrameSize(payloadSize, totalSize)` where `payloadSize` is the
  size of *this frame's (possibly encrypted) payload* and `totalSize` is `message_->getPayload().size()` — i.e. the
  **plaintext** total message length. For MIDDLE/LAST/BULK frames only the 2-byte per-frame size is written.
- Independent confirmation in WirelessAndroidAutoDongle `proxyHandler.cpp` `readMessage`: reads a 4-byte header,
  `message_length = (buffer[2] << 8) + buffer[3]`, and `if ((buffer[1] & 0x03) == 0x01) message_length += 4;`
  (comment: "This means the header is 8 bytes long, we need to read four more bytes.").

### 1.3 Message splitting / max frame payload
Source: `MessageOutStream.hpp/.cpp`.

- `static constexpr size_t cMaxFramePayloadSize = 0x4000;` (16384).
- `if (message_->getPayload().size() >= cMaxFramePayloadSize)` → split (note `>=`); otherwise a single `BULK` frame.
- Split frames: first chunk `FIRST`, intermediate `MIDDLE`, final `LAST`; each chunk ≤ 0x4000 plaintext bytes.
- Receiver (`MessageInStream`): accumulates frames until `BULK` or `LAST`; if a frame for a different channel id arrives
  mid-message it rejects with `MESSENGER_INTERTWINED_CHANNELS` (aasdk does **not** interleave channels on receive).

### 1.4 Message payload layout / message id
Source: `Message.cpp`, `MessageId.cpp/.hpp`, every `*ServiceChannel.cpp`.

```
message payload = [ uint16 BE message id ][ protobuf-serialised body  OR raw bytes ]
```

- `MessageId::getData()` = `native_to_big(id)` → 2 bytes BE; `MessageId::getSizeOf() = 2`.
- Every channel's `messageHandler` does `MessageId messageId(message->getPayload()); payload = DataConstBuffer(message->getPayload(), 2)`.
- The frame-level `MessageType::CONTROL` flag (0x04) is set only on `CHANNEL_OPEN_RESPONSE` sent on a non-control
  channel (every `send*ChannelOpenResponse` uses `MessageType::CONTROL`); all other messages use `SPECIFIC`.
  Incoming `CHANNEL_OPEN_REQUEST` (id 0x0007) is dispatched inside each service channel's handler by id.

### 1.5 Encryption — what exactly is encrypted
Source: `MessageOutStream::compoundFrame`, `MessageInStream::receiveFramePayloadHandler`, `Cryptor.cpp`.

- Per frame: `compoundFrame(frameType, payloadBuffer)` where `payloadBuffer` is this frame's chunk of the *message
  payload* (which already starts with the 2-byte message id). If `EncryptionType::ENCRYPTED`, the chunk is fed to
  `SSL_write` and whatever appears in the write memory-BIO (TLS record(s)) becomes the frame payload; the 2-byte frame
  size then holds the **encrypted** length. So: encryption is applied **per frame**, over `[message id + body]` chunked
  at 0x4000 plaintext bytes; the 2-byte frame header and 2/6-byte size field are never encrypted.
- Receive: each encrypted frame payload is written to the read BIO and `SSL_read` is looped (`SSL_pending`) until
  drained; plaintext is appended to the message. Plain frames are appended directly.
- Which messages are PLAIN (from `ControlServiceChannel.cpp`): `VERSION_REQUEST`, `SSL_HANDSHAKE`, `AUTH_COMPLETE`,
  `PING_REQUEST`. ENCRYPTED: `SERVICE_DISCOVERY_RESPONSE`, `AUDIO_FOCUS_RESPONSE`, `SHUTDOWN_REQUEST`,
  `SHUTDOWN_RESPONSE`, `NAVIGATION_FOCUS_RESPONSE`, and every message on every non-control channel (all
  `*ServiceChannel.cpp` constructors use `EncryptionType::ENCRYPTED`).

---

## 2. Control channel (channel 0) — observed/implemented in aasdk

### 2.1 `ControlMessageIdsEnum.proto` (proto3, package `f1x.aasdk.proto.ids`, message `ControlMessage.Enum`)
```
NONE                       = 0x0000
VERSION_REQUEST            = 0x0001
VERSION_RESPONSE           = 0x0002
SSL_HANDSHAKE              = 0x0003
AUTH_COMPLETE              = 0x0004
SERVICE_DISCOVERY_REQUEST  = 0x0005
SERVICE_DISCOVERY_RESPONSE = 0x0006
CHANNEL_OPEN_REQUEST       = 0x0007
CHANNEL_OPEN_RESPONSE      = 0x0008
PING_REQUEST               = 0x000b
PING_RESPONSE              = 0x000c
NAVIGATION_FOCUS_REQUEST   = 0x000d
NAVIGATION_FOCUS_RESPONSE  = 0x000e
SHUTDOWN_REQUEST           = 0x000f
SHUTDOWN_RESPONSE          = 0x0010
VOICE_SESSION_REQUEST      = 0x0011
AUDIO_FOCUS_REQUEST        = 0x0012
AUDIO_FOCUS_RESPONSE       = 0x0013
```
(0x0009 and 0x000a are not defined in aasdk.)

### 2.2 VERSION_REQUEST / VERSION_RESPONSE — NOT protobuf (raw bytes)
Source: `ControlServiceChannel::sendVersionRequest`, `handleVersionResponse`, `Version.hpp`.

VERSION_REQUEST (head unit → phone), channel 0, PLAIN, SPECIFIC, 6-byte payload:
```
[0x00 0x01]            message id 0x0001 BE
[uint16 BE AASDK_MAJOR = 1]   -> 0x00 0x01
[uint16 BE AASDK_MINOR = 1]   -> 0x00 0x01
```
i.e. full frame on the wire: `00 03 00 06 00 01 00 01 00 01` (channel 0, BULK|PLAIN|SPECIFIC = 0x03, length 6).

VERSION_RESPONSE (phone → head unit) payload after the 2-byte id, parsed as an array of `uint16`:
```
[0] uint16 BE  major   (big_to_native applied)
[1] uint16 BE  minor   (big_to_native applied)
[2] uint16     status  -> cast to VersionResponseStatus::Enum WITHOUT byte swap (as written in aasdk)
```
If fewer than 3 elements are present, status defaults to `MISMATCH`.

`VersionResponseStatusEnum.proto` (`VersionResponseStatus.Enum`): `MATCH = 0; MISMATCH = 0xFFFF;`
(0xFFFF is byte-order symmetric, which is why the missing swap does not matter in practice.)

### 2.3 SSL_HANDSHAKE (0x0003)
Payload after the id = raw TLS handshake bytes (whatever `Cryptor::readHandshakeBuffer()` drained from the write BIO).
PLAIN. See §9.

### 2.4 Control protobuf messages (exact field numbers)

`StatusEnum.proto` (proto3, `enums.Status.Enum`):
```
OK   = 0
FAIL = 1
```

`AuthCompleteIndicationMessage.proto` (**proto2**) — `message AuthCompleteIndication`
```
1: required enums.Status.Enum status
```

`ServiceDiscoveryRequestMessage.proto` (proto3) — `message ServiceDiscoveryRequest`
```
4: string device_name
5: string device_brand
```
(fields 1–3 are not defined in aasdk.)

`ServiceDiscoveryResponseMessage.proto` (proto3) — `message ServiceDiscoveryResponse`
```
1:  repeated data.ChannelDescriptor channels
2:  string head_unit_name
3:  string car_model
4:  string car_year
5:  string car_serial
6:  bool   left_hand_drive_vehicle
7:  string headunit_manufacturer
8:  string headunit_model
9:  string sw_build
10: string sw_version
11: bool   can_play_native_media_during_vr
12: bool   hide_clock
```

`ChannelDescriptorData.proto` (proto3) — `message ChannelDescriptor` (package `f1x.aasdk.proto.data`)
```
1:  uint32 channel_id
2:  SensorChannel          sensor_channel
3:  AVChannel              av_channel
4:  InputChannel           input_channel
5:  AVInputChannel         av_input_channel
6:  BluetoothChannel       bluetooth_channel
8:  NavigationChannel      navigation_channel
12: VendorExtensionChannel vendor_extension_channel
```
(7, 9, 10, 11 not defined in aasdk.)

`ChannelOpenRequestMessage.proto` (proto3) — `message ChannelOpenRequest`
```
1: int32 priority
2: int32 channel_id
```

`ChannelOpenResponseMessage.proto` (**proto2**) — `message ChannelOpenResponse`
```
1: required enums.Status.Enum status
```

`PingRequestMessage.proto` (proto3) — `message PingRequest`
```
1: int64 timestamp
```

`PingResponseMessage.proto` (proto3) — `message PingResponse`
```
1: int64 timestamp
```

`NavigationFocusRequestMessage.proto` (proto3) — `message NavigationFocusRequest`
```
1: uint32 type
```

`NavigationFocusResponseMessage.proto` (proto3) — `message NavigationFocusResponse`
```
1: uint32 type
```

`ShutdownRequestMessage.proto` (proto3) — `message ShutdownRequest`
```
1: enums.ShutdownReason.Enum reason
```

`ShutdownResponseMessage.proto` (proto3) — `message ShutdownResponse` — **no fields**.

`ShutdownReasonEnum.proto` (`ShutdownReason.Enum`):
```
NONE = 0
QUIT = 1
```

`VoiceSessionRequestMessage.proto` — **NOT PRESENT in aasdk** (only the id `VOICE_SESSION_REQUEST = 0x0011` exists;
no `.proto` for it is in the `aasdk_proto` directory listing). **Does not exist in this source.**

`AudioFocusRequestMessage.proto` (proto3) — `message AudioFocusRequest`
```
1: enums.AudioFocusType.Enum audio_focus_type
```

`AudioFocusResponseMessage.proto` (proto3) — `message AudioFocusResponse`
```
1: enums.AudioFocusState.Enum audio_focus_state
```

`AudioFocusTypeEnum.proto` (`AudioFocusType.Enum`):
```
NONE           = 0
GAIN           = 1
GAIN_TRANSIENT = 2
GAIN_NAVI      = 3
RELEASE        = 4
```

`AudioFocusStateEnum.proto` (`AudioFocusState.Enum`):
```
NONE                         = 0
GAIN                         = 1
GAIN_TRANSIENT               = 2
LOSS                         = 3
LOSS_TRANSIENT_CAN_DUCK      = 4
LOSS_TRANSIENT               = 5
GAIN_MEDIA_ONLY              = 6
GAIN_TRANSIENT_GUIDANCE_ONLY = 7
```

### 2.5 Which control messages aasdk handles (receive side)
`ControlServiceChannel::messageHandler` switch: `VERSION_RESPONSE`, `SSL_HANDSHAKE`, `SERVICE_DISCOVERY_REQUEST`,
`AUDIO_FOCUS_REQUEST`, `SHUTDOWN_REQUEST`, `SHUTDOWN_RESPONSE`, `NAVIGATION_FOCUS_REQUEST`, `PING_RESPONSE`.
Anything else is logged "message not handled" and the receive is re-armed.

---

## 3. Channel descriptor sub-messages — observed/implemented in aasdk

`AVChannelData.proto` (proto3) — `message AVChannel`
```
1: enums.AVStreamType.Enum stream_type
2: enums.AudioType.Enum    audio_type
3: repeated AudioConfig    audio_configs
4: repeated VideoConfig    video_configs
5: bool                    available_while_in_call
```

`AVStreamTypeEnum.proto` (`AVStreamType.Enum`):
```
NONE  = 0
AUDIO = 1
VIDEO = 3
```
(2 is not defined.)

`AudioTypeEnum.proto` (`AudioType.Enum`):
```
NONE   = 0
SPEECH = 1
SYSTEM = 2
MEDIA  = 3
ALARM  = 4
```

`AudioConfigData.proto` (proto3) — `message AudioConfig`
```
1: uint32 sample_rate
2: uint32 bit_depth
3: uint32 channel_count
```

`VideoConfigData.proto` (proto3) — `message VideoConfig`
```
1: enums.VideoResolution.Enum video_resolution
2: enums.VideoFPS.Enum        video_fps
3: uint32 margin_width
4: uint32 margin_height
5: uint32 dpi
6: uint32 additional_depth
```

`VideoResolutionEnum.proto` (`VideoResolution.Enum`):
```
NONE   = 0
_480p  = 1
_720p  = 2
_1080p = 3
```

`VideoFPSEnum.proto` (`VideoFPS.Enum`):
```
NONE = 0
_30  = 1
_60  = 2
```

`InputChannelData.proto` (proto3) — `message InputChannel`
```
1: repeated uint32 supported_keycodes
2: TouchConfig     touch_screen_config
3: TouchConfig     touch_pad_config
```

`TouchConfigData.proto` (proto3) — `message TouchConfig`
```
1: uint32 width
2: uint32 height
```

`AVInputChannelData.proto` (proto3) — `message AVInputChannel`
```
1: enums.AVStreamType.Enum stream_type
2: AudioConfig             audio_config
3: bool                    available_while_in_call
```

`SensorChannelData.proto` (proto3) — `message SensorChannel`
```
1: repeated Sensor sensors
```

`SensorData.proto` (proto3) — `message Sensor`
```
1: enums.SensorType.Enum type
```

`SensorTypeEnum.proto` (`SensorType.Enum`):
```
NONE           = 0
LOCATION       = 1
COMPASS        = 2
CAR_SPEED      = 3
RPM            = 4
ODOMETER       = 5
FUEL_LEVEL     = 6
PARKING_BRAKE  = 7
GEAR           = 8
DIAGNOSTICS    = 9
NIGHT_DATA     = 10
ENVIRONMENT    = 11
HVAC           = 12
DRIVING_STATUS = 13
DEAD_RECONING  = 14
PASSENGER      = 15
DOOR           = 16
LIGHT          = 17
TIRE           = 18
ACCEL          = 19
GYRO           = 20
GPS            = 21
```

`BluetoothChannelData.proto` (proto3) — `message BluetoothChannel`
```
1: string adapter_address
2: repeated enums.BluetoothPairingMethod.Enum supported_pairing_methods
```

`BluetoothPairingMethodEnum.proto` (`BluetoothPairingMethod.Enum`):
```
NONE  = 0
UNK_1 = 1
A2DP  = 2
UNK_3 = 3
HFP   = 4
```

`NavigationChannelData.proto` (proto3) — `message NavigationChannel`
```
1: uint32 minimum_interval_ms
2: uint32 type
3: NavigationImageOptions image_options
```

`NavigationImageOptionsData.proto` (proto3) — `message NavigationImageOptions`
```
1: int32 width
2: int32 height
3: int32 colour_depth_bits
```

`MediaInfoChannelData.proto` — **NOT PRESENT in aasdk** (not in the directory listing).

`VendorExtensionChannelData.proto` (proto3) — `message VendorExtensionChannel`
```
1: string name
2: repeated string package_white_list
3: bytes data
```

---

## 4. AV channels (VIDEO=3, MEDIA_AUDIO=4, SPEECH_AUDIO=5, SYSTEM_AUDIO=6, AV_INPUT=7) — observed/implemented in aasdk

### 4.1 `AVChannelMessageIdsEnum.proto` (`AVChannelMessage.Enum`)
```
AV_MEDIA_WITH_TIMESTAMP_INDICATION = 0x0000
AV_MEDIA_INDICATION                = 0x0001
SETUP_REQUEST                      = 0x8000
START_INDICATION                   = 0x8001
STOP_INDICATION                    = 0x8002
SETUP_RESPONSE                     = 0x8003
AV_MEDIA_ACK_INDICATION            = 0x8004
AV_INPUT_OPEN_REQUEST              = 0x8005
AV_INPUT_OPEN_RESPONSE             = 0x8006
VIDEO_FOCUS_REQUEST                = 0x8007
VIDEO_FOCUS_INDICATION             = 0x8008
```

### 4.2 Protobufs

`AVChannelSetupRequestMessage.proto` (proto3) — `message AVChannelSetupRequest`
```
1: uint32 config_index
```

`AVChannelSetupResponseMessage.proto` (**proto2**) — `message AVChannelSetupResponse`
```
1: required enums.AVChannelSetupStatus.Enum media_status
2: required uint32 max_unacked
3: repeated uint32 configs
```

`AVChannelSetupStatusEnum.proto` (`AVChannelSetupStatus.Enum`):
```
NONE = 0
FAIL = 1
OK   = 2
```

`AVChannelStartIndicationMessage.proto` (proto3) — `message AVChannelStartIndication`
```
1: int32  session
2: uint32 config
```

`AVChannelStopIndicationMessage.proto` (proto3) — `message AVChannelStopIndication` — **no fields**.

`AVMediaAckIndicationMessage.proto` (**proto2**) — `message AVMediaAckIndication`
```
1: required int32  session
2: required uint32 value
```

`AVInputOpenRequestMessage.proto` (proto3) — `message AVInputOpenRequest`
```
1: bool  open
2: bool  anc
3: bool  ec
4: int32 max_unacked
```

`AVInputOpenResponseMessage.proto` (**proto2**) — `message AVInputOpenResponse`
```
1: required int32  session
2: required uint32 value
```

`VideoFocusRequestMessage.proto` (proto3) — `message VideoFocusRequest`
```
1: int32 disp_index
2: enums.VideoFocusMode.Enum   focus_mode
3: enums.VideoFocusReason.Enum focus_reason
```

`VideoFocusIndicationMessage.proto` (proto3) — `message VideoFocusIndication`
```
1: enums.VideoFocusMode.Enum focus_mode
2: bool unrequested
```

`VideoFocusModeEnum.proto` (`VideoFocusMode.Enum`):
```
NONE      = 0
FOCUSED   = 1
UNFOCUSED = 2
```

`VideoFocusReasonEnum.proto` (`VideoFocusReason.Enum`):
```
NONE  = 0
UNK_1 = 1
UNK_2 = 2
```

### 4.3 AV_MEDIA_WITH_TIMESTAMP_INDICATION payload layout
Source: `Timestamp.hpp/.cpp`, `VideoServiceChannel::handleAVMediaWithTimestampIndication`,
`AudioServiceChannel::handleAVMediaWithTimestampIndication`, `AVInputServiceChannel::sendAVMediaWithTimestampIndication`.

```
[uint16 BE 0x0000 message id][uint64 BE timestamp][raw media bytes ...]
```
- `Timestamp::ValueType` = `uint64_t`; encoded with `boost::endian::native_to_big` → **8 bytes big-endian**.
- Receive: requires `payload.size >= sizeof(uint64_t)` else `PARSE_PAYLOAD` error; the handler is given
  `timestamp.getValue()` and `DataConstBuffer(payload, offset 8)` (everything after the timestamp = raw H.264 / PCM).
- Send (microphone, `AVInputServiceChannel`): payload = id `0x0000` + `Timestamp(timestamp).getData()` + data.
  openauto's `AudioInputService` fills the timestamp with
  `duration_cast<microseconds>(high_resolution_clock::now().time_since_epoch()).count()` (microseconds).

### 4.4 AV_MEDIA_INDICATION (0x0001) handling
- aasdk: `case AV_MEDIA_INDICATION: eventHandler->onAVMediaIndication(payload);` — payload after the 2-byte id is
  passed through as raw media with **no timestamp**.
- openauto `VideoService::onAVMediaIndication(buffer)`: `videoOutput_->write(0, buffer)` then ACK `{session, value 1}`.
- openauto `AudioService::onAVMediaIndication(buffer)`: `this->onAVMediaWithTimestampIndication(0, buffer)`.

### 4.5 Receive-side dispatch per channel class
- `VideoServiceChannel` handles: `SETUP_REQUEST`, `START_INDICATION`, `AV_MEDIA_WITH_TIMESTAMP_INDICATION`,
  `AV_MEDIA_INDICATION`, `ControlMessage::CHANNEL_OPEN_REQUEST`, `VIDEO_FOCUS_REQUEST`. (No `STOP_INDICATION`.)
- `AudioServiceChannel` handles: `SETUP_REQUEST`, `START_INDICATION`, `STOP_INDICATION`,
  `AV_MEDIA_WITH_TIMESTAMP_INDICATION`, `AV_MEDIA_INDICATION`, `CHANNEL_OPEN_REQUEST`.
- `AVInputServiceChannel` handles: `SETUP_REQUEST`, `AV_INPUT_OPEN_REQUEST`, `AV_MEDIA_ACK_INDICATION`,
  `CHANNEL_OPEN_REQUEST`; sends `SETUP_RESPONSE`, `AV_INPUT_OPEN_RESPONSE`, `AV_MEDIA_WITH_TIMESTAMP_INDICATION`,
  `CHANNEL_OPEN_RESPONSE`.
- `MediaAudioServiceChannel` = `AudioServiceChannel(ChannelId::MEDIA_AUDIO)` (Speech/System analogous files exist in
  the listing; only MediaAudio was read).

---

## 5. Sensor channel (SENSOR = 2) — observed/implemented in aasdk

### 5.1 `SensorChannelMessageIdsEnum.proto` (`SensorChannelMessage.Enum`)
```
NONE                    = 0x0000
SENSOR_START_REQUEST    = 0x8001
SENSOR_START_RESPONSE   = 0x8002
SENSOR_EVENT_INDICATION = 0x8003
```

### 5.2 Protobufs

`SensorStartRequestMessage.proto` (proto3) — `message SensorStartRequestMessage`
```
1: enums.SensorType.Enum sensor_type
2: int64 refresh_interval
```

`SensorStartResponseMessage.proto` (**proto2**) — `message SensorStartResponseMessage`
```
1: required enums.Status.Enum status
```

`SensorEventIndicationMessage.proto` (proto3) — `message SensorEventIndication`
```
1:  repeated data.GPSLocation   gps_location
2:  repeated data.Compass       compass
3:  repeated data.Speed         speed
4:  repeated data.RPM           rpm
5:  repeated data.Odometer      odometer
6:  repeated data.FuelLevel     fuel_level
7:  repeated data.ParkingBrake  parking_brake
8:  repeated data.Gear          gear
9:  repeated data.Diagnostics   diagnostics
10: repeated data.NightMode     night_mode
11: repeated data.Environment   enviorment        (sic — spelled this way in aasdk)
12: repeated data.HVAC          hvac
13: repeated data.DrivingStatus driving_status
14: repeated data.SteeringWheel steering_wheel
15: repeated data.Passenger     passenger
16: repeated data.Door          door
17: repeated data.Light         light
19: repeated data.Accel         accel
20: repeated data.Gyro          gyro
```
(18 is not defined — matches `SensorType::TIRE = 18` having no data message.)

Sub-messages (all package `f1x.aasdk.proto.data`):

`DrivingStatusData.proto` (**proto2**) — `message DrivingStatus`
```
1: required int32 status
```

`DrivingStatusEnum.proto` (proto3, `enums.DrivingStatus.Enum`) — bit flags:
```
UNRESTRICTED      = 0
NO_VIDEO          = 1
NO_KEYBOARD_INPUT = 2
NO_VOICE_INPUT    = 4
NO_CONFIG         = 8
LIMIT_MESSAGE_LEN = 16
FULLY_RESTRICTED  = 31
```

`NightModeData.proto` (**proto2**) — `message NightMode`
```
1: required bool is_night
```

`GPSLocationData.proto` (proto3) — `message GPSLocation`
```
1: uint64 timestamp
2: int32  latitude
3: int32  longitude
4: uint32 accuracy
5: int32  altitude
6: int32  speed
7: int32  bearing
```

`CompassData.proto` — `message Compass`: `1: int32 bearing; 2: int32 pitch; 3: int32 roll`
`SpeedData.proto` — `message Speed`: `1: int32 speed; 2: bool cruise_engaged; 3: bool cruise_set_speed`
`RPMData.proto` — `message RPM`: `1: int32 rpm`
`OdometerData.proto` — `message Odometer`: `1: int32 total_mileage; 2: int32 trip_mileage`
`FuelLevelData.proto` — `message FuelLevel`: `1: int32 fuel_level; 2: int32 range; 3: bool low_fuel`
`ParkingBrakeData.proto` — `message ParkingBrake`: `1: bool parking_brake`
`GearData.proto` — `message Gear`: `1: enums.Gear.Enum gear`
`GearEnum.proto` (`Gear.Enum`): `NEUTRAL=0 FIRST=1 SECOND=2 THIRD=3 FOURTH=4 FIFTH=5 SIXTH=6 SEVENTH=7 EIGHTH=8 NINTH=9 TENTH=10 DRIVE=100 PARK=101 REVERSE=102`
`DiagnosticsData.proto` — `message Diagnostics`: `1: bytes diagnostics`
`EnvironmentData.proto` — `message Environment`: `1: int32 temperature; 2: int32 pressure; 3: int32 rain`
`HVACData.proto` — `message HVAC`: `1: int32 target_temperature; 2: int32 current_temperature`
`SteeringWheelData.proto` — `message SteeringWheel`: `1: int32 steering_angel (sic); 2: int32 wheel_speed`
`PassengerData.proto` — `message Passenger`: `1: bool passenger_present`
`DoorData.proto` — `message Door`: `1: bool hood_open; 2: bool boot_open; 3: repeated bool door_open`
`LightData.proto` — `message Light`: `1: enums.HeadlightStatus.Enum headlight; 2: enums.IndicatorStatus.Enum indicator; 3: bool hazard_light_on`
`HeadlightStatusEnum.proto` (`HeadlightStatus.Enum`): `STATE_0=0 STATE_1=1 STATE_2=2 STATE_3=3`
`IndicatorStatusEnum.proto` (`IndicatorStatus.Enum`): `STATE_0=0 STATE_1=1 STATE_2=2 STATE_3=3`
`AccelData.proto` — `message Accel`: `1: int32 acceleration_x; 2: int32 acceleration_y; 3: int32 acceleration_z`
`GyroData.proto` — `message Gyro`: `1: int32 rotation_speed_x; 2: int32 rotation_speed_y; 3: int32 rotation_speed_z`

### 5.3 Receive-side dispatch
`SensorServiceChannel::messageHandler` handles `SENSOR_START_REQUEST` and `ControlMessage::CHANNEL_OPEN_REQUEST`;
sends `SENSOR_EVENT_INDICATION`, `SENSOR_START_RESPONSE`, `CHANNEL_OPEN_RESPONSE`.

---

## 6. Input channel (INPUT = 1) — observed/implemented in aasdk

### 6.1 `InputChannelMessageIdsEnum.proto` (`InputChannelMessage.Enum`)
```
NONE                   = 0x0000
INPUT_EVENT_INDICATION = 0x8001
BINDING_REQUEST        = 0x8002
BINDING_RESPONSE       = 0x8003
```

### 6.2 Protobufs

`InputEventIndicationMessage.proto` (proto3) — `message InputEventIndication`
```
1: uint64 timestamp
2: int32  disp_channel
3: data.TouchEvent           touch_event
4: data.ButtonEvents         button_event
5: data.AbsoluteInputEvents  absolute_input_event
6: data.RelativeInputEvents  relative_input_event
```

`TouchEventData.proto` (proto3) — `message TouchEvent`
```
1: repeated data.TouchLocation touch_location
2: uint32 action_index
3: enums.TouchAction.Enum touch_action
```

`TouchLocationData.proto` (proto3) — `message TouchLocation`
```
1: uint32 x
2: uint32 y
3: uint32 pointer_id
```

`TouchActionEnum.proto` (`TouchAction.Enum`):
```
PRESS   = 0
RELEASE = 1
DRAG    = 2
```

`ButtonEventsData.proto` (proto3) — `message ButtonEvents`
```
1: repeated ButtonEvent button_events
```

`ButtonEventData.proto` (proto3) — `message ButtonEvent`
```
1: uint32 scan_code
2: bool   is_pressed
3: uint32 meta
4: bool   long_press
```

`AbsoluteInputEventsData.proto` — `message AbsoluteInputEvents`: `1: repeated AbsoluteInputEvent absolute_input_events`
`AbsoluteInputEventData.proto` — `message AbsoluteInputEvent`: `1: uint32 scan_code; 2: int32 value`
`RelativeInputEventsData.proto` — `message RelativeInputEvents`: `1: repeated RelativeInputEvent relative_input_events`
`RelativeInputEventData.proto` — `message RelativeInputEvent`: `1: uint32 scan_code; 2: int32 delta`

`BindingRequestMessage.proto` (proto3) — `message BindingRequest`
```
1: repeated int32 scan_codes
```

`BindingResponseMessage.proto` (**proto2**) — `message BindingResponse`
```
1: required enums.Status.Enum status
```

`ButtonCodeEnum.proto` (`ButtonCode.Enum`) — complete:
```
NONE         = 0x00
MICROPHONE_2 = 0x01
MENU         = 0x02
HOME         = 0x03
BACK         = 0x04
PHONE        = 0x05
CALL_END     = 0x06
UP           = 0x13
DOWN         = 0x14
LEFT         = 0x15
RIGHT        = 0x16
ENTER        = 0x17
MICROPHONE_1 = 0x54
TOGGLE_PLAY  = 0x55
NEXT         = 0x57
PREV         = 0x58
PLAY         = 0x7E
PAUSE        = 0x7F
SCROLL_WHEEL = 65536
```
Values openauto actually emits for media/voice keys (`InputDevice::handleKeyEvent`): `PLAY` (0x7E, key X),
`PAUSE` (0x7F, key C), `PREV` (0x58, key V / MediaPrevious), `TOGGLE_PLAY` (0x55, key B / MediaPlay),
`NEXT` (0x57, key N / MediaNext), `MICROPHONE_1` (0x54, key M), `PHONE` (0x05, key P), `CALL_END` (0x06, key O),
`HOME` (0x03, key H), `BACK` (0x04, Escape), `ENTER` (0x17), `UP/DOWN/LEFT/RIGHT`, `SCROLL_WHEEL` (keys 1/2).
openauto never emits `MICROPHONE_2` or `MENU`.

### 6.3 Event encoding used by openauto (`InputService.cpp`)
- Button: `InputEventIndication{timestamp = µs since epoch, button_event.button_events[0] = {scan_code = code, is_pressed = (PRESS), meta = 0, long_press = false}}`.
- Scroll wheel: `relative_input_event.relative_input_events[0] = {scan_code = SCROLL_WHEEL (65536), delta = -1 (LEFT) or +1}`.
- Touch: `touch_event = {touch_action = event.type, touch_location[0] = {x, y, pointer_id = 0}}` (no `action_index`, no `disp_channel` set).

### 6.4 Receive-side dispatch
`InputServiceChannel::messageHandler` handles `BINDING_REQUEST` and `ControlMessage::CHANNEL_OPEN_REQUEST`;
sends `INPUT_EVENT_INDICATION`, `BINDING_RESPONSE`, `CHANNEL_OPEN_RESPONSE`.

---

## 7. Bluetooth channel over AA (BLUETOOTH = 8) — observed/implemented in aasdk

### 7.1 `BluetoothChannelMessageIdsEnum.proto` (`BluetoothChannelMessage.Enum`)
```
NONE             = 0x0000
PAIRING_REQUEST  = 0x8001
PAIRING_RESPONSE = 0x8002
AUTH_DATA        = 0x8003
```

### 7.2 Protobufs

`BluetoothPairingRequestMessage.proto` (proto3) — `message BluetoothPairingRequest`
```
1: string phone_address
2: enums.BluetoothPairingMethod.Enum pairing_method
```

`BluetoothPairingResponseMessage.proto` (proto3) — `message BluetoothPairingResponse`
```
1: bool already_paired
2: enums.BluetoothPairingStatus.Enum status
```

`BluetoothPairingStatusEnum.proto` (`BluetoothPairingStatus.Enum`):
```
NONE = 0
OK   = 1
FAIL = 2
```

`BluetoothAuthenticationDataMessage.proto` — **NOT PRESENT in aasdk** (only the id `AUTH_DATA = 0x8003` exists; aasdk
does not define or handle a message for it). **Does not exist in this source.**

### 7.3 Dispatch
`BluetoothServiceChannel::messageHandler` handles `ControlMessage::CHANNEL_OPEN_REQUEST` and `PAIRING_REQUEST`;
sends `PAIRING_RESPONSE`, `CHANNEL_OPEN_RESPONSE`.

---

## 8. Startup sequence — observed/implemented in openauto (f1xpl/openauto, using aasdk)

### 8.1 Wiring (`AndroidAutoEntityFactory.cpp`, `ServiceFactory.cpp`)
- Transport = `USBTransport(AOAPDevice)` or `TCPTransport(TCPEndpoint)`; `SSLWrapper` → `Cryptor` → `cryptor->init()`
  (certificate/key loaded, TLS client state set) **before** anything is sent.
- `Messenger(MessageInStream, MessageOutStream)`; `Pinger(ioService, 5000)` (5000 ms).
- Services are created in this order (this is also the order of `ChannelDescriptor`s in the discovery response):
  1. `AudioInputService` — `QtAudioInput(1, 16, 16000)` → AV_INPUT (7)
  2. `MediaAudioService` (if `musicAudioChannelEnabled`, default true) — output `(2, 16, 48000)` → MEDIA_AUDIO (4)
  3. `SpeechAudioService` (if `speechAudioChannelEnabled`, default true) — output `(1, 16, 16000)` → SPEECH_AUDIO (5)
  4. `SystemAudioService` — output `(1, 16, 16000)` → SYSTEM_AUDIO (6)
  5. `SensorService` → SENSOR (2)
  6. `VideoService` → VIDEO (3)
  7. `BluetoothService` → BLUETOOTH (8) (descriptor added only if `bluetoothDevice_->isAvailable()`; default adapter type NONE → DummyBluetoothDevice)
  8. `InputService` → INPUT (1)
  (The constructor argument order for the audio classes is `(channelCount, sampleSize, sampleRate)` as used in
  `fillFeatures`: `set_sample_rate(getSampleRate())`, `set_bit_depth(getSampleSize())`, `set_channel_count(getChannelCount())`.)

### 8.2 Message order on connect (`AndroidAutoEntity.cpp`)
1. `start()`: every service calls `channel_->receive(...)` (arms receive on its channel); `schedulePing()`;
   **head unit sends `VERSION_REQUEST`** (PLAIN, 6 bytes, see §2.2); arms control receive.
2. Phone → `VERSION_RESPONSE`. If status == `MISMATCH` → quit. Otherwise `cryptor_->doHandshake()`
   (OpenSSL produces ClientHello into the write BIO; returns false = `SSL_ERROR_WANT_READ`) and head unit sends
   **`SSL_HANDSHAKE`** (PLAIN) with `readHandshakeBuffer()` (the ClientHello).
3. Phone → `SSL_HANDSHAKE` (server flight). Head unit `writeHandshakeBuffer(payload)` → `doHandshake()`:
   - not finished → send another `SSL_HANDSHAKE` with the next client flight (loop);
   - finished → send **`AUTH_COMPLETE`** (PLAIN) `AuthCompleteIndication{status = OK (0)}`.
4. Phone → `SERVICE_DISCOVERY_REQUEST{device_name, device_brand}` (encrypted from here on).
   Head unit sends **`SERVICE_DISCOVERY_RESPONSE`** (ENCRYPTED) with exactly these values:
   ```
   channels                       = one ChannelDescriptor per service, in the §8.1 order
   head_unit_name                 = "OpenAuto"
   car_model                      = "Universal"
   car_year                       = "2018"
   car_serial                     = "20180301"
   left_hand_drive_vehicle        = (HandednessOfTrafficType == LEFT_HAND_DRIVE)   // default true
   headunit_manufacturer          = "f1x"
   headunit_model                 = "OpenAuto Autoapp"
   sw_build                       = "1"
   sw_version                     = "1.0"
   can_play_native_media_during_vr= false
   hide_clock                     = !showClock                                      // default false
   ```
   Per-channel descriptor contents:
   - AV_INPUT (7): `av_input_channel{stream_type = AUDIO(1), audio_config{sample_rate 16000, bit_depth 16, channel_count 1}}` (available_while_in_call not set).
   - MEDIA_AUDIO (4): `av_channel{stream_type AUDIO(1), audio_type MEDIA(3), available_while_in_call true, audio_configs[{48000, 16, 2}]}`.
   - SPEECH_AUDIO (5): `av_channel{AUDIO, SPEECH(1), true, audio_configs[{16000, 16, 1}]}`.
   - SYSTEM_AUDIO (6): `av_channel{AUDIO, SYSTEM(2), true, audio_configs[{16000, 16, 1}]}`.
   - SENSOR (2): `sensor_channel{sensors[{DRIVING_STATUS(13)}, {NIGHT_DATA(10)}]}` (LOCATION is commented out).
   - VIDEO (3): `av_channel{stream_type VIDEO(3), available_while_in_call true, video_configs[{video_resolution, video_fps, margin_width, margin_height, dpi}]}`;
     defaults from `Configuration::reset()`: `_480p (1)`, `_60 (2)`, margins `0,0`, dpi `140`. `additional_depth` not set.
   - BLUETOOTH (8): `bluetooth_channel{adapter_address = local adapter address}` (no `supported_pairing_methods`).
   - INPUT (1): `input_channel{supported_keycodes = configured button codes, touch_screen_config{width, height} = touchscreen geometry}`
     (touchscreen geometry = primary screen geometry; video geometry used for scaling is 800×480 for 480p, 1280×720, 1920×1080).
5. Phone → `CHANNEL_OPEN_REQUEST{priority, channel_id}` on each declared channel (frame flag CONTROL). Each service
   replies **`CHANNEL_OPEN_RESPONSE{status}`** on that channel with `MessageType::CONTROL`:
   sensor/input/bluetooth → always `OK`; video → `OK` iff `videoOutput_->open()`; audio → `OK` iff `audioOutput_->open()`;
   mic → `OK` iff `audioInput_->open()`.
6. Sensor channel: phone → `SENSOR_START_REQUEST{sensor_type, refresh_interval}`; head unit →
   **`SENSOR_START_RESPONSE{status OK}`**, then (after the send completes):
   - `sensor_type == DRIVING_STATUS` → **`SENSOR_EVENT_INDICATION{driving_status = [{status = UNRESTRICTED (0)}]}`**
   - `sensor_type == NIGHT_DATA` → **`SENSOR_EVENT_INDICATION{night_mode = [{is_night = false}]}`**
   - anything else → no event.
7. Video channel: phone → `SETUP_REQUEST{config_index}`; head unit →
   **`SETUP_RESPONSE{media_status = OK (2) iff videoOutput_->init() else FAIL (1), max_unacked = 1, configs = [0]}`**,
   and immediately after that send completes → **`VIDEO_FOCUS_INDICATION{focus_mode = FOCUSED (1), unrequested = false}`**.
   The same `VideoFocusIndication{FOCUSED, false}` is also sent in reply to every incoming `VIDEO_FOCUS_REQUEST`.
   `START_INDICATION{session, config}` → store `session_`. Each `AV_MEDIA_WITH_TIMESTAMP_INDICATION` / `AV_MEDIA_INDICATION`
   → write to decoder and reply **`AV_MEDIA_ACK_INDICATION{session = session_, value = 1}`**.
8. Audio channels (media/speech/system): `SETUP_REQUEST` → `SETUP_RESPONSE{OK, max_unacked 1, configs [0]}`;
   `START_INDICATION` → `session_ = session; audioOutput_->start()`; `STOP_INDICATION` → `session_ = -1; suspend()`;
   each media indication → `AV_MEDIA_ACK_INDICATION{session_, 1}`.
9. Microphone (AV_INPUT): `SETUP_REQUEST` → `SETUP_RESPONSE{OK, 1, [0]}`. `AV_INPUT_OPEN_REQUEST{open, anc, ec, max_unacked}`:
   - `open == true` → start capture → **`AV_INPUT_OPEN_RESPONSE{session = session_ (initialised 0), value = 0}`**; on failure `value = 1`.
     Then every captured buffer is sent as `AV_MEDIA_WITH_TIMESTAMP_INDICATION` (µs timestamp, see §4.3).
   - `open == false` → stop capture → `AV_INPUT_OPEN_RESPONSE{session_, value = 0}`.
   - incoming `AV_MEDIA_ACK_INDICATION` is consumed without action.
10. Input: phone → `BINDING_REQUEST{scan_codes}`; head unit checks every code is in the configured list →
    **`BINDING_RESPONSE{OK}`** (and starts the input device) or `{FAIL}` if any code unsupported.
11. Bluetooth: phone → `PAIRING_REQUEST{phone_address, pairing_method}`; head unit →
    **`PAIRING_RESPONSE{already_paired = isPaired(phone_address), status = OK if paired else FAIL}`**.
12. Control-channel requests during the session:
    - `AUDIO_FOCUS_REQUEST{audio_focus_type}` → `AUDIO_FOCUS_RESPONSE{audio_focus_state = LOSS (3) if type == RELEASE (4) else GAIN (1)}`.
    - `NAVIGATION_FOCUS_REQUEST{type}` → `NAVIGATION_FOCUS_RESPONSE{type = 2}` (constant).
    - `SHUTDOWN_REQUEST{reason}` → `SHUTDOWN_RESPONSE{}` then quit. `SHUTDOWN_RESPONSE` received → quit.
    - Ping: every 5000 ms head unit sends `PING_REQUEST{}` (PLAIN, `timestamp` **not set**); `PING_RESPONSE` → `pong()`.
      `Pinger::onTimerExceeded`: if `pingsCount_ - pongsCount_ > 1` → error → quit ("ping timer exceeded").
    - Any channel error on the control channel → quit.

---

## 9. TLS — observed/implemented in aasdk (and headunit)

### 9.1 aasdk (`Cryptor.cpp`, `SSLWrapper.cpp`)
- **Head unit is the TLS client**: `SSLWrapper::setConnectState` → `SSL_set_connect_state(ssl); SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr);`
- Method: `TLS_client_method()` (OpenSSL ≥ 1.1.0) / `TLSv1_2_client_method()` (older).
- Context: `SSL_CTX_new(method)`, `SSL_CTX_use_certificate(ctx, cert)`, `SSL_CTX_use_PrivateKey(ctx, key)`.
  Certificate read with `PEM_read_bio_X509_AUX`, key with `PEM_read_bio_PrivateKey` (no passphrase).
- BIOs: two memory BIOs (`BIO_s_mem`), `SSL_set_bio(ssl, readBIO, writeBIO)`, both `BIO_set_write_buf_size(..., 1024*20)`.
- `Cryptor::doHandshake()`: `SSL_do_handshake` → `SSL_get_error`: `SSL_ERROR_WANT_READ` → return false (more flights
  needed); `SSL_ERROR_NONE` → `isActive_ = true`, return true; anything else → throw `SSL_HANDSHAKE`.
- Shuttling: `readHandshakeBuffer()` drains the write BIO (`BIO_ctrl_pending` + `BIO_read`) → sent as the payload of a
  control-channel `SSL_HANDSHAKE` (0x0003) PLAIN message. Incoming `SSL_HANDSHAKE` payload → `writeHandshakeBuffer()`
  → `BIO_write` into the read BIO, then `doHandshake()` again. (Sequence in §8.2 steps 2–3.)
- Data path after handshake: `encrypt()` = `SSL_write` all plaintext then drain write BIO; `decrypt()` = `BIO_write`
  ciphertext then `SSL_read` while `SSL_pending() > 0`.
- **Where the certificate/private key live:** inline string constants `Cryptor::cCertificate` and `Cryptor::cPrivateKey`
  at the bottom of `AASDK/src/Messenger/Cryptor.cpp`. They are reproduced verbatim in `docs/reference/headunit-cert.pem`
  and `docs/reference/headunit-key.pem`.
- **Repo license:** GNU GPLv3 (Readme) / "GPL version 3 or (at your option) any later version" (file header).
- **Stated provenance of the credentials in the aasdk repo:** none. Neither `Cryptor.cpp`, `Readme.md` nor the
  file header says where the certificate/key came from. Decoding the saved PEM locally with `openssl x509` gives:
  `subject=C=JP, ST=Tokyo, L=Hachioji, O=JVC Kenwood, OU=01`; `issuer=C=US, ST=California, L=Mountain View,
  O=Google Automotive Link`; `notBefore=Jul 4 07:00:00 2014 GMT`; `notAfter=Apr 29 21:28:38 2045 GMT`; `serial=1B`.
  `openssl rsa -check` → "RSA key ok"; certificate and key moduli match (MD5 `dd5e1d97cc7337cb8983eb6d0b227af4`).
  (These decoded fields are derived from the PEM, not from any statement in the repo.)

### 9.2 headunit (mikereidis, `jni/hu_ssl.c`, `jni/hu_ssl.h`)
- Also TLS client: `hu_ssl_method = TLSv1_2_client_method(); ... SSL_set_connect_state(hu_ssl_ssl); SSL_set_verify(hu_ssl_ssl, SSL_VERIFY_NONE, NULL);`
  A separate JNI "SslWrapperNative"/"VmNativeSslInfo" section uses `SSL_set_accept_state` (server mode) with a filter BIO.
- Comments in `hu_ssl.c` (verbatim): `// chan:0/AA_CH_CTR   flags:first+last    msg_type:SSL`,
  `// HANDSHAKE: ServerHello+ServerCert, ClientKeyExchange+ChangeCipher`, `// !!!! SSL_is_init_finished() does not work for some reason !!!`.
- `hu_ssl.h` contains, under `#ifdef MR_SSL_INTERNAL`, a **different** certificate/key pair (`hu_ssl_cert_mr_buf` /
  `hu_ssl_pkey_mr_buf`, comment `// 2048 bits,  Signature Algorithm: sha256WithRSAEncryption`). Its PEM text was read;
  it is a self-signed "Internet Widgits Pty Ltd" certificate (base64 visibly contains that string; validity lines
  `150520011001Z`/`150521011001Z`). It is **not** the Google-Automotive-Link-issued one above and was **not** saved to
  `docs/reference/` (only the credentials aasdk uses are kept). The `#define cert_buf hu_ssl_cert_mr_buf`
  indicates the non-`MR_SSL_INTERNAL` build expects `cert_buf`/`pkey_buf` from elsewhere — that source was **NOT CHECKED**.
- headunit license: `LICENSE` = "GNU AFFERO GENERAL PUBLIC LICENSE Version 3, 19 November 2007" (also a file
  `COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt` in the root listing, not read).

---

## 10. USB / AOA (Android Open Accessory) — observed/implemented in aasdk

### 10.1 Control requests (`AccessoryMode*Query.cpp/.hpp`, `AccessoryModeQuery.hpp`, `USBWrapper.cpp`)
`fillControlSetup` = `libusb_fill_control_setup(buffer, bmRequestType, bRequest, wValue, wIndex, wLength)`;
`USB_TYPE_VENDOR = 0x40`; transfer timeout `cTransferTimeoutMs = 1000`.

| Query | bmRequestType | bRequest | wValue | wIndex | wLength / data |
|---|---|---|---|---|---|
| Get protocol | `LIBUSB_ENDPOINT_IN \| 0x40` (= 0xC0) | `ACC_REQ_GET_PROTOCOL = 51` | 0 | 0 | 2 (reads `uint16 ProtocolVersion`) |
| Send string | `LIBUSB_ENDPOINT_OUT \| 0x40` (= 0x40) | `ACC_REQ_SEND_STRING = 52` | 0 | string index (below) | string bytes + `'\0'` |
| Start | `LIBUSB_ENDPOINT_OUT \| 0x40` | `ACC_REQ_START = 53` | 0 | 0 | 0 |

Protocol version accepted: `protocolVersion == 1 || protocolVersion == 2` (read as native `uint16` at data_[8]);
otherwise `USB_AOAP_PROTOCOL_VERSION` error.

String indices (`AccessoryModeSendStringType`, implicit values): `MANUFACTURER = 0, MODEL = 1, DESCRIPTION = 2, VERSION = 3, URI = 4, SERIAL = 5`.

Exact strings sent (`AccessoryModeQueryFactory.cpp`):
```
MANUFACTURER : "Android"
MODEL        : "Android Auto"
DESCRIPTION  : "Android Auto"
VERSION      : "2.0.1"
URI          : "https://f1xstudio.com"
SERIAL       : "HU-AAAAAA001"
```

Order (`AccessoryModeQueryChain.cpp`): `PROTOCOL_VERSION → SEND_MANUFACTURER → SEND_MODEL → SEND_DESCRIPTION →
SEND_VERSION → SEND_URI → SEND_SERIAL → START`. After START the chain resolves with the device handle; the phone
re-enumerates with an accessory PID and `USBHub` picks it up.

### 10.2 VID / PID filter (`USBHub.hpp`, `AOAPDevice.hpp`)
```
cGoogleVendorId = 0x18D1
cAOAPId         = 0x2D00
cAOAPWithAdbId  = 0x2D01
```
`isAOAPDevice` = `idVendor == 0x18D1 && (idProduct == 0x2D00 || idProduct == 0x2D01)`.

### 10.3 Hotplug / enumeration (`USBHub.cpp`, `ConnectedAccessoriesEnumerator.cpp`)
- `libusb_hotplug_register_callback(LIBUSB_HOTPLUG_EVENT_DEVICE_ARRIVED, NO_FLAGS, MATCH_ANY, MATCH_ANY, MATCH_ANY, ...)`.
- On arrival: get descriptor, `libusb_open`; if AOAP → resolve the hub promise with the handle (→ `AOAPDevice::create`);
  else sleep 1000 ms (`////////// Workaround for VMware`) and start a query chain on it.
- `ConnectedAccessoriesEnumerator::enumerate`: `libusb_get_device_list`, open each device in turn and run the query
  chain; stops at the first success.

### 10.4 Endpoint discovery (`AOAPDevice.cpp`)
- `libusb_get_config_descriptor(device, 0)`; require `bNumInterfaces > 0`; take `interface[0]`; require
  `num_altsetting > 0`; take `altsetting[0]`; require `bNumEndpoints >= 2` else `USB_INVALID_DEVICE_ENDPOINTS`.
- `libusb_claim_interface(handle, bInterfaceNumber)`.
- If `(endpoint[0].bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN` → IN = endpoint[0], OUT = endpoint[1];
  otherwise IN = endpoint[1], OUT = endpoint[0]. Destructor cancels transfers and releases the interface.

---

## 11. Wireless — observed/implemented in WirelessAndroidAutoDongle (aawgd) and aa-proxy-rs

### 11.1 Bluetooth RFCOMM service (WirelessAndroidAutoDongle `bluetoothHandler.cpp`)
Constants (verbatim):
```
AAWG_PROFILE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
HSP_AG_UUID       = "00001112-0000-1000-8000-00805f9b34fb"
HSP_HS_UUID       = "00001108-0000-1000-8000-00805f9b34fb"
ADAPTER_ALIAS_PREFIX        = "WirelessAADongle-"
ADAPTER_ALIAS_DONGLE_PREFIX = "AndroidAuto-Dongle-"
```
Registration with BlueZ `org.bluez.ProfileManager1.RegisterProfile` (verbatim options):
```
registerProfile(AAWG_PROFILE_OBJECT_PATH, AAWG_PROFILE_UUID, {
    {"Name", DBus::Variant("AA Wireless")},
    {"Role", DBus::Variant("server")},
    {"Channel", DBus::Variant(uint16_t(8))},
});
```
→ the dongle/head unit is the **RFCOMM server on channel 8**; the **phone opens the RFCOMM connection** (BlueZ calls the
dongle's `Profile1.NewConnection(path, fd, props)`, after which the dongle immediately runs the message exchange on `fd`).
aa-proxy-rs uses the same UUID: `pub const AAWG_PROFILE_UUID: Uuid = Uuid::from_u128(0x4de17a0052cb11e6bdf40800200c9a66);`.

How the phone is prompted to connect (`connectDevice`): for each known BlueZ device the dongle calls
`org.bluez.Device1.ConnectProfile(isDongleMode ? "" : HSP_AG_UUID)` — i.e. in PHONE_FIRST/USB_FIRST modes it connects
*to the phone's HSP Audio Gateway*; in DONGLE_MODE it connects with an empty UUID and also runs a BLE advertisement
(`type "peripheral"`, `serviceUUIDs [AAWG_PROFILE_UUID]`, `localName = adapter alias`). Retries every 20 s until the
phone connects over TCP (`stopConnectWithRetry` is called from `proxyHandler.cpp` when the TCP client is accepted).

### 11.2 Does the dongle register HFP/HSP and why
- WirelessAndroidAutoDongle registers an **HSP Headset (HS) profile** (`HSP_HS_UUID 00001108-…`, name `"HSP HS"`,
  object path `/com/aawgd/bluetooth/hsp`) whenever `ConnectionStrategy != DONGLE_MODE`; its `NewConnection` handler only
  logs. The code comment is just `// Register HSP Handset profile` — **no rationale is given in the dongle source**.
  It does not register HFP.
- aa-proxy-rs README (verbatim quote): *"Bluetooth: Register two profiles: One for Android Auto, One for a fake headset
  (to trick the phone into recognizing a wireless Android Auto head unit)."* aa-proxy-rs `bluetooth.rs` defines
  `HSP_HS_UUID`/`HSP_AG_UUID` and, for its MITM "phone-like" SDP emulation, `SDP_HANDSFREE_AG_UUID` with the comment
  *"The minimal set mirrors the common phone-facing capabilities seen on Android phones: HFP Audio Gateway, PBAP server,
  MAP server, OPP, A2DP source, AVRCP target."* The actual `bluer::rfcomm::Profile{...}` registration block in
  `bluetooth.rs` was not read in full: **NOT CHECKED**.

### 11.3 RFCOMM message framing (`bluetoothProfiles.cpp` `SendMessage`/`ReadMessage`; aa-proxy-rs `send_proxy_frame_raw`/`read_proxy_frame`)
```
[uint16 BE payload length][uint16 BE message id][protobuf payload (length bytes)]
```
Verbatim (dongle): `networkShort = htons(messageSize); memcpy(buffer, ...); networkShort = htons(messageId); memcpy(buffer + 2, ...); message->SerializeToArray(buffer + 4, messageSize);`
Verbatim (aa-proxy-rs): `const HEADER_LEN: usize = 4;` … `packet.extend_from_slice(&(payload.len() as u16).to_be_bytes()); packet.extend_from_slice(&message_id.to_be_bytes());`
Read side: `len = u16::from_be_bytes([header[0], header[1]]); message_id = u16::from_be_bytes([header[2], header[3]]);`

### 11.4 Message ids
Dongle `enum class MessageId` (verbatim):
```
Invalid             = -1
WifiStartRequest    = 1
WifiInfoRequest     = 2
WifiInfoResponse    = 3
WifiVersionRequest  = 4
WifiVersionResponse = 5
WifiConnectStatus   = 6
WifiStartResponse   = 7
```
aa-proxy-rs `enum MessageId` / `ProxyMessageId` (verbatim) adds:
```
WifiPingRequest  = 8
WifiPingResponse = 9
WifiSetupInfo    = 11
```
(10 is not defined.)

### 11.5 Exchange performed by the dongle (`AAWirelessLauncher::launch`, verbatim order)
1. Dongle → `WifiStartRequest{ip_address, port}` (id 1).
2. Dongle reads one message; if it is not `WifiInfoRequest` (id 2) → log "Expected WifiInfoRequest, got …. Abort." and return.
3. Dongle → `WifiInfoResponse{ssid, key, bssid, security_mode, access_point_type}` (id 3).
4. Dongle reads **two** more messages and discards their contents (`ReadMessage(); ReadMessage();`) — it does not
   parse `WifiVersionRequest`/`WifiConnectStatus`/`WifiStartResponse`.

### 11.6 Protobufs (identical text in dongle `proto/` and aa-proxy-rs `src/protos/`; both `syntax = "proto2"; option optimize_for = LITE_RUNTIME;`)

`WifiStartRequest.proto` — `message WifiStartRequest`
```
1: required string ip_address
2: required int32  port
```

`WifiInfoResponse.proto`
```
enum AccessPointType {
    STATIC  = 0;
    DYNAMIC = 1;
}

enum SecurityMode {
    UNKNOWN_SECURITY_MODE = 0;
    OPEN                  = 1;
    WEP_64                = 2;
    WEP_128               = 3;
    WPA_PERSONAL          = 4;
    WPA2_PERSONAL         = 8;
    WPA_WPA2_PERSONAL     = 12;
    WPA_ENTERPRISE        = 20;
    WPA2_ENTERPRISE       = 24;
    WPA_WPA2_ENTERPRISE   = 28;
}

message WifiInfoResponse {
    1: required string          ssid
    2: required string          key
    3: required string          bssid
    4: required SecurityMode    security_mode
    5: required AccessPointType access_point_type
}
```
`WifiInfoRequest`, `WifiVersionRequest`, `WifiVersionResponse`, `WifiConnectStatus`, `WifiStartResponse` — **no .proto
definitions exist in either repo** (neither project parses their bodies; the dongle only reads and discards them).
**Not defined in these sources.**

Additionally aa-proxy-rs `src/protos/protos.proto` defines an unrelated in-session (AA-channel) message set — reproduced
for completeness (verbatim, proto2):
```
message WifiProjectionService      { optional string car_wifi_bssid = 1; }
message WifiCredentialsRequest     {}
message WifiCredentialsResponse    { optional string car_wifi_password = 1; optional WifiSecurityMode car_wifi_security_mode = 2;
                                     optional string car_wifi_ssid = 3; repeated int32 supported_wifi_channels = 4;
                                     optional A_AccessPointType access_point_type = 5; }
message VersionRequestOptions      { optional int64 snapshot_version = 1; }
message VersionResponseOptions     { optional ConnectionConfiguration connection_configuration = 1; }
enum WifiSecurityMode { A_UNKNOWN_SECURITY_MODE=0; A_OPEN=1; A_WEP_64=2; A_WEP_128=3; A_WPA_PERSONAL=4; A_WPA2_PERSONAL=5;
                        A_WPA_WPA2_PERSONAL=6; A_WPA_ENTERPRISE=7; A_WPA2_ENTERPRISE=8; A_WPA_WPA2_ENTERPRISE=9; }
enum A_AccessPointType { A_STATIC=0; A_DYNAMIC=1; }
enum WifiProjectionMessageId { WIFI_MESSAGE_CREDENTIALS_REQUEST = 32769; WIFI_MESSAGE_CREDENTIALS_RESPONSE = 32770; }
```
(Note the enum numbering differs from the RFCOMM `SecurityMode`; this set is not used on the RFCOMM link. `ConnectionConfiguration` and `ev.proto` were not read.)

### 11.7 Default Wi-Fi/TCP values and TCP port the head unit listens on
Dongle `Config::getWifiInfo()` (verbatim):
```
ssid            = getenv("AAWG_WIFI_SSID", "AAWirelessDongle")
key             = getenv("AAWG_WIFI_PASSWORD", "ConnectAAWirelessDongle")
bssid           = getenv("AAWG_WIFI_BSSID", getMacAddress("wlan0"))
securityMode    = SecurityMode::WPA2_PERSONAL        (= 8)
accessPointType = AccessPointType::DYNAMIC           (= 1)
ipAddress       = getenv("AAWG_PROXY_IP_ADDRESS", "10.0.0.1")
port            = getenv("AAWG_PROXY_PORT", 5288)
```
`AAWProxy::startServer(port)`: `socket(AF_INET, SOCK_STREAM)`, `SO_REUSEADDR|SO_REUSEPORT`, `bind(INADDR_ANY, htons(port))`,
`listen(3)`, then `accept()` → **the dongle (head-unit side) listens on TCP 5288 (default) and the phone connects**.
After accept it opens `/dev/usb_accessory` and forwards bytes TCP↔USB (`AAWProxy::forward`), parsing AA frame lengths
on the TCP→USB direction (§1.2). Connection strategies: `DONGLE_MODE = 0, PHONE_FIRST = 1 (default), USB_FIRST = 2`.

aa-proxy-rs `config.rs` (verbatim): `pub const TCP_SERVER_PORT: i32 = 5288;` `pub const TCP_DHU_PORT: i32 = 5277;`
`proxy.rs`: `tcp_listener_bind!(TCP_SERVER_PORT)` on `0.0.0.0` ("MD TCP server" — the phone/mobile-device side) and
`tcp_listener_bind!(TCP_DHU_PORT)` ("TCP server for DHU"). aa-proxy-rs license: `LICENSE` = "GNU GENERAL PUBLIC LICENSE
Version 2, June 1991". aa-proxy-rs README credits provenance: *"I discovered a great open-source project based on
Raspberry Pi hardware: WirelessAndroidAutoDongle by Nisarg Jhaveri."*

### 11.8 "Manual" wireless path — port the PHONE listens on (head unit server, 5277)
- openauto `Readme.md` (verbatim): *"Wireless (WiFi) mode via head unit server (must be enabled in hidden developer settings)"*.
- openauto `src/autoapp/UI/ConnectDialog.cpp` (verbatim): `tcpWrapper_.asyncConnect(*socket, ipAddress, 5277, ...)` — the head
  unit **connects out** to the user-entered phone IP on **TCP 5277**; the resulting socket becomes a `TCPTransport`
  (`App::start` → `AndroidAutoEntityFactory::create(tcpEndpoint)`), after which the normal §8 sequence runs unchanged.
- headunit `jni/hu_tcp.c` (verbatim): `cli_addr.sin_addr.s_addr = htonl (INADDR_LOOPBACK); cli_addr.sin_family = AF_INET;
  cli_addr.sin_port = htons (5277);` … `ret = connect (tcp_so_fd, ...)` — connects to the phone's head-unit server on 5277.
- aa-proxy-rs: `TCP_DHU_PORT = 5277` (used for its "DHU" listener).
- headunit `README.md` contains no mention of the head unit server or 5277 (checked). No Google documentation of the
  developer setting was read (out of scope: only open-source implementations were used).

---

## 12. Licenses

| Repo | Evidence | License |
|---|---|---|
| f1xpl/aasdk | root listing has **no LICENSE file** (`/LICENSE` → 404); `Readme.md` "### License / GNU GPLv3 / Copyrights (c) 2018 f1x.studio (Michal Szwaj)"; every source header: "GNU General Public License … either version 3 of the License, or (at your option) any later version" | GPL-3.0-or-later (as stated) |
| f1xpl/openauto | same situation: no LICENSE file; `Readme.md` "### License / GNU GPLv3"; same per-file header text ("openauto is free software…") | GPL-3.0-or-later (as stated) |
| nisargjhaveri/WirelessAndroidAutoDongle | `LICENSE`: "MIT License / Copyright (c) 2023 Nisarg Jhaveri" | MIT |
| manio/aa-proxy-rs | `LICENSE`: "GNU GENERAL PUBLIC LICENSE Version 2, June 1991" | GPL-2.0 |
| mikereidis/headunit | `LICENSE`: "GNU AFFERO GENERAL PUBLIC LICENSE Version 3, 19 November 2007"; root also lists `COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt` (not read) | AGPL-3.0 |

---

## 13. Gaps

1. **`VoiceSessionRequestMessage.proto`** — does not exist in aasdk (`aasdk_proto` listing). Only the id `VOICE_SESSION_REQUEST = 0x0011` is defined. No field numbers available from any source read.
2. **`BluetoothAuthenticationDataMessage.proto`** — does not exist in aasdk. Only `AUTH_DATA = 0x8003` is defined and aasdk neither sends nor handles it.
3. **`MediaInfoChannelData.proto`** — does not exist in aasdk.
4. **`SpeechAudioServiceChannel.cpp` / `SystemAudioServiceChannel.cpp`** — listed in `src/Channel/AV` but not read (MediaAudio was; they are one-line subclasses by pattern, but that is inference, not observation).
5. **aasdk `LICENSE` / openauto `LICENSE` files** — do not exist (404 / absent from root listing). License taken from Readme + file headers.
6. **Provenance of the aasdk certificate/private key** — not stated anywhere in the aasdk files that were read. Only the decoded X.509 subject/issuer (JVC Kenwood / Google Automotive Link) is available, derived from the PEM.
7. **headunit non-`MR_SSL_INTERNAL` certificate source** — `hu_ssl.h` only contains the "Internet Widgits" test pair under `#ifdef MR_SSL_INTERNAL`; the `cert_buf`/`pkey_buf` used otherwise were not located.
8. **aa-proxy-rs `bluetooth.rs` profile registration (`bluer::rfcomm::Profile {...}` / `register_profile`)** — the file was read only in part: constants, enums, framing and SDP data, but not the registration block or the top-level connection function. HFP/HSP rationale is therefore taken from the aa-proxy-rs README quote and the dongle's code, not from `bluetooth.rs` comments.
9. **Proto definitions for `WifiInfoRequest`, `WifiVersionRequest/Response`, `WifiConnectStatus`, `WifiStartResponse`, `WifiPingRequest/Response`, `WifiSetupInfo`** — none exist in either wireless repo; only their numeric ids are available.
10. **aa-proxy-rs `src/protos/ev.proto`** and the `ConnectionConfiguration` message referenced by `protos.proto` — not read.
11. **Google's own documentation of the "Start head unit server" developer setting / port 5277** — not read (only the open-source implementations' hard-coded 5277 were used).
12. **openauto `Configuration::getButtonCodes()` default list** — `reset()` shows `buttonCodes_.clear()`; the key→code mapping is from `InputDevice::handleKeyEvent`, but which codes are enabled by default was not checked (the settings UI populates it).
13. **aasdk `Messenger.cpp`, `USBTransport.cpp`, `TCPTransport.cpp`, `USBEndpoint.cpp`** — not read (not needed here; transport-level buffering behaviour therefore not recorded).

---

## 14. Observed with a real phone (2026-10-03) — supersedes aasdk where they differ

Evidence class: **observed directly** by this project against a Samsung SM-S948B running the Android Auto
app's head unit server (TCP 5277), traced by `RealPhoneProbeTest`. These are measurements, not source reading.

1. **Version.** The phone answers our `VERSION_REQUEST` 1.1 with major 1, minor 7, status 0 (MATCH):
   `00 03 00 08 00 02 00 01 00 07 00 00`.
2. **TLS.** TLS 1.2, `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` (0xC02F); the aasdk head-unit certificate is accepted.
3. **Channels are interleaved.** Frames of another channel (media audio) arrive between the FIRST/MIDDLE/LAST
   frames of a video message. aasdk's `MESSENGER_INTERTWINED_CHANNELS` rejection (§1.3) must not be copied;
   reassembly is per channel.
4. **Ping.** An aasdk-style `PING_REQUEST` with no fields is ignored. With field 1 (int64 timestamp) set, the
   phone answers every ping with `PING_RESPONSE{1: same timestamp}` (encrypted). The request is sent plain.
5. **Frame rate enum (§3 `VideoFPS`).** Value 1 produced ~55-59 fps, value 2 produced 30.1 fps. aasdk's names
   (`_30 = 1`, `_60 = 2`) are reversed.
6. **`SETUP_REQUEST` field 1 (§4.2, called `config_index` in aasdk).** The phone sends 1 on audio channels and
   3 on the video channel, i.e. a media codec type, not an index. Our reply does not depend on it.
7. **`SERVICE_DISCOVERY_REQUEST`.** Fields 1-3 carry PNG icons (the message is ~840 bytes); field 4 was
   "Android", field 5 "samsung SM-S948B".
8. **Audio focus.** The phone first sends `AUDIO_FOCUS_REQUEST{RELEASE (4)}`, later `{GAIN (1)}` followed by
   `AV_START` and PCM on the media channel.
9. **Video.** The first unit is `AV_MEDIA_INDICATION` (no timestamp) holding SPS+PPS with 4-byte start codes
   (`67 42 80 1f ...`, Baseline, level 3.1, 800x480); then timestamped IDR and P frames, one slice each.
   After `START_INDICATION` the phone also sends `VIDEO_FOCUS_REQUEST{focus_mode 1}`.
10. **Binding.** `BINDING_REQUEST` listed exactly the key codes declared in the discovery response.
11. **Shutdown.** `SHUTDOWN_REQUEST{QUIT}` from the head unit is answered with an empty `SHUTDOWN_RESPONSE`.
12. **Unexplained.** After an attempt that was abandoned right after the version exchange, the phone accepted
    TCP connections but sent nothing for several minutes.
13. **A microphone channel is mandatory.** A discovery response without an `av_input_channel` makes the phone
    close the connection immediately after discovery (reproduced twice; with the channel declared the same
    response is accepted). The head unit may still answer `AV_INPUT_OPEN_REQUEST` with a failure value.
14. **Video margins (§3 `VideoConfig` fields 3 and 4).** With `margin_height = 110` on an 800x480 config the phone
    still sends 800x480 frames but lays its UI out in the centred 800x370 area, leaves 55 px black above and
    below, and switches to its wide layout. Values 12, 100 and 110 were all accepted.
15. **Touch with margins.** Coordinates are taken relative to the content area, 1:1 in video pixels: a tap sent
    at (765, 330) activated the control drawn at frame position (765, 385); taps sent at y = 385 and y = 428
    (outside the 370 px content height) were ignored. Without margins, (760, 445) hit the control drawn there.
    Declaring `touch_screen_config` as 800x480 or as 800x370 made no difference.
16. **Leaving Android Auto from the phone UI.** Tapping its "Exit" entry sends `VIDEO_FOCUS_REQUEST` with field 2
    (`focus_mode`) = 2: `10 02 18 00`. At start it sends the same message with mode 1.
