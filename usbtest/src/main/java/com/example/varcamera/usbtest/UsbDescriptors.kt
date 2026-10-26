package com.example.varcamera.usbtest

/**
 * Reads raw USB descriptors (as UsbDeviceConnection.getRawDescriptors returns them) and
 * keeps what a UVC test needs: interfaces with their alternate settings and endpoints,
 * the UVC version, and every video format and frame size. [lines] describes all of it
 * in plain text for the log.
 */
class UsbDescriptors private constructor() {

    class Endpoint(val address: Int, val attributes: Int, val maxPacketSize: Int, val interval: Int) {
        /** SuperSpeed companion values; -1 while the device runs at USB 2 speed. */
        var maxBurst = -1
        var ssAttributes = 0
        var ssBytesPerInterval = -1

        val type: Int get() = attributes and 0x03
        val isIn: Boolean get() = address and 0x80 != 0
        val isIsochronous: Boolean get() = type == 1
        val isBulk: Boolean get() = type == 2

        /** What one isochronous packet can carry per service interval. */
        val bytesPerInterval: Int
            get() = if (ssBytesPerInterval >= 0) ssBytesPerInterval
            else (maxPacketSize and 0x7ff) * (((maxPacketSize shr 11) and 0x3) + 1)

        fun describe(): String {
            val kind = arrayOf("control", "isochronous", "bulk", "interrupt")[type]
            val ss = if (maxBurst >= 0) {
                " burst=${maxBurst + 1} ssAttributes=0x%02x".format(ssAttributes)
            } else ""
            val perInterval = if (isIsochronous) " -> $bytesPerInterval bytes per interval" else ""
            return "EP 0x%02x %s %s maxPacket=0x%04x interval=%d%s%s".format(
                address, if (isIn) "IN" else "OUT", kind, maxPacketSize, interval, ss, perInterval
            )
        }
    }

    class Interface(val number: Int, val alternate: Int, val interfaceClass: Int, val subclass: Int, val protocol: Int) {
        val endpoints = mutableListOf<Endpoint>()
    }

    class Frame(
        val index: Int,
        val width: Int,
        val height: Int,
        val defaultInterval: Int,
        /** Discrete frame intervals; empty when the range below applies instead. */
        val intervals: List<Int>,
        val minInterval: Int,
        val maxInterval: Int,
        val intervalStep: Int,
        val maxFrameBytes: Int
    )

    class Format(val index: Int, val name: String, val subtype: Int) {
        val frames = mutableListOf<Frame>()
        val isMjpeg: Boolean get() = subtype == VS_FORMAT_MJPEG
    }

    class Streaming(val interfaceNumber: Int) {
        var endpointAddress = 0
        val formats = mutableListOf<Format>()
    }

    var bcdUsb = 0; private set
    var bcdUvc = 0; private set
    /** At least one SuperSpeed companion descriptor: the camera is connected at USB 3 speed. */
    var superSpeed = false; private set
    val interfaces = mutableListOf<Interface>()
    val streaming = mutableListOf<Streaming>()
    val lines = mutableListOf<String>()

    /** Probe/commit control length for this UVC version, chosen as Linux uvcvideo does. */
    val controlSize: Int
        get() = when {
            bcdUvc < 0x0110 -> 26
            bcdUvc < 0x0150 -> 34
            else -> 48
        }

    fun alternates(interfaceNumber: Int): List<Interface> = interfaces.filter { it.number == interfaceNumber }

    companion object {
        private const val DESC_DEVICE = 0x01
        private const val DESC_CONFIG = 0x02
        private const val DESC_INTERFACE = 0x04
        private const val DESC_ENDPOINT = 0x05
        private const val DESC_IAD = 0x0B
        private const val DESC_CS_INTERFACE = 0x24
        private const val DESC_SS_COMPANION = 0x30
        private const val DESC_SSP_ISO_COMPANION = 0x31

        private const val CLASS_AUDIO = 1
        private const val CLASS_VIDEO = 14
        private const val SUB_CONTROL = 1
        private const val SUB_STREAMING = 2

        private const val VS_INPUT_HEADER = 0x01
        private const val VS_STILL_FRAME = 0x03
        private const val VS_FORMAT_UNCOMPRESSED = 0x04
        private const val VS_FRAME_UNCOMPRESSED = 0x05
        const val VS_FORMAT_MJPEG = 0x06
        private const val VS_FRAME_MJPEG = 0x07
        private const val VS_COLORFORMAT = 0x0D
        private const val VS_FORMAT_FRAME_BASED = 0x10
        private const val VS_FRAME_FRAME_BASED = 0x11
        private const val VS_FORMAT_H264 = 0x13
        private const val VS_FRAME_H264 = 0x14

        fun fps(interval: Int): String = if (interval <= 0) "?" else "%.2f".format(10_000_000.0 / interval)

        fun parse(raw: ByteArray): UsbDescriptors = UsbDescriptors().apply { read(raw) }
    }

    private fun read(raw: ByteArray) {
        fun u8(o: Int) = if (o in raw.indices) raw[o].toInt() and 0xff else 0
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int) = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)
        fun fourcc(o: Int): String {
            val c = (0 until 4).map { u8(o + it) }
            return if (c.all { it in 0x20..0x7e }) c.map { it.toChar() }.joinToString("")
            else (0 until 16).joinToString("") { "%02x".format(u8(o + it)) }
        }
        fun version(bcd: Int) = "%x.%02x".format(bcd shr 8, bcd and 0xff)

        var current: Interface? = null
        var lastEndpoint: Endpoint? = null
        var lastEndpointLine = -1
        var currentStreaming: Streaming? = null
        var currentFormat: Format? = null

        var i = 0
        while (i + 1 < raw.size) {
            val length = u8(i)
            if (length < 2 || i + length > raw.size) {
                lines += "Descriptor walk stopped at byte $i (length $length)"
                break
            }
            when (u8(i + 1)) {
                DESC_DEVICE -> {
                    bcdUsb = u16(i + 2)
                    lines += "Device: USB %s, VID %04x PID %04x, release %s, class %d/%d/%d, %d configuration(s)".format(
                        version(bcdUsb), u16(i + 8), u16(i + 10), version(u16(i + 12)),
                        u8(i + 4), u8(i + 5), u8(i + 6), u8(i + 17)
                    )
                }
                DESC_CONFIG -> {
                    val unit = if (bcdUsb >= 0x0300) 8 else 2
                    lines += "Configuration %d: %d interfaces, attributes 0x%02x, max power %d mA".format(
                        u8(i + 5), u8(i + 4), u8(i + 7), u8(i + 8) * unit
                    )
                }
                DESC_IAD -> lines += "Function: interfaces %d-%d, class %d/%d".format(
                    u8(i + 2), u8(i + 2) + u8(i + 3) - 1, u8(i + 4), u8(i + 5)
                )
                DESC_INTERFACE -> {
                    val itf = Interface(u8(i + 2), u8(i + 3), u8(i + 5), u8(i + 6), u8(i + 7))
                    interfaces += itf
                    current = itf
                    lastEndpoint = null
                    currentFormat = null
                    currentStreaming = if (itf.interfaceClass == CLASS_VIDEO && itf.subclass == SUB_STREAMING) {
                        streaming.firstOrNull { it.interfaceNumber == itf.number }
                            ?: Streaming(itf.number).also { streaming += it }
                    } else null
                    lines += "Interface %d alt %d: class %d/%d/%d %s, %d endpoint(s)".format(
                        itf.number, itf.alternate, itf.interfaceClass, itf.subclass, itf.protocol,
                        className(itf), u8(i + 4)
                    )
                }
                DESC_ENDPOINT -> {
                    val ep = Endpoint(u8(i + 2), u8(i + 3), u16(i + 4), u8(i + 6))
                    current?.endpoints?.add(ep)
                    lastEndpoint = ep
                    lastEndpointLine = lines.size
                    lines += "    " + ep.describe()
                }
                DESC_SS_COMPANION -> lastEndpoint?.let { ep ->
                    ep.maxBurst = u8(i + 2)
                    ep.ssAttributes = u8(i + 3)
                    ep.ssBytesPerInterval = u16(i + 4)
                    superSpeed = true
                    lines[lastEndpointLine] = "    " + ep.describe()
                }
                DESC_SSP_ISO_COMPANION -> lines += "    SuperSpeedPlus companion: %d bytes per interval".format(u32(i + 4))
                DESC_CS_INTERFACE -> {
                    val itf = current
                    val vs = currentStreaming
                    when {
                        itf == null -> Unit
                        itf.interfaceClass == CLASS_VIDEO && itf.subclass == SUB_CONTROL -> when (u8(i + 2)) {
                            0x01 -> {
                                bcdUvc = u16(i + 3)
                                lines += "  UVC version %s (probe/commit %d bytes), clock %d Hz".format(
                                    version(bcdUvc), controlSize, u32(i + 7).toLong() and 0xffffffffL
                                )
                            }
                            0x02 -> lines += "  Input terminal %d, type 0x%04x".format(u8(i + 3), u16(i + 4))
                            0x03 -> lines += "  Output terminal %d, type 0x%04x".format(u8(i + 3), u16(i + 4))
                            0x05 -> lines += "  Processing unit %d".format(u8(i + 3))
                            0x06 -> lines += "  Extension unit %d".format(u8(i + 3))
                            else -> lines += "  Video control descriptor 0x%02x".format(u8(i + 2))
                        }
                        vs != null -> when (val subtype = u8(i + 2)) {
                            VS_INPUT_HEADER -> {
                                vs.endpointAddress = u8(i + 6)
                                lines += "  Streaming header: %d format(s), endpoint 0x%02x, still method %d".format(
                                    u8(i + 3), u8(i + 6), u8(i + 9)
                                )
                            }
                            VS_FORMAT_MJPEG, VS_FORMAT_UNCOMPRESSED, VS_FORMAT_FRAME_BASED, VS_FORMAT_H264 -> {
                                val name = when (subtype) {
                                    VS_FORMAT_MJPEG -> "MJPEG"
                                    VS_FORMAT_UNCOMPRESSED -> "Uncompressed ${fourcc(i + 5)} ${u8(i + 21)} bit"
                                    VS_FORMAT_FRAME_BASED -> "Frame-based ${fourcc(i + 5)}"
                                    else -> "H.264"
                                }
                                val format = Format(u8(i + 3), name, subtype)
                                vs.formats += format
                                currentFormat = format
                                lines += "  Format #%d: %s, %d frame size(s)".format(format.index, name, u8(i + 4))
                            }
                            VS_FRAME_MJPEG, VS_FRAME_UNCOMPRESSED, VS_FRAME_FRAME_BASED -> {
                                // Frame-based frames have no buffer size field, so their layout shifts.
                                val frameBased = subtype == VS_FRAME_FRAME_BASED
                                val defaultAt = if (frameBased) i + 17 else i + 21
                                val typeAt = if (frameBased) i + 21 else i + 25
                                val count = u8(typeAt)
                                val intervals = (0 until count).map { u32(i + 26 + 4 * it) }
                                val frame = Frame(
                                    index = u8(i + 3),
                                    width = u16(i + 5),
                                    height = u16(i + 7),
                                    defaultInterval = u32(defaultAt),
                                    intervals = intervals,
                                    minInterval = if (count == 0) u32(i + 26) else 0,
                                    maxInterval = if (count == 0) u32(i + 30) else 0,
                                    intervalStep = if (count == 0) u32(i + 34) else 0,
                                    maxFrameBytes = if (frameBased) 0 else u32(i + 17)
                                )
                                currentFormat?.frames?.add(frame)
                                val rates = if (count > 0) intervals.joinToString(" / ") { fps(it) } + " fps"
                                else "any of ${fps(frame.maxInterval)}..${fps(frame.minInterval)} fps"
                                lines += "    Frame #%d: %dx%d, %s (default %s), up to %d bytes".format(
                                    frame.index, frame.width, frame.height, rates, fps(frame.defaultInterval),
                                    frame.maxFrameBytes
                                )
                            }
                            VS_FRAME_H264 -> lines += "    H.264 frame #%d: %dx%d".format(u8(i + 3), u16(i + 4), u16(i + 6))
                            VS_STILL_FRAME -> lines += "    Still image frame descriptor"
                            VS_COLORFORMAT -> lines += "    Color: primaries %d, transfer %d, matrix %d".format(
                                u8(i + 3), u8(i + 4), u8(i + 5)
                            )
                            else -> lines += "  Video streaming descriptor 0x%02x".format(subtype)
                        }
                        itf.interfaceClass == CLASS_AUDIO && itf.subclass == SUB_STREAMING && u8(i + 2) == 0x02 -> {
                            val rates = (0 until u8(i + 7)).map { k ->
                                u8(i + 8 + 3 * k) or (u8(i + 9 + 3 * k) shl 8) or (u8(i + 10 + 3 * k) shl 16)
                            }
                            lines += "  Audio format: %d channel(s), %d bit, %s Hz".format(
                                u8(i + 4), u8(i + 6), rates.joinToString("/")
                            )
                        }
                    }
                }
            }
            i += length
        }
    }

    private fun className(itf: Interface): String = when (itf.interfaceClass) {
        CLASS_AUDIO -> when (itf.subclass) {
            1 -> "(audio control)"
            2 -> "(audio streaming)"
            else -> "(audio)"
        }
        CLASS_VIDEO -> when (itf.subclass) {
            SUB_CONTROL -> "(video control)"
            SUB_STREAMING -> "(video streaming)"
            else -> "(video)"
        }
        3 -> "(HID)"
        6 -> "(still image / PTP)"
        8 -> "(mass storage)"
        255 -> "(vendor specific)"
        else -> ""
    }
}
