/*
 * Isochronous USB reading for UVC cameras.
 *
 * Android's Java USB API only offers control, bulk and interrupt transfers, but most
 * cameras stream video isochronously. This talks to the kernel's usbfs directly through
 * the file descriptor UsbDeviceConnection already opened: it keeps a ring of
 * isochronous URBs in flight and hands every completed one to Kotlin (IsoPump.onUrb),
 * which parses the UVC payloads. The interface must already be claimed and switched to
 * a streaming alternate setting.
 */
#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>

#define TAG "IsoPump"
#define MAX_URBS 32
#define MAX_PACKETS 128 /* usbfs refuses more packets per URB */
#define POLL_MS 100
#define DRAIN_MS 1000

typedef struct {
    JNIEnv *env;
    jobject self;
    jmethodID on_log;
} Ctx;

static void say(Ctx *c, const char *fmt, ...) {
    char msg[320];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg);
    jstring s = (*c->env)->NewStringUTF(c->env, msg);
    if (s) {
        (*c->env)->CallVoidMethod(c->env, c->self, c->on_log, s);
        (*c->env)->DeleteLocalRef(c->env, s);
    }
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Resets a URB for (re)submission; usbfs writes lengths and statuses back into it. */
static void prepare(struct usbdevfs_urb *u, int endpoint, int packet_size, int packets, int index) {
    void *buffer = u->buffer;
    memset(u, 0, sizeof(*u));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) endpoint;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = buffer;
    u->buffer_length = packet_size * packets;
    u->number_of_packets = packets;
    u->usercontext = (void *) (intptr_t) index;
    for (int p = 0; p < packets; p++) {
        u->iso_frame_desc[p].length = (unsigned int) packet_size;
        u->iso_frame_desc[p].actual_length = 0;
        u->iso_frame_desc[p].status = 0;
    }
}

/*
 * Streams until Kotlin asks to stop (onUrb returns false, or shouldStop() is true).
 * Packet p of a URB sits at byte p * packet_size of its buffer, whatever its length.
 * Returns 0, or a negative errno when the stream could not run.
 */
JNIEXPORT jint JNICALL
Java_com_example_varcamera_usbtest_IsoPump_nativeRun(JNIEnv *env, jobject self, jint fd, jint endpoint,
                                                     jint packet_size, jint packets, jint urb_count) {
    if (urb_count < 1 || urb_count > MAX_URBS || packets < 1 || packets > MAX_PACKETS || packet_size < 1) {
        return -EINVAL;
    }

    jclass cls = (*env)->GetObjectClass(env, self);
    Ctx c = {env, self, (*env)->GetMethodID(env, cls, "onLog", "(Ljava/lang/String;)V")};
    jmethodID on_urb = (*env)->GetMethodID(env, cls, "onUrb", "(Ljava/nio/ByteBuffer;[I[III)Z");
    jmethodID should_stop = (*env)->GetMethodID(env, cls, "shouldStop", "()Z");
    if (!c.on_log || !on_urb || !should_stop) return -ENOSYS;

    struct usbdevfs_urb *urbs[MAX_URBS] = {0};
    jobject buffers[MAX_URBS] = {0};
    int in_flight[MAX_URBS] = {0};
    jint lengths[MAX_PACKETS], statuses[MAX_PACKETS];
    size_t buffer_bytes = (size_t) packet_size * (size_t) packets;
    long long submitted = 0, reaped = 0, deadline;
    int result = 0, stop = 0, leaked = 0;

    jintArray j_lengths = (*env)->NewIntArray(env, packets);
    jintArray j_statuses = (*env)->NewIntArray(env, packets);
    if (!j_lengths || !j_statuses) {
        result = -ENOMEM;
        goto cleanup;
    }

    for (int i = 0; i < urb_count; i++) {
        struct usbdevfs_urb *u =
            calloc(1, sizeof(*u) + (size_t) packets * sizeof(struct usbdevfs_iso_packet_desc));
        void *data = calloc(1, buffer_bytes);
        if (!u || !data) {
            free(u);
            free(data);
            result = -ENOMEM;
            goto cleanup;
        }
        u->buffer = data;
        urbs[i] = u;
        jobject bb = (*env)->NewDirectByteBuffer(env, data, (jlong) buffer_bytes);
        if (!bb) {
            result = -ENOMEM;
            goto cleanup;
        }
        buffers[i] = (*env)->NewGlobalRef(env, bb);
        (*env)->DeleteLocalRef(env, bb);
    }

    for (int i = 0; i < urb_count; i++) {
        prepare(urbs[i], endpoint, packet_size, packets, i);
        if (ioctl(fd, USBDEVFS_SUBMITURB, urbs[i]) < 0) {
            result = -errno;
            say(&c, "submitting URB %d failed: %s (errno %d)", i, strerror(errno), errno);
            goto cleanup;
        }
        in_flight[i] = 1;
        submitted++;
    }
    say(&c, "%d URBs x %d packets x %d bytes in flight on endpoint 0x%02x",
        urb_count, packets, packet_size, endpoint);

    while (!stop) {
        struct pollfd pfd = {.fd = fd, .events = POLLOUT | POLLWRNORM};
        int ready = poll(&pfd, 1, POLL_MS);
        if (ready < 0 && errno != EINTR) {
            result = -errno;
            say(&c, "poll failed: %s (errno %d)", strerror(errno), errno);
            break;
        }
        if (ready > 0 && (pfd.revents & (POLLERR | POLLHUP))) {
            result = -ENODEV;
            say(&c, "device disconnected (poll events 0x%x)", pfd.revents);
            break;
        }

        for (;;) {
            struct usbdevfs_urb *u = NULL;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
                if (errno == EAGAIN) break;
                result = -errno;
                say(&c, "reaping failed: %s (errno %d)", strerror(errno), errno);
                stop = 1;
                break;
            }
            int i = (int) (intptr_t) u->usercontext;
            if (i < 0 || i >= urb_count || urbs[i] != u) continue;
            in_flight[i] = 0;
            reaped++;

            int n = u->number_of_packets;
            for (int p = 0; p < n; p++) {
                lengths[p] = (jint) u->iso_frame_desc[p].actual_length;
                statuses[p] = (jint) u->iso_frame_desc[p].status;
            }
            (*env)->SetIntArrayRegion(env, j_lengths, 0, n, lengths);
            (*env)->SetIntArrayRegion(env, j_statuses, 0, n, statuses);
            jboolean keep = (*env)->CallBooleanMethod(env, self, on_urb, buffers[i], j_lengths, j_statuses,
                                                      n, u->status);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionDescribe(env);
                (*env)->ExceptionClear(env);
                keep = JNI_FALSE;
            }
            if (!keep) {
                stop = 1;
                break;
            }

            prepare(u, endpoint, packet_size, packets, i);
            if (ioctl(fd, USBDEVFS_SUBMITURB, u) < 0) {
                result = -errno;
                say(&c, "resubmitting URB %d failed: %s (errno %d)", i, strerror(errno), errno);
                stop = 1;
                break;
            }
            in_flight[i] = 1;
            submitted++;
        }

        if (!stop && (*env)->CallBooleanMethod(env, self, should_stop)) stop = 1;
    }

cleanup:
    for (int i = 0; i < urb_count; i++) {
        if (in_flight[i]) ioctl(fd, USBDEVFS_DISCARDURB, urbs[i]);
    }
    /* Discarded URBs still complete; reap them so the kernel never writes into freed memory. */
    deadline = now_ms() + DRAIN_MS;
    for (;;) {
        int pending = 0;
        for (int i = 0; i < urb_count; i++) pending += in_flight[i];
        if (!pending || now_ms() > deadline) break;
        struct usbdevfs_urb *u = NULL;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &u) == 0) {
            int i = (int) (intptr_t) u->usercontext;
            if (i >= 0 && i < urb_count && urbs[i] == u) in_flight[i] = 0;
        } else if (errno == EAGAIN) {
            struct pollfd pfd = {.fd = fd, .events = POLLOUT | POLLWRNORM};
            poll(&pfd, 1, 20);
        } else {
            break; /* device gone: the kernel dropped the URBs with it */
        }
    }

    for (int i = 0; i < urb_count; i++) {
        if (buffers[i]) (*env)->DeleteGlobalRef(env, buffers[i]);
        if (!urbs[i]) continue;
        if (in_flight[i]) {
            leaked++; /* still owned by the kernel: leaking is the only safe choice */
            continue;
        }
        free(urbs[i]->buffer);
        free(urbs[i]);
    }
    if (submitted > 0 || leaked > 0) {
        say(&c, "stopped: %lld URBs submitted, %lld completed%s", submitted, reaped,
            leaked ? ", some never came back (left allocated)" : "");
    }
    return result;
}
